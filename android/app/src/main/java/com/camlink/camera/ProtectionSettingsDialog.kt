package com.camlink.camera

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.text.InputType
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView

/** Native-Views editor for the complete local protection configuration. */
class ProtectionSettingsDialog(
    private val activity: Activity,
    private val initial: ProtectionSettings,
    private val onSaved: (ProtectionSettings) -> Unit
) {
    fun show() {
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        val scroll = ScrollView(activity).apply { addView(content) }
        fun heading(text: String) = content.addView(TextView(activity).apply {
            this.text = text
            textSize = 17f
            setTextColor(0xff1d3557.toInt())
            setPadding(0, dp(14), 0, dp(4))
        })
        fun toggle(label: String, value: Boolean) = CheckBox(activity).apply {
            text = label
            isChecked = value
            content.addView(this)
        }
        fun number(label: String, value: Number, decimal: Boolean = false) = EditText(activity).apply {
            hint = label
            setText(value.toString())
            inputType = if (decimal) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL else InputType.TYPE_CLASS_NUMBER
            content.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        fun text(label: String, value: String) = EditText(activity).apply {
            hint = label
            setText(value)
            content.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        fun <T> choice(label: String, values: List<T>, selected: T): Spinner {
            content.addView(TextView(activity).apply { this.text = label })
            return Spinner(activity).apply {
                adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, values)
                setSelection(values.indexOf(selected).coerceAtLeast(0))
                content.addView(this)
            }
        }

        heading("Protection profile")
        val profile = choice("Profile", ProtectionProfile.entries.toList(), initial.profile)
        content.addView(TextView(activity).apply {
            text = "Quality Lock only informs before critical conditions. Balanced steps down bitrate, FPS, then resolution. Maximum Safety acts earlier. Temperature limits are device-dependent user thresholds, not universal safe limits."
            textSize = 12f
            setTextColor(Color.DKGRAY)
        })

        heading("General")
        val enabled = toggle("Enable protection system", initial.enabled)
        val warnings = toggle("Show warnings", initial.warningsEnabled)
        val sound = toggle("Audible warnings", initial.audibleWarnings)
        val forwardWarnings = toggle("Forward warnings to Windows Hub", initial.forwardWarningsToHub)
        val throttle = toggle("Allow automatic quality throttling", initial.automaticThrottlingEnabled)
        val stop = toggle("Allow automatic controlled stream stop", initial.automaticStopEnabled)

        heading("Battery")
        val lowBattery = number("Low battery warning (%)", initial.lowBatteryPercent)
        val criticalBattery = number("Critical battery (%)", initial.criticalBatteryPercent)
        val stopBattery = toggle("Stop at critical battery", initial.stopOnCriticalBattery)
        val ignoreCharging = toggle("Ignore low-battery warning while charging", initial.ignoreLowBatteryWhileCharging)
        val warnBatteryTemperature = toggle("Warn about high battery temperature", initial.warnBatteryTemperature)
        val batteryTempWarning = number("Battery temperature warning (°C, device-dependent)", initial.batteryTemperatureWarningCelsius, decimal = true)
        val batteryTempCritical = number("Battery temperature critical (°C, device-dependent)", initial.batteryTemperatureCriticalCelsius, decimal = true)
        val unavailableTemperature = choice("If battery temperature is unavailable", ProtectionAction.entries.toList(), initial.temperatureUnavailableAction)

        heading("Android thermal status actions")
        val thermalChoices = mutableMapOf<Int, Spinner>()
        ThermalStatus.all.forEach { status ->
            thermalChoices[status] = choice(
                "${ThermalStatus.label(status)}",
                ProtectionAction.entries.toList(),
                initial.thermalActions[status] ?: ProtectionAction.NONE
            )
        }

        heading("Throttling and hysteresis")
        val bitrateReduction = number("Bitrate reduction per stage (%)", initial.bitrateReductionPercent)
        val minimumBitrate = number("Minimum bitrate (Mbps)", initial.minimumBitrateMbps)
        val fpsOrder = text("FPS fallback order (comma-separated)", initial.fpsFallbackOrder.joinToString(","))
        val resolutionOrder = text("Resolution fallback heights (comma-separated)", initial.resolutionFallbackHeights.joinToString(","))
        val actionInterval = number("Minimum time between actions (seconds)", initial.minimumActionIntervalMs / 1_000L)
        val thresholdDuration = number("Threshold must persist (seconds)", initial.thresholdDurationMs / 1_000L)
        val cooldown = number("Cooldown before recovery (seconds)", initial.cooldownMs / 1_000L)
        val restore = toggle("Restore original quality automatically", initial.automaticallyRestoreQuality)
        val restoreConfirmation = toggle("Require confirmation before restoration", initial.restoreRequiresConfirmation)
        val maximumChanges = number("Maximum profile changes in window", initial.maximumProfileChanges)
        val changeWindow = number("Profile-change window (minutes)", initial.profileChangeWindowMs / 60_000L)

        fun loadPreset(preset: ProtectionSettings) {
            enabled.isChecked = preset.enabled
            warnings.isChecked = preset.warningsEnabled
            sound.isChecked = preset.audibleWarnings
            forwardWarnings.isChecked = preset.forwardWarningsToHub
            throttle.isChecked = preset.automaticThrottlingEnabled
            stop.isChecked = preset.automaticStopEnabled
            lowBattery.setText(preset.lowBatteryPercent.toString())
            criticalBattery.setText(preset.criticalBatteryPercent.toString())
            stopBattery.isChecked = preset.stopOnCriticalBattery
            ignoreCharging.isChecked = preset.ignoreLowBatteryWhileCharging
            warnBatteryTemperature.isChecked = preset.warnBatteryTemperature
            batteryTempWarning.setText(preset.batteryTemperatureWarningCelsius.toString())
            batteryTempCritical.setText(preset.batteryTemperatureCriticalCelsius.toString())
            unavailableTemperature.setSelection(ProtectionAction.entries.indexOf(preset.temperatureUnavailableAction))
            ThermalStatus.all.forEach { status ->
                thermalChoices.getValue(status).setSelection(ProtectionAction.entries.indexOf(preset.thermalActions[status] ?: ProtectionAction.NONE))
            }
            bitrateReduction.setText(preset.bitrateReductionPercent.toString())
            minimumBitrate.setText(preset.minimumBitrateMbps.toString())
            fpsOrder.setText(preset.fpsFallbackOrder.joinToString(","))
            resolutionOrder.setText(preset.resolutionFallbackHeights.joinToString(","))
            actionInterval.setText((preset.minimumActionIntervalMs / 1_000L).toString())
            thresholdDuration.setText((preset.thresholdDurationMs / 1_000L).toString())
            cooldown.setText((preset.cooldownMs / 1_000L).toString())
            restore.isChecked = preset.automaticallyRestoreQuality
            restoreConfirmation.isChecked = preset.restoreRequiresConfirmation
            maximumChanges.setText(preset.maximumProfileChanges.toString())
            changeWindow.setText((preset.profileChangeWindowMs / 60_000L).toString())
        }
        profile.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: android.view.View?, position: Int, id: Long) {
                val selected = ProtectionProfile.entries[position]
                if (selected != initial.profile) loadPreset(ProtectionSettings.preset(selected))
            }
            override fun onNothingSelected(parent: AdapterView<*>) = Unit
        }

        lateinit var dialog: AlertDialog
        dialog = AlertDialog.Builder(activity)
            .setTitle("CamLink protection settings")
            .setView(scroll)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val updated = runCatching {
                    initial.copy(
                        profile = profile.selectedItem as ProtectionProfile,
                        enabled = enabled.isChecked,
                        warningsEnabled = warnings.isChecked,
                        audibleWarnings = sound.isChecked,
                        forwardWarningsToHub = forwardWarnings.isChecked,
                        automaticThrottlingEnabled = throttle.isChecked,
                        automaticStopEnabled = stop.isChecked,
                        lowBatteryPercent = lowBattery.intValue(),
                        criticalBatteryPercent = criticalBattery.intValue(),
                        stopOnCriticalBattery = stopBattery.isChecked,
                        ignoreLowBatteryWhileCharging = ignoreCharging.isChecked,
                        warnBatteryTemperature = warnBatteryTemperature.isChecked,
                        batteryTemperatureWarningCelsius = batteryTempWarning.floatValue(),
                        batteryTemperatureCriticalCelsius = batteryTempCritical.floatValue(),
                        temperatureUnavailableAction = unavailableTemperature.selectedItem as ProtectionAction,
                        thermalActions = ThermalStatus.all.associateWith { status -> thermalChoices.getValue(status).selectedItem as ProtectionAction },
                        bitrateReductionPercent = bitrateReduction.intValue(),
                        minimumBitrateMbps = minimumBitrate.intValue(),
                        fpsFallbackOrder = fpsOrder.intList(),
                        resolutionFallbackHeights = resolutionOrder.intList(),
                        minimumActionIntervalMs = actionInterval.longValue() * 1_000L,
                        thresholdDurationMs = thresholdDuration.longValue() * 1_000L,
                        cooldownMs = cooldown.longValue() * 1_000L,
                        automaticallyRestoreQuality = restore.isChecked,
                        restoreRequiresConfirmation = restoreConfirmation.isChecked,
                        maximumProfileChanges = maximumChanges.intValue(),
                        profileChangeWindowMs = changeWindow.longValue() * 60_000L
                    )
                }.getOrElse {
                    dialog.setMessage("Invalid value: ${it.message}")
                    return@setOnClickListener
                }
                val errors = updated.validationErrors()
                if (errors.isNotEmpty()) {
                    dialog.setMessage(errors.joinToString("\n"))
                    return@setOnClickListener
                }
                onSaved(updated)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun EditText.intValue(): Int = text.toString().trim().toInt()
    private fun EditText.longValue(): Long = text.toString().trim().toLong()
    private fun EditText.floatValue(): Float = text.toString().trim().replace(',', '.').toFloat()
    private fun EditText.intList(): List<Int> = text.toString().split(',').map { it.trim().toInt() }.filter { it > 0 }
}
