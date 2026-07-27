package com.camlink.camera

import org.json.JSONArray
import org.json.JSONObject

enum class ChargingSource { NONE, USB, AC, WIRELESS, UNKNOWN }

enum class ProtectionProfile { QUALITY_LOCK, BALANCED, MAXIMUM_SAFETY, CUSTOM }

enum class ProtectionAction {
    NONE,
    INFORM,
    REDUCE_BITRATE,
    REDUCE_FPS,
    REDUCE_RESOLUTION,
    STOP_STREAM,
    RELEASE_RESOURCES,
    RESTORE_QUALITY
}

/** Public Android thermal-status values are kept here so policy tests do not need Android services. */
object ThermalStatus {
    const val NONE = 0
    const val LIGHT = 1
    const val MODERATE = 2
    const val SEVERE = 3
    const val CRITICAL = 4
    const val EMERGENCY = 5
    const val SHUTDOWN = 6

    val all = listOf(NONE, LIGHT, MODERATE, SEVERE, CRITICAL, EMERGENCY, SHUTDOWN)

    fun label(status: Int?): String = when (status) {
        NONE -> "Normal"
        LIGHT -> "Leicht"
        MODERATE -> "Moderat"
        SEVERE -> "Stark"
        CRITICAL -> "Kritisch"
        EMERGENCY -> "Notfall"
        SHUTDOWN -> "Abschaltung"
        else -> "Nicht verfügbar"
    }

    fun isEmergency(status: Int?) = status == EMERGENCY || status == SHUTDOWN
}

data class HealthStreamProfile(
    val width: Int,
    val height: Int,
    val fps: Int,
    val codec: String
) {
    override fun toString(): String = "${width}x${height}@${fps}/${codec}"
}

data class DeviceHealthState(
    val batteryLevelPercent: Int?,
    /** Battery temperature only; never an estimate of the whole phone temperature. */
    val batteryTemperatureCelsius: Float?,
    val isCharging: Boolean,
    val chargingSource: ChargingSource,
    val thermalStatus: Int?,
    val thermalStatusLabel: String,
    val thermalHeadroom: Float?,
    val actualFps: Float?,
    val droppedFrames: Long?,
    /** Frames dropped since the previous one-second metrics window. */
    val recentDroppedFrames: Long? = null,
    val activeProtectionAction: ProtectionAction? = null,
    val requestedProfile: HealthStreamProfile? = null,
    val activeProfile: HealthStreamProfile? = null,
    val activeBitrateMbps: Float? = null,
    val timestampMs: Long
)

