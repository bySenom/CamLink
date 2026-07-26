package com.camlink.camera

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.CamcorderProfile
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.util.Range
import android.util.Size
import android.util.SizeF
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max

/**
 * Enumerates candidates from Camera2 first. CamcorderProfile remains a useful
 * vendor hint, but it must never be the sole gate for a Camera2/MediaCodec stream.
 */
class CameraCapabilityProbe(context: Context) {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val encoders = VideoEncoderProbe()
    private val validationStore = ProfileValidationStore(context)

    fun inspect(): CameraCapabilities {
        val cameras = manager.cameraIdList.mapNotNull { id ->
            val characteristics = manager.getCameraCharacteristics(id)
            descriptor(id, characteristics)
        }.sortedBy { it.name }

        val reference = cameras.firstOrNull()?.let { manager.getCameraCharacteristics(it.id) }
        val exposureRange = reference?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0)
        return CameraCapabilities(
            deviceName = "${Build.MANUFACTURER} ${Build.MODEL}",
            cameras = cameras,
            exposureMin = exposureRange.lower,
            exposureMax = exposureRange.upper,
            whiteBalanceModes = whiteBalanceModes(reference)
        )
    }

    private fun descriptor(id: String, characteristics: CameraCharacteristics): CameraDescriptor {
        val map = requireNotNull(characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)) {
            "Camera $id does not expose a stream configuration map."
        }
        val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList().orEmpty()
        val focal = focalLengths.minOrNull() ?: 0f
        val name = if (characteristics.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT) {
            "Front (${String.format("%.1f", focal)} mm)"
        } else {
            cameraName(focal)
        }
        val zoom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.upper ?: 1f
        } else {
            characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        }
        val exposureRange = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0)
        val usableSizes = outputSizes(map)
        val camera2Profiles = normalProfiles(map, characteristics, usableSizes)
        val vendorProfiles = recorderProfiles(id, usableSizes)
        val highSpeedProfiles = highSpeedProfiles(map, characteristics)
        val profiles = (CameraProfileRules.mergeNormalProfiles(vendorProfiles, camera2Profiles) + highSpeedProfiles)
            .map { profile -> profile.copy(verification = validationStore.verificationFor(id, profile)) }

        val descriptor = CameraDescriptor(
            id = id,
            name = name,
            maxZoom = max(1f, zoom),
            hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
            profiles = profiles,
            exposureMin = exposureRange.lower,
            exposureMax = exposureRange.upper,
            whiteBalanceModes = whiteBalanceModes(characteristics),
            focusModes = focusModes(characteristics),
            controls = controls(characteristics),
            diagnostics = diagnostics(id, characteristics, map, usableSizes, focalLengths)
        )
        logDiagnostics(descriptor)
        return descriptor
    }

    private fun cameraName(focalMm: Float): String = when {
        // The S22 reports its ultra-wide camera as 2.2 mm. Keep this threshold
        // wide enough to avoid selecting it as the default main ("Wide") lens.
        focalMm <= 3.0f -> "Ultra-wide (${String.format("%.1f", focalMm)} mm)"
        focalMm >= 6.0f -> "Telephoto (${String.format("%.1f", focalMm)} mm)"
        else -> "Wide (${String.format("%.1f", focalMm)} mm)"
    }

    private fun normalProfiles(
        map: StreamConfigurationMap,
        characteristics: CameraCharacteristics,
        outputSizes: List<Size>
    ): List<CameraProfile> {
        val fpsRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList().orEmpty()
        // 60 FPS is deliberately a normal Camera2 candidate. It is not a high-speed
        // profile unless the device only exposes it through a constrained session.
        val requestedFps = listOf(30, 60)
        return outputSizes.flatMap { size ->
            requestedFps.mapNotNull { fps ->
                if (!containsFps(fpsRanges, fps) || !canReachFps(map, size, fps)) {
                    null
                } else {
                    val codec = encoders.codecFor(size, fps) ?: return@mapNotNull null
                    CameraProfile(size.width, size.height, fps, highSpeed = false, codec = codec, source = ProfileSource.CAMERA2)
                }
            }
        }
            .filter { profile -> profile.width >= 1280 && profile.height >= 720 && isVideoAspect(profile.width, profile.height) }
            .sortedWith(profileComparator())
            .distinctBy { Triple(it.width, it.height, it.fps) }
    }

    /** Vendor recorder combinations are hints, not a replacement for [normalProfiles]. */
    private fun recorderProfiles(cameraId: String, supportedSizes: List<Size>): List<CameraProfile> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return emptyList()
        val supported = supportedSizes.toSet()
        val qualities = listOf(
            CamcorderProfile.QUALITY_8KUHD,
            CamcorderProfile.QUALITY_4KDCI,
            CamcorderProfile.QUALITY_2160P,
            CamcorderProfile.QUALITY_2K,
            CamcorderProfile.QUALITY_1080P,
            CamcorderProfile.QUALITY_720P,
            CamcorderProfile.QUALITY_480P
        )
        return qualities.flatMap { quality ->
            val profiles = runCatching { CamcorderProfile.getAll(cameraId, quality) }.getOrNull() ?: return@flatMap emptyList()
            profiles.videoProfiles.mapNotNull { video ->
                val size = Size(video.width, video.height)
                if (size !in supported || !isVideoAspect(size.width, size.height)) return@mapNotNull null
                val codec = encoders.codecFor(size, video.frameRate) ?: return@mapNotNull null
                CameraProfile(
                    video.width,
                    video.height,
                    video.frameRate,
                    highSpeed = false,
                    codec = codec,
                    source = ProfileSource.CAMCORDER_HINT
                )
            }
        }
            .filter { it.width >= 1280 && it.height >= 720 }
            .sortedWith(profileComparator())
            .distinctBy { Triple(it.width, it.height, it.fps) }
    }

    private fun highSpeedProfiles(map: StreamConfigurationMap, characteristics: CameraCharacteristics): List<CameraProfile> {
        val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet().orEmpty()
        if (!capabilities.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO)) {
            return emptyList()
        }
        return map.highSpeedVideoSizes.flatMap { size ->
            map.getHighSpeedVideoFpsRangesFor(size)
                .filter { it.lower == 120 && it.upper == 120 }
                .mapNotNull {
                    val codec = encoders.codecFor(size, 120) ?: return@mapNotNull null
                    CameraProfile(size.width, size.height, 120, highSpeed = true, codec = codec, source = ProfileSource.HIGH_SPEED)
                }
        }
            .filter { it.width == 1920 && it.height == 1080 }
            .sortedWith(profileComparator())
            .distinctBy { Triple(it.width, it.height, it.fps) }
    }

    /**
     * The stream uses both a TextureView preview and a MediaCodec input surface.
     * Keeping the union prevents a class-specific metadata omission from hiding a
     * candidate; the explicit runtime validator is the final authority.
     */
    private fun outputSizes(map: StreamConfigurationMap): List<Size> = buildList {
        addAll(map.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty())
        addAll(runCatching { map.getOutputSizes(MediaCodec::class.java)?.toList().orEmpty() }.getOrDefault(emptyList()))
    }.distinct().sortedWith(compareByDescending<Size> { it.width.toLong() * it.height }.thenByDescending { it.width })

    private fun canReachFps(map: StreamConfigurationMap, size: Size, fps: Int): Boolean {
        val frameBudgetNs = 1_000_000_000L / fps
        val durations = listOfNotNull(
            outputMinFrameDuration(map, SurfaceTexture::class.java, size),
            outputMinFrameDuration(map, MediaCodec::class.java, size)
        ).filter { it > 0L }
        return durations.maxOrNull()?.let { it <= frameBudgetNs } ?: true
    }

    private fun outputMinFrameDuration(map: StreamConfigurationMap, klass: Class<*>, size: Size): Long? = try {
        map.getOutputMinFrameDuration(klass, size)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun containsFps(ranges: List<Range<Int>>, fps: Int): Boolean = ranges.any { it.lower <= fps && it.upper >= fps }

    private fun whiteBalanceModes(characteristics: CameraCharacteristics?): List<String> {
        val available = characteristics?.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.toSet().orEmpty()
        return buildList {
            if (available.contains(CameraMetadata.CONTROL_AWB_MODE_AUTO)) add("Auto")
            if (available.contains(CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT)) add("Daylight")
            if (available.contains(CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT)) add("Cloudy")
            if (available.contains(CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT)) add("Incandescent")
            if (available.contains(CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT)) add("Fluorescent")
        }.ifEmpty { listOf("Auto") }
    }

    private fun focusModes(characteristics: CameraCharacteristics): List<Int> {
        val available = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toSet().orEmpty()
        return buildList {
            if (available.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)) add(0)
            if (available.contains(CameraMetadata.CONTROL_AF_MODE_AUTO)) add(1)
            if (available.contains(CameraMetadata.CONTROL_AF_MODE_OFF)) add(2)
        }.ifEmpty { listOf(0) }
    }

    private fun controls(characteristics: CameraCharacteristics): CameraControlCapabilities {
        val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet().orEmpty()
        val sensitivity = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val exposureTime = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        return CameraControlCapabilities(
            supportsManualSensor = capabilities.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR),
            isoMin = sensitivity?.lower,
            isoMax = sensitivity?.upper,
            exposureTimeMinNs = exposureTime?.lower,
            exposureTimeMaxNs = exposureTime?.upper,
            minFocusDistance = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
            supportsAeLock = characteristics.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true,
            supportsAwbLock = characteristics.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true,
            antiBandingModes = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES)?.toList().orEmpty(),
            opticalStabilizationModes = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.toList().orEmpty(),
            videoStabilizationModes = characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)?.toList().orEmpty(),
            noiseReductionModes = characteristics.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)?.toList().orEmpty(),
            edgeModes = characteristics.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)?.toList().orEmpty()
        )
    }

    private fun diagnostics(
        id: String,
        characteristics: CameraCharacteristics,
        map: StreamConfigurationMap,
        outputSizes: List<Size>,
        focalLengths: List<Float>
    ): CameraDiagnostics {
        val physicalCameras = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            characteristics.physicalCameraIds.map { physicalId ->
                val physical = manager.getCameraCharacteristics(physicalId)
                physicalInfo(physicalId, physical)
            }.sortedBy { it.id }
        } else {
            emptyList()
        }
        val physicalSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val highSpeedSizes = map.highSpeedVideoSizes.toList()
        return CameraDiagnostics(
            hardwareLevel = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ?: -1,
            availableCapabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList().orEmpty(),
            physicalCameras = physicalCameras,
            sensorWidthMm = physicalSize?.width,
            sensorHeightMm = physicalSize?.height,
            focalLengthsMm = focalLengths,
            orientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
            fpsRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.map { it.lower to it.upper }.orEmpty(),
            outputSizes = outputSizes.map { it.width to it.height },
            highSpeedSizes = highSpeedSizes.map { it.width to it.height },
            highSpeedFpsRanges = highSpeedSizes.flatMap { size ->
                map.getHighSpeedVideoFpsRangesFor(size).map { it.lower to it.upper }
            }.distinct()
        )
    }

    private fun physicalInfo(id: String, characteristics: CameraCharacteristics): PhysicalCameraInfo {
        val physicalSize: SizeF? = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val highSpeedSizes = map?.highSpeedVideoSizes?.toList().orEmpty()
        return PhysicalCameraInfo(
            id = id,
            hardwareLevel = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ?: -1,
            sensorWidthMm = physicalSize?.width,
            sensorHeightMm = physicalSize?.height,
            focalLengthsMm = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList().orEmpty(),
            fpsRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.map { it.lower to it.upper }
                .orEmpty(),
            outputSizes = map?.let(::outputSizes)?.map { it.width to it.height }.orEmpty(),
            highSpeedSizes = highSpeedSizes.map { it.width to it.height },
            highSpeedFpsRanges = highSpeedSizes.flatMap { size ->
                map?.getHighSpeedVideoFpsRangesFor(size)?.map { it.lower to it.upper }.orEmpty()
            }.distinct()
        )
    }

    private fun logDiagnostics(camera: CameraDescriptor) {
        val diagnostics = camera.diagnostics
        val profileSummary = camera.profiles.joinToString { profile ->
            "${profile.width}x${profile.height}@${profile.fps}/${profile.codec}/${profile.source}"
        }
        Log.i(
            LOG_TAG,
            "camera=${camera.id} name=${camera.name} level=${diagnostics.hardwareLevel} physical=${diagnostics.physicalCameras.map { it.id }} " +
                "sensor=${diagnostics.sensorWidthMm}x${diagnostics.sensorHeightMm}mm focal=${diagnostics.focalLengthsMm} " +
                "fps=${diagnostics.fpsRanges} outputs=${diagnostics.outputSizes} highSpeed=${diagnostics.highSpeedSizes}/${diagnostics.highSpeedFpsRanges} " +
                "profiles=$profileSummary"
        )
    }

    private fun isVideoAspect(width: Int, height: Int): Boolean {
        val ratio = width.toFloat() / height.toFloat()
        return abs(ratio - 16f / 9f) < 0.03f || abs(ratio - 17f / 9f) < 0.03f
    }

    fun asJson(capabilities: CameraCapabilities): JSONObject = JSONObject().apply {
        put("type", "capabilities")
        put("deviceName", capabilities.deviceName)
        put("exposureMin", capabilities.exposureMin)
        put("exposureMax", capabilities.exposureMax)
        put("whiteBalanceModes", JSONArray(capabilities.whiteBalanceModes))
        put("cameras", JSONArray().apply {
            capabilities.cameras.forEach { camera ->
                put(JSONObject().apply {
                    put("id", camera.id)
                    put("name", camera.name)
                    put("maxZoom", camera.maxZoom)
                    put("hasFlash", camera.hasFlash)
                    put("exposureMin", camera.exposureMin)
                    put("exposureMax", camera.exposureMax)
                    put("whiteBalanceModes", JSONArray(camera.whiteBalanceModes))
                    put("focusModes", JSONArray(camera.focusModes))
                    put("controls", controlsJson(camera.controls))
                    put("diagnostics", diagnosticsJson(camera.diagnostics))
                    put("profiles", JSONArray().apply {
                        camera.profiles.forEach { profile ->
                            put(JSONObject().apply {
                                put("width", profile.width)
                                put("height", profile.height)
                                put("fps", profile.fps)
                                put("highSpeed", profile.highSpeed)
                                put("codec", profile.codec)
                                put("source", profile.source.name)
                                put("verification", profile.verification.name)
                            })
                        }
                    })
                })
            }
        })
    }

    private fun controlsJson(controls: CameraControlCapabilities): JSONObject = JSONObject().apply {
        put("supportsManualSensor", controls.supportsManualSensor)
        put("isoMin", controls.isoMin)
        put("isoMax", controls.isoMax)
        put("exposureTimeMinNs", controls.exposureTimeMinNs)
        put("exposureTimeMaxNs", controls.exposureTimeMaxNs)
        put("minFocusDistance", controls.minFocusDistance)
        put("supportsAeLock", controls.supportsAeLock)
        put("supportsAwbLock", controls.supportsAwbLock)
        put("antiBandingModes", JSONArray(controls.antiBandingModes))
        put("opticalStabilizationModes", JSONArray(controls.opticalStabilizationModes))
        put("videoStabilizationModes", JSONArray(controls.videoStabilizationModes))
        put("noiseReductionModes", JSONArray(controls.noiseReductionModes))
        put("edgeModes", JSONArray(controls.edgeModes))
    }

    private fun diagnosticsJson(diagnostics: CameraDiagnostics): JSONObject = JSONObject().apply {
        put("hardwareLevel", diagnostics.hardwareLevel)
        put("availableCapabilities", JSONArray(diagnostics.availableCapabilities))
        put("physicalCameraIds", JSONArray(diagnostics.physicalCameras.map { it.id }))
        put("physicalCameras", JSONArray().apply {
            diagnostics.physicalCameras.forEach { physical ->
                put(JSONObject().apply {
                    put("id", physical.id)
                    put("hardwareLevel", physical.hardwareLevel)
                    put("sensorWidthMm", physical.sensorWidthMm)
                    put("sensorHeightMm", physical.sensorHeightMm)
                    put("focalLengthsMm", JSONArray(physical.focalLengthsMm))
                    put("fpsRanges", JSONArray(physical.fpsRanges.map { JSONArray(listOf(it.first, it.second)) }))
                    put("outputSizes", JSONArray(physical.outputSizes.map { JSONArray(listOf(it.first, it.second)) }))
                    put("highSpeedSizes", JSONArray(physical.highSpeedSizes.map { JSONArray(listOf(it.first, it.second)) }))
                    put("highSpeedFpsRanges", JSONArray(physical.highSpeedFpsRanges.map { JSONArray(listOf(it.first, it.second)) }))
                })
            }
        })
        put("sensorWidthMm", diagnostics.sensorWidthMm)
        put("sensorHeightMm", diagnostics.sensorHeightMm)
        put("focalLengthsMm", JSONArray(diagnostics.focalLengthsMm))
        put("orientation", diagnostics.orientation)
        put("fpsRanges", JSONArray(diagnostics.fpsRanges.map { JSONArray(listOf(it.first, it.second)) }))
        put("outputSizes", JSONArray(diagnostics.outputSizes.map { JSONArray(listOf(it.first, it.second)) }))
        put("highSpeedSizes", JSONArray(diagnostics.highSpeedSizes.map { JSONArray(listOf(it.first, it.second)) }))
        put("highSpeedFpsRanges", JSONArray(diagnostics.highSpeedFpsRanges.map { JSONArray(listOf(it.first, it.second)) }))
    }

    private companion object {
        const val LOG_TAG = "CamLinkCapabilities"
    }
}

