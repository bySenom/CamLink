package com.camlink.camera

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class AppUpdate(val version: String, val apkUrl: String, val checksumUrl: String)

class UpdateManager(private val activity: Activity) {
    fun check(
        onAvailable: (AppUpdate) -> Unit,
        onNoUpdate: () -> Unit,
        onError: (String) -> Unit
    ) {
        Thread {
            try {
                val update = fetchLatestUpdate()
                activity.runOnUiThread {
                    if (update == null) onNoUpdate() else onAvailable(update)
                }
            } catch (exception: Exception) {
                activity.runOnUiThread { onError(exception.message ?: "Unknown update error") }
            }
        }.start()
    }

    fun downloadAndInstall(update: AppUpdate, onProgress: (String) -> Unit, onError: (String) -> Unit) {
        Thread {
            try {
                activity.runOnUiThread { onProgress("Downloading CamLink Camera ${update.version}…") }
                val apkFile = File(activity.filesDir, "updates/CamLinkCamera-${update.version}.apk").apply { parentFile?.mkdirs() }
                download(URL(update.apkUrl), apkFile)
                val expectedHash = URL(update.checksumUrl).readText().trim().split(Regex("\\s+"))[0]
                val actualHash = sha256(apkFile)
                require(actualHash.equals(expectedHash, ignoreCase = true)) { "The update failed its SHA-256 check." }

                activity.runOnUiThread {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
                        onProgress("Allow CamLink to install updates, then check for the update again.")
                        activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
                    } else {
                        install(apkFile)
                    }
                }
            } catch (exception: Exception) {
                activity.runOnUiThread { onError(exception.message ?: "Update download failed") }
            }
        }.start()
    }

    private fun fetchLatestUpdate(): AppUpdate? {
        val release = JSONObject(URL(LATEST_RELEASE_URL).readText())
        if (release.optBoolean("prerelease")) return null
        val version = release.getString("tag_name").removePrefix("v")
        if (!isNewer(version, activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "0.0.0")) return null

        var apkUrl: String? = null
        var checksumUrl: String? = null
        val assets = release.getJSONArray("assets")
        for (index in 0 until assets.length()) {
            val asset = assets.getJSONObject(index)
            when (asset.getString("name")) {
                APK_NAME -> apkUrl = asset.getString("browser_download_url")
                "$APK_NAME.sha256" -> checksumUrl = asset.getString("browser_download_url")
            }
        }
        return if (apkUrl != null && checksumUrl != null) AppUpdate(version, apkUrl, checksumUrl) else null
    }

    private fun install(apkFile: File) {
        val installer = activity.packageManager.packageInstaller
        val parameters = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(activity.packageName)
        }
        val sessionId = installer.createSession(parameters)
        installer.openSession(sessionId).use { session ->
            FileInputStream(apkFile).use { input ->
                session.openWrite("CamLinkCamera.apk", 0, apkFile.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            val statusIntent = Intent(activity, UpdateInstallReceiver::class.java)
            val statusReceiver = PendingIntent.getBroadcast(
                activity,
                sessionId,
                statusIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            session.commit(statusReceiver.intentSender)
        }
    }

    private fun download(source: URL, destination: File) {
        val connection = source.openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("User-Agent", "CamLink-Camera-Updater/0.1")
        connection.inputStream.use { input -> destination.outputStream().use { output -> input.copyTo(output) } }
    }

    private fun URL.readText(): String {
        val connection = openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("User-Agent", "CamLink-Camera-Updater/0.1")
        return connection.inputStream.bufferedReader().use { it.readText() }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun isNewer(available: String, current: String): Boolean {
        val availableParts = available.split('.').mapNotNull { it.toIntOrNull() }
        val currentParts = current.split('.').mapNotNull { it.toIntOrNull() }
        for (index in 0 until maxOf(availableParts.size, currentParts.size)) {
            val left = availableParts.getOrElse(index) { 0 }
            val right = currentParts.getOrElse(index) { 0 }
            if (left != right) return left > right
        }
        return false
    }

    private companion object {
        const val LATEST_RELEASE_URL = "https://api.github.com/repos/bySenom/CamLink/releases/latest"
        const val APK_NAME = "CamLinkCamera.apk"
    }
}
