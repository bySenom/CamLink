package com.camlink.camera

import android.content.Context
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.charset.StandardCharsets

data class LanHubEndpoint(val host: String, val port: Int)

/** Finds a running CamLink Hub on the current Wi-Fi network without requiring a PC IP address. */
class LanHubDiscovery(private val context: Context) {
    fun find(onFound: (LanHubEndpoint) -> Unit, onNotFound: (String) -> Unit) {
        Thread {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val multicastLock = wifi?.createMulticastLock("CamLinkLanDiscovery")?.apply {
                setReferenceCounted(false)
                acquire()
            }
            try {
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    socket.soTimeout = RESPONSE_TIMEOUT_MS
                    val request = DISCOVERY_REQUEST.toByteArray(StandardCharsets.UTF_8)
                    val target = InetAddress.getByName("255.255.255.255")

                    repeat(4) {
                        socket.send(DatagramPacket(request, request.size, target, DISCOVERY_PORT))
                        val buffer = ByteArray(256)
                        try {
                            val response = DatagramPacket(buffer, buffer.size)
                            socket.receive(response)
                            parseResponse(response.data.copyOf(response.length))?.let { port ->
                                onFound(LanHubEndpoint(response.address.hostAddress ?: return@let, port))
                                return@Thread
                            }
                        } catch (_: java.net.SocketTimeoutException) {
                            // Retry a few times; many Wi-Fi adapters briefly drop the first broadcast.
                        }
                    }
                }
                onNotFound("No CamLink Hub found on this Wi-Fi. Check that both devices use the same network and that client isolation is off.")
            } catch (exception: Exception) {
                onNotFound("Wi-Fi hub search failed: ${exception.message}")
            } finally {
                if (multicastLock?.isHeld == true) multicastLock.release()
            }
        }.start()
    }

    private fun parseResponse(bytes: ByteArray): Int? {
        val parts = bytes.toString(StandardCharsets.UTF_8).trim().split('|')
        if (parts.size != 2 || parts[0] != "CAMLINK_HUB_V1") return null
        return parts[1].toIntOrNull()?.takeIf { it in 1..65535 }
    }

    private companion object {
        const val DISCOVERY_PORT = 6021
        const val RESPONSE_TIMEOUT_MS = 700
        const val DISCOVERY_REQUEST = "CAMLINK_DISCOVER_V1"
    }
}