data class ProtectionSettings(
    val schemaVersion: Int = SCHEMA_VERSION,
    val profile: ProtectionProfile = ProtectionProfile.BALANCED,
    val enabled: Boolean = true,
    val warningsEnabled: Boolean = true,
    val audibleWarnings: Boolean = false,
    val forwardWarningsToHub: Boolean = true,
    val automaticThrottlingEnabled: Boolean = true,
    val automaticStopEnabled: Boolean = true,
    val lowBatteryPercent: Int = 20,
    val criticalBatteryPercent: Int = 8,
    val stopOnCriticalBattery: Boolean = true,
    val ignoreLowBatteryWhileCharging: Boolean = true,
    val warnBatteryTemperature: Boolean = true,
    /** Device-dependent user threshold, not a universal safety limit. */
    val batteryTemperatureWarningCelsius: Float = 42f,
    /** Device-dependent user threshold, not a universal safety limit. */
    val batteryTemperatureCriticalCelsius: Float = 47f,
    val temperatureUnavailableAction: ProtectionAction = ProtectionAction.INFORM,
    val thermalActions: Map<Int, ProtectionAction> = balancedThermalActions(),
    val bitrateReductionPercent: Int = 25,
    val minimumBitrateMbps: Int = 8,
    val fpsFallbackOrder: List<Int> = listOf(60, 30),
    val resolutionFallbackHeights: List<Int> = listOf(2160, 1440, 1080, 720),
    val minimumActionIntervalMs: Long = 60_000L,
    val thresholdDurationMs: Long = 10_000L,
    val cooldownMs: Long = 180_000L,
    val automaticallyRestoreQuality: Boolean = false,
    val restoreRequiresConfirmation: Boolean = true,
    val maximumProfileChanges: Int = 3,
    val profileChangeWindowMs: Long = 900_000L
) {
    fun validationErrors(): List<String> = buildList {
        if (lowBatteryPercent !in 1..100) add("Low battery threshold must be between 1 and 100 percent.")
        if (criticalBatteryPercent !in 1..100 || criticalBatteryPercent > lowBatteryPercent) add("Critical battery threshold must be between 1 percent and the warning threshold.")
        if (batteryTemperatureWarningCelsius !in 0f..80f) add("Battery-temperature warning threshold must be between 0 and 80 °C.")
        if (batteryTemperatureCriticalCelsius !in 0f..80f || batteryTemperatureCriticalCelsius <= batteryTemperatureWarningCelsius) add("Battery-temperature critical threshold must be above the warning threshold and at most 80 °C.")
        if (bitrateReductionPercent !in 1..90) add("Bitrate reduction must be between 1 and 90 percent.")
        if (minimumBitrateMbps !in 1..200) add("Minimum bitrate must be between 1 and 200 Mbps.")
        if (fpsFallbackOrder.any { it !in 1..240 } || fpsFallbackOrder.isEmpty()) add("FPS fallback order must contain values between 1 and 240.")
        if (resolutionFallbackHeights.any { it !in 240..8640 } || resolutionFallbackHeights.isEmpty()) add("Resolution fallback order must contain sensible video heights.")
        if (minimumActionIntervalMs !in 1_000L..3_600_000L) add("Action interval must be between 1 second and 60 minutes.")
        if (thresholdDurationMs !in 0L..3_600_000L) add("Threshold duration must be between 0 seconds and 60 minutes.")
        if (cooldownMs !in 0L..7_200_000L) add("Cooldown must be between 0 seconds and 2 hours.")
        if (maximumProfileChanges !in 1..20) add("Maximum profile changes must be between 1 and 20.")
        if (profileChangeWindowMs !in 60_000L..86_400_000L) add("Profile-change window must be between 1 minute and 24 hours.")
    }

    companion object {
        const val SCHEMA_VERSION = 1

        fun preset(profile: ProtectionProfile): ProtectionSettings = when (profile) {
            ProtectionProfile.QUALITY_LOCK -> ProtectionSettings(
                profile = profile,
                automaticThrottlingEnabled = false,
                thermalActions = qualityLockThermalActions()
            )
            ProtectionProfile.BALANCED -> ProtectionSettings(
                profile = profile,
                thermalActions = balancedThermalActions()
            )
            ProtectionProfile.MAXIMUM_SAFETY -> ProtectionSettings(
                profile = profile,
                lowBatteryPercent = 25,
                criticalBatteryPercent = 12,
                batteryTemperatureWarningCelsius = 40f,
                batteryTemperatureCriticalCelsius = 45f,
                thresholdDurationMs = 3_000L,
                minimumActionIntervalMs = 30_000L,
                cooldownMs = 300_000L,
                maximumProfileChanges = 2,
                thermalActions = maximumSafetyThermalActions()
            )
            ProtectionProfile.CUSTOM -> ProtectionSettings(profile = profile)
        }

        fun balancedThermalActions() = mapOf(
            ThermalStatus.NONE to ProtectionAction.NONE,
            ThermalStatus.LIGHT to ProtectionAction.INFORM,
            ThermalStatus.MODERATE to ProtectionAction.REDUCE_BITRATE,
            ThermalStatus.SEVERE to ProtectionAction.REDUCE_BITRATE,
            ThermalStatus.CRITICAL to ProtectionAction.STOP_STREAM,
            ThermalStatus.EMERGENCY to ProtectionAction.RELEASE_RESOURCES,
            ThermalStatus.SHUTDOWN to ProtectionAction.RELEASE_RESOURCES
        )

        fun qualityLockThermalActions() = balancedThermalActions() + mapOf(
            ThermalStatus.MODERATE to ProtectionAction.INFORM,
            ThermalStatus.SEVERE to ProtectionAction.INFORM
        )

        fun maximumSafetyThermalActions() = balancedThermalActions() + mapOf(
            ThermalStatus.LIGHT to ProtectionAction.REDUCE_BITRATE,
            ThermalStatus.MODERATE to ProtectionAction.REDUCE_FPS,
            ThermalStatus.SEVERE to ProtectionAction.REDUCE_RESOLUTION
        )
    }
}

object ProtectionSettingsJson {
    fun toJson(settings: ProtectionSettings): JSONObject = JSONObject().apply {
        put("schemaVersion", ProtectionSettings.SCHEMA_VERSION)
        put("profile", settings.profile.name)
        put("enabled", settings.enabled)
        put("warningsEnabled", settings.warningsEnabled)
        put("audibleWarnings", settings.audibleWarnings)
        put("forwardWarningsToHub", settings.forwardWarningsToHub)
        put("automaticThrottlingEnabled", settings.automaticThrottlingEnabled)
        put("automaticStopEnabled", settings.automaticStopEnabled)
        put("lowBatteryPercent", settings.lowBatteryPercent)
        put("criticalBatteryPercent", settings.criticalBatteryPercent)
        put("stopOnCriticalBattery", settings.stopOnCriticalBattery)
        put("ignoreLowBatteryWhileCharging", settings.ignoreLowBatteryWhileCharging)
        put("warnBatteryTemperature", settings.warnBatteryTemperature)
        put("batteryTemperatureWarningCelsius", settings.batteryTemperatureWarningCelsius)
        put("batteryTemperatureCriticalCelsius", settings.batteryTemperatureCriticalCelsius)
        put("temperatureUnavailableAction", settings.temperatureUnavailableAction.name)
        put("thermalActions", JSONObject().apply {
            settings.thermalActions.forEach { (status, action) -> put(status.toString(), action.name) }
        })
        put("bitrateReductionPercent", settings.bitrateReductionPercent)
        put("minimumBitrateMbps", settings.minimumBitrateMbps)
        put("fpsFallbackOrder", JSONArray(settings.fpsFallbackOrder))
        put("resolutionFallbackHeights", JSONArray(settings.resolutionFallbackHeights))
        put("minimumActionIntervalMs", settings.minimumActionIntervalMs)
        put("thresholdDurationMs", settings.thresholdDurationMs)
        put("cooldownMs", settings.cooldownMs)
        put("automaticallyRestoreQuality", settings.automaticallyRestoreQuality)
        put("restoreRequiresConfirmation", settings.restoreRequiresConfirmation)
        put("maximumProfileChanges", settings.maximumProfileChanges)
        put("profileChangeWindowMs", settings.profileChangeWindowMs)
    }

