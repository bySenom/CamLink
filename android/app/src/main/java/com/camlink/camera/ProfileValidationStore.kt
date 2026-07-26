package com.camlink.camera

import android.content.Context
import android.os.Build
import org.json.JSONObject

data class ProfileValidationReport(
    val cameraId: String,
    val profile: CameraProfile,
    val status: ProfileVerification,
    val requestedFps: Int,
    val measuredFps: Double,
    val encodedFrames: Int,
    val captureResults: Int,
    val droppedFramesEstimate: Int,
    val actualWidth: Int,
    val actualHeight: Int,
    val averageBitrate: Int,
    val exposureTimeNs: Long?,
    val encoderName: String,
    val encoderProfile: Int?,
    val encoderLevel: Int?,
    val thermalStatus: Int?,
    val message: String,
    val timestampMs: Long = System.currentTimeMillis()
)

/** Device-, build- and camera-specific validation cache. A user-triggered scan overwrites entries. */
class ProfileValidationStore(context: Context) {
    private val preferences = context.getSharedPreferences("camlink.profileValidation", Context.MODE_PRIVATE)

    fun verificationFor(cameraId: String, profile: CameraProfile): ProfileVerification = reportFor(cameraId, profile)?.status
        ?: ProfileVerification.REPORTED

    fun reportFor(cameraId: String, profile: CameraProfile): ProfileValidationReport? {
        val raw = preferences.getString(key(cameraId, profile), null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            ProfileValidationReport(
                cameraId = cameraId,
                profile = profile,
                status = ProfileVerification.valueOf(json.getString("status")),
                requestedFps = json.getInt("requestedFps"),
                measuredFps = json.optDouble("measuredFps"),
                encodedFrames = json.optInt("encodedFrames"),
                captureResults = json.optInt("captureResults"),
                droppedFramesEstimate = json.optInt("droppedFramesEstimate"),
                actualWidth = json.optInt("actualWidth", profile.width),
                actualHeight = json.optInt("actualHeight", profile.height),
                averageBitrate = json.optInt("averageBitrate"),
                exposureTimeNs = if (json.has("exposureTimeNs")) json.optLong("exposureTimeNs") else null,
                encoderName = json.optString("encoderName"),
                encoderProfile = if (json.has("encoderProfile")) json.optInt("encoderProfile") else null,
                encoderLevel = if (json.has("encoderLevel")) json.optInt("encoderLevel") else null,
                thermalStatus = if (json.has("thermalStatus")) json.optInt("thermalStatus") else null,
                message = json.optString("message"),
                timestampMs = json.optLong("timestampMs")
            )
        }.getOrNull()
    }

    fun save(report: ProfileValidationReport) {
        val json = JSONObject().apply {
            put("status", report.status.name)
            put("requestedFps", report.requestedFps)
            put("measuredFps", report.measuredFps)
            put("encodedFrames", report.encodedFrames)
            put("captureResults", report.captureResults)
            put("droppedFramesEstimate", report.droppedFramesEstimate)
            put("actualWidth", report.actualWidth)
            put("actualHeight", report.actualHeight)
            put("averageBitrate", report.averageBitrate)
            report.exposureTimeNs?.let { put("exposureTimeNs", it) }
            put("encoderName", report.encoderName)
            report.encoderProfile?.let { put("encoderProfile", it) }
            report.encoderLevel?.let { put("encoderLevel", it) }
            report.thermalStatus?.let { put("thermalStatus", it) }
            put("message", report.message)
            put("timestampMs", report.timestampMs)
        }
        preferences.edit().putString(key(report.cameraId, report.profile), json.toString()).apply()
    }

    private fun key(cameraId: String, profile: CameraProfile): String {
        val identity = "${Build.FINGERPRINT}|$cameraId|${profile.width}x${profile.height}@${profile.fps}|${profile.highSpeed}|${profile.codec}"
        return "validation.${identity.hashCode().toUInt().toString(16)}"
    }
}
