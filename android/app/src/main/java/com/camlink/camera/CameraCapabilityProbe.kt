package com.camlink.camera

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.CamcorderProfile
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Range
import android.util.Size
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max

class CameraCapabilityProbe(context: Context) {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val encoders = VideoEncoderProbe()

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
        val focal = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: 0f
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
        return CameraDescriptor(
            id = id,
            name = name,
            maxZoom = max(1f, zoom),
            hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
            profiles = recorderProfiles(id, map).ifEmpty { normalProfiles(map, characteristics) } + highSpeedProfiles(map, characteristics),
            exposureMin = exposureRange.lower,
            exposureMax = exposureRange.upper,
            whiteBalanceModes = whiteBalanceModes(characteristics),
            focusModes = focusModes(characteristics)
        )
    }

    private fun cameraName(focalMm: Float): String = when {
        // The S22 reports its ultra-wide camera as 2.2 mm.  Keep this threshold
        // wide enough to avoid selecting it as the default main ("Wide") lens.
        focalMm <= 3.0f -> "Ultra-wide (${String.format("%.1f", focalMm)} mm)"
        focalMm >= 6.0f -> "Telephoto (${String.format("%.1f", focalMm)} mm)"
        else -> "Wide (${String.format("%.1f", focalMm)} mm)"
    }

    private fun normalProfiles(map: StreamConfigurationMap, characteristics: CameraCharacteristics): List<CameraProfile> {
        val fpsRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList().orEmpty()
        val outputSizes = map.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
        val requestedFps = listOf(30, 60)
        return outputSizes.flatMap { size ->
            requestedFps.mapNotNull { fps ->
                if (!containsFps(fpsRanges, fps) || !canReachFps(map, size, fps)) {
                    null
                } else {
                    val codec = encoders.codecFor(size, fps) ?: return@mapNotNull null
                    CameraProfile(size.width, size.height, fps, highSpeed = false, codec = codec)
                }
            }
        }
            .filter { profile -> profile.width >= 1280 && profile.height >= 720 && isVideoAspect(profile.width, profile.height) }
            .sortedWith(compareByDescending<CameraProfile> { it.width.toLong() * it.height }.thenByDescending { it.fps })
            .distinctBy { Triple(it.width, it.height, it.fps) }
    }

    /** Vendor-validated recorder combinations for this exact camera ID. */
    private fun recorderProfiles(cameraId: String, map: StreamConfigurationMap): List<CameraProfile> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return emptyList()
        val supportedSizes = map.getOutputSizes(SurfaceTexture::class.java)?.toSet().orEmpty()
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
                if (size !in supportedSizes || !isVideoAspect(size.width, size.height)) return@mapNotNull null
                val codec = encoders.codecFor(size, video.frameRate) ?: return@mapNotNull null
                CameraProfile(size.width, size.height, video.frameRate, highSpeed = false, codec = codec)
            }
        }
            .filter { it.width >= 1280 && it.height >= 720 }
            .sortedWith(compareByDescending<CameraProfile> { it.width.toLong() * it.height }.thenByDescending { it.fps })
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
                    CameraProfile(size.width, size.height, 120, highSpeed = true, codec = codec)
                }
        }
            .filter { it.width == 1920 && it.height == 1080 }
            .sortedWith(compareByDescending<CameraProfile> { it.width.toLong() * it.height }.thenByDescending { it.fps })
            .distinctBy { Triple(it.width, it.height, it.fps) }
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

    private fun canReachFps(map: StreamConfigurationMap, size: Size, fps: Int): Boolean = try {
        val minimumDurationNs = map.getOutputMinFrameDuration(SurfaceTexture::class.java, size)
        minimumDurationNs == 0L || minimumDurationNs <= 1_000_000_000L / fps
    } catch (_: IllegalArgumentException) {
        false
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
                    put("profiles", JSONArray().apply {
                        camera.profiles.forEach { profile ->
                            put(JSONObject().apply {
                                put("width", profile.width)
                                put("height", profile.height)
                                put("fps", profile.fps)
                                put("highSpeed", profile.highSpeed)
                                put("codec", profile.codec)
                            })
                        }
                    })
                })
            }
        })
    }
}

private class VideoEncoderProbe {
    private val h264 = encodersFor(MediaFormat.MIMETYPE_VIDEO_AVC)
    private val h265 = encodersFor(MediaFormat.MIMETYPE_VIDEO_HEVC)

    fun codecFor(size: Size, fps: Int): String? = when {
        supports(h264, MediaFormat.MIMETYPE_VIDEO_AVC, size, fps) -> "h264"
        supports(h265, MediaFormat.MIMETYPE_VIDEO_HEVC, size, fps) -> "h265"
        else -> null
    }

    private fun encodersFor(mime: String): List<MediaCodecInfo> = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        .filter { it.isEncoder && it.supportedTypes.any { supported -> supported.equals(mime, ignoreCase = true) } }

    private fun supports(infos: List<MediaCodecInfo>, mime: String, size: Size, fps: Int): Boolean = try {
        infos.any { info ->
            info.getCapabilitiesForType(mime).videoCapabilities
                ?.areSizeAndRateSupported(size.width, size.height, fps.toDouble()) == true
        }
    } catch (_: IllegalArgumentException) {
        false
    }
}