    fun fromJson(json: JSONObject, fallback: ProtectionSettings = ProtectionSettings.preset(ProtectionProfile.BALANCED)): ProtectionSettings {
        val profile = json.enumOr("profile", fallback.profile)
        val defaults = if (profile == ProtectionProfile.CUSTOM) fallback else ProtectionSettings.preset(profile)
        val thermal = json.optJSONObject("thermalActions")
        val thermalActions = ThermalStatus.all.associateWith { status ->
            thermal?.enumOr(status.toString(), defaults.thermalActions[status] ?: ProtectionAction.NONE)
                ?: defaults.thermalActions[status]
                ?: ProtectionAction.NONE
        }
        return defaults.copy(
            schemaVersion = json.optInt("schemaVersion", ProtectionSettings.SCHEMA_VERSION),
            profile = profile,
            enabled = json.optBoolean("enabled", defaults.enabled),
            warningsEnabled = json.optBoolean("warningsEnabled", defaults.warningsEnabled),
            audibleWarnings = json.optBoolean("audibleWarnings", defaults.audibleWarnings),
            forwardWarningsToHub = json.optBoolean("forwardWarningsToHub", defaults.forwardWarningsToHub),
            automaticThrottlingEnabled = json.optBoolean("automaticThrottlingEnabled", defaults.automaticThrottlingEnabled),
            automaticStopEnabled = json.optBoolean("automaticStopEnabled", defaults.automaticStopEnabled),
            lowBatteryPercent = json.optInt("lowBatteryPercent", defaults.lowBatteryPercent),
            criticalBatteryPercent = json.optInt("criticalBatteryPercent", defaults.criticalBatteryPercent),
            stopOnCriticalBattery = json.optBoolean("stopOnCriticalBattery", defaults.stopOnCriticalBattery),
            ignoreLowBatteryWhileCharging = json.optBoolean("ignoreLowBatteryWhileCharging", defaults.ignoreLowBatteryWhileCharging),
            warnBatteryTemperature = json.optBoolean("warnBatteryTemperature", defaults.warnBatteryTemperature),
            batteryTemperatureWarningCelsius = json.optDouble("batteryTemperatureWarningCelsius", defaults.batteryTemperatureWarningCelsius.toDouble()).toFloat(),
            batteryTemperatureCriticalCelsius = json.optDouble("batteryTemperatureCriticalCelsius", defaults.batteryTemperatureCriticalCelsius.toDouble()).toFloat(),
            temperatureUnavailableAction = json.enumOr("temperatureUnavailableAction", defaults.temperatureUnavailableAction),
            thermalActions = thermalActions,
            bitrateReductionPercent = json.optInt("bitrateReductionPercent", defaults.bitrateReductionPercent),
            minimumBitrateMbps = json.optInt("minimumBitrateMbps", defaults.minimumBitrateMbps),
            fpsFallbackOrder = json.intList("fpsFallbackOrder", defaults.fpsFallbackOrder),
            resolutionFallbackHeights = json.intList("resolutionFallbackHeights", defaults.resolutionFallbackHeights),
            minimumActionIntervalMs = json.optLong("minimumActionIntervalMs", defaults.minimumActionIntervalMs),
            thresholdDurationMs = json.optLong("thresholdDurationMs", defaults.thresholdDurationMs),
            cooldownMs = json.optLong("cooldownMs", defaults.cooldownMs),
            automaticallyRestoreQuality = json.optBoolean("automaticallyRestoreQuality", defaults.automaticallyRestoreQuality),
            restoreRequiresConfirmation = json.optBoolean("restoreRequiresConfirmation", defaults.restoreRequiresConfirmation),
            maximumProfileChanges = json.optInt("maximumProfileChanges", defaults.maximumProfileChanges),
            profileChangeWindowMs = json.optLong("profileChangeWindowMs", defaults.profileChangeWindowMs)
        )
    }

    private inline fun <reified T : Enum<T>> JSONObject.enumOr(name: String, fallback: T): T =
        runCatching { enumValueOf<T>(optString(name, fallback.name)) }.getOrDefault(fallback)

    private fun JSONObject.intList(name: String, fallback: List<Int>): List<Int> {
        val array = optJSONArray(name) ?: return fallback
        return buildList {
            for (index in 0 until array.length()) add(array.optInt(index))
        }.ifEmpty { fallback }
    }
}
