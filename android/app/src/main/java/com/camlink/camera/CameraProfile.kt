package com.camlink.camera

data class CameraProfile(
    val width: Int,
    val height: Int,
    val fps: Int,
    val highSpeed: Boolean,
    val codec: String = "h264"
) {
    override fun toString(): String = "${width}×${height} @ ${fps} fps${if (highSpeed) " high-speed" else ""}"
}

data class CameraDescriptor(
    val id: String,
    val name: String,
    val maxZoom: Float,
    val hasFlash: Boolean,
    val profiles: List<CameraProfile>
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