internal object CameraProfileRules {
    /** Preserve Camera2 candidates even if the vendor recorder list is non-empty. */
    fun mergeNormalProfiles(vendorProfiles: List<CameraProfile>, camera2Profiles: List<CameraProfile>): List<CameraProfile> =
        (vendorProfiles + camera2Profiles)
            .sortedWith(profileComparator())
            .distinctBy { Triple(it.width, it.height, it.fps) }
}

private fun profileComparator(): Comparator<CameraProfile> = compareByDescending<CameraProfile> { it.width.toLong() * it.height }
    .thenByDescending { it.fps }
    .thenBy { if (it.codec == "h264") 0 else 1 }
    .thenBy { if (it.source == ProfileSource.CAMCORDER_HINT) 0 else 1 }

internal data class VideoEncoderSelection(
    val name: String,
    val codec: String,
    val mime: String,
    val hardwareAccelerated: Boolean,
    val widthAlignment: Int,
    val heightAlignment: Int,
    val minBitrate: Int,
    val maxBitrate: Int,
    val profileLevels: List<Pair<Int, Int>>
)

/** Chooses the exact encoder that was checked instead of relying on Android's arbitrary default encoder. */
internal class VideoEncoderProbe {
    private val h264 = encodersFor(MediaFormat.MIMETYPE_VIDEO_AVC)
    private val h265 = encodersFor(MediaFormat.MIMETYPE_VIDEO_HEVC)

