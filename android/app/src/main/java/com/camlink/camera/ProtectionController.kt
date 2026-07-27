package com.camlink.camera

data class ProtectionDecision(
    val action: ProtectionAction,
    val reason: String,
    val immediate: Boolean = false
)

/**
 * Pure, clock-injected protection policy. It does not touch Camera2 or the Hub;
 * callers apply a returned decision and then call [recordApplied].
 */
class ProtectionController(settings: ProtectionSettings) {
    private var settings = settings
    private var conditionKey: String? = null
    private var conditionSinceMs = 0L
    private var lastActionMs = Long.MIN_VALUE / 2
    private var lastRecoveryMs = Long.MIN_VALUE / 2
    private var activeAdaptiveStage = -1
    private var activeAction: ProtectionAction? = null
    private var announcedKey: String? = null
    private val profileChangeTimes = ArrayDeque<Long>()

    fun updateSettings(value: ProtectionSettings) {
        settings = value
        reset()
    }

    fun activeAction(): ProtectionAction? = activeAction

    fun reset() {
        conditionKey = null
        conditionSinceMs = 0L
        activeAdaptiveStage = -1
        activeAction = null
        announcedKey = null
        profileChangeTimes.clear()
    }

    fun evaluate(state: DeviceHealthState): ProtectionDecision? {
        if (!settings.enabled) return null
        val now = state.timestampMs
        val candidate = candidateFor(state) ?: return recoveryDecision(now)
        val effective = effectiveAction(candidate.action, state)
        val key = "${candidate.reason}:${effective.name}"
        if (conditionKey != key) {
            conditionKey = key
            conditionSinceMs = now
            announcedKey = null
        }

        if (effective == ProtectionAction.NONE) return null
        if (effective == ProtectionAction.INFORM) {
            if (announcedKey == key) return null
            announcedKey = key
            activeAction = ProtectionAction.INFORM
            return ProtectionDecision(ProtectionAction.INFORM, candidate.reason)
        }

        val emergency = ThermalStatus.isEmergency(state.thermalStatus)
        if (!emergency && now - conditionSinceMs < effectiveThresholdDuration()) return null
        if (!emergency && now - lastActionMs < settings.minimumActionIntervalMs) return null

        val action = when (effective) {
            ProtectionAction.REDUCE_BITRATE,
            ProtectionAction.REDUCE_FPS,
            ProtectionAction.REDUCE_RESOLUTION -> nextAdaptiveAction(effective, now) ?: return null
            else -> effective
        }
        return ProtectionDecision(action, candidate.reason, immediate = emergency || action == ProtectionAction.RELEASE_RESOURCES)
    }

    fun recordApplied(decision: ProtectionDecision, nowMs: Long) {
        activeAction = decision.action
        lastActionMs = nowMs
        if (decision.action == ProtectionAction.REDUCE_FPS || decision.action == ProtectionAction.REDUCE_RESOLUTION) {
            profileChangeTimes += nowMs
        }
        if (decision.action == ProtectionAction.RESTORE_QUALITY) {
            activeAdaptiveStage = -1
            activeAction = null
            lastRecoveryMs = nowMs
        }
    }

