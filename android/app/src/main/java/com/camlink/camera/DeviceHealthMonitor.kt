package com.camlink.camera

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import kotlin.math.roundToInt

/**
 * Uses only public Android battery and thermal APIs. Battery temperature is
 * explicitly labelled as a battery value; Android does not promise a single
 * exact device-temperature sensor to third-party apps.
 */
class DeviceHealthMonitor(context: Context) {
    interface Listener {
        fun onHealthState(state: DeviceHealthState, immediate: Boolean)
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val powerManager = appContext.getSystemService(PowerManager::class.java)
    private var listener: Listener? = null
    private var started = false
    private var batteryIntent: Intent? = null
    private var thermalStatus: Int? = null
    private var actualFps: Float? = null
    private var droppedFrames: Long? = null
    private var recentDroppedFrames: Long? = null
    private var activeAction: ProtectionAction? = null
    private var requestedProfile: HealthStreamProfile? = null
    private var activeProfile: HealthStreamProfile? = null
    private var activeBitrateMbps: Float? = null

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            batteryIntent = intent
            emit(immediate = true)
        }
    }

    private val thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
        thermalStatus = status
        Log.i(LOG_TAG, "Thermal status changed: ${ThermalStatus.label(status)}")
        emit(immediate = true)
    }

    private val poll = object : Runnable {
        override fun run() {
            if (!started) return
            emit(immediate = false)
            mainHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    fun start(listener: Listener) {
        if (started) {
            this.listener = listener
            emit(immediate = true)
            return
        }
        this.listener = listener
        started = true
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        batteryIntent = appContext.registerReceiver(null, filter)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(batteryReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(batteryReceiver, filter)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            thermalStatus = powerManager.currentThermalStatus
            powerManager.addThermalStatusListener(thermalListener)
        }
        emit(immediate = true)
        mainHandler.postDelayed(poll, POLL_INTERVAL_MS)
    }

    fun stop() {
        if (!started) return
        started = false
        mainHandler.removeCallbacks(poll)
        runCatching { appContext.unregisterReceiver(batteryReceiver) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { powerManager.removeThermalStatusListener(thermalListener) }
        }
        listener = null
    }

    fun updateStreamingMetrics(fps: Float?, dropped: Long?, recentDropped: Long?, activeBitrateMbps: Float?) {
        actualFps = fps
        droppedFrames = dropped
        recentDroppedFrames = recentDropped
        this.activeBitrateMbps = activeBitrateMbps
    }

    fun updateProfiles(requested: HealthStreamProfile?, active: HealthStreamProfile?) {
        requestedProfile = requested
        activeProfile = active
    }

    fun updateProtectionAction(action: ProtectionAction?) {
        activeAction = action
    }

    private fun emit(immediate: Boolean) {
        if (!started) return
        val intent = batteryIntent ?: appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.batteryLevelPercent()
        val temperature = intent?.batteryTemperatureCelsius()
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val chargingStatus = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val charging = chargingStatus == BatteryManager.BATTERY_STATUS_CHARGING || chargingStatus == BatteryManager.BATTERY_STATUS_FULL || plugged != 0
        listener?.onHealthState(
            DeviceHealthState(
                batteryLevelPercent = level,
                batteryTemperatureCelsius = temperature,
                isCharging = charging,
                chargingSource = chargingSource(plugged, charging),
                thermalStatus = thermalStatus,
                thermalStatusLabel = ThermalStatus.label(thermalStatus),
                thermalHeadroom = thermalHeadroom(),
                actualFps = actualFps,
                droppedFrames = droppedFrames,
                recentDroppedFrames = recentDroppedFrames,
                activeProtectionAction = activeAction,
                requestedProfile = requestedProfile,
                activeProfile = activeProfile,
                activeBitrateMbps = activeBitrateMbps,
                timestampMs = System.currentTimeMillis()
            ),
            immediate
        )
    }

    private fun thermalHeadroom(): Float? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching { powerManager.getThermalHeadroom(0) }
            .getOrNull()
            ?.takeIf { it.isFinite() && it >= 0f }
    }

    companion object {
        private const val LOG_TAG = "CamLinkHealth"
        private const val POLL_INTERVAL_MS = 1_000L

        internal fun Intent.batteryTemperatureCelsius(): Float? {
            val tenths = getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            return batteryTemperatureFromTenths(tenths)
        }

        internal fun batteryTemperatureFromTenths(tenths: Int): Float? = tenths.takeIf { it >= 0 }?.div(10f)

        internal fun Intent.batteryLevelPercent(): Int? {
            val level = getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            return if (level >= 0 && scale > 0) ((level * 100f) / scale).roundToInt().coerceIn(0, 100) else null
        }

        internal fun chargingSource(plugged: Int, charging: Boolean): ChargingSource = when {
            !charging -> ChargingSource.NONE
            plugged and BatteryManager.BATTERY_PLUGGED_USB != 0 -> ChargingSource.USB
            plugged and BatteryManager.BATTERY_PLUGGED_AC != 0 -> ChargingSource.AC
            plugged and BatteryManager.BATTERY_PLUGGED_WIRELESS != 0 -> ChargingSource.WIRELESS
            else -> ChargingSource.UNKNOWN
        }
    }
}