    fun codecFor(size: Size, fps: Int): String? = when {
        select("h264", size, fps) != null -> "h264"
        select("h265", size, fps) != null -> "h265"
        else -> null
    }

    fun select(codec: String, size: Size, fps: Int): VideoEncoderSelection? {
        val mime = when (codec) {
            "h264" -> MediaFormat.MIMETYPE_VIDEO_AVC
            "h265" -> MediaFormat.MIMETYPE_VIDEO_HEVC
            else -> return null
        }
        val candidates = if (codec == "h264") h264 else h265
        return candidates.mapNotNull { info ->
            val video = runCatching { info.getCapabilitiesForType(mime).videoCapabilities }.getOrNull() ?: return@mapNotNull null
            val widthAligned = size.width % video.widthAlignment == 0
            val heightAligned = size.height % video.heightAlignment == 0
            if (!widthAligned || !heightAligned || !video.isSizeSupported(size.width, size.height) || !video.areSizeAndRateSupported(size.width, size.height, fps.toDouble())) {
                return@mapNotNull null
            }
            VideoEncoderSelection(
                name = info.name,
                codec = codec,
                mime = mime,
                hardwareAccelerated = isHardwareAccelerated(info),
                widthAlignment = video.widthAlignment,
                heightAlignment = video.heightAlignment,
                minBitrate = video.bitrateRange.lower,
                maxBitrate = video.bitrateRange.upper,
                profileLevels = info.getCapabilitiesForType(mime).profileLevels.map { it.profile to it.level }
            )
        }.sortedWith(
            compareByDescending<VideoEncoderSelection> { it.hardwareAccelerated }
                .thenByDescending { it.maxBitrate }
                .thenBy { it.name }
        ).firstOrNull()
    }

    private fun encodersFor(mime: String): List<MediaCodecInfo> = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        .filter { it.isEncoder && it.supportedTypes.any { supported -> supported.equals(mime, ignoreCase = true) } }

    private fun isHardwareAccelerated(info: MediaCodecInfo): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> info.isHardwareAccelerated && !info.isSoftwareOnly
        else -> !info.name.startsWith("OMX.google.", ignoreCase = true) && !info.name.contains("software", ignoreCase = true)
    }
}
