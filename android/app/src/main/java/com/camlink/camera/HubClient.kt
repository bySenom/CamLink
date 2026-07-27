package com.camlink.camera

import android.os.Build
import android.util.Base64
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class HubClient(
    private val deviceName: String = "${Build.MANUFACTURER} ${Build.MODEL}"
) {
    interface Listener {
        fun onCommand(command: JSONObject)
        fun onConnected(endpoint: String)
        fun onDisconnected(message: String)
    }

    private val executor = Executors.newCachedThreadPool()
    private val videoExecutor = Executors.newSingleThreadExecutor()
    private val connected = AtomicBoolean(false)
    private val disconnectNotified = AtomicBoolean(false)
    private val controlWriteLock = Any()
    private val videoWriteLock = Any()
    private var controlSocket: Socket? = null
    private var controlWriter: BufferedWriter? = null
    private var videoSocket: Socket? = null
    private var videoOutput: DataOutputStream? = null
    private var endpoint: Endpoint? = null
    @Volatile private var listener: Listener? = null

    fun connectSmart(wifiHost: String, port: Int, listener: Listener) {
        val candidates = buildList {
            add(Endpoint("USB", "127.0.0.1", port))
            if (wifiHost.isNotBlank() && wifiHost != "127.0.0.1" && wifiHost != "localhost") {
                add(Endpoint("Wi-Fi", wifiHost, port))
            }
        }
        connect(candidates, listener)
    }

    fun connectWifi(wifiHost: String, port: Int, listener: Listener) {
        connect(listOf(Endpoint("Wi-Fi", wifiHost, port)), listener)
    }

    fun connectUsb(port: Int, listener: Listener) {
        connect(listOf(Endpoint("USB", "127.0.0.1", port)), listener)
    }

    private fun connect(candidates: List<Endpoint>, listener: Listener) {
        close()
        disconnectNotified.set(false)
        this.listener = listener
        executor.execute {
            var failure: Exception? = null
            for (candidate in candidates) {
                try {
                    openControl(candidate)
                    endpoint = candidate
                    connected.set(true)
                    listener.onConnected("${candidate.label} (${candidate.host}:${candidate.port})")
                    readControlLoop()
                    return@execute
                } catch (exception: Exception) {
                    failure = exception
                    closeControlOnly()
                }
            }
            notifyDisconnected("No CamLink hub reached: ${failure?.message ?: "unknown error"}")
        }
    }

    fun send(json: JSONObject) {
        synchronized(controlWriteLock) {
            val writer = controlWriter ?: return
            try {
                writer.write(json.toString())
                writer.newLine()
                writer.flush()
            } catch (exception: Exception) {
                notifyDisconnected("Control connection lost: ${exception.message}")
                close()
            }
        }
    }

    fun sendStatus(message: String, error: Boolean = false) {
        send(JSONObject().put("type", "status").put("message", message).put("error", error))
    }

    /** Bounded by [DeviceHealthMonitor] to one regular message per second plus state changes. */
    fun sendHealth(state: DeviceHealthState) {
        send(JSONObject().apply {
            put("type", "health")
            put("schemaVersion", 1)
            put("batteryLevelPercent", state.batteryLevelPercent)
            put("batteryTemperatureCelsius", state.batteryTemperatureCelsius)
            put("isCharging", state.isCharging)
            put("chargingSource", state.chargingSource.name)
            put("thermalStatus", state.thermalStatus)
            put("thermalStatusLabel", state.thermalStatusLabel)
            put("thermalHeadroom", state.thermalHeadroom)
            put("actualFps", state.actualFps)
            put("droppedFrames", state.droppedFrames)
            put("recentDroppedFrames", state.recentDroppedFrames)
            put("activeProtectionAction", state.activeProtectionAction?.name)
            put("requestedProfile", state.requestedProfile?.asJson())
            put("activeProfile", state.activeProfile?.asJson())
            put("activeBitrateMbps", state.activeBitrateMbps)
            put("timestampMs", state.timestampMs)
        })
    }

    fun sendProtectionConfiguration(settings: ProtectionSettings) {
        send(JSONObject().apply {
            put("type", "protectionConfig")
            put("schemaVersion", ProtectionSettings.SCHEMA_VERSION)
            put("config", ProtectionSettingsJson.toJson(settings))
        })
    }

    fun sendProtectionConfigurationAck(accepted: Boolean, settings: ProtectionSettings?, error: String? = null) {
        send(JSONObject().apply {
            put("type", "protectionConfigAck")
            put("schemaVersion", ProtectionSettings.SCHEMA_VERSION)
            put("accepted", accepted)
            if (settings != null) put("config", ProtectionSettingsJson.toJson(settings))
            if (error != null) put("error", error)
        })
    }

    fun sendStreamProfile(event: String, requested: HealthStreamProfile?, active: HealthStreamProfile?) {
        send(JSONObject().apply {
            put("type", "streamProfile")
            put("schemaVersion", 1)
            put("event", event)
            put("requestedProfile", requested?.asJson())
            put("activeProfile", active?.asJson())
            put("timestampMs", System.currentTimeMillis())
        })
    }

    fun sendVideoConfig(codec: String, sps: ByteArray, pps: ByteArray, vps: ByteArray?, fps: Int) {
        send(JSONObject().apply {
            put("type", "videoConfig")
            put("codec", codec)
            put("sps", Base64.encodeToString(sps, Base64.NO_WRAP))
            put("pps", Base64.encodeToString(pps, Base64.NO_WRAP))
            if (vps != null) put("vps", Base64.encodeToString(vps, Base64.NO_WRAP))
            put("fps", fps)
        })
    }

    fun sendVideoFrame(data: ByteBuffer, size: Int, presentationTimeUs: Long) {
        val bytes = ByteArray(size)
        data.get(bytes)
        videoExecutor.execute {
            sendVideoBytes(bytes, presentationTimeUs)
        }
    }

    private fun sendVideoBytes(bytes: ByteArray, presentationTimeUs: Long) {
        if (!ensureVideoConnection()) {
            return
        }
        synchronized(videoWriteLock) {
            try {
                val output = videoOutput ?: return
                output.writeInt(bytes.size)
                output.writeLong(presentationTimeUs)
                output.write(bytes)
                output.flush()
            } catch (exception: Exception) {
                sendStatus("Video connection lost: ${exception.message}", error = true)
                closeVideoOnly()
            }
        }
    }

    private fun ensureVideoConnection(): Boolean {
        if (videoOutput != null) {
            return true
        }
        val currentEndpoint = endpoint ?: return false
        return try {
            val socket = Socket()
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(currentEndpoint.host, currentEndpoint.port), 2_000)
            val output = DataOutputStream(socket.getOutputStream())
            output.write((JSONObject().apply {
                put("type", "hello")
                put("channel", "video")
                put("deviceName", deviceName)
                put("protocol", 1)
            }.toString() + "\n").toByteArray(Charsets.UTF_8))
            output.flush()
            val acknowledgement = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8)).readLine()
            if (JSONObject(acknowledgement).optString("type") != "accepted") {
                throw IllegalStateException("Hub rejected video channel")
            }
            videoSocket = socket
            videoOutput = output
            true
        } catch (exception: Exception) {
            sendStatus("Cannot open video channel: ${exception.message}", error = true)
            closeVideoOnly()
            false
        }
    }

    private fun openControl(candidate: Endpoint) {
        val socket = Socket()
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(candidate.host, candidate.port), 2_000)
        val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        writer.write(JSONObject().apply {
            put("type", "hello")
            put("channel", "control")
            put("deviceName", deviceName)
            put("protocol", 1)
        }.toString())
        writer.newLine()
        writer.flush()
        val acknowledgement = reader.readLine() ?: throw IllegalStateException("Hub closed control channel")
        if (JSONObject(acknowledgement).optString("type") != "accepted") {
            throw IllegalStateException("Hub rejected control channel")
        }
        controlSocket = socket
        controlWriter = writer
        controlReader = reader
    }

    private var controlReader: BufferedReader? = null

    private fun readControlLoop() {
        val reader = controlReader ?: return
        try {
            while (connected.get()) {
                val line = reader.readLine()
                if (line == null) {
                    if (connected.get()) notifyDisconnected("CamLink hub disconnected.")
                    return
                }
                val json = JSONObject(line)
                if (json.optString("type") == "command") {
                    listener?.onCommand(json)
                }
            }
        } catch (exception: Exception) {
            if (connected.get()) notifyDisconnected("Control connection lost: ${exception.message}")
        } finally {
            close()
        }
    }

    private fun notifyDisconnected(message: String) {
        if (disconnectNotified.compareAndSet(false, true)) {
            listener?.onDisconnected(message)
        }
    }

    fun close() {
        connected.set(false)
        closeVideoOnly()
        closeControlOnly()
    }

    private fun closeControlOnly() {
        try { controlReader?.close() } catch (_: Exception) { }
        try { controlWriter?.close() } catch (_: Exception) { }
        try { controlSocket?.close() } catch (_: Exception) { }
        controlReader = null
        controlWriter = null
        controlSocket = null
    }

    private fun closeVideoOnly() {
        try { videoOutput?.close() } catch (_: Exception) { }
        try { videoSocket?.close() } catch (_: Exception) { }
        videoOutput = null
        videoSocket = null
    }

    private data class Endpoint(val label: String, val host: String, val port: Int)

    private fun HealthStreamProfile.asJson(): JSONObject = JSONObject().apply {
        put("width", width)
        put("height", height)
        put("fps", fps)
        put("codec", codec)
    }
}
