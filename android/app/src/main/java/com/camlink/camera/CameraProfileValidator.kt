package com.camlink.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.util.Range
import android.util.Log
import android.util.Size
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * User-triggered, one-profile-at-a-time Camera2/MediaCodec validation. It never
 * runs alongside [CameraPipeline]; MainActivity only starts it from the connect screen.
 */
class CameraProfileValidator(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(CameraManager::class.java)
    private val encoders = VideoEncoderProbe()
    private val store = ProfileValidationStore(appContext)
    private val powerManager = appContext.getSystemService(PowerManager::class.java)

    fun validateAll(
        capabilities: CameraCapabilities,
        onProgress: (completed: Int, total: Int, camera: CameraDescriptor, profile: CameraProfile) -> Unit,
        onComplete: (List<ProfileValidationReport>) -> Unit
    ) {
        val candidates = capabilities.cameras.flatMap { camera ->
            camera.profiles
                .map { profile -> camera to profile }
        }
        Thread {
            val reports = mutableListOf<ProfileValidationReport>()
            candidates.forEachIndexed { index, (camera, profile) ->
                onProgress(index, candidates.size, camera, profile)
                val report = validateOneBlocking(camera, profile)
                store.save(report)
                reports += report
            }
            onComplete(reports)
        }.apply {
            name = "CamLinkProfileValidation"
            start()
        }
    }

    private fun validateOneBlocking(camera: CameraDescriptor, profile: CameraProfile): ProfileValidationReport {
        val latch = CountDownLatch(1)
        var result: ProfileValidationReport? = null
        validateOne(camera, profile) {
            result = it
            latch.countDown()
        }
        if (!latch.await(VALIDATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            return failureReport(camera.id, profile, "Timed out waiting for Camera2 validation.")
        }
        return result ?: failureReport(camera.id, profile, "Camera2 validation returned no result.")
    }

    @SuppressLint("MissingPermission")
    private fun validateOne(camera: CameraDescriptor, profile: CameraProfile, callback: (ProfileValidationReport) -> Unit) {
        val thread = HandlerThread("CamLinkValidate-${camera.id}-${profile.height}p${profile.fps}").apply { start() }
        val handler = Handler(thread.looper)
        val completed = AtomicBoolean(false)
        val selection = encoders.select(profile.codec, Size(profile.width, profile.height), profile.fps)
        if (selection == null) {
            thread.quitSafely()
            callback(failureReport(camera.id, profile, "No ${profile.codec} encoder supports this profile."))
            return
        }

        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var encoder: MediaCodec? = null
        var encoderSurface: Surface? = null
        var previewReader: ImageReader? = null
        var previewSurface: Surface? = null
        var failure: String? = null
        var encodedFrames = 0
        var encodedBytes = 0L
        var captureResults = 0
        var firstFramePtsUs: Long? = null
        var lastFramePtsUs: Long? = null
        var firstSensorTimestampNs: Long? = null
        var lastSensorTimestampNs: Long? = null
        var actualWidth = profile.width
        var actualHeight = profile.height
        var encoderProfile: Int? = null
        var encoderLevel: Int? = null
        var actualExposureTimeNs: Long? = null
        val startedNs = System.nanoTime()
        lateinit var openTimeout: Runnable

        fun closeResources() {
            try { session?.stopRepeating() } catch (_: Exception) { }
            try { session?.close() } catch (_: Exception) { }
            try { device?.close() } catch (_: Exception) { }
            try { encoder?.stop() } catch (_: Exception) { }
            try { encoder?.release() } catch (_: Exception) { }
            try { encoderSurface?.release() } catch (_: Exception) { }
            try { previewSurface?.release() } catch (_: Exception) { }
            try { previewReader?.close() } catch (_: Exception) { }
            session = null
            device = null
            encoder = null
            encoderSurface = null
            previewSurface = null
            previewReader = null
        }

        fun finish(message: String? = null) {
            if (!completed.compareAndSet(false, true)) return
            handler.removeCallbacksAndMessages(null)
            val measuredDurationUs = when {
                firstFramePtsUs != null && lastFramePtsUs != null && lastFramePtsUs!! > firstFramePtsUs!! -> lastFramePtsUs!! - firstFramePtsUs!!
                firstSensorTimestampNs != null && lastSensorTimestampNs != null && lastSensorTimestampNs!! > firstSensorTimestampNs!! -> (lastSensorTimestampNs!! - firstSensorTimestampNs!!) / 1_000L
                else -> (System.nanoTime() - startedNs) / 1_000L
            }.coerceAtLeast(1L)
            val measuredFps = when {
                encodedFrames > 1 -> (encodedFrames - 1) * 1_000_000.0 / measuredDurationUs
                captureResults > 1 -> (captureResults - 1) * 1_000_000.0 / measuredDurationUs
                else -> 0.0
            }
            val expectedFrames = (profile.fps * measuredDurationUs / 1_000_000.0).toInt()
            val status = when {
                failure != null -> ProfileVerification.UNSUPPORTED
                encodedFrames < MINIMUM_ENCODED_FRAMES || captureResults < MINIMUM_CAPTURE_RESULTS -> ProfileVerification.UNSTABLE
                measuredFps < profile.fps * 0.80 -> ProfileVerification.UNSTABLE
                else -> ProfileVerification.VERIFIED
            }
            val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) powerManager.currentThermalStatus else null
            val report = ProfileValidationReport(
                cameraId = camera.id,
                profile = profile,
                status = status,
                requestedFps = profile.fps,
                measuredFps = measuredFps,
                encodedFrames = encodedFrames,
                captureResults = captureResults,
                droppedFramesEstimate = max(0, expectedFrames - encodedFrames),
                actualWidth = actualWidth,
                actualHeight = actualHeight,
                averageBitrate = (encodedBytes * 8_000_000L / measuredDurationUs).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                exposureTimeNs = actualExposureTimeNs,
                encoderName = selection.name,
                encoderProfile = encoderProfile,
                encoderLevel = encoderLevel,
                thermalStatus = thermalStatus,
                message = message ?: failure ?: "Validated ${actualWidth}x${actualHeight} @ ${"%.1f".format(measuredFps)} fps"
            )
            Log.i(LOG_TAG, "camera=${camera.id} profile=${profile.width}x${profile.height}@${profile.fps}/${profile.codec} " +
                "status=${report.status} measuredFps=${"%.2f".format(report.measuredFps)} encoded=${report.encodedFrames} " +
                "captures=${report.captureResults} droppedEstimate=${report.droppedFramesEstimate} bitrate=${report.averageBitrate} " +
                "encoder=${report.encoderName} profile=${report.encoderProfile} level=${report.encoderLevel} thermal=${report.thermalStatus} message=${report.message}")
            closeResources()
            thread.quitSafely()
            callback(report)
        }

        val captureCallback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                captureSession: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                captureResults++
                result.get(CaptureResult.SENSOR_TIMESTAMP)?.let { timestamp ->
                    if (firstSensorTimestampNs == null) firstSensorTimestampNs = timestamp
                    lastSensorTimestampNs = timestamp
                }
                result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { actualExposureTimeNs = it }
            }

            override fun onCaptureFailed(
                captureSession: CameraCaptureSession,
                request: CaptureRequest,
                failureResult: CaptureFailure
            ) {
                failure = "Capture failed: reason ${failureResult.reason}"
                finish()
            }
        }

        val codecCallback = object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                try {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        encodedFrames++
                        encodedBytes += info.size
                        if (firstFramePtsUs == null) firstFramePtsUs = info.presentationTimeUs
                        lastFramePtsUs = info.presentationTimeUs
                    }
                } finally {
                    runCatching { codec.releaseOutputBuffer(index, false) }
                }
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                if (format.containsKey(MediaFormat.KEY_WIDTH)) actualWidth = format.getInteger(MediaFormat.KEY_WIDTH)
                if (format.containsKey(MediaFormat.KEY_HEIGHT)) actualHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
                if (format.containsKey(MediaFormat.KEY_PROFILE)) encoderProfile = format.getInteger(MediaFormat.KEY_PROFILE)
                if (format.containsKey(MediaFormat.KEY_LEVEL)) encoderLevel = format.getInteger(MediaFormat.KEY_LEVEL)
            }

            override fun onError(codec: MediaCodec, exception: MediaCodec.CodecException) {
                failure = "Encoder error: ${exception.diagnosticInfo}"
                finish()
            }
        }

        val cameraCallback = object : CameraDevice.StateCallback() {
            override fun onOpened(openedDevice: CameraDevice) {
                device = openedDevice
                try {
                    val format = MediaFormat.createVideoFormat(selection.mime, profile.width, profile.height).apply {
                        setInteger(MediaFormat.KEY_COLOR_FORMAT, android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                        setInteger(MediaFormat.KEY_BIT_RATE, validationBitrate(profile, selection))
                        setInteger(MediaFormat.KEY_FRAME_RATE, profile.fps)
                        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    }
                    encoder = MediaCodec.createByCodecName(selection.name).apply {
                        setCallback(codecCallback, handler)
                        configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                        encoderSurface = createInputSurface()
                        start()
                    }
                    val target = requireNotNull(encoderSurface)
                    // The live pipeline uses a private preview surface alongside the encoder.
                    // Keep the validator's output topology identical, and drain every private
                    // image so this bounded test cannot stall the capture session.
                    previewReader = ImageReader.newInstance(
                        profile.width,
                        profile.height,
                        android.graphics.ImageFormat.PRIVATE,
                        3
                    ).apply {
                        setOnImageAvailableListener({ reader ->
                            while (true) {
                                val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: break
                                image.close()
                            }
                        }, handler)
                    }
                    previewSurface = requireNotNull(previewReader).surface
                    val request = openedDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(target)
                        addTarget(requireNotNull(previewSurface))
                        if (!profile.highSpeed) set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(profile.fps, profile.fps))
                    }
                    if (profile.highSpeed) {
                        openedDevice.createConstrainedHighSpeedCaptureSession(listOf(target, requireNotNull(previewSurface)), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(configuredSession: CameraCaptureSession) {
                                handler.removeCallbacks(openTimeout)
                                session = configuredSession
                                val highSpeedSession = configuredSession as CameraConstrainedHighSpeedCaptureSession
                                highSpeedSession.setRepeatingBurst(highSpeedSession.createHighSpeedRequestList(request.build()), captureCallback, handler)
                                handler.postDelayed({ finish() }, CAPTURE_WINDOW_MS)
                            }

                            override fun onConfigureFailed(configuredSession: CameraCaptureSession) {
                                failure = "High-speed capture session rejected by Camera2."
                                finish()
                            }
                        }, handler)
                    } else {
                        openedDevice.createCaptureSession(listOf(target, requireNotNull(previewSurface)), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(configuredSession: CameraCaptureSession) {
                                handler.removeCallbacks(openTimeout)
                                session = configuredSession
                                configuredSession.setRepeatingRequest(request.build(), captureCallback, handler)
                                handler.postDelayed({ finish() }, CAPTURE_WINDOW_MS)
                            }

                            override fun onConfigureFailed(configuredSession: CameraCaptureSession) {
                                failure = "Normal capture session rejected by Camera2."
                                finish()
                            }
                        }, handler)
                    }
                } catch (exception: Exception) {
                    failure = "Validation setup failed: ${exception.message}"
                    finish()
                }
            }

            override fun onDisconnected(disconnectedDevice: CameraDevice) {
                failure = "Camera disconnected during validation."
                finish()
            }

            override fun onError(errorDevice: CameraDevice, error: Int) {
                failure = "Camera error $error during validation."
                finish()
            }
        }

        openTimeout = Runnable {
            failure = failure ?: "Camera open timed out."
            finish()
        }
        try {
            handler.postDelayed(openTimeout, OPEN_TIMEOUT_MS)
            manager.openCamera(camera.id, cameraCallback, handler)
        } catch (exception: Exception) {
            failure = "Could not open camera: ${exception.message}"
            finish()
        }
    }

    private fun validationBitrate(profile: CameraProfile, encoder: VideoEncoderSelection): Int {
        val megabits = when {
            profile.width >= 7680 -> if (profile.codec == "h265") 80 else 120
            profile.width >= 3840 && profile.fps >= 60 -> if (profile.codec == "h265") 70 else 100
            profile.width >= 3840 -> if (profile.codec == "h265") 42 else 55
            profile.width >= 2560 && profile.fps >= 60 -> if (profile.codec == "h265") 32 else 48
            profile.width >= 2560 -> if (profile.codec == "h265") 22 else 32
            profile.width >= 1920 && profile.fps >= 60 -> if (profile.codec == "h265") 18 else 28
            profile.width >= 1920 -> if (profile.codec == "h265") 11 else 16
            profile.fps >= 60 -> if (profile.codec == "h265") 10 else 16
            else -> if (profile.codec == "h265") 7 else 10
        }
        return (megabits * 1_000_000).coerceIn(encoder.minBitrate, encoder.maxBitrate)
    }

    private fun failureReport(cameraId: String, profile: CameraProfile, message: String): ProfileValidationReport = ProfileValidationReport(
        cameraId = cameraId,
        profile = profile,
        status = ProfileVerification.UNSUPPORTED,
        requestedFps = profile.fps,
        measuredFps = 0.0,
        encodedFrames = 0,
        captureResults = 0,
        droppedFramesEstimate = 0,
        actualWidth = profile.width,
        actualHeight = profile.height,
        averageBitrate = 0,
        exposureTimeNs = null,
        encoderName = "",
        encoderProfile = null,
        encoderLevel = null,
        thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) powerManager.currentThermalStatus else null,
        message = message
    )

    private companion object {
        const val LOG_TAG = "CamLinkValidation"
        const val CAPTURE_WINDOW_MS = 3_000L
        const val OPEN_TIMEOUT_MS = 6_000L
        const val VALIDATION_TIMEOUT_SECONDS = 10L
        const val MINIMUM_ENCODED_FRAMES = 3
        const val MINIMUM_CAPTURE_RESULTS = 3
    }
}
