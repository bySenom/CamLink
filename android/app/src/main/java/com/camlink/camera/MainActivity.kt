package com.camlink.camera

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.util.Log
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
    private var waitingForSmartUsbFallback = false
    private var discoveringLanHub = false
    private var validatingProfiles = false
    private lateinit var protectionStore: ProtectionSettingsStore
    private lateinit var protectionController: ProtectionController
    private var protectionSettings = ProtectionSettings.preset(ProtectionProfile.BALANCED)
    private var healthMonitor: DeviceHealthMonitor? = null
    private var latestHealthState: DeviceHealthState? = null
    private var originalProfile: CameraProfile? = null
    private var pendingProtectionRollback: CameraProfile? = null
    private var handlingPipelineFailure = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The connection screen never owns a camera session or foreground service.
        // This also clears an orphaned service from an interrupted earlier session.
        cameraMode = false
        stopCameraService()
        protectionStore = ProtectionSettingsStore(this)
        protectionSettings = protectionStore.load()
        protectionController = ProtectionController(protectionSettings)
        setContentView(createConnectContent())
        requestCameraPermissionIfNeeded()
        checkForUpdates(showNoUpdate = false)
    }

    override fun onDestroy() {
        setDisplayDimmed(false)
        restoreSystemBars()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hub.close()
        healthMonitor?.stop()
        healthMonitor = null
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
            hint = "Windows PC LAN IP (optional)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        root.addView(transport)
        root.addView(hostInput)
        root.addView(Button(this).apply {
            text = "Connect to Windows companion"
            setOnClickListener { connect() }
        })
        root.addView(Button(this).apply {
            text = "Find hub on Wi-Fi"
            setOnClickListener { discoverAndConnect() }
        })
        root.addView(Button(this).apply {
            text = "Validate camera profiles"
            setOnClickListener { validateCameraProfiles() }
        })
        root.addView(Button(this).apply {
            text = "Protection settings"
            setOnClickListener { showProtectionSettings() }
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
            0 -> {
                if (host.isBlank()) {
                    waitingForSmartUsbFallback = true
                    setConnectStatus("Trying USB first. Wi-Fi search starts automatically if USB is unavailable.")
                    hub.connectUsb(HUB_PORT, this)
                } else {
                    hub.connectSmart(host, HUB_PORT, this)
                }
            }
            1 -> {
                waitingForSmartUsbFallback = false
                hub.connectUsb(HUB_PORT, this)
            }
            2 -> {
                if (host.isBlank()) discoverAndConnect() else hub.connectWifi(host, HUB_PORT, this)
            }
            else -> setConnectStatus("Bluetooth is reserved for pairing/control. Use USB or Wi-Fi for the video session.", true)
        }
    }

    private fun discoverAndConnect() {
        if (!hasCameraPermission()) {
            requestCameraPermissionIfNeeded()
            return
        }
        if (discoveringLanHub) {
            setConnectStatus("Wi-Fi hub search is already running.")
            return
        }
        waitingForSmartUsbFallback = false
        discoveringLanHub = true
        setConnectStatus("Searching for CamLink Hub on this Wi-Fi…")
        LanHubDiscovery(this).find(
            onFound = { endpoint ->
                runOnUiThread {
                    discoveringLanHub = false
                    hostInput.setText(endpoint.host)
                    transport.setSelection(2)
                    setConnectStatus("Hub found at ${endpoint.host}. Connecting…")
                    hub.connectWifi(endpoint.host, endpoint.port, this)
                }
            },
            onNotFound = { message ->
                runOnUiThread {
                    discoveringLanHub = false
                    setConnectStatus(message, true)
                }
            }
        )
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

    private fun showProtectionSettings() {
        ProtectionSettingsDialog(this, protectionSettings) { updated ->
            protectionStore.save(updated).onSuccess { saved ->
                protectionSettings = saved
                protectionController.updateSettings(saved)
                hub.sendProtectionConfiguration(saved)
                val message = "Protection settings saved locally (${saved.profile.name})."
                if (cameraMode) {
                    hub.sendStatus(message)
                    updateCameraHealthUi(latestHealthState)
                } else {
                    setConnectStatus(message)
                }
            }.onFailure { error ->
                if (cameraMode) hub.sendStatus("Protection settings rejected: ${error.message}", error = true)
                else setConnectStatus("Protection settings rejected: ${error.message}", true)
            }
        }.show()
    }

    /** Runs only from the connection screen, so it never competes with the live Camera2 pipeline. */
    private fun validateCameraProfiles() {
        if (!hasCameraPermission()) {
            requestCameraPermissionIfNeeded()
            return
        }
        if (validatingProfiles) {
            setConnectStatus("Camera profile validation is already running.")
            return
        }
        validatingProfiles = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setConnectStatus("Scanning Camera2 and encoder candidates before validation…")
        Thread {
            try {
                val detectedCapabilities = CameraCapabilityProbe(this).inspect()
                CameraProfileValidator(this).validateAll(
                    detectedCapabilities,
                    onProgress = { completed, total, camera, profile ->
                        runOnUiThread {
                            setConnectStatus("Validating ${completed + 1}/$total: ${camera.name} ${profile.width}×${profile.height} @ ${profile.fps} fps")
                        }
                    },
                    onComplete = { reports ->
                        runOnUiThread {
                            validatingProfiles = false
                            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            val verified = reports.count { it.status == ProfileVerification.VERIFIED }
                            val unstable = reports.count { it.status == ProfileVerification.UNSTABLE }
                            val unsupported = reports.count { it.status == ProfileVerification.UNSUPPORTED }
                            val firstIssue = reports.firstOrNull { it.status != ProfileVerification.VERIFIED }?.message
                            setConnectStatus(
                                "Profile validation complete: $verified verified, $unstable unstable, $unsupported unsupported." +
                                    (firstIssue?.let { " First issue: $it" } ?: "") +
                                    " Reconnect to refresh the profile list.",
                                unstable > 0 || unsupported > 0
                            )
                        }
                    }
                )
            } catch (exception: Exception) {
                runOnUiThread {
                    validatingProfiles = false
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    setConnectStatus("Camera profile validation failed: ${exception.message}", true)
                }
            }
        }.apply {
            name = "CamLinkProfileScan"
            start()
        }
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
        originalProfile = defaultProfile
        pendingProtectionRollback = null
        protectionController.reset()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        cameraRoot = root
        val cameraPreview = AspectRatioTextureView(this).apply {
            id = PREVIEW_ID
            isOpaque = true
        }
        root.addView(cameraPreview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        preview = cameraPreview

        cameraStatus = TextView(this).apply {
            text = "●  preparing"
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = roundedSurface(0xc918202b.toInt(), dp(18))
        }
        root.addView(cameraStatus, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            setMargins(dp(16), dp(16), 0, 0)
        })

        var zoomRatio = 1f
        var exposureEv = 0
        var whiteBalanceIndex = 0
        var focusMode = defaultCamera.focusModes.firstOrNull() ?: 0
        val toolbar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = roundedSurface(0xe61a2330.toInt(), dp(24))
        }
        fun addTool(button: TextView) {
            toolbar.addView(button, LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                setMargins(dp(2), 0, dp(2), 0)
            })
        }

        val lens = iconButton("◎", "Lens")
        val profile = iconButton("▣", "Video profile")
        val whiteBalance = iconButton("◌", "White balance")
        val focus = iconButton("⊙", "Focus mode")
        val zoom = iconButton("⌕", "Zoom")
        val exposure = iconButton("±", "Exposure")
        val torch = iconButton("✦", "Torch / fill light")
        val protection = iconButton("◇", "Protection settings")
        val dim = iconButton("◐", "Dim screen without locking")
        val disconnect = iconButton("×", "Disconnect", destructive = true)
        fun updateTorchButton(enabled: Boolean) {
            torch.isSelected = enabled
            torch.background = iconBackground(enabled)
            torch.alpha = if (torch.isEnabled) 1f else 0.35f
        }
        torch.isEnabled = defaultCamera.hasFlash
        updateTorchButton(false)

        listOf(lens, profile, whiteBalance, focus, zoom, exposure, torch, protection, dim, disconnect).forEach(::addTool)
        root.addView(toolbar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            setMargins(0, 0, 0, dp(16))
        })

        pipeline?.release()
        pipeline = CameraPipeline(this, cameraPreview, hub, object : CameraPipeline.Listener {
            override fun onStreamStarted(config: StreamConfiguration, bitrateMbps: Float) {
                runOnUiThread {
                    val profileInfo = config.asHealthProfile()
                    healthMonitor?.updateProfiles(requested = originalProfile?.asHealthProfile() ?: profileInfo, active = profileInfo)
                    healthMonitor?.updateStreamingMetrics(latestHealthState?.actualFps, latestHealthState?.droppedFrames, latestHealthState?.recentDroppedFrames, bitrateMbps)
                    hub.sendStreamProfile("active", originalProfile?.asHealthProfile() ?: profileInfo, profileInfo)
                    pendingProtectionRollback = null
                    handlingPipelineFailure = false
                    updateCameraHealthUi(latestHealthState)
                }
            }

            override fun onStreamFailed(config: StreamConfiguration, message: String) {
                runOnUiThread { handlePipelineFailure(config, message) }
            }

            override fun onStreamMetrics(metrics: StreamMetrics) {
                runOnUiThread {
                    healthMonitor?.updateStreamingMetrics(metrics.actualFps, metrics.droppedFrames, metrics.recentDroppedFrames, metrics.activeBitrateMbps)
                }
            }

            override fun onPreviewTransform(config: StreamConfiguration, rotationDegrees: Int) {
                runOnUiThread {
                    (preview as? AspectRatioTextureView)?.setAspectRatio(config.width, config.height, rotationDegrees)
                }
            }
        })
        setContentView(root)
        startHealthMonitoring()

        lens.setOnClickListener {
            showChoiceDialog("Lens", detectedCapabilities.cameras, detectedCapabilities.cameras.indexOf(selectedCamera)) { camera ->
                if (camera.id == selectedCamera?.id) return@showChoiceDialog
                selectedCamera = camera
                selectedProfile = camera.defaultLiveProfile()
                originalProfile = selectedProfile
                zoomRatio = 1f
                exposureEv = 0
                whiteBalanceIndex = 0
                focusMode = camera.focusModes.firstOrNull() ?: 0
                torch.isEnabled = camera.hasFlash
                updateTorchButton(false)
                applyCameraCommand("setZoom", zoomRatio)
                applyCameraCommand("setExposure", exposureEv)
                applyCameraCommand("setWhiteBalance", "Auto")
                applyCameraCommand("setFocusMode", focusMode)
                startSelectedCamera()
            }
        }
        profile.setOnClickListener {
            val camera = selectedCamera ?: return@setOnClickListener
            showChoiceDialog("Video profile", camera.profiles, camera.profiles.indexOf(selectedProfile)) { selected ->
                if (selected == selectedProfile) return@showChoiceDialog
                selectedProfile = selected
                originalProfile = selected
                startSelectedCamera()
            }
        }
        whiteBalance.setOnClickListener {
            val modes = (selectedCamera ?: defaultCamera).whiteBalanceModes
            showChoiceDialog("White balance", modes, whiteBalanceIndex) { selected ->
                whiteBalanceIndex = modes.indexOf(selected)
                applyCameraCommand("setWhiteBalance", selected)
            }
        }
        focus.setOnClickListener {
            val supportedModes = (selectedCamera ?: defaultCamera).focusModes
            val choices = supportedModes.map(::focusLabel)
            showChoiceDialog("Focus", choices, supportedModes.indexOf(focusMode)) { selected ->
                focusMode = supportedModes[choices.indexOf(selected)]
                applyCameraCommand("setFocusMode", focusMode)
            }
        }
        zoom.setOnClickListener {
            val maximum = ((selectedCamera?.maxZoom ?: 1f) * 10f).roundToInt().coerceAtLeast(10)
            showSliderDialog("Zoom", 10, maximum, (zoomRatio * 10f).roundToInt(), { "%.1f×".format(it / 10f) }) { value ->
                zoomRatio = value / 10f
                applyCameraCommand("setZoom", zoomRatio)
            }
        }
        exposure.setOnClickListener {
            val camera = selectedCamera ?: defaultCamera
            showSliderDialog("Exposure", camera.exposureMin, camera.exposureMax, exposureEv, { "$it EV" }) { value ->
                exposureEv = value
                applyCameraCommand("setExposure", exposureEv)
            }
        }
        torch.setOnClickListener {
            if (!torch.isEnabled) return@setOnClickListener
            updateTorchButton(!torch.isSelected)
            applyCameraCommand("setTorch", torch.isSelected)
        }
        protection.setOnClickListener { showProtectionSettings() }
        dim.setOnClickListener { setDisplayDimmed(true) }
        disconnect.setOnClickListener { returnToConnectScreen() }

        cameraPreview.post {
            hideSystemBarsForCamera()
            startSelectedCamera()
        }
    }

    private fun showLegacyCameraMode(detectedCapabilities: CameraCapabilities, endpoint: String) {
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
        val cameraPreview = AspectRatioTextureView(this).apply {
            id = PREVIEW_ID
            isOpaque = true
        }
        root.addView(cameraPreview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        preview = cameraPreview

        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = roundedSurface(0xe619202b.toInt(), dp(18))
        }
        header.addView(TextView(this).apply {
            text = "●"
            textSize = 18f
            setTextColor(0xff49e07f.toInt())
        })
        cameraStatus = TextView(this).apply {
            text = "Preparing ${defaultCamera.name}"
            setTextColor(Color.WHITE)
            textSize = 13f
            maxLines = 1
            setPadding(dp(8), 0, 0, 0)
        }
        header.addView(cameraStatus, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(header, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
            setMargins(dp(16), dp(12), dp(16), 0)
        })

        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = roundedSurface(0xe919202b.toInt(), dp(20))
        }

        val cameraSpinner = Spinner(this)
        val profileSpinner = Spinner(this)
        val whiteBalanceSpinner = Spinner(this)
        val focusSpinner = Spinner(this)
        val zoom = SeekBar(this).apply { contentDescription = "Zoom" }
        val exposure = SeekBar(this).apply { contentDescription = "Exposure" }
        val zoomValue = valueChip()
        val exposureValue = valueChip()
        listOf(cameraSpinner, profileSpinner, whiteBalanceSpinner, focusSpinner).forEach { spinner ->
            spinner.background = roundedSurface(0xff273343.toInt(), dp(12))
            spinner.setPadding(dp(6), 0, dp(4), 0)
        }

        overlay.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(iconTile("◉", "Lens", cameraSpinner), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, dp(5), 0) })
            addView(iconTile("▣", "Video profile", profileSpinner), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(5), 0, dp(5), 0) })
            addView(iconTile("☀", "White balance", whiteBalanceSpinner), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(5), 0, dp(5), 0) })
            addView(iconTile("◎", "Focus mode", focusSpinner), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(5), 0, 0, 0) })
        })

        val torch = iconButton("☼", "Torch / fill light")
        fun updateTorchButton(enabled: Boolean) {
            torch.isSelected = enabled
            torch.background = iconBackground(enabled)
            torch.alpha = if (torch.isEnabled) 1f else 0.35f
        }
        val dim = iconButton("◐", "Dim screen without locking")
        val disconnect = iconButton("×", "Disconnect", destructive = true)
        overlay.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
            addView(adjustmentControl("⌕", "Zoom", zoom, zoomValue), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(adjustmentControl("±", "Exposure", exposure, exposureValue), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(8), 0, dp(8), 0) })
            addView(torch, LinearLayout.LayoutParams(dp(44), dp(44)).apply { setMargins(0, 0, dp(6), 0) })
            addView(dim, LinearLayout.LayoutParams(dp(44), dp(44)).apply { setMargins(0, 0, dp(6), 0) })
            addView(disconnect, LinearLayout.LayoutParams(dp(44), dp(44)))
        })
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            setMargins(dp(16), 0, dp(16), dp(14))
        })

        pipeline?.release()
        pipeline = CameraPipeline(this, cameraPreview, hub, object : CameraPipeline.Listener {
            override fun onStreamStarted(config: StreamConfiguration, bitrateMbps: Float) {
                runOnUiThread {
                    val profileInfo = config.asHealthProfile()
                    healthMonitor?.updateProfiles(requested = profileInfo, active = profileInfo)
                    healthMonitor?.updateStreamingMetrics(latestHealthState?.actualFps, latestHealthState?.droppedFrames, latestHealthState?.recentDroppedFrames, bitrateMbps)
                    hub.sendStreamProfile("active", profileInfo, profileInfo)
                    pendingProtectionRollback = null
                    handlingPipelineFailure = false
                    updateCameraHealthUi(latestHealthState)
                }
            }

            override fun onStreamFailed(config: StreamConfiguration, message: String) {
                runOnUiThread { handlePipelineFailure(config, message) }
            }

            override fun onStreamMetrics(metrics: StreamMetrics) {
                runOnUiThread {
                        healthMonitor?.updateStreamingMetrics(metrics.actualFps, metrics.droppedFrames, metrics.recentDroppedFrames, metrics.activeBitrateMbps)
                }
            }
        })
        setContentView(root)
        startHealthMonitoring()

        updatingControls = true
        cameraSpinner.adapter = darkSpinnerAdapter(detectedCapabilities.cameras)
        cameraSpinner.setSelection(detectedCapabilities.cameras.indexOf(defaultCamera).coerceAtLeast(0))
        loadProfiles(profileSpinner, defaultCamera, defaultProfile)
        whiteBalanceSpinner.adapter = darkSpinnerAdapter(detectedCapabilities.whiteBalanceModes)
        focusSpinner.adapter = darkSpinnerAdapter(listOf("Continuous video", "Auto focus", "Locked focus"))
        zoom.max = ((defaultCamera.maxZoom - 1f) * 10f).roundToInt().coerceAtLeast(1)
        zoom.progress = 0
        zoomValue.text = "1.0×"
        exposure.max = (detectedCapabilities.exposureMax - detectedCapabilities.exposureMin).coerceAtLeast(0)
        exposure.progress = (0 - detectedCapabilities.exposureMin).coerceIn(0, exposure.max)
        exposureValue.text = "0 EV"
        torch.isEnabled = defaultCamera.hasFlash
        updateTorchButton(false)
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
            updateTorchButton(false)
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
        torch.setOnClickListener {
            if (!torch.isEnabled) return@setOnClickListener
            updateTorchButton(!torch.isSelected)
            applyCameraCommand("setTorch", torch.isSelected)
        }

        cameraPreview.post { startSelectedCamera() }
    }

    private fun iconTile(icon: String, description: String, control: View): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(7), dp(4), dp(7), dp(4))
            background = roundedSurface(0x401e2b3b, dp(14))
            addView(TextView(this@MainActivity).apply {
                text = icon
                textSize = 17f
                contentDescription = description
                setTextColor(0xffb8c7da.toInt())
                gravity = Gravity.CENTER
            })
            addView(control, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun adjustmentControl(icon: String, description: String, control: View, value: TextView): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            contentDescription = description
            addView(TextView(this@MainActivity).apply {
                text = icon
                textSize = 20f
                setTextColor(0xffb8c7da.toInt())
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(28), dp(36)))
            addView(control, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(value, LinearLayout.LayoutParams(dp(48), dp(30)).apply { setMargins(dp(4), 0, 0, 0) })
        }
    }

    private fun valueChip(): TextView = TextView(this).apply {
        gravity = Gravity.CENTER
        textSize = 12f
        setTextColor(Color.WHITE)
        maxLines = 1
        background = roundedSurface(0xff33465b.toInt(), dp(10))
    }

    private fun iconButton(symbol: String, description: String, destructive: Boolean = false): TextView = TextView(this).apply {
        text = symbol
        textSize = 23f
        gravity = Gravity.CENTER
        contentDescription = description
        isClickable = true
        isFocusable = true
        setTextColor(if (destructive) 0xffff9d9d.toInt() else Color.WHITE)
        background = iconBackground(active = false, destructive = destructive)
    }

    private fun iconBackground(active: Boolean, destructive: Boolean = false): GradientDrawable = roundedSurface(
        when {
            destructive -> 0xff62333a.toInt()
            active -> 0xff1769aa.toInt()
            else -> 0xff2a394b.toInt()
        },
        dp(14)
    )

    private fun roundedSurface(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private fun <T> darkSpinnerAdapter(items: List<T>): ArrayAdapter<T> = object : ArrayAdapter<T>(
        this,
        android.R.layout.simple_spinner_item,
        items
    ) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            styleSpinnerText(super.getView(position, convertView, parent), popup = false)

        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
            styleSpinnerText(super.getDropDownView(position, convertView, parent), popup = true)
    }

    private fun styleSpinnerText(view: View, popup: Boolean): View {
        (view as? TextView)?.apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(9), dp(7), dp(9), dp(7))
            if (popup) background = roundedSurface(0xff202b38.toInt(), dp(8))
        }
        return view
    }

    private fun <T> showChoiceDialog(title: String, choices: List<T>, selectedIndex: Int, onSelected: (T) -> Unit) {
        if (choices.isEmpty()) return
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(
                choices.map { it.toString() }.toTypedArray(),
                selectedIndex.coerceIn(0, choices.lastIndex)
            ) { dialog, index ->
                onSelected(choices[index])
                dialog.dismiss()
            }
            .show()
    }

    private fun showSliderDialog(
        title: String,
        minimum: Int,
        maximum: Int,
        current: Int,
        format: (Int) -> String,
        onChanged: (Int) -> Unit
    ) {
        val range = (maximum - minimum).coerceAtLeast(0)
        val value = TextView(this).apply {
            text = format(current.coerceIn(minimum, maximum))
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(4))
        }
        val slider = SeekBar(this).apply {
            max = range
            progress = (current - minimum).coerceIn(0, range)
            setPadding(dp(24), 0, dp(24), 0)
        }
        slider.setOnSeekBarChangeListener(seekListener { progress ->
            val selected = minimum + progress
            value.text = format(selected)
            onChanged(selected)
        })
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            addView(value)
            addView(slider)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setView(content)
            .setPositiveButton("Done", null)
            .show()
    }

    private fun hideSystemBarsForCamera() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.apply {
                hide(WindowInsets.Type.systemBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
        }
    }

    private fun restoreSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.show(WindowInsets.Type.systemBars())
            window.setDecorFitsSystemWindows(true)
        } else {
            @Suppress("DEPRECATION")
            run { window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE }
        }
    }

    private fun loadProfiles(spinner: Spinner, camera: CameraDescriptor, selected: CameraProfile) {
        spinner.adapter = darkSpinnerAdapter(camera.profiles)
        spinner.setSelection(camera.profiles.indexOf(selected).coerceAtLeast(0))
    }

    private fun startHealthMonitoring() {
        val monitor = DeviceHealthMonitor(this)
        healthMonitor?.stop()
        healthMonitor = monitor
        monitor.updateProfiles(originalProfile?.asHealthProfile(), latestHealthState?.activeProfile)
        monitor.start(object : DeviceHealthMonitor.Listener {
            override fun onHealthState(state: DeviceHealthState, immediate: Boolean) {
                runOnUiThread { handleHealthState(state, immediate) }
            }
        })
    }

    private fun handleHealthState(rawState: DeviceHealthState, immediate: Boolean) {
        if (!cameraMode) return
        val decision = protectionController.evaluate(rawState)
        if (decision != null) applyProtectionDecision(decision, rawState)
        val state = rawState.copy(activeProtectionAction = protectionController.activeAction())
        healthMonitor?.updateProtectionAction(state.activeProtectionAction)
        latestHealthState = state
        updateCameraHealthUi(state)
        // Health data remains deliberately rate-limited by DeviceHealthMonitor. A thermal
        // status change is emitted immediately; routine snapshots are emitted once/second.
        hub.sendHealth(state)
    }

    private fun applyProtectionDecision(decision: ProtectionDecision, state: DeviceHealthState) {
        val now = state.timestampMs
        fun announce(message: String, error: Boolean = false) {
            Log.i("CamLinkProtection", message)
            if (protectionSettings.forwardWarningsToHub) hub.sendStatus(message, error)
            if (protectionSettings.warningsEnabled && protectionSettings.audibleWarnings) {
                runCatching {
                    ToneGenerator(AudioManager.STREAM_NOTIFICATION, 65).also { tone ->
                        tone.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
                        window.decorView.postDelayed({ tone.release() }, 150L)
                    }
                }
            }
        }

        when (decision.action) {
            ProtectionAction.NONE -> Unit
            ProtectionAction.INFORM -> if (protectionSettings.warningsEnabled) announce(decision.reason)
            ProtectionAction.REDUCE_BITRATE -> {
                announce("Protection action scheduled: reduce bitrate by ${protectionSettings.bitrateReductionPercent}%. ${decision.reason}")
                protectionController.recordApplied(decision, now)
                healthMonitor?.updateProtectionAction(decision.action)
                pipeline?.reduceBitrate(protectionSettings.bitrateReductionPercent, protectionSettings.minimumBitrateMbps) { applied, bitrate ->
                    runOnUiThread {
                        healthMonitor?.updateStreamingMetrics(latestHealthState?.actualFps, latestHealthState?.droppedFrames, latestHealthState?.recentDroppedFrames, bitrate)
                        if (!applied) {
                            announce("Dynamic bitrate change was unavailable; requesting FPS fallback.")
                            applyProtectionFallback(ProtectionAction.REDUCE_FPS, "Encoder rejected dynamic bitrate change.", now)
                        }
                    }
                }
            }
            ProtectionAction.REDUCE_FPS,
            ProtectionAction.REDUCE_RESOLUTION -> {
                protectionController.recordApplied(decision, now)
                healthMonitor?.updateProtectionAction(decision.action)
                applyProtectionFallback(decision.action, decision.reason, now)
            }
            ProtectionAction.RESTORE_QUALITY -> {
                val restore = originalProfile
                if (restore != null && restore != selectedProfile) {
                    announce("Protection recovery requested: restoring ${restore.width}x${restore.height}@${restore.fps}.")
                    protectionController.recordApplied(decision, now)
                    selectedProfile = restore
                    startSelectedCamera(protectionChange = true)
                }
            }
            ProtectionAction.STOP_STREAM,
            ProtectionAction.RELEASE_RESOURCES -> {
                protectionController.recordApplied(decision, now)
                val release = decision.action == ProtectionAction.RELEASE_RESOURCES
                announce(if (release) "Critical thermal status: releasing camera and encoder. ${decision.reason}" else "Critical condition: stopping stream. ${decision.reason}", error = true)
                hub.sendStreamProfile("stopping", originalProfile?.asHealthProfile(), state.activeProfile)
                returnToConnectScreen("Protection stopped the stream safely. ${decision.reason}", true)
            }
        }
    }

    private fun applyProtectionFallback(action: ProtectionAction, reason: String, now: Long) {
        val current = selectedProfile ?: return
        val fallback = findProtectionFallback(action, current)
        if (fallback == null) {
            hub.sendStatus("Protection fallback unavailable for ${current.width}x${current.height}@${current.fps}: $reason", error = true)
            return
        }
        pendingProtectionRollback = current
        selectedProfile = fallback
        val requested = originalProfile?.asHealthProfile()
        hub.sendStatus("Profile fallback requested: ${current.width}x${current.height}@${current.fps} -> ${fallback.width}x${fallback.height}@${fallback.fps}. $reason")
        hub.sendStreamProfile("switchRequested", requested, current.asHealthProfile())
        healthMonitor?.updateProfiles(requested, current.asHealthProfile())
        startSelectedCamera(protectionChange = true)
    }

    private fun findProtectionFallback(action: ProtectionAction, current: CameraProfile): CameraProfile? {
        val camera = selectedCamera ?: return null
        val supported = camera.profiles
            .filter { !it.highSpeed && it.verification != ProfileVerification.UNSUPPORTED }
            .let { profiles -> profiles.filter { it.codec == current.codec }.ifEmpty { profiles } }
        return when (action) {
            ProtectionAction.REDUCE_FPS -> {
                val desired = protectionSettings.fpsFallbackOrder.filter { it < current.fps }
                desired.firstNotNullOfOrNull { fps ->
                    supported.filter { it.width == current.width && it.height == current.height && it.fps <= fps }
                        .maxByOrNull { it.fps }
                } ?: supported.filter { it.width == current.width && it.height == current.height && it.fps < current.fps }
                    .maxByOrNull { it.fps }
                    ?: findProtectionFallback(ProtectionAction.REDUCE_RESOLUTION, current)
            }
            ProtectionAction.REDUCE_RESOLUTION -> {
                val desiredHeights = protectionSettings.resolutionFallbackHeights.filter { it < current.height }
                desiredHeights.firstNotNullOfOrNull { height ->
                    supported.filter { it.height <= height && it.height < current.height && it.fps <= current.fps }
                        .maxWithOrNull(compareBy<CameraProfile> { it.height }.thenBy { it.fps })
                } ?: supported.filter { it.height < current.height && it.fps <= current.fps }
                    .maxWithOrNull(compareBy<CameraProfile> { it.height }.thenBy { it.fps })
            }
            else -> null
        }
    }

    private fun handlePipelineFailure(config: StreamConfiguration, message: String) {
        if (handlingPipelineFailure) return
        val rollback = pendingProtectionRollback ?: run {
            cameraStatus?.text = "⚠  $message"
            return
        }
        handlingPipelineFailure = true
        pendingProtectionRollback = null
        hub.sendStatus("Profile fallback failed; restoring ${rollback.width}x${rollback.height}@${rollback.fps}.", error = true)
        selectedProfile = rollback
        startSelectedCamera(protectionChange = true)
    }

    private fun updateCameraHealthUi(state: DeviceHealthState?) {
        val profile = state?.activeProfile ?: selectedProfile?.asHealthProfile()
        val fps = state?.actualFps?.let { String.format("%.2f FPS", it) } ?: "-- FPS"
        val temperature = state?.batteryTemperatureCelsius?.let { "Akku ${String.format("%.1f", it)} °C" } ?: "Temperatur: –"
        val thermals = state?.thermalStatusLabel ?: "Nicht verfügbar"
        val warning = when (state?.thermalStatus) {
            ThermalStatus.CRITICAL, ThermalStatus.EMERGENCY, ThermalStatus.SHUTDOWN -> "⚠ "
            else -> "● "
        }
        cameraStatus?.text = "$warning${profile?.height ?: "--"}p · ${profile?.fps ?: "--"} fps  ·  $fps  ·  $temperature  ·  Thermik: $thermals"
        val color = when (state?.thermalStatus) {
            ThermalStatus.LIGHT -> 0xffffd54f.toInt()
            ThermalStatus.MODERATE -> 0xffffa726.toInt()
            ThermalStatus.SEVERE -> 0xffef5350.toInt()
            ThermalStatus.CRITICAL, ThermalStatus.EMERGENCY, ThermalStatus.SHUTDOWN -> 0xffff5252.toInt()
            else -> Color.WHITE
        }
        cameraStatus?.setTextColor(color)
    }

    private fun CameraProfile.asHealthProfile() = HealthStreamProfile(width, height, fps, codec)
    private fun StreamConfiguration.asHealthProfile() = HealthStreamProfile(width, height, fps, codec)

    private fun startSelectedCamera(protectionChange: Boolean = false) {
        if (!cameraMode || !hub.isConnected) {
            if (cameraMode) returnToConnectScreen("Hub connection is not active; camera was not started.", true)
            return
        }
        val camera = selectedCamera ?: return
        val profile = selectedProfile ?: return
        val config = StreamConfiguration(camera.id, profile.width, profile.height, profile.fps, profile.highSpeed, profile.codec)
        if (!protectionChange) originalProfile = profile
        val requested = originalProfile?.asHealthProfile() ?: profile.asHealthProfile()
        healthMonitor?.updateProfiles(requested, latestHealthState?.activeProfile)
        (preview as? AspectRatioTextureView)?.setAspectRatio(profile.width, profile.height)
        updateCameraHealthUi(latestHealthState)
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
        healthMonitor?.stop()
        healthMonitor = null
        latestHealthState = null
        pendingProtectionRollback = null
        handlingPipelineFailure = false
        preview = null
        stopCameraService()
        hub.close()
        capabilities = null
        selectedCamera = null
        selectedProfile = null
        originalProfile = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        restoreSystemBars()
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
                "getProtectionConfig" -> hub.sendProtectionConfiguration(protectionSettings)
                "setProtectionConfig" -> {
                    val config = command.optJSONObject("value")
                    if (config == null) {
                        hub.sendProtectionConfigurationAck(false, null, "Protection configuration must be a JSON object.")
                    } else {
                        val candidate = ProtectionSettingsJson.fromJson(config, protectionSettings)
                        protectionStore.save(candidate).onSuccess { saved ->
                            protectionSettings = saved
                            protectionController.updateSettings(saved)
                            hub.sendProtectionConfigurationAck(true, saved)
                            hub.sendStatus("Protection settings accepted from Windows Hub.")
                        }.onFailure { error ->
                            hub.sendProtectionConfigurationAck(false, null, error.message ?: "Invalid protection configuration.")
                        }
                    }
                }
            }
            pipeline?.applyCommand(command)
        }
    }

    override fun onConnected(endpoint: String) {
        waitingForSmartUsbFallback = false
        Thread {
            try {
                val capabilityProbe = CameraCapabilityProbe(this)
                val detectedCapabilities = capabilityProbe.inspect()
                hub.send(capabilityProbe.asJson(detectedCapabilities))
                hub.sendProtectionConfiguration(protectionSettings)
                runOnUiThread { showCameraMode(detectedCapabilities, endpoint) }
            } catch (exception: Exception) {
                runOnUiThread { setConnectStatus("Camera capability scan failed: ${exception.message}", true) }
            }
        }.start()
    }

    override fun onDisconnected(message: String) {
        runOnUiThread {
            if (waitingForSmartUsbFallback && !cameraMode) {
                waitingForSmartUsbFallback = false
                discoverAndConnect()
            } else if (cameraMode) {
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

    private fun focusLabel(mode: Int): String = when (mode) {
        1 -> "Auto focus"
        2 -> "Locked focus"
        else -> "Continuous video"
    }

    private fun startCameraService() {
        if (!cameraMode || !hub.isConnected) return
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
        .filter { !it.highSpeed && it.codec == "h264" && it.verification != ProfileVerification.UNSUPPORTED }
        // Do not make an untested Camera2-only candidate the automatic stream.
        // Once validated, 1080p60 is preferred, followed by 4K30 and the stable 1080p30 fallback.
        .sortedWith(compareByDescending<CameraProfile> { it.verification == ProfileVerification.VERIFIED || it.source == ProfileSource.CAMCORDER_HINT }
            .thenByDescending { it.verification == ProfileVerification.VERIFIED && it.width == 1920 && it.height == 1080 && it.fps == 60 }
            .thenByDescending { it.verification == ProfileVerification.VERIFIED && it.width >= 3840 && it.fps == 30 }
            .thenByDescending { it.width == 1920 && it.height == 1080 && it.fps == 30 }
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
