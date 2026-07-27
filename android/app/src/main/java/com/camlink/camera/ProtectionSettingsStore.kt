package com.camlink.camera

import android.content.Context
import org.json.JSONObject

/** Versioned local configuration. Android remains authoritative when the Hub disconnects. */
class ProtectionSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(): ProtectionSettings {
        val raw = preferences.getString(KEY_SETTINGS, null) ?: return ProtectionSettings.preset(ProtectionProfile.BALANCED)
        return runCatching { ProtectionSettingsJson.fromJson(JSONObject(raw)) }
            .getOrElse { ProtectionSettings.preset(ProtectionProfile.BALANCED) }
            .takeIf { it.validationErrors().isEmpty() }
            ?: ProtectionSettings.preset(ProtectionProfile.BALANCED)
    }

    fun save(settings: ProtectionSettings): Result<ProtectionSettings> {
        val errors = settings.validationErrors()
        if (errors.isNotEmpty()) return Result.failure(IllegalArgumentException(errors.joinToString(" ")))
        preferences.edit().putString(KEY_SETTINGS, ProtectionSettingsJson.toJson(settings).toString()).apply()
        return Result.success(settings)
    }

    private companion object {
        const val PREFERENCES = "camlink.protection"
        const val KEY_SETTINGS = "settings.v1"
    }
}
