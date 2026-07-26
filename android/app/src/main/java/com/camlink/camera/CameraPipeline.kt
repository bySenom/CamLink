package com.camlink.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.view.Surface
import android.view.TextureView
import org.json.JSONObject
import java.nio.ByteBuffer
import kotlin.math.max

class CameraPipeline(
    context: Context,
    private val previewView: TextureView,
    private val hub: HubClient
) {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val encoders = VideoEncoderProbe()
    private val cameraThread = HandlerThread("CamLinkCamera").apply { start() }
    private val handler = Handler(cameraThread.looper)
    private val lock = Any()
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var encoder: MediaCodec? = null
    private var encoderSurface: Surface? = null
    private var previewSurface: Surface? = null
    private var requestBuilder: CaptureRequest.Builder? = null
    private var characteristics: CameraCharacteristics? = null
    private var configuration: StreamConfiguration? = null
    private var zoom = 1f
    private var exposure = 0
    private var whiteBalance = "Auto"
    private var torch = false
    private var focusMode = 0
    private var triggerAutoFocus = false

    val isStreaming: Boolean get() = session != null

    @SuppressLint("MissingPermission")
    fun start(config: StreamConfiguration) {
        stop()
        configuration = config
        handler.post {
            try {
                characteristics = manager.getCameraCharacteristics(config.cameraId)
                prepareEncoder(config)
                if (!previewView.isAvailable) {
                    throw IllegalStateException("Preview surface is not ready yet. Keep the CamLink app in the foreground and try again.")
                }
                val texture = requireNotNull(previewView.surfaceTexture)
                texture.setDefaultBufferSize(config.width, config.height)
                previewSurface = Surface(texture)
                manager.openCamera(config.cameraId, cameraCallback, handler)
            } catch (exception: Exception) {
                hub.sendStatus("Cannot start ${config.width}x${config.height}@${config.fps}: ${exception.message}", error = true)
                stop()
            }
        }
    }

    fun stop() {
        handler.post {
            try { session?.stopRepeating() } catch (_: Exception) { }
            try { session?.close() } catch (_: Exception) { }
            try { device?.close() } catch (_: Exception) { }
            try { encoder?.stop() } catch (_: Exception) { }
            try { encoder?.release() } catch (_: Exception) { }
            try { encoderSurface?.release() } catch (_: Exception) { }
            try { previewSurface?.release() } catch (_: Exception) { }
            session = null
            device = null
            encoder = null
            encoderSurface = null
            previewSurface = null
            requestBuilder = null
            characteristics = null
        }
    }

    fun release() {
        stop()
        cameraThread.quitSafely()
    }

    fun applyCommand(command: JSONObject) {
        val name = command.optString("name")
        val value = command.opt("value")
        when (name) {
            "start" -> {
                val json = value as? JSONObject ?: return
                start(StreamConfiguration(
                    cameraId = json.getString("cameraId"),
                    width = json.getInt("width"),
                    height = json.getInt("height"),
                    fps = json.getInt("fps"),
                    highSpeed = json.optBoolean("highSpeed"),
                    codec = json.optString("codec", "h264")
                ))
            }
            "stop" -> {
                stop()
                hub.sendStatus("Camera stopped")
            }
            "selectCamera" -> {
                val cameraId = value as? String ?: return
                configuration = configuration?.copy(cameraId = cameraId)
                hub.sendStatus("Camera selected. Press Start stream to apply it.")
            }
            "selectProfile" -> {
                val json = value as? JSONObject ?: return
                configuration = configuration?.copy(
                    width = json.getInt("width"),
                    height = json.getInt("height"),
                    fps = json.getInt("fps"),
                    highSpeed = json.optBoolean("highSpeed"),
                    codec = json.optString("codec", "h264")
                )
                hub.sendStatus("Profile selected. Press Start stream to apply it.")
            }
            "setZoom" -> {
                zoom = (value as? Number)?.toFloat() ?: zoom
                applyControls()
            }
            "setExposure" -> {
                exposure = (value as? Number)?.toInt() ?: exposure
                applyControls()
            }
            "setWhiteBalance" -> {
                whiteBalance = value?.toString() ?: "Auto"
                applyControls()
            }
            "setTorch" -> {
                torch = value as? Boolean ?: false
                applyControls()
            }
            "setFocusMode" -> {
                focusMode = (value as? Number)?.toInt() ?: 0
                triggerAutoFocus = focusMode == 1
                applyControls()
            }
        }
    }

    private fun prepareEncoder(config: StreamConfiguration) {
        val selection = encoders.select(config.codec, android.util.Size(config.width, config.height), config.fps)
            ?: throw UnsupportedOperationException("No ${config.codec} encoder supports ${config.width}x${config.height}@${config.fps}.")
        val bitrate = chooseBitrate(config, selection)
        val mime = selection.mime
        val format = MediaFormat.createVideoFormat(mime, config.width, config.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            }
        }
        encoder = MediaCodec.createByCodecName(selection.name).apply {
            setCallback(encoderCallback, handler)
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoderSurface = createInputSurface()
            start()
        }
    }

    private fun chooseBitrate(config: StreamConfiguration, encoder: VideoEncoderSelection): Int {
        val megabits = when {
            // 8K needs an intentionally high HEVC budget. A blanket HEVC multiplier
            // would make it look encodable while visibly starving the stream.
            config.width >= 7680 -> if (config.codec == "h265") 80 else 120
            config.width >= 3840 && config.fps >= 60 -> if (config.codec == "h265") 70 else 100
            config.width >= 3840 -> if (config.codec == "h265") 42 else 55
            config.width >= 2560 && config.fps >= 60 -> if (config.codec == "h265") 32 else 48
            config.width >= 2560 -> if (config.codec == "h265") 22 else 32
            config.width >= 1920 && config.fps >= 60 -> if (config.codec == "h265") 18 else 28
            config.width >= 1920 -> if (config.codec == "h265") 11 else 16
            config.fps >= 60 -> if (config.codec == "h265") 10 else 16
            else -> if (config.codec == "h265") 7 else 10
        }
        return (megabits * 1_000_000).coerceIn(encoder.minBitrate, encoder.maxBitrate)
    }

    private val encoderCallback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            // Surface input; no byte-buffer input is supplied.
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            try {
                if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                    val output = requireNotNull(codec.getOutputBuffer(index)).duplicate()
                    output.position(info.offset)
                    output.limit(info.offset + info.size)
                    hub.sendVideoFrame(output.slice(), info.size, info.presentationTimeUs)
                }
            } catch (exception: Exception) {
                hub.sendStatus("Encoder output error: ${exception.message}", error = true)
            } finally {
                codec.releaseOutputBuffer(index, false)
            }
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            val codecName = configuration?.codec ?: "h264"
            val configUnits = mutableListOf<ByteArray>()
            format.getByteBuffer("csd-0")?.let { configUnits += it.copyRemaining() }
            format.getByteBuffer("csd-1")?.let { configUnits += it.copyRemaining() }
            if (codecName == "h264") {
                val sps = configUnits.firstOrNull { it.h264Type() == 7 } ?: configUnits.firstOrNull()
                    ?: throw IllegalStateException("Encoder supplied no H.264 SPS.")
                val pps = configUnits.firstOrNull { it.h264Type() == 8 } ?: configUnits.getOrNull(1)
                    ?: throw IllegalStateException("Encoder supplied no H.264 PPS.")
                hub.sendVideoConfig("h264", sps, pps, null, configuration?.fps ?: 30)
            } else {
                val units = configUnits.flatMap { it.splitAnnexBNals() }
                val vps = units.firstOrNull { it.h265Type() == 32 } ?: throw IllegalStateException("Encoder supplied no H.265 VPS.")
                val sps = units.firstOrNull { it.h265Type() == 33 } ?: throw IllegalStateException("Encoder supplied no H.265 SPS.")
                val pps = units.firstOrNull { it.h265Type() == 34 } ?: throw IllegalStateException("Encoder supplied no H.265 PPS.")
                hub.sendVideoConfig("h265", sps, pps, vps, configuration?.fps ?: 30)
            }
        }

        override fun onError(codec: MediaCodec, exception: MediaCodec.CodecException) {
            hub.sendStatus("Video encoder error: ${exception.diagnosticInfo}", error = true)
        }
    }

    private val cameraCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            device = camera
            try {
                configureSession(camera)
            } catch (exception: Exception) {
                hub.sendStatus("Cannot configure camera session: ${exception.message}", error = true)
                stop()
            }
        }

        override fun onDisconnected(camera: CameraDevice) {
            hub.sendStatus("Camera disconnected", error = true)
            stop()
        }

        override fun onError(camera: CameraDevice, error: Int) {
            hub.sendStatus("Camera error $error", error = true)
            stop()
        }
    }

    private fun configureSession(camera: CameraDevice) {
        val encoderTarget = requireNotNull(encoderSurface)
        val previewTarget = requireNotNull(previewSurface)
        val config = requireNotNull(configuration)
        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(encoderTarget)
            addTarget(previewTarget)
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(config.fps, config.fps))
        }
        requestBuilder = builder
        applyControlsTo(builder)

        if (config.highSpeed) {
            camera.createConstrainedHighSpeedCaptureSession(listOf(encoderTarget, previewTarget), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(captureSession: CameraCaptureSession) {
                    session = captureSession
                    applyRepeatingRequest()
                    hub.sendStatus("Camera streaming ${config.width}x${config.height} @ ${config.fps} fps (high-speed; focus/white balance may be device-managed)")
                }

                override fun onConfigureFailed(captureSession: CameraCaptureSession) {
                    hub.sendStatus("High-speed profile was rejected by the camera.", error = true)
                }
            }, handler)
        } else {
            camera.createCaptureSession(listOf(encoderTarget, previewTarget), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(captureSession: CameraCaptureSession) {
                    session = captureSession
                    applyRepeatingRequest()
                    hub.sendStatus("Camera streaming ${config.width}x${config.height} @ ${config.fps} fps")
                }

                override fun onConfigureFailed(captureSession: CameraCaptureSession) {
                    hub.sendStatus("Camera profile was rejected by the device/encoder.", error = true)
                }
            }, handler)
        }
    }

    private fun applyControls() {
        handler.post {
            val builder = requestBuilder ?: return@post
            applyControlsTo(builder)
            val triggerFocus = triggerAutoFocus && configuration?.highSpeed != true
            triggerAutoFocus = false
            if (triggerFocus) {
                try {
                    builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                    session?.capture(builder.build(), null, handler)
                } catch (exception: Exception) {
                    hub.sendStatus("Auto focus trigger was rejected: ${exception.message}", error = true)
                } finally {
                    builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
                }
            }
            applyRepeatingRequest()
        }
    }

    private fun applyControlsTo(builder: CaptureRequest.Builder) {
        val chars = characteristics ?: return
        val streamConfig = configuration ?: return
        val availableAwb = chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.toSet().orEmpty()
        val requestedAwb = when (whiteBalance) {
            "Daylight" -> CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT
            "Cloudy" -> CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
            "Incandescent" -> CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT
            "Fluorescent" -> CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT
            else -> CaptureRequest.CONTROL_AWB_MODE_AUTO
        }
        val availableAf = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toSet().orEmpty()
        val requestedAf = when (focusMode) {
            1 -> CaptureRequest.CONTROL_AF_MODE_AUTO
            2 -> CaptureRequest.CONTROL_AF_MODE_OFF
            else -> CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
        }
        val appliedAf = when {
            availableAf.contains(requestedAf) -> requestedAf
            availableAf.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO) -> CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            availableAf.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) -> CaptureRequest.CONTROL_AF_MODE_AUTO
            availableAf.contains(CaptureRequest.CONTROL_AF_MODE_OFF) -> CaptureRequest.CONTROL_AF_MODE_OFF
            else -> CaptureRequest.CONTROL_AF_MODE_OFF
        }
        val exposureRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val appliedExposure = exposureRange?.clamp(exposure) ?: 0
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        builder.set(CaptureRequest.CONTROL_AE_LOCK, false)
        builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, appliedExposure)
        builder.set(CaptureRequest.FLASH_MODE, if (torch && chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
        builder.set(CaptureRequest.CONTROL_AWB_LOCK, false)
        builder.set(
            CaptureRequest.CONTROL_AWB_MODE,
            if (availableAwb.contains(requestedAwb)) requestedAwb else CaptureRequest.CONTROL_AWB_MODE_AUTO
        )
        builder.set(CaptureRequest.CONTROL_AF_MODE, appliedAf)
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val maxZoom = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.upper ?: 1f
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom.coerceIn(1f, maxZoom))
        } else {
            val sensor = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            val maxZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
            if (sensor != null) {
                builder.set(CaptureRequest.SCALER_CROP_REGION, cropForZoom(sensor, zoom.coerceIn(1f, maxZoom)))
            }
        }
        if (!streamConfig.highSpeed) {
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(streamConfig.fps, streamConfig.fps))
        }
    }

    private fun applyRepeatingRequest() {
        val captureSession = session ?: return
        val builder = requestBuilder ?: return
        try {
            if (configuration?.highSpeed == true) {
                val highSpeedSession = captureSession as? CameraConstrainedHighSpeedCaptureSession
                    ?: throw IllegalStateException("Camera did not create a high-speed capture session.")
                highSpeedSession.setRepeatingBurst(highSpeedSession.createHighSpeedRequestList(builder.build()), null, handler)
            } else {
                captureSession.setRepeatingRequest(builder.build(), null, handler)
            }
        } catch (exception: Exception) {
            hub.sendStatus("Control update rejected: ${exception.message}", error = true)
        }
    }

    private fun Range<Int>.clamp(value: Int): Int = value.coerceIn(lower, upper)

    private fun cropForZoom(sensor: Rect, zoom: Float): Rect {
        val width = (sensor.width() / zoom).toInt()
        val height = (sensor.height() / zoom).toInt()
        val left = sensor.centerX() - width / 2
        val top = sensor.centerY() - height / 2
        return Rect(left, top, left + width, top + height)
    }

    private fun ByteBuffer.copyRemaining(): ByteArray {
        val duplicate = duplicate()
        return ByteArray(duplicate.remaining()).also { duplicate.get(it) }
    }

    private fun ByteArray.h264Type(): Int = normalizeNal().firstOrNull()?.toInt()?.and(0x1f) ?: -1
    private fun ByteArray.h265Type(): Int = normalizeNal().firstOrNull()?.toInt()?.shr(1)?.and(0x3f) ?: -1

    private fun ByteArray.normalizeNal(): ByteArray {
        val offset = when {
            size >= 4 && this[0] == 0.toByte() && this[1] == 0.toByte() && this[2] == 0.toByte() && this[3] == 1.toByte() -> 4
            size >= 3 && this[0] == 0.toByte() && this[1] == 0.toByte() && this[2] == 1.toByte() -> 3
            else -> 0
        }
        return copyOfRange(offset, size)
    }

    private fun ByteArray.splitAnnexBNals(): List<ByteArray> {
        val starts = mutableListOf<Int>()
        for (index in 0 until size - 3) {
            if (this[index] == 0.toByte() && this[index + 1] == 0.toByte() && (this[index + 2] == 1.toByte() || (this[index + 2] == 0.toByte() && this[index + 3] == 1.toByte()))) {
                starts += index
            }
        }
        if (starts.isEmpty()) return listOf(normalizeNal())
        return starts.mapIndexedNotNull { index, start ->
            val payloadStart = start + if (this[start + 2] == 1.toByte()) 3 else 4
            val end = starts.getOrElse(index + 1) { size }
            if (end > payloadStart) copyOfRange(payloadStart, end) else null
        }
    }
}
