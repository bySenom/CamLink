package com.camlink.camera

enum class ProfileSource {
    CAMERA2,
    CAMCORDER_HINT,
    HIGH_SPEED
}

enum class ProfileVerification {
    /** Camera2 metadata and the selected hardware encoder report this combination. */
    REPORTED,
    /** A local capture-session probe completed with frames from the camera and encoder. */
    VERIFIED,
    /** The probe completed, but the measured output was below the requested frame rate. */
    UNSTABLE,
    /** The device or encoder rejected the profile during the local probe. */
    UNSUPPORTED
}

data class CameraProfile(
    val width: Int,
    val height: Int,
    val fps: Int,
    val highSpeed: Boolean,
    val codec: String = "h264",
    val source: ProfileSource = if (highSpeed) ProfileSource.HIGH_SPEED else ProfileSource.CAMERA2,
    val verification: ProfileVerification = ProfileVerification.REPORTED
) {
    override fun toString(): String {
        val status = when (verification) {
            ProfileVerification.VERIFIED -> " verified"
            ProfileVerification.UNSTABLE -> " unstable"
            ProfileVerification.UNSUPPORTED -> " unsupported"
            ProfileVerification.REPORTED -> ""
        }
        return "${width}×${height} @ ${fps} fps${if (highSpeed) " high-speed" else ""}$status"
    }
}

data class CameraControlCapabilities(
    val supportsManualSensor: Boolean = false,
    val isoMin: Int? = null,
    val isoMax: Int? = null,
    val exposureTimeMinNs: Long? = null,
    val exposureTimeMaxNs: Long? = null,
    val minFocusDistance: Float = 0f,
    val supportsAeLock: Boolean = false,
    val supportsAwbLock: Boolean = false,
    val antiBandingModes: List<Int> = emptyList(),
    val opticalStabilizationModes: List<Int> = emptyList(),
    val videoStabilizationModes: List<Int> = emptyList(),
    val noiseReductionModes: List<Int> = emptyList(),
    val edgeModes: List<Int> = emptyList()
)

data class PhysicalCameraInfo(
    val id: String,
    val hardwareLevel: Int,
    val sensorWidthMm: Float?,
    val sensorHeightMm: Float?,
    val focalLengthsMm: List<Float>,
    val fpsRanges: List<Pair<Int, Int>>,
    val outputSizes: List<Pair<Int, Int>>,
    val highSpeedSizes: List<Pair<Int, Int>>,
    val highSpeedFpsRanges: List<Pair<Int, Int>>
)

data class CameraDiagnostics(
    val hardwareLevel: Int,
    val availableCapabilities: List<Int>,
    val physicalCameras: List<PhysicalCameraInfo>,
    val sensorWidthMm: Float?,
    val sensorHeightMm: Float?,
    val focalLengthsMm: List<Float>,
    val orientation: Int,
    val fpsRanges: List<Pair<Int, Int>>,
    val outputSizes: List<Pair<Int, Int>>,
    val highSpeedSizes: List<Pair<Int, Int>>,
    val highSpeedFpsRanges: List<Pair<Int, Int>>
)

data class CameraDescriptor(
    val id: String,
    val name: String,
    val maxZoom: Float,
    val hasFlash: Boolean,
    val profiles: List<CameraProfile>,
    val exposureMin: Int,
    val exposureMax: Int,
    val whiteBalanceModes: List<String>,
    /** 0 = continuous video, 1 = auto one-shot, 2 = focus locked. */
    val focusModes: List<Int>,
    val controls: CameraControlCapabilities,
    val diagnostics: CameraDiagnostics
) {
    override fun toString(): String = name
}

data class CameraCapabilities(
    val deviceName: String,
    val cameras: List<CameraDescriptor>,
    val exposureMin: Int,
    val exposureMax: Int,
    val whiteBalanceModes: List<String>
)

data class StreamConfiguration(
    val cameraId: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val highSpeed: Boolean,
    val codec: String = "h264"
)
