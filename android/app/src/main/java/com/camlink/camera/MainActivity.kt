package com.camlink.camera

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import org.json.JSONObject
import kotlin.math.roundToInt

class MainActivity : Activity(), HubClient.Listener {
    private val hub = HubClient()
    private val updates = UpdateManager(this)
    private var pipeline: CameraPipeline? = null
    private var preview: TextureView? = null
    private lateinit var hostInput: EditText
    private lateinit var transport: Spinner
    private lateinit var connectStatus: TextView
    private var cameraStatus: TextView? = null
    private var capabilities: CameraCapabilities? = null
    private var selectedCamera: CameraDescriptor? = null
    private var selectedProfile: CameraProfile? = null
    private var cameraMode = false
    private var updatingControls = false
    private var cameraRoot: FrameLayout? = null
    private var dimOverlay: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createConnectContent())
        requestCameraPermissionIfNeeded()
        checkForUpdates(showNoUpdate = false)
    }

    override fun onDestroy() {
        setDisplayDimmed(false)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hub.close()
        pipeline?.release()
        stopService(Intent(this, CamLinkCameraService::class.java))
        super.onDestroy()
    }

    private fun createConnectContent(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        root.addView(TextView(this).apply {
            text = "CamLink Camera"
            textSize = 24f
        })
        root.addView(TextView(this).apply {
            text = "Connect once. CamLink then switches directly to the locked-orientation camera view with local controls. Smart tries USB first, then Wi-Fi."
            textSize = 14f
            setPadding(0, dp(8), 0, dp(8))
        })
        transport = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Smart (USB then Wi-Fi)", "USB", "Wi-Fi", "Bluetooth control only"))
        }
        hostInput = EditText(this).apply {
            hint = "Windows PC LAN IP (for Wi-Fi / Smart fallback)"
            setText("192.168.")
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        root.addView(transport)
        root.addView(hostInput)
        root.addView(Button(this).apply {
            text = "Connect to Windows companion"
            setOnClickListener { connect() }
        })
        root.addView(Button(this).apply {
            text = "Check for updates"
            setOnClickListener { checkForUpdates(showNoUpdate = true) }
        })
        connectStatus = TextView(this).apply {
            text = "Grant camera permission, start the Windows hub, then connect."
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(connectStatus)
        return root
    }

    private fun connect() {
        if (!hasCameraPermission()) {
            requestCameraPermissionIfNeeded()
            return
        }
        val host = hostInput.text.toString().trim()
        when (transport.selectedItemPosition) {
            0 -> hub.connectSmart(host, HUB_PORT, this)
            1 -> hub.connectUsb(HUB_PORT, this)
            2 -> {
                if (host.isBlank() || host == "192.168.") {
                    setConnectStatus("Enter the Windows PC's LAN IPv4 address first.", true)
                    return
                }
                hub.connectWifi(host, HUB_PORT, this)
            }
            else -> setConnectStatus("Bluetooth is reserved for pairing/control. Use USB or Wi-Fi for the video session.", true)
        }
    }

    private fun checkForUpdates(showNoUpdate: Boolean) {
        updates.check(
            onAvailable = { update ->
                android.app.AlertDialog.Builder(this)
                    .setTitle("CamLink update available")
                    .setMessage("CamLink Camera ${update.version} is available. Download and install it now?")
                    .setPositiveButton("Update") { _, _ ->
                        updates.downloadAndInstall(update, { setConnectStatus(it) }, { setConnectStatus(it, true) })
                    }
                    .setNegativeButton("Later", null)
                    .show()
            },
            onNoUpdate = {
                if (showNoUpdate) setConnectStatus("CamLink Camera is up to date.")
            },
            onError = { message ->
                if (showNoUpdate) setConnectStatus("Update check failed: $message", true)
            }
        )
    }

    private fun showCameraMode(detectedCapabilities: CameraCapabilities, endpoint: String) {
        val defaultCamera = detectedCapabilities.cameras.firstOrNull { it.name.startsWith("Wide") }
            ?: detectedCapabilities.cameras.firstOrNull { !it.name.startsWith("Front") }
            ?: detectedCapabilities.cameras.firstOrNull()
            ?: run {
                setConnectStatus("No Camera2 video camera is available.", true)
                return
            }
        val defaultProfile = defaultCamera.defaultLiveProfile() ?: run {
            setConnectStatus("The selected camera has no encodable video profile.", true)
            return
        }

        cameraMode = true
        capabilities = detectedCapabilities
        selectedCamera = defaultCamera
        selectedProfile = defaultProfile
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        cameraRoot = root
        val cameraPreview = TextureView(this).apply {
            id = PREVIEW_ID
            isOpaque = true
        }
        root.addView(cameraPreview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        preview = cameraPreview

        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(0xc9000000.toInt())
        }
        cameraStatus = TextView(this).apply {
            text = "Connected through $endpoint · preparing ${defaultCamera.name}"
            setTextColor(Color.WHITE)
            textSize = 14f
        }
        overlay.addView(cameraStatus)

        val cameraSpinner = Spinner(this)
        val profileSpinner = Spinner(this)
        val whiteBalanceSpinner = Spinner(this)
        val focusSpinner = Spinner(this)
        val zoom = SeekBar(this)
        val exposure = SeekBar(this)
        val torch = Switch(this).apply { text = "Light"; setTextColor(Color.WHITE) }
        val zoomValue = TextView(this).apply { setTextColor(Color.WHITE) }
        val exposureValue = TextView(this).apply { setTextColor(Color.WHITE) }

        overlay.addView(controlRow("Lens", cameraSpinner, "Profile", profileSpinner))
        overlay.addView(controlRow("White balance", whiteBalanceSpinner, "Focus", focusSpinner))
        overlay.addView(controlRow("Zoom", zoom, "", zoomValue))
        overlay.addView(controlRow("Exposure", exposure, "", exposureValue))
        overlay.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(torch)
            addView(Button(this@MainActivity).apply {
                text = "Black screen"
                setOnClickListener { setDisplayDimmed(true) }
            })
            addView(Button(this@MainActivity).apply {
                text = "Disconnect"
                setOnClickListener { returnToConnectScreen() }
            })
        })
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        pipeline?.release()
        pipeline = CameraPipeline(this, cameraPreview, hub)
        setContentView(root)

        updatingControls = true
        cameraSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, detectedCapabilities.cameras)
        cameraSpinner.setSelection(detectedCapabilities.cameras.indexOf(defaultCamera).coerceAtLeast(0))
        loadProfiles(profileSpinner, defaultCamera, defaultProfile)
        whiteBalanceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, detectedCapabilities.whiteBalanceModes)
        focusSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("Continuous video", "Auto focus", "Locked focus"))
        zoom.max = ((defaultCamera.maxZoom - 1f) * 10f).roundToInt().coerceAtLeast(1)
        zoom.progress = 0
        zoomValue.text = "1.0×"
        exposure.max = (detectedCapabilities.exposureMax - detectedCapabilities.exposureMin).coerceAtLeast(0)
        exposure.progress = (0 - detectedCapabilities.exposureMin).coerceIn(0, exposure.max)
        exposureValue.text = "0 EV"
        torch.isEnabled = defaultCamera.hasFlash
        updatingControls = false

        cameraSpinner.onItemSelectedListener = itemSelectionListener { _, position ->
            val camera = detectedCapabilities.cameras[position]
            if (camera.id == selectedCamera?.id) return@itemSelectionListener
            selectedCamera = camera
            val profile = camera.defaultLiveProfile() ?: return@itemSelectionListener
            selectedProfile = profile
            updatingControls = true
            loadProfiles(profileSpinner, camera, profile)
            zoom.max = ((camera.maxZoom - 1f) * 10f).roundToInt().coerceAtLeast(1)
            zoom.progress = 0
            zoomValue.text = "1.0×"
            torch.isEnabled = camera.hasFlash
            torch.isChecked = false
            updatingControls = false
            startSelectedCamera()
        }
        profileSpinner.onItemSelectedListener = itemSelectionListener { _, position ->
            val profile = selectedCamera?.profiles?.getOrNull(position) ?: return@itemSelectionListener
            if (profile == selectedProfile) return@itemSelectionListener
            selectedProfile = profile
            startSelectedCamera()
        }
        whiteBalanceSpinner.onItemSelectedListener = itemSelectionListener { _, position ->
            applyCameraCommand("setWhiteBalance", detectedCapabilities.whiteBalanceModes[position])
        }
        focusSpinner.onItemSelectedListener = itemSelectionListener { _, position ->
            applyCameraCommand("setFocusMode", position)
        }
        zoom.setOnSeekBarChangeListener(seekListener { progress ->
            val ratio = 1f + progress / 10f
            zoomValue.text = String.format("%.1f×", ratio)
            applyCameraCommand("setZoom", ratio)
        })
        exposure.setOnSeekBarChangeListener(seekListener { progress ->
            val ev = detectedCapabilities.exposureMin + progress
            exposureValue.text = "$ev EV"
            applyCameraCommand("setExposure", ev)
        })
        torch.setOnCheckedChangeListener { _, enabled ->
            if (!updatingControls) applyCameraCommand("setTorch", enabled)
        }

        cameraPreview.post { startSelectedCamera() }
    }

    private fun controlRow(leftLabel: String, leftControl: View, rightLabel: String, rightControl: View): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val labelParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.18f)
            val controlParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.32f)
            addView(TextView(this@MainActivity).apply { text = leftLabel; setTextColor(Color.WHITE); textSize = 12f }, labelParams)
            addView(leftControl, controlParams)
            if (rightLabel.isNotBlank()) {
                addView(TextView(this@MainActivity).apply { text = rightLabel; setTextColor(Color.WHITE); textSize = 12f }, labelParams)
            } else {
                addView(View(this@MainActivity), labelParams)
            }
            addView(rightControl, controlParams)
        }
    }

    private fun loadProfiles(spinner: Spinner, camera: CameraDescriptor, selected: CameraProfile) {
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, camera.profiles)
        spinner.setSelection(camera.profiles.indexOf(selected).coerceAtLeast(0))
    }

    private fun startSelectedCamera() {
        val camera = selectedCamera ?: return
        val profile = selectedProfile ?: return
        val config = StreamConfiguration(camera.id, profile.width, profile.height, profile.fps, profile.highSpeed, profile.codec)
        cameraStatus?.text = "Starting ${camera.name} · $profile"
        startCameraService()
        val cameraPreview = preview ?: return
        val start = { pipeline?.start(config) }
        if (cameraPreview.isAvailable) {
            start()
        } else {
            cameraPreview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    cameraPreview.surfaceTextureListener = null
                    start()
                }
                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
            }
        }
    }

    private fun applyCameraCommand(name: String, value: Any) {
        if (updatingControls) return
        pipeline?.applyCommand(JSONObject().put("name", name).put("value", value))
    }

    private fun returnToConnectScreen(
        message: String = "Disconnected. Start the hub and connect again.",
        error: Boolean = false
    ) {
        // Mark the camera session as ended before closing sockets so an intentional
        // disconnect cannot leave the full-screen UI behind.
        cameraMode = false
        setDisplayDimmed(false)
        pipeline?.stop()
        pipeline?.release()
        pipeline = null
        preview = null
        stopCameraService()
        hub.close()
        capabilities = null
        selectedCamera = null
        selectedProfile = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        setContentView(createConnectContent())
        cameraRoot = null
        setConnectStatus(message, error)
    }

    private fun setDisplayDimmed(dimmed: Boolean) {
        if (dimmed) {
            if (dimOverlay != null) return
            window.attributes = window.attributes.apply {
                screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF
            }
            val blackout = View(this).apply {
                setBackgroundColor(Color.BLACK)
                contentDescription = "Tap to restore camera controls"
                isClickable = true
                isFocusable = true
                setOnClickListener { setDisplayDimmed(false) }
            }
            dimOverlay = blackout
            cameraRoot?.addView(
                blackout,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        } else {
            dimOverlay?.let { overlay ->
                (overlay.parent as? ViewGroup)?.removeView(overlay)
            }
            dimOverlay = null
            window.attributes = window.attributes.apply {
                screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }
    }

    override fun onCommand(command: JSONObject) {
        runOnUiThread {
            when (command.optString("name")) {
                "start" -> startCameraService()
                "stop" -> stopCameraService()
            }
            pipeline?.applyCommand(command)
        }
    }

    override fun onConnected(endpoint: String) {
        Thread {
            try {
                val capabilityProbe = CameraCapabilityProbe(this)
                val detectedCapabilities = capabilityProbe.inspect()
                hub.send(capabilityProbe.asJson(detectedCapabilities))
                runOnUiThread { showCameraMode(detectedCapabilities, endpoint) }
            } catch (exception: Exception) {
                runOnUiThread { setConnectStatus("Camera capability scan failed: ${exception.message}", true) }
            }
        }.start()
    }

    override fun onDisconnected(message: String) {
        runOnUiThread {
            if (cameraMode) {
                returnToConnectScreen(message, true)
            } else {
                setConnectStatus(message, true)
            }
        }
    }

    private fun requestCameraPermissionIfNeeded() {
        if (!hasCameraPermission()) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
        }
    }

    private fun hasCameraPermission(): Boolean = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            setConnectStatus(if (hasCameraPermission()) "Camera permission granted. Start the Windows hub, then connect." else "Camera permission is required to use CamLink.", !hasCameraPermission())
        }
    }

    private fun setConnectStatus(message: String, error: Boolean = false) {
        if (::connectStatus.isInitialized) {
            connectStatus.text = message
            connectStatus.setTextColor(if (error) 0xffb00020.toInt() else 0xff1b5e20.toInt())
        }
    }

    private fun startCameraService() {
        val intent = Intent(this, CamLinkCameraService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    private fun stopCameraService() = stopService(Intent(this, CamLinkCameraService::class.java))

    private fun itemSelectionListener(onSelected: (AdapterView<*>, Int) -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
            if (!updatingControls) onSelected(parent, position)
        }
        override fun onNothingSelected(parent: AdapterView<*>) = Unit
    }

    private fun seekListener(onChanged: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
            if (fromUser) onChanged(progress)
        }
        override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
    }

    private fun CameraDescriptor.defaultLiveProfile(): CameraProfile? = profiles
        .filter { !it.highSpeed && it.codec == "h264" }
        .sortedWith(compareByDescending<CameraProfile> { it.width == 1920 && it.height == 1080 && it.fps == 60 }
            .thenByDescending { it.width == 1920 && it.height == 1080 }
            .thenByDescending { it.width.toLong() * it.height }
            .thenByDescending { it.fps })
        .firstOrNull()
        ?: profiles.firstOrNull()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val PREVIEW_ID = 0xC0A1
        const val HUB_PORT = 6020
        const val CAMERA_PERMISSION_REQUEST = 100
    }
}
