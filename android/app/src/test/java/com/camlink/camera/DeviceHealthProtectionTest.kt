package com.camlink.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceHealthProtectionTest {
    @Test
    fun batteryTemperatureUsesTenthsOfADegreeAndRejectsMissingValue() {
        assertEquals(38.4f, requireNotNull(DeviceHealthMonitor.batteryTemperatureFromTenths(384)), 0.001f)
        assertNull(DeviceHealthMonitor.batteryTemperatureFromTenths(-1))
    }

    @Test
    fun thermalStatusHasClearPublicLabels() {
        assertEquals("Normal", ThermalStatus.label(ThermalStatus.NONE))
        assertEquals("Stark", ThermalStatus.label(ThermalStatus.SEVERE))
        assertEquals("Nicht verfügbar", ThermalStatus.label(null))
    }

    @Test
    fun validationRejectsObviousInvalidThresholds() {
        val invalid = ProtectionSettings.preset(ProtectionProfile.BALANCED).copy(
            lowBatteryPercent = 5,
            criticalBatteryPercent = 10,
            batteryTemperatureCriticalCelsius = 30f,
            batteryTemperatureWarningCelsius = 40f
        )
        assertTrue(invalid.validationErrors().isNotEmpty())
    }

    @Test
    fun qualityLockOnlyInformsBeforeCriticalStop() {
        val controller = ProtectionController(ProtectionSettings.preset(ProtectionProfile.QUALITY_LOCK).copy(thresholdDurationMs = 0))
        val severe = health(thermal = ThermalStatus.SEVERE, now = 1_000L)
        val critical = health(thermal = ThermalStatus.CRITICAL, now = 2_000L)

        assertEquals(ProtectionAction.INFORM, controller.evaluate(severe)?.action)
        assertEquals(ProtectionAction.STOP_STREAM, controller.evaluate(critical)?.action)
    }

    @Test
    fun profilePresetsProvideDistinctSafeDefaults() {
        val qualityLock = ProtectionSettings.preset(ProtectionProfile.QUALITY_LOCK)
        val maximumSafety = ProtectionSettings.preset(ProtectionProfile.MAXIMUM_SAFETY)

        assertFalse(qualityLock.automaticThrottlingEnabled)
        assertEquals(ProtectionAction.INFORM, qualityLock.thermalActions[ThermalStatus.SEVERE])
        assertEquals(25, maximumSafety.lowBatteryPercent)
        assertEquals(3_000L, maximumSafety.thresholdDurationMs)
        assertEquals(ProtectionAction.RELEASE_RESOURCES, maximumSafety.thermalActions[ThermalStatus.EMERGENCY])
    }

    @Test
    fun balancedEscalatesBitrateThenFpsWithHysteresis() {
        val controller = ProtectionController(ProtectionSettings.preset(ProtectionProfile.BALANCED).copy(
            thresholdDurationMs = 1_000L,
            minimumActionIntervalMs = 5_000L
        ))
        val severeAtStart = health(thermal = ThermalStatus.SEVERE, now = 0L)
        val severeAfterHold = health(thermal = ThermalStatus.SEVERE, now = 1_000L)
        val severeTooSoon = health(thermal = ThermalStatus.SEVERE, now = 3_000L)
        val severeLater = health(thermal = ThermalStatus.SEVERE, now = 6_000L)

        assertNull(controller.evaluate(severeAtStart))
        val bitrate = controller.evaluate(severeAfterHold)
        assertEquals(ProtectionAction.REDUCE_BITRATE, bitrate?.action)
        controller.recordApplied(requireNotNull(bitrate), 1_000L)
        assertNull(controller.evaluate(severeTooSoon))
        assertEquals(ProtectionAction.REDUCE_FPS, controller.evaluate(severeLater)?.action)
    }

    @Test
    fun maximumSafetyActsEarlierThanBalanced() {
        val settings = ProtectionSettings.preset(ProtectionProfile.MAXIMUM_SAFETY).copy(thresholdDurationMs = 10_000L)
        val controller = ProtectionController(settings)
        assertNull(controller.evaluate(health(thermal = ThermalStatus.MODERATE, now = 0L)))
        assertEquals(ProtectionAction.REDUCE_FPS, controller.evaluate(health(thermal = ThermalStatus.MODERATE, now = 3_000L))?.action)
    }

    @Test
    fun criticalBatteryStopsEvenWhenPhoneIsChargingButLowWarningIsIgnored() {
        val controller = ProtectionController(ProtectionSettings.preset(ProtectionProfile.BALANCED).copy(thresholdDurationMs = 0))
        assertNull(controller.evaluate(health(battery = 15, charging = true, now = 1_000L)))
        assertEquals(ProtectionAction.STOP_STREAM, controller.evaluate(health(battery = 8, charging = true, now = 2_000L))?.action)
    }

    @Test
    fun emergencyAlwaysReleasesResourcesEvenWhenAutomaticStopIsDisabled() {
        val controller = ProtectionController(ProtectionSettings.preset(ProtectionProfile.BALANCED).copy(automaticStopEnabled = false))
        assertEquals(ProtectionAction.RELEASE_RESOURCES, controller.evaluate(health(thermal = ThermalStatus.EMERGENCY, now = 1_000L))?.action)
    }

    @Test
    fun recoveryWaitsForCooldownAndCanRestoreQuality() {
        val controller = ProtectionController(ProtectionSettings.preset(ProtectionProfile.BALANCED).copy(
            thresholdDurationMs = 0,
            cooldownMs = 5_000L,
            automaticallyRestoreQuality = true,
            restoreRequiresConfirmation = false
        ))
        val action = requireNotNull(controller.evaluate(health(thermal = ThermalStatus.MODERATE, now = 1_000L)))
        controller.recordApplied(action, 1_000L)
        assertNull(controller.evaluate(health(now = 4_000L)))
        assertEquals(ProtectionAction.RESTORE_QUALITY, controller.evaluate(health(now = 6_000L))?.action)
    }

    @Test
    fun rapidThermalChangesRestartTheHoldTimerInsteadOfThrashingProfiles() {
        val controller = ProtectionController(ProtectionSettings.preset(ProtectionProfile.BALANCED).copy(
            thresholdDurationMs = 3_000L,
            minimumActionIntervalMs = 1_000L
        ))
        assertNull(controller.evaluate(health(thermal = ThermalStatus.MODERATE, now = 0L)))
        assertNull(controller.evaluate(health(thermal = ThermalStatus.SEVERE, now = 1_000L)))
        assertNull(controller.evaluate(health(thermal = ThermalStatus.MODERATE, now = 2_000L)))
        assertNull(controller.evaluate(health(thermal = ThermalStatus.SEVERE, now = 3_000L)))
        assertEquals(ProtectionAction.REDUCE_BITRATE, controller.evaluate(health(thermal = ThermalStatus.SEVERE, now = 6_000L))?.action)
    }

    @Test
    fun protectionStateCanBeResetWhenConnectionLossReleasesThePipeline() {
        val controller = ProtectionController(ProtectionSettings.preset(ProtectionProfile.BALANCED).copy(thresholdDurationMs = 0L))
        val action = requireNotNull(controller.evaluate(health(thermal = ThermalStatus.MODERATE, now = 1_000L)))
        controller.recordApplied(action, 1_000L)
        assertEquals(ProtectionAction.REDUCE_BITRATE, controller.activeAction())

        controller.reset()

        assertNull(controller.activeAction())
        assertNull(controller.evaluate(health(now = 2_000L)))
    }

    @Test
    fun settingsSerializationRemainsBackwardsCompatibleWithMissingFields() {
        val minimal = org.json.JSONObject().put("profile", "BALANCED")
        val parsed = ProtectionSettingsJson.fromJson(minimal)
        assertEquals(ProtectionProfile.BALANCED, parsed.profile)
        assertTrue(parsed.validationErrors().isEmpty())
        assertEquals(ProtectionSettings.SCHEMA_VERSION, ProtectionSettingsJson.toJson(parsed).getInt("schemaVersion"))
    }

    private fun health(
        battery: Int? = 80,
        charging: Boolean = false,
        thermal: Int? = ThermalStatus.NONE,
        now: Long,
        fps: Float? = 30f,
        drops: Long? = 0L
    ) = DeviceHealthState(
        batteryLevelPercent = battery,
        batteryTemperatureCelsius = 35f,
        isCharging = charging,
        chargingSource = if (charging) ChargingSource.USB else ChargingSource.NONE,
        thermalStatus = thermal,
        thermalStatusLabel = ThermalStatus.label(thermal),
        thermalHeadroom = null,
        actualFps = fps,
        droppedFrames = drops,
        recentDroppedFrames = drops,
        activeProfile = HealthStreamProfile(1920, 1080, 30, "h264"),
        timestampMs = now
    )
}