    private fun candidateFor(state: DeviceHealthState): ProtectionDecision? {
        if (ThermalStatus.isEmergency(state.thermalStatus)) {
            return ProtectionDecision(ProtectionAction.RELEASE_RESOURCES, "Android thermal status is ${state.thermalStatusLabel}.", immediate = true)
        }
        val batteryLevel = state.batteryLevelPercent
        if (batteryLevel != null && batteryLevel <= settings.criticalBatteryPercent && settings.stopOnCriticalBattery) {
            return ProtectionDecision(ProtectionAction.STOP_STREAM, "Battery level is critical: $batteryLevel%.")
        }
        if (batteryLevel != null && batteryLevel <= settings.lowBatteryPercent && !(state.isCharging && settings.ignoreLowBatteryWhileCharging)) {
            return ProtectionDecision(ProtectionAction.INFORM, "Battery level warning: $batteryLevel%.")
        }
        val batteryTemperature = state.batteryTemperatureCelsius
        if (batteryTemperature == null && settings.warnBatteryTemperature) {
            return when (settings.temperatureUnavailableAction) {
                ProtectionAction.NONE -> null
                else -> ProtectionDecision(settings.temperatureUnavailableAction, "Battery temperature unavailable.")
            }
        }
        if (batteryTemperature != null && settings.warnBatteryTemperature) {
            if (batteryTemperature >= settings.batteryTemperatureCriticalCelsius) {
                return ProtectionDecision(ProtectionAction.STOP_STREAM, "Battery temperature is ${"%.1f".format(batteryTemperature)} °C.")
            }
            if (batteryTemperature >= settings.batteryTemperatureWarningCelsius) {
                return ProtectionDecision(ProtectionAction.INFORM, "Battery temperature warning: ${"%.1f".format(batteryTemperature)} °C.")
            }
        }
        state.thermalStatus?.let { thermal ->
            val action = settings.thermalActions[thermal] ?: ProtectionAction.NONE
            if (action != ProtectionAction.NONE) return ProtectionDecision(action, "Android thermal status: ${state.thermalStatusLabel}.")
        }
        val activeFps = state.activeProfile?.fps?.toFloat()
        val actualFps = state.actualFps
        if (activeFps != null && actualFps != null && activeFps > 0f) {
            val ratio = actualFps / activeFps
            if (ratio < 0.65f) return ProtectionDecision(ProtectionAction.REDUCE_FPS, "Actual FPS is ${"%.1f".format(actualFps)} of ${activeFps.toInt()} requested.")
            if (ratio < 0.85f || (state.recentDroppedFrames ?: 0L) > 0L) return ProtectionDecision(ProtectionAction.REDUCE_BITRATE, "Frame delivery is below target or recent frames were dropped.")
        }
        return null
    }

    private fun effectiveAction(action: ProtectionAction, state: DeviceHealthState): ProtectionAction = when {
        ThermalStatus.isEmergency(state.thermalStatus) -> ProtectionAction.RELEASE_RESOURCES
        action == ProtectionAction.STOP_STREAM && !settings.automaticStopEnabled -> ProtectionAction.INFORM
        action in adaptiveActions && (!settings.automaticThrottlingEnabled || settings.profile == ProtectionProfile.QUALITY_LOCK) -> ProtectionAction.INFORM
        else -> action
    }

    private fun nextAdaptiveAction(requested: ProtectionAction, now: Long): ProtectionAction? {
        val requestedStage = when (requested) {
            ProtectionAction.REDUCE_BITRATE -> 0
            ProtectionAction.REDUCE_FPS -> 1
            ProtectionAction.REDUCE_RESOLUTION -> 2
            else -> return requested
        }
        val stage = maxOf(requestedStage, activeAdaptiveStage + 1).coerceAtMost(2)
        val action = listOf(ProtectionAction.REDUCE_BITRATE, ProtectionAction.REDUCE_FPS, ProtectionAction.REDUCE_RESOLUTION)[stage]
        if (action == ProtectionAction.REDUCE_FPS || action == ProtectionAction.REDUCE_RESOLUTION) {
            while (profileChangeTimes.firstOrNull()?.let { now - it > settings.profileChangeWindowMs } == true) profileChangeTimes.removeFirst()
            if (profileChangeTimes.size >= settings.maximumProfileChanges) return ProtectionAction.INFORM
        }
        activeAdaptiveStage = stage
        return action
    }

    private fun recoveryDecision(now: Long): ProtectionDecision? {
        conditionKey = null
        announcedKey = null
        if (!settings.automaticallyRestoreQuality || settings.restoreRequiresConfirmation || activeAdaptiveStage < 0) return null
        if (now - lastActionMs < settings.cooldownMs || now - lastRecoveryMs < settings.cooldownMs) return null
        return ProtectionDecision(ProtectionAction.RESTORE_QUALITY, "Thermal recovery completed after cooldown.")
    }

    private fun effectiveThresholdDuration(): Long = when (settings.profile) {
        ProtectionProfile.MAXIMUM_SAFETY -> minOf(settings.thresholdDurationMs, 3_000L)
        else -> settings.thresholdDurationMs
    }

    private companion object {
        val adaptiveActions = setOf(ProtectionAction.REDUCE_BITRATE, ProtectionAction.REDUCE_FPS, ProtectionAction.REDUCE_RESOLUTION)
    }
}
