// SPDX-License-Identifier: AGPL-3.0-only
// UI copy and visual language adapted from DiAuto. See docs/THIRD_PARTY_NOTICES.md.
package com.shilapi.xcertplay

import android.Manifest
import android.app.AlertDialog
import android.app.Dialog
import android.app.TimePickerDialog
import android.view.Window
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.widget.doAfterTextChanged
import androidx.core.view.doOnLayout
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.airplay.CarPlayDisplayScale
import com.shilapi.xcertplay.airplay.ClusterSpeedometerOverlay
import com.shilapi.xcertplay.airplay.ClusterTurnCardOverlay
import com.shilapi.xcertplay.backup.SettingsBackupCodec
import com.shilapi.xcertplay.backup.SettingsBackupEndpoint
import com.shilapi.xcertplay.backup.SettingsBackupServer
import com.shilapi.xcertplay.backup.SettingsQrCode
import com.shilapi.xcertplay.compat.closeCompat
import com.shilapi.xcertplay.cluster.CastGateClient
import com.shilapi.xcertplay.telecom.PhoneAudioRoute
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.MtkDecoderTuning
import com.shilapi.xcertplay.network.CarHotspotSettings
import com.shilapi.xcertplay.network.CarHotspotTethering
import com.shilapi.xcertplay.network.WifiP2pChannels
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.settings.SettingsTheme
import com.shilapi.xcertplay.settings.SettingsWidgets
import com.shilapi.xcertplay.setup.DiLinkGeneration
import com.shilapi.xcertplay.setup.SetupGuide
import com.shilapi.xcertplay.transport.EvChargingConnectors
import com.shilapi.xcertplay.update.BackgroundUpdateScheduler
import com.shilapi.xcertplay.update.UpdateApkCache
import com.shilapi.xcertplay.update.UpdateApkExport
import com.shilapi.xcertplay.update.UpdateAvailability
import com.shilapi.xcertplay.update.UpdateCatalog
import com.shilapi.xcertplay.update.UpdateChecksums
import com.shilapi.xcertplay.update.UpdateClient
import com.shilapi.xcertplay.update.UpdateRelease
import com.shilapi.xcertplay.update.UpdateReleaseLookup
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

internal enum class SettingsCategory {
    // v113: 仪表与方控各自独立成菜单 —— 仪表的设置原来散在「导航」里，
    // 方控的设置一半在「车辆」一半在「高级」，两边都不好找。
    OVERVIEW, CONNECTION, DISPLAY, AUDIO, NAVIGATION, CLUSTER, WHEEL, VEHICLE, LANGUAGE, DIAGNOSTICS, ADVANCED, ABOUT,
}

/** A settings card. Every entry needs one category in [SettingsInformationArchitecture]; see AGENTS.md. */
internal enum class SettingsSection {
    CARPLAY_CONTROLS,
    WHEEL_KEYS,
    CALL_KEYS,
    CONNECTION_SETUP,
    DIAGNOSTICS,
    AUTOMATIC_CONNECTION,
    USB_CONNECTION,
    DISPLAY_AND_PERFORMANCE,
    EXPERIMENTAL_DISPLAY,
    ADVANCED_MEDIA,
    CAR_BUTTON,
    AUDIO_ROUTING,
    NAVIGATION_WHEEL,
    LOCATION,
    CLUSTER_MAP,
    PERMISSIONS_AND_HELP,
    LANGUAGE,
}

internal object SettingsInformationArchitecture {
    val sectionsByCategory: Map<SettingsCategory, Set<SettingsSection>> = mapOf(
        SettingsCategory.OVERVIEW to emptySet(),
        SettingsCategory.LANGUAGE to setOf(SettingsSection.LANGUAGE),
        SettingsCategory.ABOUT to emptySet(),
        SettingsCategory.CONNECTION to setOf(
            SettingsSection.CONNECTION_SETUP,
            SettingsSection.AUTOMATIC_CONNECTION,
            SettingsSection.USB_CONNECTION,
            SettingsSection.PERMISSIONS_AND_HELP,
        ),
        SettingsCategory.DISPLAY to setOf(SettingsSection.DISPLAY_AND_PERFORMANCE),
        SettingsCategory.AUDIO to setOf(SettingsSection.AUDIO_ROUTING),
        SettingsCategory.NAVIGATION to setOf(
            SettingsSection.LOCATION,
        ),
        // v113: 仪表的全部设置都归到「仪表」菜单。
        SettingsCategory.CLUSTER to setOf(
            SettingsSection.CLUSTER_MAP,
        ),
        // v113: 方控的全部设置都归到「方控」菜单。
        SettingsCategory.WHEEL to setOf(
            SettingsSection.WHEEL_KEYS,
            SettingsSection.CALL_KEYS,
            SettingsSection.NAVIGATION_WHEEL,
        ),
        SettingsCategory.VEHICLE to setOf(
            SettingsSection.CARPLAY_CONTROLS,
            SettingsSection.CAR_BUTTON,
        ),
        SettingsCategory.DIAGNOSTICS to setOf(SettingsSection.DIAGNOSTICS),
        // v113: NAVIGATION_WHEEL 已挪到「方控」分类（原来在「高级」里）。
        SettingsCategory.ADVANCED to setOf(
            SettingsSection.EXPERIMENTAL_DISPLAY,
            SettingsSection.ADVANCED_MEDIA,
        ),
    )
}

/** DiAuto's visual language, with a connection flow for an independent CarPlay receiver. */
class DiPlayActivity : ComponentActivity(), AppAppearanceOwner {
    private val handler = Handler(Looper.getMainLooper())
    private var appNight = true
    override val currentAppNight: Boolean get() = appNight
    private var palette = DiPlayPalette.DARK
    private var appearanceObserverRemoval: (() -> Unit)? = null
    private var appearanceUpdatesResumed = false
    private var appearanceRenderPending = false
    private var appearanceButtonFocusPending = false
    private var windowLearning: WindowKeyLearning? = null
    /** v113: 正在等用户按下一个键，用来绑接听键或挂断键。 */
    private var callKeyLearning: CallKeyTarget? = null
    private val endCallKeyLearning = Runnable { cancelCallKeyLearning() }
    private val windowLearningPresses = WheelKeyPresses()
    private val endWindowLearning = Runnable { cancelKeyLearning() }
    private var page = "home"
    internal var ambientSupportCheck: (Context) -> java.util.concurrent.CompletableFuture<Boolean> =
        { java.util.concurrent.CompletableFuture.completedFuture(false) }
    private var settingsCategory = SettingsCategory.OVERVIEW
    private var connectionSettingsReturnCategory: SettingsCategory? = null
    private var setupStep = SetupGuide.STEP_CAR
    private var setupFromSettings = false
    private var settingsSectionFilter: Set<SettingsSection>? = null
    private var clusterSafeAreaDialog: Dialog? = null

    /**
     * 上一次判定出的昼夜。
     *
     * 车机把 `uiMode` 报成 `UI_MODE_NIGHT_UNDEFINED` 时用它兜住,
     * 否则卡片预览会在不明状态下来回跳。(BYD 版从 DiLink 拿昼夜,
     * B561 走通用 uiMode + 这份记忆。)
     */
    private var lastKnownNight = false
    private var clusterContentRequestVersion = 0L
    private var pendingCarHotspotSetup = false
    private var hotspotJoinControls: HotspotJoinControls? = null
    private var setupError: String? = null
    private var status: TextView? = null
    private var connectButton: Button? = null
    private var disconnectButton: Button? = null
    private var lastRunning: Boolean? = null
    private var pendingWireless = false
    private var initialLaunch = true
    private var notificationTransport = true
    private var exportInProgress = false
    private var usbPermissionOperation: UsbPermissionSetup.Operation? = null
    private var usbPermissionDialog: AlertDialog? = null
    internal var usbPermissionOperationFactory: (Context) -> UsbPermissionSetup.Operation = {
        UsbPermissionSetup.Operation(it.applicationContext)
    }
    internal var selectedMtkDecoderName: (String) -> String? = MtkDecoderTuning::selectedDecoderName
    private var navigationStreamType = 14
    private var testToneTrack: AudioTrack? = null
    private var toneStop: Runnable? = null
    private var exportButton: Button? = null
    private var rootScroll: ScrollView? = null
    private var settingsRailScroll: ScrollView? = null
    private var reconnectBar: View? = null
    private var readinessCard: LinearLayout? = null
    private var searchIndexSink: MutableList<String>? = null
    private var settingsSearchBox: EditText? = null
    private var settingsSearchPopup: PopupWindow? = null
    private var settingsSearchIndex: List<SettingsSearchResult>? = null
    private var settingsSearchQuery = ""
    private var settingsSearchExpanded = false
    private var settingsSearchIndexing = false
    private var settingsSearchRestoreFocus = false
    private var renderedReadiness: SettingsReadiness? = null
    private var renderedPage: String? = null
    private var renderedSettingsCategory: SettingsCategory? = null
    private var pendingScrollY: Int? = null
    private var pendingRailScrollY: Int? = null
    private var pendingRailFocus = false
    private var adbStatus: TextView? = null
    // v100 B561: 车辆探测 / ADB 授权状态机随 BYD 集成删除。以下常量替代，
    // 保留它们是为了让「重新连接」类交互逻辑无需再动刀。
    private var bydVehicleAdvancedExpanded = false
    private var adbCheckInProgress = false
    private var vehicleProbeAuthorizationInProgress = false
    private var vehicleProbeInProgress = false
    private var automaticVehicleValidationInProgress = false
    private var automaticVehicleValidationPending = false
    private var adbCheckGeneration = 0
    private val automaticVehicleValidation = Runnable { }
    private var availableUpdate: UpdateRelease? = null
    private var updateSavedPath: String? = null
    private var updateSavedUri: Uri? = null
    /** Runs the check of the public update copy; tests run it inline. */
    internal var updateInstallWorker: (Runnable) -> Unit = { Thread(it, "diplay-update-install").start() }
    @Volatile private var updateStage = UpdateStage.IDLE
    @Volatile private var backupServer: SettingsBackupServer? = null
    @Volatile private var backupEndpoint: SettingsBackupEndpoint? = null
    /** The Settings category that opened the phone page, and the one to go back to. */
    private var backupOpenedFrom = SettingsCategory.ABOUT
    @Volatile private var backupSummary: String? = null
    @Volatile private var updateGeneration = 0
    @Volatile private var updateProgress: Int? = null
    private var updateRelease: UpdateRelease? = null
    private var updateFile: File? = null
    private var updateMessage: String? = null
    private var carButtonCard: LinearLayout? = null
    private var bydAdbControls: LinearLayout? = null
    private var adbSwitchChangePending = false
    private var pausedForAdbSwitchChange = false
    private var updatingAdbSwitches = false
    private val adbSwitches = mutableMapOf<Int, Pair<Switch, () -> Boolean>>()
    private var hotspotStartupResult: CarHotspotTethering.Result? = null
    @Volatile private var startupHotspotCancelled = false
    @Volatile private var vehicleProbeGeneration = 0
    @Volatile private var vehicleValidationGeneration = 0
    private val vehicleOperationLock = Any()
    private var automaticVehicleValidationStarted = false
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        connect(notificationTransport)
    }
    private val tick = object : Runnable {
        override fun run() { refreshStatus(); handler.postDelayed(this, 1000) }
    }
    private val appearancePoll = object : Runnable {
        override fun run() {
            checkForAppearanceChange()
            if (appearanceUpdatesResumed) handler.postDelayed(this, APPEARANCE_POLL_MILLIS)
        }
    }
    private val bluetoothPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) choosePhone() else permissionHelp(getString(R.string.nearby_devices), getString(R.string.allow_nearby_devices_so_diplay_can_connect_to_your_paired))
    }
    private val bluetoothAutoConnectPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            if (DiPlayPreferences.phoneAddress(this) == null) choosePhone()
        } else {
            DiPlayPreferences.saveConnectOnPhoneBluetooth(this, false)
            render()
            permissionHelp(getString(R.string.nearby_devices), getString(R.string.allow_nearby_devices_so_diplay_can_connect_to_your_paired))
        }
    }
    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasPreciseLocation()) {
            applyLocationReporting(true)
        } else {
            render()
            permissionHelp(getString(R.string.location), getString(R.string.allow_precise_location_for_diplay_in_the_head_unit_s_app_p))
        }
    }
    private val iconPicker = registerForActivityResult(ActivityResultContracts.GetContent(), ::cropIcon)
    private val iconDocumentPicker = registerForActivityResult(ActivityResultContracts.OpenDocument(), ::cropIcon)
    private fun cropIcon(uri: Uri?) {
        if (uri != null) iconCrop.launch(Intent(this, ImageCropActivity::class.java).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
    private val iconCrop = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        refreshCarButton()
        carButtonSaved()
    }
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) exportDiagnostics(uri)
    }
    // Android 9: storage access puts the report in the visible Downloads/DiPlay folder.
    // A denial still saves the report, only in DiPlay's own storage.
    private val reportStoragePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        exportDiagnostics()
    }

    private var languagePreferenceAtCreate = AppLocale.SYSTEM

    private var interfaceOverride: Configuration? = null
    private var interfaceSystemDensityDpi = 0
    private var interfaceRecreateRequested = false

    private fun enforceInterfaceSize(): Boolean {
        val language = AppLocale.enforce(this)
        if (language) android.util.Log.i("DiPlayUi", "app language re-applied")
        val override = interfaceOverride ?: return language
        val found = resources.displayMetrics.densityDpi
        if (!InterfaceSize.enforce(resources, override)) return language
        android.util.Log.i("DiPlayUi", "interface size re-applied: $found -> ${override.densityDpi} dpi")
        return true
    }

    override fun attachBaseContext(newBase: Context) {
        val base = AppLocale.wrap(newBase)
        super.attachBaseContext(base)
        interfaceSystemDensityDpi = base.resources.configuration.densityDpi
        interfaceOverride = InterfaceSize.attach(this, base)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Back on the home page finishes this activity while the session runs on, so the icon lands here.
        if (savedInstanceState == null && isLauncherIntent(intent) && CarPlayBackgroundSession.hasSession() &&
            AirPlayPersistence.loadLauncherReturnsToCarPlay(this)) {
            openProjection(); finish(); return
        }
        scheduleBackgroundUpdateChecks()
        availableUpdate = UpdateAvailability.available(this, version())
        availableUpdate?.let {
            updateRelease = it
            updateStage = UpdateStage.AVAILABLE
        }
        enforceInterfaceSize()
        refreshAppearance()
        rememberLaunchAppearance()
        languagePreferenceAtCreate = AppLocale.preference(this)
        PhoneAudioRoute.attach(applicationContext)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        applyWindowAppearance()
        setupError = runCatching { DiPlayBootstrap.ensure(this, AirPlayPersistence.loadMfiTarget(this)) }.exceptionOrNull()?.let {
            android.util.Log.e("DiPlaySetup", "CarPlay authentication could not be loaded", it)
            getString(R.string.setup_error_auth)
        }
        pendingCarHotspotSetup = savedInstanceState?.getBoolean("pending_car_hotspot") ?: false
        bydVehicleAdvancedExpanded = savedInstanceState?.getBoolean("byd_vehicle_advanced") ?: false
        settingsCategory = savedInstanceState?.getString("settings_category")
            ?.let { runCatching { SettingsCategory.valueOf(it) }.getOrNull() }
            ?: SettingsCategory.OVERVIEW
        connectionSettingsReturnCategory = savedInstanceState?.getString("connection_settings_return_category")
            ?.let { runCatching { SettingsCategory.valueOf(it) }.getOrNull() }
        setupStep = savedInstanceState?.getInt("setup_step") ?: SetupGuide.STEP_CAR
        setupFromSettings = savedInstanceState?.getBoolean("setup_from_settings") ?: false
        page = savedInstanceState?.getString("page") ?: intent.getStringExtra("page")
            ?: if (setupError == null && SetupGuide.shouldOpenOnLaunch(SetupGuide.seen(this),
                    DiPlayPreferences.phoneAddress(this) != null)) "setup" else "home"
        adoptAboutAsSettingsCategory()
        render()
        handleWirelessRecovery()
        if (consumeBluetoothAutoConnectIntent(intent)) {
            if (initialLaunch) {
                initialLaunch = false
                startCarHotspotOnLaunch()
            }
            handler.post { connect(true) }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (page != "home") navigateBack()
                // Back on the home page only hides the app: a car app must return exactly where
                // it left off instead of reopening from scratch.
                else moveTaskToBack(true)
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        connectionSettingsReturnCategory = null
        // CarPlay runs in its own task, so the launcher icon resumes this one. Settings opened from
        // CarPlay carry a "page" extra, which isLauncherIntent rejects.
        if (isLauncherIntent(intent) && CarPlayBackgroundSession.hasSession() &&
            AirPlayPersistence.loadLauncherReturnsToCarPlay(this)) {
            page = "home"; render(); openProjection(); return
        }
        page = intent.getStringExtra("page") ?: "home"
        adoptAboutAsSettingsCategory()
        render()
        automaticVehicleValidationStarted = false
        handleWirelessRecovery()
        if (consumeBluetoothAutoConnectIntent(intent)) {
            if (initialLaunch) {
                initialLaunch = false
                startCarHotspotOnLaunch()
            }
            handler.post { connect(true) }
        }
    }
    private fun consumeBluetoothAutoConnectIntent(intent: Intent): Boolean {
        val requested = intent.getBooleanExtra(PhoneBluetoothReceiver.EXTRA_AUTO_CONNECT, false)
        intent.removeExtra(PhoneBluetoothReceiver.EXTRA_AUTO_CONNECT)
        return requested && DiPlayPreferences.connectOnPhoneBluetooth(this) &&
            DiPlayPreferences.phoneAddress(this) != null && setupError == null &&
            !CarPlayBackgroundSession.hasSession()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("page", page)
        outState.putString("settings_category", settingsCategory.name)
        connectionSettingsReturnCategory?.let { outState.putString("connection_settings_return_category", it.name) }
        outState.putBoolean("pending_car_hotspot", pendingCarHotspotSetup)
        outState.putBoolean("byd_vehicle_advanced", bydVehicleAdvancedExpanded)
        outState.putInt("setup_step", setupStep)
        outState.putBoolean("setup_from_settings", setupFromSettings)
        super.onSaveInstanceState(outState)
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (updateInterfaceSize(newConfig)) return
        refreshAppearance()
        render()
    }

    /** True while a density change is recreating this activity. */
    private fun updateInterfaceSize(configuration: Configuration): Boolean {
        if (interfaceRecreateRequested) return true
        // The activity handles real system density changes too. Its callback contains our installed
        // density, while application resources retain the current unscaled system density.
        applicationContext.resources.configuration.densityDpi.takeIf { it > 0 }?.let {
            interfaceSystemDensityDpi = it
        }
        val next = InterfaceSize.configurationChange(configuration, interfaceSystemDensityDpi,
            InterfaceSize.preference(this))
        if (InterfaceSize.needsRecreate(interfaceOverride, next)) {
            interfaceRecreateRequested = true
            recreate()
            return true
        }
        interfaceOverride = next
        return false
    }
    private fun openOverlayPermission() {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        if (runCatching { startActivity(intent) }.isFailure) {
            android.widget.Toast.makeText(this, R.string.center_map_no_permission_screen, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    override fun onStart() {
        super.onStart()
        // Results from the image picker and crop screens arrive before onResume.
        enforceInterfaceSize()
        CenterMapOverlay.onDiPlayScreenShown()
    }

    override fun onStop() {
        cancelUsbPermissionSetup()
        clusterSafeAreaDialog?.dismiss()
        startupHotspotCancelled = true
        super.onStop()
        if (!isFinishing && !isChangingConfigurations) CenterMapOverlay.scheduleShow()
    }

    override fun onResume() {
        super.onResume()
        WheelKeyService.restoreIfNeeded(this)
        applyFullscreenMode()
        // Returning from another activity can bring the head unit's own density back.
        if (enforceInterfaceSize()) render()
        if (Build.VERSION.SDK_INT < 33 && AppLocale.preference(this) != languagePreferenceAtCreate) {
            recreate()
            return
        }
        handler.removeCallbacks(tick); handler.post(tick)
        // Back from the car settings: refresh the car hotspot reminder on the home page.
        if (!initialLaunch && !adbSwitchChangePending && !pausedForAdbSwitchChange &&
            (page == "home" || page == "settings" || page == "connection" || page == "setup")) render()
        startAppearanceUpdates()
        pausedForAdbSwitchChange = false
        if (initialLaunch) {
            initialLaunch = false
            startCarHotspotOnLaunch()
            if (consumeBluetoothAutoConnectIntent(intent)) {
                handler.post { connect(true) }
            } else if (setupError == null && !CarPlayBackgroundSession.hasSession() &&
                DiPlayPreferences.autoConnect(this) && intent.getStringExtra("page") == null && page != "setup") {
                handler.post { connect(DiPlayPreferences.autoConnectWireless(this)) }
            }
        }
    }
    override fun onPause() {
        appearanceUpdatesResumed = false
        appearanceObserverRemoval?.invoke()
        appearanceObserverRemoval = null
        handler.removeCallbacks(appearancePoll)
        cancelKeyLearning()
        pausedForAdbSwitchChange = adbSwitchChangePending
        handler.removeCallbacks(tick)
        super.onPause()
    }

    override fun onDestroy() {
        settingsSearchPopup?.dismiss()
        appearanceObserverRemoval?.invoke()
        appearanceObserverRemoval = null
        handler.removeCallbacks(appearancePoll)
        hotspotJoinControls?.close()
        cancelUsbPermissionSetup()
        cancelKeyLearning()
        stopBackupServer()
        handler.removeCallbacks(automaticVehicleValidation)
        adbCheckGeneration++
        synchronized(vehicleOperationLock) {
            vehicleProbeGeneration++
            vehicleValidationGeneration++
        }
        adbCheckInProgress = false
        vehicleProbeAuthorizationInProgress = false
        vehicleProbeInProgress = false
        automaticVehicleValidationInProgress = false
        super.onDestroy()
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        applyFullscreenMode()
        if (updateInterfaceSize(newConfig)) return
        render()
    }

    private val isCompactLayout: Boolean
        // Window size only: a multi-window task can fill the whole screen, and in multi-window the
        // configuration already reports the window's own size.
        get() = resources.configuration.screenWidthDp < 550 ||
            resources.configuration.screenHeightDp < 450

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyFullscreenMode()
        // Some head units put their own density back without any other callback.
        if (hasFocus && enforceInterfaceSize()) render()
    }

    private fun applyFullscreenMode() {
        val multiWindow = Build.VERSION.SDK_INT >= 24 && isInMultiWindowMode
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = palette.systemBarIconsAreDark
            isAppearanceLightNavigationBars = palette.systemBarIconsAreDark
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (AirPlayPersistence.loadHideTopBar(this@DiPlayActivity) && !multiWindow) {
                hide(WindowInsetsCompat.Type.statusBars())
            } else {
                show(WindowInsetsCompat.Type.statusBars())
            }
            if (AirPlayPersistence.loadHideBottomBar(this@DiPlayActivity) && !multiWindow) {
                hide(WindowInsetsCompat.Type.navigationBars())
            } else {
                show(WindowInsetsCompat.Type.navigationBars())
            }
        }
        ViewCompat.requestApplyInsets(window.decorView)
    }

    private val isNaturallyExpandedSettingsLayout: Boolean
        get() = resources.configuration.let {
            SettingsLayoutPolicy.isExpanded(it.screenWidthDp, it.screenHeightDp, it.fontScale)
        }

    private val isExpandedSettingsLayout: Boolean
        get() = SettingsLayoutPreferences.isActive(this) || isNaturallyExpandedSettingsLayout

    private data class FocusSnapshot(
        val tag: Any?,
        val contentDescription: String?,
        val label: String?,
    )

    private fun focusLabel(view: View): String? = when (view) {
        is TextView -> view.text?.toString()
        is ViewGroup -> descendants(view).filterIsInstance<TextView>()
            .firstOrNull { it.text.isNotEmpty() }?.text?.toString()
        else -> null
    }

    private fun render() = render(appearanceOnly = false)

    private fun render(appearanceOnly: Boolean, preferredFocusTag: Any? = null) {
        enforceInterfaceSize()
        // ★ v103: 旧的仪表卡预览 View 随整棵视图树一起被丢弃 ——
        //   必须先把字段置 null，否则 ensureClusterPreview 会把**已经挂在旧树上**
        //   的 View 再挂到新树上，Android 抛「The specified child already has a parent」。
        //   （v102 移植时漏了这行，模拟器上点选屏 → render() 即崩。）
        clusterPreview = null
        refreshAppearance()
        applyWindowAppearance()
        // A pending assignment belongs to the widgets being replaced, never to another page.
        if (!appearanceOnly) cancelKeyLearning()
        // The backup server lives only while its page is shown, however the driver left it.
        if (page != "backup" && backupServer != null) stopBackupServer()
        val focusSnapshot = if (appearanceOnly) {
            currentFocus?.let { focused ->
                FocusSnapshot(
                    tag = preferredFocusTag ?: focused.tag,
                    contentDescription = focused.contentDescription?.toString(),
                    label = focusLabel(focused),
                )
            } ?: preferredFocusTag?.let { FocusSnapshot(it, null, null) }
        } else null
        // A restore still waiting for layout keeps its target: the old page was never laid out.
        val sameDestination = renderedPage == page &&
            (page != "settings" || renderedSettingsCategory == settingsCategory)
        val previousScrollY = (pendingScrollY ?: rootScroll?.scrollY)?.takeIf { sameDestination }
        // The rail is one destination even when its selected category changes.
        val keepRailPosition = renderedPage == "settings" && page == "settings"
        val previousRailScrollY = (pendingRailScrollY ?: settingsRailScroll?.scrollY)
            ?.takeIf { keepRailPosition }
        val restoreRailFocus = keepRailPosition &&
            (pendingRailFocus || settingsRailScroll?.hasFocus() == true)
        settingsRailScroll = null
        // The results hang off the old header; the new header reopens them if the search continues.
        settingsSearchPopup?.dismiss()
        settingsSearchPopup = null
        settingsSearchRestoreFocus = settingsSearchRestoreFocus ||
            (settingsSearchExpanded && settingsSearchBox?.hasFocus() == true)
        settingsSearchBox = null
        if (page != "settings") clearSettingsSearchState()
        status = null; connectButton = null; disconnectButton = null; lastRunning = null; carButtonCard = null
        reconnectBar = null
        readinessCard = null
        exportButton = null
        bydAdbControls = null
        adbSwitches.clear()
        adbStatus = null
        val compact = isCompactLayout && when (page) {
            "settings" -> !SettingsLayoutPreferences.isActive(this)
            else -> true
        }
        val scroll = ScrollView(this).apply { setBackgroundColor(BG); isFillViewport = true; clipToPadding = false }
        rootScroll = scroll
        val content = column()
        scroll.addView(content)
        val header = if (page == "settings") settingsHeader(compact) else standardHeader(compact)
        val root: View = if (page == "settings" && isExpandedSettingsLayout) {
            content.setPadding(0, 0, 0, dp(32))
            settingsCategoryContent(content)
            expandedSettingsShell(header, scroll)
        } else {
            if (compact) content.setPadding(dp(12), dp(10), dp(12), dp(12))
            else content.setPadding(dp(32), dp(24), dp(32), dp(32))
            content.addView(header)
            content.addView(space(when {
                compact && (page == "settings" || page == "home") -> SETTINGS_BLOCK_GAP_DP
                compact -> 8
                else -> 24
            }))
            if (page == "settings") content.addView(reconnectBar(), LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(SETTINGS_BLOCK_GAP_DP)
            })
            when (page) {
                "connection" -> connectionSetup(content)
                "setup" -> setupGuide(content)
                "settings" -> settingsCategoryContent(content)
                "backup" -> backup(content)
                else -> home(content)
            }
            scroll
        }
        // Keep the background full screen, but all controls inside the camera and
        // visible system/keyboard bounds. Insets are physical pixels, not scaled dp.
        val baseLeft = root.paddingLeft
        val baseTop = root.paddingTop
        val baseRight = root.paddingRight
        val baseBottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            view.setPadding(baseLeft + safe.left, baseTop + safe.top,
                baseRight + safe.right, baseBottom + safe.bottom)
            insets
        }
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
        renderedPage = page
        renderedSettingsCategory = settingsCategory.takeIf { page == "settings" }
        refreshStatus()
        focusSnapshot?.let { snapshot ->
            root.doOnLayout {
                descendants(root).firstOrNull { candidate ->
                    candidate.isFocusable && when {
                        snapshot.tag != null -> candidate.tag == snapshot.tag
                        snapshot.contentDescription != null ->
                            candidate.contentDescription?.toString() == snapshot.contentDescription
                        snapshot.label != null -> focusLabel(candidate) == snapshot.label
                        else -> false
                    }
                }?.requestFocus()
            }
        }
        pendingScrollY = previousScrollY
        // A stopped window still dispatches pre-draw but skips layout, so wait for a real layout;
        // the listener stays on this view and goes away with it.
        previousScrollY?.let { y ->
            scroll.doOnLayout {
                if (rootScroll === scroll) {
                    scroll.scrollTo(0, y)
                    pendingScrollY = null
                }
            }
        }
        val rail = settingsRailScroll
        pendingRailScrollY = previousRailScrollY.takeIf { rail != null }
        pendingRailFocus = restoreRailFocus && rail != null
        rail?.doOnLayout {
            if (settingsRailScroll !== rail) return@doOnLayout
            previousRailScrollY?.let { rail.scrollTo(0, it) }
            val destinations = (rail.getChildAt(0) as ViewGroup).getChildAt(0) as ViewGroup
            val selected = (0 until destinations.childCount)
                .map(destinations::getChildAt).firstOrNull { it.isSelected }
            if (selected != null) {
                if (restoreRailFocus) selected.requestFocus()
                selected.requestRectangleOnScreen(android.graphics.Rect(0, 0, selected.width, selected.height), true)
            }
            pendingRailScrollY = null
            pendingRailFocus = false
        }
    }

    private fun standardHeader(compact: Boolean): LinearLayout = row().apply {
        gravity = Gravity.CENTER_VERTICAL
        val logoSize = dp(if (compact) 30 else 36)
        addView(ImageView(this@DiPlayActivity).apply {
            setImageResource(R.drawable.ic_carplay)
            contentDescription = getString(R.string.carplay)
        }, LinearLayout.LayoutParams(logoSize, logoSize))
        addView(label(getString(R.string.diplay), if (compact) 20 else 26, TEXT, true,
            centreGlyphs = resources.configuration.locales[0].language != "zh").apply {
            setPadding(if (compact) dp(8) else dp(12), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, if (compact) dp(44) else dp(52), 1f))
        addView(appearanceButton(), LinearLayout.LayoutParams(dp(if (compact) 44 else 52), dp(if (compact) 44 else 52)).apply {
            marginEnd = dp(if (compact) 8 else 12)
        })
        addView(headerButton(if (page == "home") getString(R.string.car_home) else getString(R.string.back),
            if (page == "home") R.drawable.ic_dp_car else R.drawable.ic_dp_back, compact) {
            if (page == "home") startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
            else navigateBack()
        }, LinearLayout.LayoutParams(-2, if (compact) dp(44) else dp(52)))
    }

    private fun navigateBack() {
        // Back closes an open search first, like a system search bar.
        if (page == "settings" && settingsSearchExpanded) {
            closeSettingsSearch()
            return
        }
        val returnCategory = connectionSettingsReturnCategory
        when {
            page == "backup" -> {
                stopBackupServer()
                page = "settings"
                settingsCategory = backupOpenedFrom
            }
            page == "setup" && setupStep > SetupGuide.STEP_CAR -> setupStep--
            page == "setup" -> {
                closeSetupGuide()
                return
            }
            page == "connection" && returnCategory != null -> {
                page = "settings"
                settingsCategory = returnCategory
                connectionSettingsReturnCategory = null
            }
            page == "settings" && !isExpandedSettingsLayout && settingsCategory != SettingsCategory.OVERVIEW -> {
                settingsCategory = SettingsCategory.OVERVIEW
            }
            else -> {
                page = "home"
                connectionSettingsReturnCategory = null
            }
        }
        render()
    }

    private fun openConnectionSetupFromSettings() {
        connectionSettingsReturnCategory = settingsCategory
        page = "connection"
        render()
    }

    private fun settingsHeader(compact: Boolean): LinearLayout = row().apply {
        val controlHeight = dp(if (compact) 44 else 52)
        val iconOnlySearch = compact || resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        gravity = Gravity.CENTER_VERTICAL
        addView(headerButton(getString(R.string.back), R.drawable.ic_dp_back, compact, ::navigateBack),
            LinearLayout.LayoutParams(-2, if (compact) dp(44) else dp(52)))
        val settingsTitle = label(getString(R.string.settings), if (compact) 20 else 26, TEXT, true,
            centreGlyphs = true).apply {
            setPadding(dp(12), 0, dp(12), 0)
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        // Reserve the action buttons first; only the title may shrink in a narrow header.
        addView(settingsTitle, LinearLayout.LayoutParams(
            if (settingsSearchExpanded) -2 else 0, controlHeight,
            if (settingsSearchExpanded) 0f else 1f,
        ))
        val searchGap = dp(if (compact) SETTINGS_HEADER_COMPACT_GAP_DP else SETTINGS_HEADER_GAP_DP)
        if (settingsSearchExpanded) {
            val searchField = settingsSearchField(compact)
            addView(searchField, LinearLayout.LayoutParams(0, controlHeight, 1f).apply {
                marginEnd = searchGap
            })
            searchField.doOnLayout {
                // Give a squeezed editor the title's space, using the current locale and font scale.
                val hintWidth = searchField.paint.measureText(searchField.hint.toString())
                val requiredWidth = hintWidth + searchField.compoundPaddingLeft + searchField.compoundPaddingRight
                if (searchField.width < requiredWidth) {
                    settingsTitle.visibility = View.GONE
                    searchField.layoutParams = (searchField.layoutParams as LinearLayout.LayoutParams).apply {
                        marginStart = searchGap
                    }
                }
            }
        } else {
            val openSearch = {
                // Indexing rebuilds the settings widgets. Do it before focusing an editor,
                // so the first keystroke never replaces the IME's input connection.
                settingsSearchIndex = buildSettingsSearchIndex()
                settingsSearchExpanded = true
                settingsSearchRestoreFocus = true
                render()
            }
            val searchButton = if (iconOnlySearch) {
                iconButton(R.drawable.ic_dp_search, getString(R.string.settings_search), openSearch).apply {
                    imageTintList = ColorStateList.valueOf(TEXT)
                }
            } else {
                headerButton(getString(R.string.settings_search_hint), R.drawable.ic_dp_search, compact, openSearch)
            }.apply {
                tag = SETTINGS_SEARCH_BUTTON_TAG
                contentDescription = getString(R.string.settings_search)
            }
            addView(searchButton, LinearLayout.LayoutParams(if (iconOnlySearch) controlHeight else -2, controlHeight).apply {
                marginEnd = searchGap
            })
        }
        addView(appearanceButton(), LinearLayout.LayoutParams(controlHeight, controlHeight))
    }

    private fun appearanceButton(): ImageButton = iconButton(
        icon = if (appNight) R.drawable.ic_dp_night else R.drawable.ic_dp_day,
        description = getString(if (appNight) R.string.settings_switch_to_light_appearance
            else R.string.settings_switch_to_dark_appearance),
    ) {
        AirPlayPersistence.saveAppAppearance(
            this,
            if (appNight) AppAppearance.LIGHT else AppAppearance.DARK,
        )
        rememberLaunchAppearance()
        requestAppearanceRender(focusAppearanceButton = true)
    }.apply {
        tag = APPEARANCE_BUTTON_TAG
    }

    private fun home(content: LinearLayout) {
        val compact = isCompactLayout
        fun homeButton(title: String, primary: Boolean, click: () -> Unit) = button(title, primary, click)
        val wide = resources.configuration.screenWidthDp >= 850
        val body = column()
        val left = column()
        if (!compact) {
            left.addView(label(getString(R.string.your_phone_your_drive), 12, ACCENT, true).apply { letterSpacing = .16f })
            left.addView(label(getString(R.string.a_familiar_drive), if (wide) 42 else 36, TEXT, true).apply { setPadding(0, dp(12), 0, dp(10)) })
            left.addView(label(getString(R.string.your_maps_music_and_conversations_carplay_right_here_on_yo), 19, MUTED))
        }
        val card = card()
        card.addView(label(getString(R.string.wireless_carplay), 12, ACCENT, true).apply { letterSpacing = .12f })
        status = label(getString(R.string.ready_when_you_are), 24, TEXT, true).apply { setPadding(0, dp(10), 0, dp(16)) }
        card.addView(status)
        connectButton = homeButton(getString(R.string.connect_phone), true) {
            if (CarPlayBackgroundSession.hasSession()) openProjection()
            else connect(true)
        }
        card.addView(connectButton, matchButton(height = if (compact) 54 else 68))
        val connectionHint = when (AirPlayPersistence.loadWirelessHotspotMode(this)) {
            WirelessHotspotMode.EXISTING_WIFI -> getString(R.string.existing_wifi_hint)
            WirelessHotspotMode.MANUAL -> getString(R.string.hotspot_hint_manual)
            WirelessHotspotMode.LOCAL_ONLY_HOTSPOT -> getString(R.string.hotspot_hint_local)
            else -> getString(R.string.hotspot_hint_p2p)
        }
        if (!compact) card.addView(label(connectionHint, 15, MUTED).apply { setPadding(0, dp(14), 0, 0) })
        val startupProblem = hotspotStartupResult?.takeIf {
            it != CarHotspotTethering.Result.READY && it != CarHotspotTethering.Result.CANCELLED &&
                CarHotspotSettings.shouldEnable(this, true, AirPlayPersistence.loadWirelessHotspotMode(this)) &&
                com.shilapi.xcertplay.network.CarHotspotStatus.isEnabled(this) != true
        }
        if (startupProblem != null) {
            card.addView(label(hotspotResultText(startupProblem), 15, WARNING))
            if (!compact) card.addView(homeButton(getString(R.string.open_car_hotspot_settings), false) { openCarWifiSettings() }, matchButton(CONTROL_GAP_DP, 56))
        } else if (carHotspotOff()) {
            card.addView(label(getString(R.string.msg_car_hotspot_off, AirPlayPersistence.loadManualHotspotSsid(this)), 15, WARNING).apply { setPadding(0, dp(14), 0, 0) })
            if (!compact) card.addView(homeButton(getString(R.string.open_car_hotspot_settings), false) { openCarWifiSettings() }, matchButton(CONTROL_GAP_DP, 56))
        }
        card.addView(homeButton(getString(R.string.choose_iphone), false) { choosePhone() }, matchButton(if (compact) CONTROL_GAP_DP else 16, if (compact) 46 else 56))
        disconnectButton = homeButton(getString(R.string.disconnect), false) {
            disconnectButton?.isEnabled = false
            CarPlayBackgroundSession.stop { runOnUiThread { refreshStatus() } }
        }.apply { visibility = View.GONE }
        card.addView(disconnectButton, matchButton(if (compact) CONTROL_GAP_DP else 16, if (compact) 46 else 56))
        val right = column().apply { gravity = Gravity.CENTER_HORIZONTAL }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.ic_carplay)
            contentDescription = getString(R.string.carplay_icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val branding = column().apply {
            gravity = Gravity.CENTER
            addView(logo, LinearLayout.LayoutParams(dp(96), dp(96)))
        }
        right.addView(homeButton(getString(R.string.connect_with_usb), false) { connect(false) }, matchButton(height = if (compact) 54 else 68))
        if (!compact) right.addView(label(getString(R.string.plug_your_iphone_into_a_usb_data_port_allow_carplay_when_y), 14, MUTED).apply { gravity = Gravity.CENTER; setPadding(dp(8), dp(10), dp(8), dp(24)) })
        else right.addView(space(16))
        right.addView(homeButton(getString(R.string.settings), false) { page = "settings"; render() }, matchButton(height = if (compact) 54 else 68))
        if (!compact) right.addView(label(getString(R.string.make_diplay_feel_right_for_your_car), 14, MUTED).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(24)) })
        else right.addView(space(12))
        right.addView(label(getString(R.string.home_public_preview, version()), 12, MUTED).apply { letterSpacing = .08f })
        availableUpdate?.let { release ->
            val message = getString(R.string.update_available_home)
            val tint = { alpha: Int -> (WARNING and 0x00FFFFFF) or (alpha shl 24) }
            val chip = GradientDrawable().apply {
                setColor(tint(0x26))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), tint(0x80))
            }
            right.addView(label(message, 14, WARNING, true).apply {
                gravity = Gravity.CENTER
                minHeight = dp(60)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                isClickable = true
                isFocusable = true
                contentDescription = message
                background = RippleDrawable(
                    ColorStateList.valueOf(RIPPLE),
                    chip,
                    GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(16).toFloat() },
                )
                foreground = focusRing(radiusDp = 16)
                setOnClickListener { openAvailableUpdate(release) }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }
        if (compact && resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            val compactRight = column().apply {
                addView(space(8))
                addView(branding)
                addView(space(24))
                addView(right)
            }
            body.addView(row().apply {
                gravity = Gravity.TOP
                addView(card, LinearLayout.LayoutParams(0, -2, 1.6f))
                addView(space(16), LinearLayout.LayoutParams(dp(16), 1))
                addView(compactRight, LinearLayout.LayoutParams(0, -2, 1f))
            })
        } else if (!compact && wide) {
            // Both rows share column widths. The USB button starts at the wireless
            // card's top edge, independently of hero wrapping or font scaling.
            fun columns(first: View, second: View, stretchSecond: Boolean = false) = row().apply {
                gravity = Gravity.TOP
                addView(first, LinearLayout.LayoutParams(0, -2, 1.6f))
                addView(space(40), LinearLayout.LayoutParams(dp(40), 1))
                addView(second, LinearLayout.LayoutParams(0, if (stretchSecond) -1 else -2, 1f))
            }
            body.addView(columns(left, branding, true))
            body.addView(space(26))
            body.addView(columns(card, right))
        } else {
            if (!compact) {
                body.addView(left)
                body.addView(space(26))
            }
            body.addView(card)
            body.addView(space(26))
            body.addView(branding)
            body.addView(space(24))
            body.addView(right)
        }
        setupError?.let { body.addView(label(it, 16, WARNING).apply { setPadding(0, dp(16), 0, 0) }) }
        content.addView(body)
    }

    // Only the category scrolls, so the rail keeps showing where the driver is.
    private fun expandedSettingsShell(header: View, categoryScroll: ScrollView): LinearLayout = column().apply {
        setBackgroundColor(BG)
        setPadding(dp(32), dp(24), dp(32), 0)
        addView(header)
        addView(space(24))
        addView(reconnectBar(), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(SETTINGS_BLOCK_GAP_DP) })
        val split = row().apply { gravity = Gravity.TOP }
        // The rail scrolls on its own only when the window is too short for every destination.
        split.addView(ScrollView(this@DiPlayActivity).apply {
            addView(column().apply {
                setPadding(0, 0, 0, dp(12))
                addView(settingsRail())
            })
            settingsRailScroll = this
        },
            LinearLayout.LayoutParams(dp(SettingsLayoutPolicy.railWidthDp(resources.configuration.fontScale)), -2))
        split.addView(space(24), LinearLayout.LayoutParams(dp(24), 1))
        split.addView(categoryScroll, LinearLayout.LayoutParams(0, -1, 1f))
        addView(split, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun settingsRail(): LinearLayout = column().apply {
        background = rounded(SURFACE, BORDER)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        SettingsCategory.entries.forEach { category ->
            addView(settingsRailDestination(category), matchButton(0, 52).apply {
                bottomMargin = if (category == SettingsCategory.entries.last()) 0 else dp(8)
            })
        }
    }

    private fun settingsRailDestination(category: SettingsCategory): View {
        val selected = settingsCategory == category
        val title = settingsCategoryTitle(category)
        return row().apply {
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            isSelected = selected
            foreground = focusRing(12)
            contentDescription = getString(R.string.settings_open_category, title)
            background = android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(RIPPLE),
                GradientDrawable().apply {
                    setColor(if (selected) RAIL_SELECTED else Color.TRANSPARENT)
                    cornerRadius = dp(12).toFloat()
                    if (selected) setStroke(dp(1), RAIL_SELECTED_BORDER)
                },
                null,
            )
            setPadding(dp(8), 0, dp(12), 0)
            addView(View(this@DiPlayActivity).apply {
                background = GradientDrawable().apply {
                    setColor(if (selected) ACCENT else Color.TRANSPARENT)
                    cornerRadius = dp(2).toFloat()
                }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(4), dp(34)).apply { marginEnd = dp(10) })
            addView(ImageView(this@DiPlayActivity).apply {
                setImageResource(settingsCategoryIcon(category))
                imageTintList = ColorStateList.valueOf(if (selected) ACCENT else TEXT)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(12) })
            addView(label(title, 16, if (selected) ACCENT else TEXT, true,
                centreGlyphs = usesChineseGlyphAlignment(resources.configuration.locales[0].language, title)),
                LinearLayout.LayoutParams(0, -1, 1f))
            setOnClickListener {
                openSettingsCategory(category)
            }
        }
    }

    private fun settingsCategoryIcon(category: SettingsCategory): Int = when (category) {
        SettingsCategory.OVERVIEW -> R.drawable.ic_dp_overview
        SettingsCategory.CONNECTION -> R.drawable.ic_dp_connection
        SettingsCategory.DISPLAY -> R.drawable.ic_dp_display
        SettingsCategory.AUDIO -> R.drawable.ic_dp_audio
        SettingsCategory.NAVIGATION -> R.drawable.ic_dp_navigation
        SettingsCategory.CLUSTER -> R.drawable.ic_dp_dashboard
        SettingsCategory.WHEEL -> R.drawable.ic_dp_controls
        SettingsCategory.VEHICLE -> R.drawable.ic_dp_car
        SettingsCategory.DIAGNOSTICS -> R.drawable.ic_dp_diagnostics
        SettingsCategory.ADVANCED -> R.drawable.ic_dp_advanced
        SettingsCategory.LANGUAGE -> R.drawable.ic_dp_language
        SettingsCategory.ABOUT -> R.drawable.ic_dp_about
    }

    private fun settingsCategoryTitle(category: SettingsCategory): String = getString(when (category) {
        SettingsCategory.OVERVIEW -> R.string.settings_overview
        SettingsCategory.CONNECTION -> R.string.connection
        SettingsCategory.DISPLAY -> R.string.settings_display
        SettingsCategory.AUDIO -> R.string.audio
        SettingsCategory.NAVIGATION -> R.string.settings_navigation
        SettingsCategory.CLUSTER -> R.string.settings_cluster
        SettingsCategory.WHEEL -> R.string.settings_wheel
        SettingsCategory.VEHICLE -> R.string.settings_vehicle
        SettingsCategory.DIAGNOSTICS -> R.string.diagnostics
        SettingsCategory.ADVANCED -> R.string.settings_advanced
        SettingsCategory.LANGUAGE -> R.string.language_section_title
        SettingsCategory.ABOUT -> R.string.about
    })

    private fun settingsCategoryContent(content: LinearLayout) {
        when (settingsCategory) {
            SettingsCategory.OVERVIEW -> settingsOverview(content)
            SettingsCategory.CONNECTION -> connectionSettings(content)
            SettingsCategory.DISPLAY -> displaySettings(content)
            SettingsCategory.AUDIO -> audioSettings(content)
            SettingsCategory.NAVIGATION -> navigationSettings(content)
            SettingsCategory.CLUSTER -> clusterSettings(content)
            SettingsCategory.WHEEL -> wheelSettings(content)
            SettingsCategory.VEHICLE -> vehicleSettings(content)
            SettingsCategory.DIAGNOSTICS -> diagnosticsSettings(content)
            SettingsCategory.ADVANCED -> advancedSettings(content)
            SettingsCategory.LANGUAGE -> {
                settingsPageTitle(content, getString(R.string.language_section_title), getString(R.string.settings_language_summary))
                renderSections(content, SettingsInformationArchitecture.sectionsByCategory.getValue(SettingsCategory.LANGUAGE))
            }
            SettingsCategory.ABOUT -> about(content)
        }
    }

    private fun settingsOverview(content: LinearLayout) {
        settingsPageTitle(content, getString(R.string.settings_overview), getString(R.string.settings_overview_subtitle))
        val readiness = card().apply {
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        readinessCard = readiness
        renderedReadiness = null
        refreshReadiness()
        content.addView(readiness, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(SETTINGS_BLOCK_GAP_DP) })
        content.addView(button(getString(R.string.setup_guide), false) { openSetupGuide(fromSettings = true) },
            LinearLayout.LayoutParams(-1, dp(56)).apply { bottomMargin = dp(6) })
        content.addView(label(getString(R.string.setup_guide_description), 14, MUTED).apply {
            setPadding(0, 0, 0, dp(20))
        })

        val destinations = listOf(
            SettingsCategory.CONNECTION to R.string.settings_connection_summary,
            SettingsCategory.DISPLAY to R.string.settings_display_summary,
            SettingsCategory.AUDIO to R.string.settings_audio_summary,
            SettingsCategory.NAVIGATION to R.string.settings_navigation_summary,
            SettingsCategory.CLUSTER to R.string.settings_cluster_summary,
            SettingsCategory.WHEEL to R.string.settings_wheel_summary,
            SettingsCategory.VEHICLE to R.string.settings_vehicle_summary,
            SettingsCategory.LANGUAGE to R.string.settings_language_summary,
            SettingsCategory.ABOUT to R.string.about_diplay,
            SettingsCategory.DIAGNOSTICS to R.string.settings_diagnostics_summary,
            SettingsCategory.ADVANCED to R.string.settings_advanced_subtitle,
        )
        val categoryGap = dp(SETTINGS_BLOCK_GAP_DP)
        val twoColumns = isExpandedSettingsLayout && resources.configuration.let {
            SettingsLayoutPolicy.overviewHasTwoColumns(it.screenWidthDp, it.fontScale)
        }
        if (twoColumns) {
            val columns = row().apply { gravity = Gravity.TOP }
            val setup = column()
            setup.addView(settingsSectionHeading(R.string.settings_your_setup))
            destinations.forEach { item ->
                setup.addView(settingsSummaryCard(item.first, getString(item.second)),
                    LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = categoryGap })
            }
            columns.addView(setup, LinearLayout.LayoutParams(0, -2, 1f))
            columns.addView(space(14), LinearLayout.LayoutParams(dp(14), 1))
            val quick = column()
            quick.addView(settingsSectionHeading(R.string.settings_quick_settings))
            quick.addView(quickSettingsCard())
            columns.addView(quick, LinearLayout.LayoutParams(0, -2, 1f))
            content.addView(columns, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(SETTINGS_BLOCK_GAP_DP) })
        } else {
            content.addView(settingsSectionHeading(R.string.settings_your_setup))
            destinations.forEach { item ->
                content.addView(settingsSummaryCard(item.first, getString(item.second)),
                    LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = categoryGap })
            }
            content.addView(settingsSectionHeading(R.string.settings_quick_settings).apply { setPadding(0, dp(10), 0, dp(8)) })
            content.addView(quickSettingsCard(), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(SETTINGS_BLOCK_GAP_DP) })
        }

        renderSections(content, SettingsInformationArchitecture.sectionsByCategory.getValue(SettingsCategory.OVERVIEW))
    }

    private fun currentReadiness() = SettingsReadiness.of(
        setupError = setupError != null,
        active = CarPlayBackgroundSession.active,
        running = CarPlayBackgroundSession.hasSession(),
        wireless = AirPlayPersistence.loadWirelessEnabled(this),
        phoneChosen = DiPlayPreferences.phoneAddress(this) != null,
        hotspotSetupNeeded = AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL &&
            (pendingCarHotspotSetup || hotspotError(storedSsid(), storedPassword()) != null),
    )

    private fun refreshReadiness() {
        val card = readinessCard ?: return
        val state = currentReadiness()
        if (state == renderedReadiness) return
        renderedReadiness = state
        card.removeAllViews()
        val title = getString(when (state) {
            SettingsReadiness.SETUP_ERROR, SettingsReadiness.HOTSPOT_SETUP -> R.string.setup_needs_attention
            SettingsReadiness.CONNECTED -> R.string.carplay_connected
            SettingsReadiness.CONNECTING -> R.string.connecting_to_your_iphone
            SettingsReadiness.CHOOSE_IPHONE -> R.string.settings_choose_iphone_title
            SettingsReadiness.READY_WIRELESS, SettingsReadiness.READY_USB -> R.string.ready
        })
        val detail = when (state) {
            SettingsReadiness.SETUP_ERROR -> setupError.orEmpty()
            SettingsReadiness.HOTSPOT_SETUP -> getString(R.string.save_the_name_and_password_from_the_car_s_hotspot_settings)
            SettingsReadiness.CONNECTED -> getString(R.string.settings_ready_connected)
            SettingsReadiness.CONNECTING -> getString(R.string.settings_connecting_description)
            SettingsReadiness.CHOOSE_IPHONE -> getString(R.string.settings_choose_iphone_description)
            SettingsReadiness.READY_WIRELESS -> getString(R.string.settings_ready_wireless, DiPlayPreferences.phoneName(this))
            SettingsReadiness.READY_USB -> getString(R.string.settings_ready_usb)
        }
        val tint = when {
            state.needsAction -> WARNING
            state == SettingsReadiness.CONNECTED -> READY
            else -> TEXT
        }
        card.addView(label(title, 22, tint, true))
        card.addView(label(detail, 15, MUTED).apply { setPadding(0, dp(6), 0, 0) })
        when (state) {
            SettingsReadiness.CHOOSE_IPHONE -> card.addView(button(getString(R.string.settings_choose_iphone_action), true) {
                choosePhone()
            }, matchButton(12, 56))
            SettingsReadiness.SETUP_ERROR, SettingsReadiness.HOTSPOT_SETUP -> card.addView(button(getString(R.string.open_connection_setup), true) {
                openConnectionSetupFromSettings()
            }, matchButton(12, 56))
            else -> Unit
        }
    }

    private fun quickSettingsCard(): LinearLayout = card().also(::buildQuickSettings).also(::normalizeSpacing)

    private fun buildQuickSettings(card: LinearLayout): Unit = card.run {
        toggle(this, getString(R.string.connect_when_diplay_opens),
            getString(R.string.default_connection_description),
            DiPlayPreferences.autoConnect(this@DiPlayActivity)) { DiPlayPreferences.saveAutoConnect(this@DiPlayActivity, it) }
        appearanceControl(this)
        toggle(this, getString(R.string.full_screen), getString(R.string.settings_full_screen_description),
            AirPlayPersistence.loadHideTopBar(this@DiPlayActivity) && AirPlayPersistence.loadHideBottomBar(this@DiPlayActivity)) { enabled ->
            AirPlayPersistence.saveHideTopBar(this@DiPlayActivity, enabled)
            AirPlayPersistence.saveHideBottomBar(this@DiPlayActivity, enabled)
            applyFullscreenMode()
        }
        toggle(this, getString(R.string.report_location_to_iphone),
            "${getString(R.string.location_reporting_reconnects)} ${getString(R.string.sends_precise_android_location_as_carplay_gps_data_when_th)}",
            AirPlayPersistence.loadLocationReportingEnabled(this@DiPlayActivity), save = ::onLocationReportingChanged)
        Unit
    }

    private fun openSettingsCategory(category: SettingsCategory) {
        setSearchKeyboard(settingsSearchBox, false)
        clearSettingsSearchState()
        settingsCategory = category
        render()
    }

    /** Older intents and saved state used a separate about page; keep the left settings rail. */
    private fun adoptAboutAsSettingsCategory() {
        if (page != "about") return
        page = "settings"
        settingsCategory = SettingsCategory.ABOUT
    }

    private fun settingsSectionHeading(title: Int) = label(getString(title), 22, TEXT, true).apply {
        setPadding(0, 0, 0, dp(10))
    }

    private fun settingsSummaryCard(category: SettingsCategory, summary: String): LinearLayout = card().apply {
        val warning = category == SettingsCategory.ADVANCED
        setPadding(dp(24), dp(10), dp(24), dp(10))
        isClickable = true
        isFocusable = true
        foreground = android.graphics.drawable.LayerDrawable(arrayOf(
            android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(RIPPLE), null, null),
            focusRing((layoutCornerRadius() * 1.35f / resources.displayMetrics.density).roundToInt())))
        contentDescription = getString(R.string.settings_open_category, settingsCategoryTitle(category))
        addView(row().apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(this@DiPlayActivity).apply {
                setImageResource(settingsCategoryIcon(category))
                imageTintList = ColorStateList.valueOf(if (warning) WARNING else ACCENT)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(20) })
            addView(column().apply {
                addView(label(settingsCategoryTitle(category), 17, if (warning) WARNING else TEXT, true))
                addView(label(summary, 13, MUTED).apply { setPadding(0, dp(3), 0, 0) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
        })
        setOnClickListener { openSettingsCategory(category) }
    }

    private fun settingsPageTitle(content: LinearLayout, title: String, subtitle: String) {
        content.addView(label(title, if (isExpandedSettingsLayout) 34 else 26, TEXT, true))
        content.addView(label(subtitle, 16, MUTED).apply { setPadding(0, dp(6), 0, dp(20)) })
    }

    private fun connectionSettings(content: LinearLayout) {
        settingsPageTitle(content, getString(R.string.connection), getString(R.string.settings_connection_summary))
        renderSections(content, SettingsInformationArchitecture.sectionsByCategory.getValue(SettingsCategory.CONNECTION))
    }

    private fun displaySettings(content: LinearLayout) {
        settingsPageTitle(content, getString(R.string.settings_display), getString(R.string.settings_display_summary))
        renderSections(content, SettingsInformationArchitecture.sectionsByCategory.getValue(SettingsCategory.DISPLAY))
    }

    // ─────────────────────────────────────────────────────────────
    // v100 B561: 仪表媒体卡 / 转向卡 / 路口放大图 的设置组
    // (从 OushangCarPlay v91 移植;通话卡未移植,相应滑块已删)
    // ─────────────────────────────────────────────────────────────

    private fun clusterMediaCardControls(card: LinearLayout) {
        // B561: 媒体卡 / 通话卡 —— 各自**独立**的一组滑块。
        //
        // ★ 原先三张卡共用转向卡那一份 x/y/size，结果媒体卡与转向卡
        //   永远重叠、无法分开摆（用户实测反馈）。所以每张卡一套：
        //   转向卡（上面那组） / 媒体卡（下面这组） / 通话卡（再下面）。
        val mediaOn = AirPlayPersistence.loadClusterMediaCardEnabled(this)
        toggle(card, getString(R.string.cluster_media_card),
            getString(R.string.cluster_media_card_description), mediaOn) {
            AirPlayPersistence.saveClusterMediaCardEnabled(this, it)
            render()
        }
        // ★ v110 B561: 通话卡开关（默认开）。与媒体卡并列 —— 通话卡压住歌名卡，
        //   用户在音乐卡旁边单独摆它（位置在「统一卡片预览」里选"通话"chip 调）。
        toggle(card, getString(R.string.cluster_call_card),
            getString(R.string.cluster_call_card_description),
            AirPlayPersistence.loadClusterCallCardEnabled(this)) {
            AirPlayPersistence.saveClusterCallCardEnabled(this, it)
            render()
        }
        if (mediaOn) {
            // 封面：仪表屏窄时纯文字两行更清爽，所以给开关而不是写死。
            toggle(card, getString(R.string.cluster_media_card_artwork),
                getString(R.string.cluster_media_card_artwork_description),
                AirPlayPersistence.loadClusterMediaArtwork(this)) {
                AirPlayPersistence.saveClusterMediaArtwork(this, it)
            }
            // v108: 位置/大小/透明度滑块已整合到仪表分区的「统一卡片预览」块
            // （三张卡共用一个预览框 + 一行 chip 选卡）。这里只留内容属性：
            // 字号、滚动、排放方向、宽高 —— 它们与"卡里放什么"相关，
            // 跟位置不是一类，混在一起反而是六七个滑块一长条。
            // B561: 字号滑块 —— 与上面「大小」分开，因为它只改字不动卡。
            // 用户反馈仪表屏上歌名偏小，但把整张卡放大会挡住地图。
            card.addView(
                overlaySliderRow(
                    getString(R.string.cluster_media_text_scale),
                    com.shilapi.xcertplay.airplay.ClusterTurnCardOverlay.mediaTextScalePercents,
                    AirPlayPersistence.loadClusterMediaTextScalePercent(this),
                ) { getString(R.string.cluster_media_text_scale_option, it) }
                    .also { row -> row.onSave = { v ->
                        AirPlayPersistence.saveClusterMediaTextScalePercent(this, v)
                        refreshClusterPreview()
                    } },
            )
            // B561: 滚动显示 —— 仪表屏窄，长歌名会被截成"…"，开了就循环走。
            toggle(
                card,
                getString(R.string.cluster_media_scroll_text),
                getString(R.string.cluster_media_scroll_text_description),
                AirPlayPersistence.loadClusterMediaScrollText(this),
            ) { checked ->
                AirPlayPersistence.saveClusterMediaScrollText(this, checked)
                refreshClusterPreview()
            }
            // ★ B561: 排放方向 + 宽/高独立。
            //   「大小」滑块是等比缩放（宽高被绑在 2.64:1），
            //   仪表屏上想要"宽而扁"或"窄而高"做不到，所以给形状单独一组。
            val orientationOptions = listOf(
                getString(R.string.cluster_media_orientation_banner),
                getString(R.string.cluster_media_orientation_vertical),
                getString(R.string.cluster_media_orientation_free),
            )
            val orientations =
                com.shilapi.xcertplay.airplay.ClusterTurnCardOverlay.CardOrientation.entries
            choice(
                card,
                getString(R.string.cluster_media_orientation),
                orientationOptions,
                orientations.indexOf(AirPlayPersistence.loadClusterMediaOrientation(this))
                    .coerceAtLeast(0),
                reconnects = false,
            ) { index ->
                AirPlayPersistence.saveClusterMediaOrientation(this, orientations[index])
                refreshClusterPreview()
            }
            // 宽/高滑块只在「自由」下显示。
            //
            // B561 v67 之后三种方向的几何分工是：
            // - 横幅（封面在左，2.64:1）/ 正方（封面在上，1:1）
            //   → 都由**通用「大小」滑块** sizePercent 驱动，比例由方向定，
            //     所以这里不该再给宽/高 —— 给了也会被忽略。
            // - 自由 → 宽高各走各的百分比滑块，卡面比例由用户自己拼，
            //     版式按实际宽高自动切（宽 ≥ 1.15倍高走横幅，否则走正方）。
            if (AirPlayPersistence.loadClusterMediaOrientation(this) ==
                com.shilapi.xcertplay.airplay.ClusterTurnCardOverlay.CardOrientation.FREEFORM
            ) {
                card.addView(
                    overlaySliderRow(
                        getString(R.string.cluster_media_width),
                        com.shilapi.xcertplay.airplay.ClusterTurnCardOverlay.mediaWidthPercents,
                        AirPlayPersistence.loadClusterMediaWidthPercent(this),
                    ) { getString(R.string.cluster_media_width_option, it) }
                        .also { row -> row.onSave = { v ->
                            AirPlayPersistence.saveClusterMediaWidthPercent(this, v)
                            refreshClusterPreview()
                        } },
                )
                card.addView(
                    overlaySliderRow(
                        getString(R.string.cluster_media_height),
                        com.shilapi.xcertplay.airplay.ClusterTurnCardOverlay.mediaHeightPercents,
                        AirPlayPersistence.loadClusterMediaHeightPercent(this),
                    ) { getString(R.string.cluster_media_height_option, it) }
                        .also { row -> row.onSave = { v ->
                            AirPlayPersistence.saveClusterMediaHeightPercent(this, v)
                            refreshClusterPreview()
                        } },
                )
            }
        }
    }

    private var clusterPreview: ClusterCardPreview? = null

    /**
     * v108: 统一预览框当前选中的卡片（"turn" / "media" / "junction"）。
     *
     * 与 [clusterPreview] 不同 —— 这个字段**不随 render() 重置**：
     * render() 重建整棵视图树，但用户选中哪张卡是跨重建的意图，
     * 重置会让用户每次切换任何设置都被打回转向卡。
     * 若选中的卡恰好不可用（被关掉/内容切换），构建时回退到第一个可用卡。
     */
    private var clusterPreviewCard = "turn"

    /**
     * ★ v69: 拿到（必要时创建并挂上）仪表卡预览。
     *
     *媒体卡的设置在「音频」分区、预览在「仪表」分区，两个分区各自 render()
     * 时互不可见 —— 所以不能只在仪表分区 new。改成**懒初始化**：
     * 谁先需要谁建，[render] 每次会重建整棵视图树，旧的 View 自然被丢弃，
     * 而 [clusterPreview] 字段每次 render 开头会置 null，不会出现「一个 View
     * 被挂进两个父容器」的 crash。
     */
    private fun ensureClusterPreview(card: LinearLayout): ClusterCardPreview? {
        clusterPreview?.let { return it }
        val created = ClusterCardPreview(this)
        clusterPreview = created
        refreshClusterPreview()
        return created
    }

    /**
     * 把当前持久化的四张卡几何/透明度/媒体卡形状推给预览并重绘。
     *
     * 每张卡读自己的 `loadClusterXxx`，不是共用转向卡那份 —— 否则预览里
     * 媒体卡永远停在默认值，用户按预览摆好的位置上车就变了。
     * 昼夜用 [lastKnownNight]：车机 `uiMode` 常报 UNDEFINED，
     * 直接读会把预览画成永远白天。
     */
    private fun refreshClusterPreview() {
        val preview = clusterPreview ?: return
        preview.update(
            geometry = mapOf(
                "turn" to Triple(
                    AirPlayPersistence.loadClusterTurnCardOverlayXPercent(this),
                    AirPlayPersistence.loadClusterTurnCardOverlayYPercent(this),
                    AirPlayPersistence.loadClusterTurnCardOverlaySizePercent(this),
                ),
                "media" to Triple(
                    AirPlayPersistence.loadClusterMediaXPercent(this),
                    AirPlayPersistence.loadClusterMediaYPercent(this),
                    AirPlayPersistence.loadClusterMediaSizePercent(this),
                ),
                "junction" to Triple(
                    AirPlayPersistence.loadClusterJunctionXPercent(this),
                    AirPlayPersistence.loadClusterJunctionYPercent(this),
                    AirPlayPersistence.loadClusterJunctionSizePercent(this),
                ),
                "lane" to Triple(
                    AirPlayPersistence.loadClusterLaneXPercent(this),
                    AirPlayPersistence.loadClusterLaneYPercent(this),
                    AirPlayPersistence.loadClusterLaneSizePercent(this),
                ),
                "call" to Triple(
                    AirPlayPersistence.loadClusterCallXPercent(this),
                    AirPlayPersistence.loadClusterCallYPercent(this),
                    AirPlayPersistence.loadClusterCallSizePercent(this),
                ),
            ),
            opacity = mapOf(
                "turn" to AirPlayPersistence.loadClusterTurnCardOpacityPercent(this),
                "media" to AirPlayPersistence.loadClusterMediaOpacityPercent(this),
                "junction" to AirPlayPersistence.loadClusterJunctionOpacityPercent(this),
                "lane" to AirPlayPersistence.loadClusterLaneOpacityPercent(this),
                "call" to AirPlayPersistence.loadClusterCallOpacityPercent(this),
            ),
            night = lastKnownNight,
        )
        preview.setMediaTextOptions(
            AirPlayPersistence.loadClusterMediaTextScalePercent(this),
            AirPlayPersistence.loadClusterMediaScrollText(this),
        )
        preview.setMediaShape(
            AirPlayPersistence.loadClusterMediaWidthPercent(this),
            AirPlayPersistence.loadClusterMediaHeightPercent(this),
            AirPlayPersistence.loadClusterMediaOrientation(this),
        )
    }

    /**
     * v108: 统一的仪表卡片预览块 —— 一行卡片选择 + 一个预览框 + 位置滑块。
     *
     * 原先主面板三张卡有**两个**预览框（转向卡一个 ClusterCardPlacementPreview、
     * 三卡共用一个 ClusterCardPreview），位置滑块还散在三个分区（转向卡在仪表区、
     * 媒体卡在音频区、路口卡在仪表区）——调媒体卡位置要跑去音频分区、
     * 看效果要回仪表分区。现在合成一块：
     *
     * ```
     *   [转向卡] [媒体卡] [路口卡]     ← 一行 chip，只列当前可用的卡
     *   ┌────────────────────┐
     *   │   预览框（选中卡高亮）  │
     *   └────────────────────┘
     *   大小 / 透明度 / 水平 / 垂直   ← 只调选中卡那一套
     *   [重置位置]
     * ```
     *
     * 小窗卡片的预览**不**并进来：它的几何是「小窗内的百分比」，
     * 与主面板的 1920×720 面板坐标不是一套体系，画进同一个框会误导。
     */
    private fun clusterCardPreviewBlock(card: LinearLayout) {
        val turnAvailable = CarPlayClusterDisplay.usesCustomTurnCard(
            AirPlayPersistence.loadClusterContent(this))
        val mediaAvailable = AirPlayPersistence.loadClusterMediaCardEnabled(this)
        val junctionAvailable = AirPlayPersistence.loadClusterJunctionCardEnabled(this)
        // v118: 车道信息卡片。官方转向卡模式下同样不可用 ——
        // 那张卡只有自定义内容(clusterContent)时才谈得上摆位。
        val laneAvailable = AirPlayPersistence.loadClusterLaneCardEnabled(this)
        val callAvailable = AirPlayPersistence.loadClusterCallCardEnabled(this)
        val available = buildList {
            if (turnAvailable) add("turn")
            if (mediaAvailable) add("media")
            if (junctionAvailable) add("junction")
            if (laneAvailable) add("lane")
            if (callAvailable) add("call")
        }
        card.addView(label(getString(R.string.card_preview_title), 16, TEXT, true))
        if (available.isEmpty()) {
            // 官方转向卡模式下媒体卡/路口卡都关着 —— 没有可摆的卡，说清楚原因。
            card.addView(label(getString(R.string.cluster_preview_no_card), 14, MUTED))
            return
        }
        // 选中的卡可能刚被关掉（或内容切回了官方转向卡）—— 回退到第一个可用卡。
        if (clusterPreviewCard !in available) clusterPreviewCard = available.first()

        // 一行 chip：等分排布，点选即切换预览高亮与下方滑块。
        val chipRow = row().apply { setPadding(0, dp(4), 0, dp(10)) }
        for (key in available) {
            chipRow.addView(clusterCardChip(key, clusterPreviewCard == key))
        }
        card.addView(chipRow)

        ensureClusterPreview(card)?.let { preview ->
            // v111: 高度改WRAP_CONTENT —— 预览自己按 1920:720 推高度。
            // 之前固定 dp(240) 高，宽度被 8:3 卡在 1248px，比卡片（1344px）窄，
            // 右侧空一块，看着就是"预览没占满预览区域"。
            card.addView(preview, LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT))
            preview.setVisibleCards(turnAvailable, mediaAvailable)
            preview.setJunctionVisible(junctionAvailable)
            preview.setLaneVisible(laneAvailable)
            preview.setCallVisible(callAvailable)
            preview.setSelectedCard(clusterPreviewCard)
        }
        card.addView(label(getString(R.string.card_preview_note), 14, MUTED).apply {
            setPadding(0, dp(8), 0, 0)
        })

        // 位置滑块：选中卡读自己那套持久化键；保存后立刻刷新预览（拖滑块即见）。
        when (clusterPreviewCard) {
            "media" -> clusterCardSliders(
                card,
                loadX = { AirPlayPersistence.loadClusterMediaXPercent(this) },
                loadY = { AirPlayPersistence.loadClusterMediaYPercent(this) },
                loadSize = { AirPlayPersistence.loadClusterMediaSizePercent(this) },
                loadOpacity = { AirPlayPersistence.loadClusterMediaOpacityPercent(this) },
                saveX = { AirPlayPersistence.saveClusterMediaXPercent(this, it) },
                saveY = { AirPlayPersistence.saveClusterMediaYPercent(this, it) },
                saveSize = { AirPlayPersistence.saveClusterMediaSizePercent(this, it) },
                saveOpacity = { AirPlayPersistence.saveClusterMediaOpacityPercent(this, it) },
            )
            "junction" -> clusterCardSliders(
                card,
                loadX = { AirPlayPersistence.loadClusterJunctionXPercent(this) },
                loadY = { AirPlayPersistence.loadClusterJunctionYPercent(this) },
                loadSize = { AirPlayPersistence.loadClusterJunctionSizePercent(this) },
                loadOpacity = { AirPlayPersistence.loadClusterJunctionOpacityPercent(this) },
                saveX = { AirPlayPersistence.saveClusterJunctionXPercent(this, it) },
                saveY = { AirPlayPersistence.saveClusterJunctionYPercent(this, it) },
                saveSize = { AirPlayPersistence.saveClusterJunctionSizePercent(this, it) },
                saveOpacity = { AirPlayPersistence.saveClusterJunctionOpacityPercent(this, it) },
            )
            // v118: 车道信息卡片 —— 横幅版式,默认压在仪表底边。
            "lane" -> clusterCardSliders(
                card,
                loadX = { AirPlayPersistence.loadClusterLaneXPercent(this) },
                loadY = { AirPlayPersistence.loadClusterLaneYPercent(this) },
                loadSize = { AirPlayPersistence.loadClusterLaneSizePercent(this) },
                loadOpacity = { AirPlayPersistence.loadClusterLaneOpacityPercent(this) },
                saveX = { AirPlayPersistence.saveClusterLaneXPercent(this, it) },
                saveY = { AirPlayPersistence.saveClusterLaneYPercent(this, it) },
                saveSize = { AirPlayPersistence.saveClusterLaneSizePercent(this, it) },
                saveOpacity = { AirPlayPersistence.saveClusterLaneOpacityPercent(this, it) },
            )
            "call" -> clusterCardSliders(
                card,
                loadX = { AirPlayPersistence.loadClusterCallXPercent(this) },
                loadY = { AirPlayPersistence.loadClusterCallYPercent(this) },
                loadSize = { AirPlayPersistence.loadClusterCallSizePercent(this) },
                loadOpacity = { AirPlayPersistence.loadClusterCallOpacityPercent(this) },
                saveX = { AirPlayPersistence.saveClusterCallXPercent(this, it) },
                saveY = { AirPlayPersistence.saveClusterCallYPercent(this, it) },
                saveSize = { AirPlayPersistence.saveClusterCallSizePercent(this, it) },
                saveOpacity = { AirPlayPersistence.saveClusterCallOpacityPercent(this, it) },
            )
            else -> clusterCardSliders(
                card,
                loadX = { AirPlayPersistence.loadClusterTurnCardOverlayXPercent(this) },
                loadY = { AirPlayPersistence.loadClusterTurnCardOverlayYPercent(this) },
                loadSize = { AirPlayPersistence.loadClusterTurnCardOverlaySizePercent(this) },
                loadOpacity = { AirPlayPersistence.loadClusterTurnCardOpacityPercent(this) },
                saveX = { AirPlayPersistence.saveClusterTurnCardOverlayXPercent(this, it) },
                saveY = { AirPlayPersistence.saveClusterTurnCardOverlayYPercent(this, it) },
                saveSize = { AirPlayPersistence.saveClusterTurnCardOverlaySizePercent(this, it) },
                saveOpacity = { AirPlayPersistence.saveClusterTurnCardOpacityPercent(this, it) },
            )
        }
    }

    /**
     * v108: 选中卡片的位置滑块组（大小 / 透明度 / 水平 / 垂直 + 重置）。
     *
     * 标签不带卡片名前缀 —— 选中态已经说明了在调哪张卡，再加
     * 「媒体卡 · 大小」在窄栏里是重复信息。
     */
    private fun clusterCardSliders(
        card: LinearLayout,
        loadX: () -> Int,
        loadY: () -> Int,
        loadSize: () -> Int,
        loadOpacity: () -> Int,
        saveX: (Int) -> Unit,
        saveY: (Int) -> Unit,
        saveSize: (Int) -> Unit,
        saveOpacity: (Int) -> Unit,
    ) {
        card.addView(overlaySliderRow(
            getString(R.string.cluster_card_size),
            ClusterTurnCardOverlay.sizePercents, loadSize(),
        ) { getString(R.string.turn_card_overlay_size_option, it) }
            .also { it.onSave = { v -> saveSize(v); refreshClusterPreview() } })
        card.addView(overlaySliderRow(
            getString(R.string.cluster_card_opacity),
            ClusterTurnCardOverlay.opacityPercents, loadOpacity(),
        ) { getString(R.string.turn_card_overlay_opacity_option, it) }
            .also { it.onSave = { v -> saveOpacity(v); refreshClusterPreview() } })
        card.addView(overlaySliderRow(
            getString(R.string.cluster_card_horizontal),
            ClusterTurnCardOverlay.xPercents, loadX(),
        ) { overlayOffsetLabel(it, getString(R.string.marker_left), getString(R.string.marker_right), 50) }
            .also { it.onSave = { v -> saveX(v); refreshClusterPreview() } })
        card.addView(overlaySliderRow(
            getString(R.string.cluster_card_vertical),
            ClusterTurnCardOverlay.yPercents, loadY(),
        ) { overlayOffsetLabel(it, getString(R.string.marker_up), getString(R.string.marker_down), 40) }
            .also { it.onSave = { v -> saveY(v); refreshClusterPreview() } })
        // 只重置位置，不动大小/透明度 —— 与旧「重置转向卡位置」按钮同语义：
        // 用户往往是"卡挡住了地图"而不是"卡太大"。
        card.addView(button(getString(R.string.cluster_preview_reset), false) {
            saveX(ClusterTurnCardOverlay.DEFAULT_X_PERCENT)
            saveY(ClusterTurnCardOverlay.DEFAULT_Y_PERCENT)
            render()
        }, matchButton(10, 56))
    }

    /**
     * v108: 预览块顶部一行的卡片选择 chip。
     *
     * 不用 [choice]（按钮 + 弹窗单选）—— 那是两步操作，而这里要的是
     * "一眼看到三张卡、点一下切换"。紧凑高度 + 等分权重，
     * 选中项填充强调色，和设置页其他按钮同一套形状常量。
     */
    private fun clusterCardChip(key: String, selected: Boolean): android.widget.Button {
        val title = getString(when (key) {
            "media" -> R.string.cluster_media_card
            "junction" -> R.string.cluster_junction_card
            "lane" -> R.string.cluster_lane_card
            "call" -> R.string.cluster_call_card
            else -> R.string.cluster_preview_card_turn
        })
        return android.widget.Button(this).apply {
            text = title
            isAllCaps = false
            textSize = 15f
            setTextColor(if (selected) ON_ACCENT else TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(RIPPLE),
                buttonShape(if (selected) ACCENT else BUTTON, if (selected) ACCENT else BORDER),
                null,
            )
            setPadding(dp(8), 0, dp(8), 0)
            minHeight = dp(40)
            stateListAnimator = null
            layoutParams = LinearLayout.LayoutParams(0, dp(40)).apply {
                weight = 1f
                leftMargin = dp(6)
                rightMargin = dp(6)
            }
            setOnClickListener {
                if (clusterPreviewCard != key) {
                    clusterPreviewCard = key
                    render()
                }
            }
        }
    }

    private fun nightModeLabels() = CarPlayNightMode.entries.map { mode ->
        getString(when (mode) {
            CarPlayNightMode.SYSTEM -> R.string.carplay_night_system
            CarPlayNightMode.AMBIENT -> R.string.carplay_night_ambient
            CarPlayNightMode.DAY -> R.string.carplay_night_day
            CarPlayNightMode.NIGHT -> R.string.carplay_night_night
            CarPlayNightMode.SCHEDULE -> R.string.carplay_night_schedule
        })
    }

    private fun appearanceControl(parent: LinearLayout) {
        val modes = CarPlayNightMode.entries
        choice(parent, getString(R.string.carplay_night_mode), nightModeLabels(),
            modes.indexOf(AirPlayPersistence.loadCarPlayNightMode(this)), reconnects = false) { index ->
            AirPlayPersistence.saveCarPlayNightMode(this, modes[index])
        }
    }

    private fun audioSettings(content: LinearLayout) {
        settingsPageTitle(content, getString(R.string.audio), getString(R.string.settings_audio_summary))
        renderSections(content, SettingsInformationArchitecture.sectionsByCategory.getValue(SettingsCategory.AUDIO))
    }

    private fun navigationSettings(content: LinearLayout) {
        settingsPageTitle(content, getString(R.string.settings_navigation), getString(R.string.settings_navigation_summary))
        renderSections(content, SettingsInformationArchitecture.sectionsByCategory.getValue(SettingsCategory.NAVIGATION))
        // The BYD card is hidden without its receiver; say so instead of leaving a gap.
        if (true) {
            content.addView(label(getString(R.string.settings_byd_navigation_unavailable), 15, MUTED).apply {
                setPadding(dp(4), 0, dp(4), dp(SETTINGS_BLOCK_GAP_DP))
            })
        }
    }

    /** v113: 仪表菜单 —— 仪表屏显示、卡片位置/大小/透明度、安全区都在这里。 */
    private fun clusterSettings(content: LinearLayout) {
        settingsPageTitle(content, getString(R.string.settings_cluster), getString(R.string.settings_cluster_summary))
        renderSections(content, SettingsInformationArchitecture.sectionsByCategory.getValue(SettingsCategory.CLUSTER))
    }

    /** v113: 方控菜单 —— 方向盘按键：缩放/摇杆/Siri、通话接听挂断键位、导航音量。 */
    private fun wheelSettings(content: LinearLayout) {
        settingsPageTitle(content, getString(R.string.settings_wheel), getString(R.string.settings_wheel_summary))
        renderSections(content, SettingsInformationArchitecture.sectionsByCategory.getValue(SettingsCategory.WHEEL))
    }

    private fun vehicleSettings(content: LinearLayout) {
        settingsPageTitle(content, getString(R.string.settings_vehicle), getString(R.string.settings_vehicle_summary))
        renderSections(content, SettingsInformationArchitecture.sectionsByCategory.getValue(SettingsCategory.VEHICLE))
        section(content, getString(R.string.settings_advanced), R.drawable.ic_dp_advanced) { card ->
            card.addView(label(getString(R.string.settings_vehicle_description), 16, MUTED))
            card.addView(button(getString(R.string.settings_open_advanced), false) {
                settingsCategory = SettingsCategory.ADVANCED
                render()
            }, matchButton(14, 56))
        }
    }

    private fun diagnosticsSettings(content: LinearLayout) {
        settingsPageTitle(content, getString(R.string.diagnostics), getString(R.string.settings_diagnostics_summary))
        renderSections(content, SettingsInformationArchitecture.sectionsByCategory.getValue(SettingsCategory.DIAGNOSTICS))
    }

    internal data class SettingsSearchResult(val title: String, val category: SettingsCategory)

    // Renders every category into a detached view and records what the builders label.
    // render() afterwards replaces every widget reference those detached builds assigned.
    // ponytail: indexes the default state only; controls behind an expander (vehicle data) are not found.
    private fun buildSettingsSearchIndex(): List<SettingsSearchResult> {
        val selected = settingsCategory
        // Category names first, so a card that only links to a category cannot claim its name.
        val index = SettingsCategory.entries.associateByTo(linkedMapOf(), ::settingsCategoryTitle)
        try {
            for (category in SettingsCategory.entries.filter { it != SettingsCategory.OVERVIEW } + SettingsCategory.OVERVIEW) {
                settingsCategory = category
                val titles = mutableListOf(settingsCategoryTitle(category))
                searchIndexSink = titles
                settingsCategoryContent(column())
                titles.forEach { index.putIfAbsent(it, category) }
            }
        } finally {
            searchIndexSink = null
            settingsCategory = selected
        }
        render()
        return index.map { SettingsSearchResult(it.key, it.value) }
    }

    private fun searchSettings(index: List<SettingsSearchResult>, query: String): List<SettingsSearchResult> {
        val words = query.trim().lowercase(Locale.getDefault()).split(Regex("\\s+")).filter(String::isNotEmpty)
        if (words.isEmpty()) return emptyList()
        return index.filter { result ->
            val haystack = "${result.title} ${settingsCategoryTitle(result.category)}".lowercase(Locale.getDefault())
            words.all(haystack::contains)
        }
    }

    private fun openSearchResult(result: SettingsSearchResult) {
        setSearchKeyboard(settingsSearchBox, false)
        clearSettingsSearchState()
        openSettingsCategory(result.category)
        val scroll = rootScroll ?: return
        val match = descendants(scroll).filterIsInstance<TextView>().firstOrNull {
            it.text.toString() == result.title || it.text.startsWith(result.title + VALUE_SEPARATOR)
        } ?: return
        val target = generateSequence<View>(match) { it.parent as? View }.firstOrNull { it.isClickable } ?: match
        scroll.doOnLayout {
            var top = 0
            var view: View? = target
            while (view != null && view !== scroll) { top += view.top; view = view.parent as? View }
            scroll.smoothScrollTo(0, (top - dp(24)).coerceAtLeast(0))
            target.requestFocus()
            target.isPressed = true
            handler.postDelayed({ target.isPressed = false }, SEARCH_HIGHLIGHT_MILLIS)
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }

    /**
     * The settings header search box; matches drop down under it as the driver types.
     * The index is built before expanding the field, so typing keeps the same editor
     * and keyboard input connection.
     */
    private fun settingsSearchField(compact: Boolean): EditText = SettingsSearchEditText(this).apply {
        settingsSearchBox = this
        setSingleLine()
        hint = getString(R.string.settings_search_hint)
        contentDescription = getString(R.string.settings_search)
        textSize = if (compact) 16f else 18f
        setTextColor(TEXT)
        setHintTextColor(MUTED)
        // The editor scrolls long text sideways and its drawing canvas scrolls with it, but Android
        // draws the background unscrolled. The icon and focus ring live there, so they stay put
        // without compensating for scroll or for the text's vertical centring.
        val iconSize = headerIconSize(compact)
        val icon = getDrawable(R.drawable.ic_dp_search)?.mutate()?.apply { setTint(MUTED) }
        background = android.graphics.drawable.LayerDrawable(
            listOfNotNull(buttonShape(BUTTON, BORDER), icon, buttonFocusRing()).toTypedArray(),
        ).apply {
            if (icon != null) {
                setLayerGravity(1, Gravity.START or Gravity.CENTER_VERTICAL)
                setLayerSize(1, iconSize, iconSize)
                setLayerInsetRelative(1, dp(16), 0, 0, 0)
            }
        }
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        includeFontPadding = false
        setPaddingRelative(dp(16) + if (icon != null) iconSize + dp(10) else 0, 0, dp(16), 0)
        inputType = android.text.InputType.TYPE_CLASS_TEXT
        // Landscape head units would otherwise cover the results with a full-screen editor.
        imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        // Touch focus waits for a tap, so opening settings never focuses the box or raises the keyboard.
        isFocusableInTouchMode = settingsSearchRestoreFocus
        setText(settingsSearchQuery)
        setSelection(text.length)
        setOnClickListener {
            if (!isFocusableInTouchMode) {
                isFocusableInTouchMode = true
                requestFocus()
                setSearchKeyboard(this, true)
            }
            showSettingsSearchResults()
        }
        setOnFocusChangeListener { _, focused -> if (focused) showSettingsSearchResults() }
        setOnEditorActionListener { _, actionId, event ->
            val search = actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH ||
                event?.keyCode == KeyEvent.KEYCODE_ENTER
            if (!search) return@setOnEditorActionListener false
            if (event == null || event.action == KeyEvent.ACTION_DOWN) settingsSearchResults().firstOrNull()?.let(::openSearchResult)
            true
        }
        doAfterTextChanged { onSettingsSearchChanged(this) }
        // The keyboard resizes the window, not this field, so follow window layouts. A listener added
        // here would join the window's observer and outlive every rebuilt field; register it only
        // while this field is attached.
        val followWindow = android.view.ViewTreeObserver.OnGlobalLayoutListener {
            if (settingsSearchBox === this && settingsSearchPopup?.isShowing == true) {
                positionSettingsSearchResults(this, settingsSearchPopup!!)
            }
        }
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = view.viewTreeObserver.addOnGlobalLayoutListener(followWindow)
            override fun onViewDetachedFromWindow(view: View) = view.viewTreeObserver.removeOnGlobalLayoutListener(followWindow)
        })
        if (settingsSearchRestoreFocus) {
            settingsSearchRestoreFocus = false
            post {
                if (settingsSearchBox !== this) return@post
                requestFocus()
                setSearchKeyboard(this, true)
                showSettingsSearchResults()
            }
        }
    }

    private fun onSettingsSearchChanged(box: EditText) {
        if (box !== settingsSearchBox) return
        settingsSearchQuery = box.text.toString()
        if (settingsSearchQuery.isEmpty()) {
            // Keep this search's index when the query is cleared. Closing search invalidates it.
            settingsSearchPopup?.dismiss()
            return
        }
        if (settingsSearchIndex != null) {
            showSettingsSearchResults()
            return
        }
        if (settingsSearchIndexing) return
        settingsSearchIndexing = true
        // Not from inside the text watcher: indexing replaces this box.
        handler.post {
            settingsSearchIndexing = false
            if (page != "settings" || settingsSearchQuery.isEmpty() || settingsSearchIndex != null) return@post
            settingsSearchRestoreFocus = true
            settingsSearchIndex = buildSettingsSearchIndex()
        }
    }

    private fun settingsSearchResults(): List<SettingsSearchResult> =
        settingsSearchIndex?.let { searchSettings(it, settingsSearchQuery) }.orEmpty()

    private fun showSettingsSearchResults() {
        val box = settingsSearchBox ?: return
        if (settingsSearchIndex == null || settingsSearchQuery.isBlank() || !box.isAttachedToWindow) {
            settingsSearchPopup?.dismiss()
            return
        }
        val results = settingsSearchResults()
        val list = column().apply { setPadding(dp(8), dp(8), dp(8), dp(8)) }
        if (results.isEmpty()) {
            list.addView(label(getString(R.string.settings_search_empty), 15, MUTED).apply {
                setPadding(dp(12), dp(12), dp(12), dp(12))
            })
        }
        results.forEach { list.addView(settingsSearchRow(it)) }
        val existing = settingsSearchPopup?.takeIf { it.isShowing }
        val scroll = (existing?.contentView as? ScrollView) ?: ScrollView(this)
        scroll.removeAllViews()
        scroll.addView(list)
        if (existing != null) {
            positionSettingsSearchResults(box, existing)
            return
        }
        settingsSearchPopup = PopupWindow(scroll, box.width, 0, false).apply {
            setBackgroundDrawable(rounded(SURFACE, BORDER))
            elevation = dp(8).toFloat()
            // Tapping elsewhere closes the list and keeps the query; tapping the box reopens it.
            isOutsideTouchable = true
            inputMethodMode = PopupWindow.INPUT_METHOD_NEEDED
            setIsLaidOutInScreen(true)
            positionSettingsSearchResults(box, this)
        }
    }

    private fun positionSettingsSearchResults(box: EditText, popup: PopupWindow) {
        val visible = android.graphics.Rect()
        box.getWindowVisibleDisplayFrame(visible)
        val location = IntArray(2)
        box.getLocationOnScreen(location)
        val top = location[1] + box.height + dp(SETTINGS_SEARCH_RESULTS_GAP_DP)
        val width = minOf(box.width, visible.width())
        val maxHeight = minOf(
            visible.bottom - top,
            dp(if (resources.configuration.screenHeightDp < 600)
                SETTINGS_SEARCH_RESULTS_SHORT_HEIGHT_DP else SETTINGS_SEARCH_RESULTS_HEIGHT_DP),
        )
        // Never let Android flip the list above its anchor and cover the field.
        if (width <= 0 || maxHeight <= 0) {
            popup.dismiss()
            return
        }
        val left = location[0].coerceIn(visible.left, visible.right - width)
        val content = popup.contentView
        content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST))
        val height = content.measuredHeight.coerceIn(1, maxHeight)
        if (popup.isShowing) {
            popup.update(left, top, width, height)
        } else {
            popup.width = width
            popup.height = height
            popup.showAtLocation(box.rootView, Gravity.TOP or Gravity.LEFT, left, top)
        }
    }

    private fun settingsSearchRow(result: SettingsSearchResult): View = column().apply {
        val category = settingsCategoryTitle(result.category)
        isClickable = true
        isFocusable = true
        contentDescription = "${result.title}, $category"
        foreground = android.graphics.drawable.LayerDrawable(arrayOf(
            android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(RIPPLE), null, null), focusRing(12)))
        minimumHeight = dp(56)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(8))
        addView(label(result.title, 17, TEXT, true))
        addView(label(category, 13, MUTED))
        setOnClickListener { openSearchResult(result) }
    }

    private fun closeSettingsSearch() {
        val box = settingsSearchBox
        setSearchKeyboard(box, false)
        clearSettingsSearchState()
        box?.clearFocus()
        render()
        descendants(window.decorView).firstOrNull { it.tag == SETTINGS_SEARCH_BUTTON_TAG }?.requestFocus()
    }

    private fun clearSettingsSearchState() {
        settingsSearchQuery = ""
        settingsSearchExpanded = false
        settingsSearchIndex = null
        settingsSearchRestoreFocus = false
        settingsSearchPopup?.dismiss()
    }

    private fun setSearchKeyboard(view: View?, show: Boolean) {
        view ?: return
        val keyboard = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        if (show) keyboard.showSoftInput(view, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        else keyboard.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun advancedSettings(content: LinearLayout) {
        settingsPageTitle(content, getString(R.string.settings_advanced), getString(R.string.settings_advanced_subtitle))
        val caution = card().apply {
            addView(label(getString(R.string.settings_advanced_caution_title), 18, WARNING, true))
            addView(label(getString(R.string.settings_advanced_caution_description), 14, MUTED).apply {
                setPadding(0, dp(6), 0, 0)
            })
        }
        content.addView(caution, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(SETTINGS_BLOCK_GAP_DP) })
        renderSections(content, SettingsInformationArchitecture.sectionsByCategory.getValue(SettingsCategory.ADVANCED))
    }

    // v100 B561: advancedVehicleDataSettings(高级车辆数据 / ADB 探测)已随 BYD 集成删除。

    private fun renderSections(
        content: LinearLayout,
        sections: Set<SettingsSection>,
    ) {
        val previous = settingsSectionFilter
        settingsSectionFilter = sections
        try {
            allSettingsSections(content)
        } finally {
            settingsSectionFilter = previous
        }
    }

    private fun allSettingsSections(content: LinearLayout) {
        filteredSection(content, SettingsSection.CARPLAY_CONTROLS,
            getString(R.string.carplay_controls), R.drawable.ic_dp_controls) { card ->
            val gestureFingers = listOf(0, 2, 3, 4)
            choice(card, getString(R.string.settings_gesture_fingers_label),
                gestureFingers.map { if (it == 0) getString(R.string.settings_gesture_disabled) else getString(R.string.settings_gesture_fingers_option, it) },
                gestureFingers.indexOf(AirPlayPersistence.loadSettingsGestureFingers(this)).coerceAtLeast(0),
                reconnects = false) {
                AirPlayPersistence.saveSettingsGestureFingers(this, gestureFingers[it])
            }
            card.addView(label(getString(R.string.settings_gesture_fingers_hint), 14, MUTED).apply {
                setPadding(0, dp(10), 0, 0)
            })
            val swipeTargets = listOf(false, true)
            choice(card, getString(R.string.settings_swipe_target_label),
                listOf(
                    getString(R.string.settings_swipe_target_overlay),
                    getString(R.string.settings_swipe_target_full),
                ),
                swipeTargets.indexOf(AirPlayPersistence.loadSwipeOpensFullSettings(this)).coerceAtLeast(0),
                reconnects = false) {
                AirPlayPersistence.saveSwipeOpensFullSettings(this, swipeTargets[it])
            }
            card.addView(label(getString(R.string.settings_swipe_target_hint), 14, MUTED).apply {
                setPadding(0, dp(10), 0, 0)
            })
            toggle(card, getString(R.string.settings_launcher_returns_to_carplay),
                getString(R.string.settings_launcher_returns_to_carplay_description),
                AirPlayPersistence.loadLauncherReturnsToCarPlay(this)) {
                AirPlayPersistence.saveLauncherReturnsToCarPlay(this, it)
            }
            toggle(card, getString(R.string.right_hand_drive), getString(R.string.place_carplay_s_controls_closer_to_the_driver), AirPlayPersistence.loadRightHandDrive(this)) { AirPlayPersistence.saveRightHandDrive(this, it); markReconnectNeeded() }
        }
        filteredSection(content, SettingsSection.WHEEL_KEYS,
            getString(R.string.settings_wheel_keys), R.drawable.ic_dp_controls, ::wheelKeysSettings)
        // v113: 通话按键（接听/挂断）独立成段，放在方控菜单里。
        filteredSection(content, SettingsSection.CALL_KEYS,
            getString(R.string.call_key_section), R.drawable.ic_dp_controls) { card ->
            callKeyControls(card)
        }
        filteredSection(content, SettingsSection.CONNECTION_SETUP,
            getString(R.string.connection_setup), R.drawable.ic_dp_connection) { card ->
            card.addView(label(getString(R.string.choose_how_to_connect_follow_the_setup_steps_and_save_your), 16, MUTED))
            card.addView(button(getString(R.string.open_connection_setup), false, ::openConnectionSetupFromSettings), matchButton(12, 60))
        }
        filteredSection(content, SettingsSection.DIAGNOSTICS,
            getString(R.string.diagnostics), R.drawable.ic_dp_diagnostics) { card ->
            exportButton = button(if (exportInProgress) getString(R.string.saving_report) else getString(R.string.save_diagnostic_report), false) {
                saveReportToDownloads()
            }.apply { isEnabled = !exportInProgress }
            card.addView(exportButton, matchButton(SETTINGS_BUTTON_GAP_DP, 60))
            card.addView(button(getString(R.string.choose_save_location), false) { chooseReportDestination() }, matchButton(SETTINGS_BUTTON_GAP_DP, 60))
            card.addView(button(getString(R.string.settings_report_on_phone), false) { openBackup(SettingsCategory.DIAGNOSTICS) }, matchButton(SETTINGS_BUTTON_GAP_DP, 60))
            val destination = getString(R.string.reports_save_to_downloads_diplay)
            card.addView(label(destination + getString(R.string.nothing_is_sent_automatically_protocol_payloads_and_creden), 14, MUTED).apply { setPadding(0, dp(12), 0, 0) })
            toggle(card, getString(R.string.settings_fps_counter), getString(R.string.settings_fps_counter_description),
                AirPlayPersistence.loadFpsCounter(this)) { AirPlayPersistence.saveFpsCounter(this, it) }
        }
        filteredSection(content, SettingsSection.AUTOMATIC_CONNECTION,
            getString(R.string.automatic_connection), R.drawable.ic_dp_automation) { card ->
            toggle(card, getString(R.string.connect_when_diplay_opens), getString(R.string.default_connection_description), DiPlayPreferences.autoConnect(this)) { DiPlayPreferences.saveAutoConnect(this, it) }
            toggle(card, getString(R.string.connect_when_iphone_bluetooth_connects),
                getString(R.string.connect_when_iphone_bluetooth_connects_description),
                DiPlayPreferences.connectOnPhoneBluetooth(this)) { enabled ->
                DiPlayPreferences.saveConnectOnPhoneBluetooth(this, enabled)
                if (enabled && Build.VERSION.SDK_INT >= 31 &&
                    checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    bluetoothAutoConnectPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                } else if (enabled && DiPlayPreferences.phoneAddress(this) == null) choosePhone()
            }
            val connectionModes = DefaultConnectionMode.entries
            choice(card, getString(R.string.default_connection_mode), listOf(
                getString(R.string.default_connection_last_used),
                getString(R.string.default_connection_wireless),
                getString(R.string.default_connection_usb)
            ), connectionModes.indexOf(DiPlayPreferences.defaultConnectionMode(this)), reconnects = false) {
                DiPlayPreferences.saveDefaultConnectionMode(this, connectionModes[it])
            }
            toggle(card, getString(R.string.connect_when_iphone_bluetooth_connects),
                getString(R.string.connect_when_iphone_bluetooth_connects_description),
                DiPlayPreferences.connectOnPhoneBluetooth(this)) { enabled ->
                DiPlayPreferences.saveConnectOnPhoneBluetooth(this, enabled)
                if (enabled && Build.VERSION.SDK_INT >= 31 &&
                    checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    bluetoothAutoConnectPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                } else if (enabled && DiPlayPreferences.phoneAddress(this) == null) choosePhone()
            }
            adbToggle(card, R.string.open_after_the_car_starts,
                R.string.availability_depends_on_your_head_unit_s_startup_settings,
                read = { AirPlayPersistence.loadAutoStartOnBoot(this) },
                needsAdb = { CarHotspotSettings.enabled(this) &&
                    AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL },
                permissions = { listOf(CarHotspotSetup.Permission.BOOT_LAUNCH) }) {
                AirPlayPersistence.saveAutoStartOnBoot(this, it)
            }
            val autoConfirmActive = UsbPermissionSetup.Permission.ACCESSIBILITY.granted(this)
            toggle(
                card,
                getString(R.string.usb_auto_confirm_title),
                getString(R.string.usb_auto_confirm_subtitle),
                autoConfirmActive,
            ) { enabled ->
                if (enabled) promptEnableUsbAutoConfirm()
                else {
                    if (!UsbAutoConfirmService.openSettings(this)) toast(getString(R.string.wheel_keys_no_settings))
                    render()
                }
            }
            if (!autoConfirmActive) {
                card.addView(
                    button(
                        getString(R.string.btn_auto_apply_permissions),
                        true,
                    ) {
                        autoApplyPermissions()
                    },
                    matchButton(8, 54),
                )
            } else {
                card.addView(label(getString(R.string.usb_auto_confirm_active_hint), 14, READY).apply {
                    setPadding(0, dp(4), 0, dp(8))
                })
            }
            // v100 B561: 「修复开机自启」(原用 network ADB 改车机白名单)随 BYD 集成删除。
            card.addView(button("${getString(R.string.choose_iphone_prefix)}${DiPlayPreferences.phoneName(this)}", false) { choosePhone() }, matchButton(12, 60))
        }
        filteredSection(content, SettingsSection.USB_CONNECTION,
            getString(R.string.settings_usb_connection), R.drawable.ic_dp_connection) { card ->
            val currents = UsbChargingCurrent.entries
            choice(card, getString(R.string.settings_usb_charging), listOf(
                getString(R.string.settings_usb_charging_normal),
                getString(R.string.settings_usb_charging_reduced),
                getString(R.string.settings_usb_charging_low),
            ), currents.indexOf(AirPlayPersistence.loadUsbChargingCurrent(this)), reconnects = false) {
                AirPlayPersistence.saveUsbChargingCurrent(this, currents[it])
                if (!AirPlayPersistence.loadWirelessEnabled(this)) markReconnectNeeded()
            }
            card.addView(label(getString(R.string.settings_usb_charging_description), 14, MUTED))
        }
        // v100 B561: BYD ADB(车辆数据授权 / 集群路由)设置区已删除,
        // 其作用户态能力改由「仪表显示」区的 ClusterDisplayCatalog 选屏承担。
        filteredSection(content, SettingsSection.DISPLAY_AND_PERFORMANCE,
            getString(R.string.display_and_performance), R.drawable.ic_dp_display) { card ->
            val appearances = AppAppearance.entries
            choice(
                card,
                getString(R.string.settings_app_appearance),
                listOf(
                    getString(R.string.settings_app_appearance_dark),
                    getString(R.string.settings_app_appearance_light),
                    getString(R.string.settings_app_appearance_auto),
                ),
                appearances.indexOf(AirPlayPersistence.loadAppAppearance(this)),
                reconnects = false,
            ) { index ->
                AirPlayPersistence.saveAppAppearance(this, appearances[index])
                rememberLaunchAppearance()
                handler.post { checkForAppearanceChange() }
            }
            card.addView(label(getString(R.string.settings_app_appearance_description), 14, MUTED))
            val nightModes = CarPlayNightMode.entries
            val nightMode = AirPlayPersistence.loadCarPlayNightMode(this)
            val ambientControls = column().apply {
                visibility = if (nightMode == CarPlayNightMode.AMBIENT) View.VISIBLE else View.GONE
            }
            val scheduleControls = column().apply {
                visibility = if (nightMode == CarPlayNightMode.SCHEDULE) View.VISIBLE else View.GONE
            }
            choice(
                card,
                getString(R.string.carplay_night_mode),
                nightModeLabels(),
                nightModes.indexOf(nightMode),
                reconnects = false,
            ) { index ->
                AirPlayPersistence.saveCarPlayNightMode(this, nightModes[index])
                ambientControls.visibility = if (nightModes[index] == CarPlayNightMode.AMBIENT) View.VISIBLE else View.GONE
                scheduleControls.visibility = if (nightModes[index] == CarPlayNightMode.SCHEDULE) View.VISIBLE else View.GONE
                handler.post(::checkForAppearanceChange)
            }
            val sentenceSeparator = if (resources.configuration.locales[0].language == "zh") "" else " "
            card.addView(label(getString(R.string.carplay_night_hint) + sentenceSeparator +
                getString(R.string.carplay_night_time_note), 14, MUTED).apply {
                setPadding(0, 0, 0, dp(18))
            })
            ambientControls.addView(label(getString(R.string.carplay_night_ambient_hint), 14, MUTED).apply {
                setPadding(0, 0, 0, dp(18))
            })
            ambientLightThresholdControl(ambientControls)
            nightDelaySettingControl(ambientControls, R.string.ambient_delay_title, R.string.ambient_delay_hint,
                0..60, 2, R.string.ambient_delay_summary, { AirPlayPersistence.loadAmbientDelaySeconds(this) },
                save = { AirPlayPersistence.saveAmbientDelaySeconds(this, it) })
            card.addView(ambientControls)
            scheduleControls.addView(label(getString(R.string.carplay_night_schedule_hint), 14, MUTED).apply {
                setPadding(0, 0, 0, dp(18))
            })
            scheduleTimeControl(scheduleControls, start = true)
            scheduleTimeControl(scheduleControls, start = false)
            card.addView(scheduleControls)
            card.addView(button(getString(R.string.picture_adjustments), false) {
                startActivity(Intent(this, CarPlayHostActivity::class.java)
                    .putExtra("picture_controls", true).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }, matchButton(0, 56).apply { bottomMargin = dp(24) })
            carPlaySizeControl(card)
            resolutionSettingControl(
                card, R.string.resolution, R.string.custom_resolution_hint,
                CarPlayDisplayScale.MIN_PERCENT..CarPlayDisplayScale.MAX_PERCENT, 100,
                R.string.custom_resolution_summary,
                { AirPlayPersistence.loadDisplayScalePercent(this) }, reconnects = true,
                save = { AirPlayPersistence.saveDisplayScalePercent(this, it) },
            )
            choice(card, getString(R.string.frame_rate), listOf(getString(R.string.s_30_fps_lighter_load), getString(R.string.s_60_fps_smoother_motion)), if (AirPlayPersistence.loadFps(this) == 60) 1 else 0) { AirPlayPersistence.saveFps(this, if (it == 1) 60 else 30) }
            carPlayDockControl(card)
            addSystemBarControls(
                hideTopBar = AirPlayPersistence.loadHideTopBar(this),
                hideBottomBar = AirPlayPersistence.loadHideBottomBar(this),
                onHideTopBarChanged = { AirPlayPersistence.saveHideTopBar(this, it); applyFullscreenMode() },
                onHideBottomBarChanged = { AirPlayPersistence.saveHideBottomBar(this, it); applyFullscreenMode() },
            ) { label, checked, onChanged ->
                toggle(card, getString(label), getString(R.string.hide_the_car_s_system_bars_while_carplay_is_open), checked, save = onChanged)
            }
            toggle(card, getString(R.string.adapt_pip_resolution), getString(R.string.adapt_pip_resolution_description), AirPlayPersistence.loadAdaptPipResolution(this)) {
                AirPlayPersistence.saveAdaptPipResolution(this, it)
            }
            card.addView(button("${getString(R.string.settings_interface_size)} · ${InterfaceSize.displayName(this, InterfaceSize.preference(this))}", false) {
                InterfaceSize.showPicker(this)
            }, matchButton(12, 60))
            card.addView(label(getString(R.string.settings_interface_size_hint), 14, MUTED))
            if (!isNaturallyExpandedSettingsLayout) toggle(card, getString(R.string.settings_force_full_settings),
                getString(R.string.settings_force_full_settings_hint), SettingsLayoutPreferences.forceFull(this)) {
                SettingsLayoutPreferences.saveForceFull(this, it)
                render()
            }
        }
        filteredSection(content, SettingsSection.EXPERIMENTAL_DISPLAY,
            getString(R.string.settings_experimental_display), R.drawable.ic_dp_display) { card ->
            toggle(card, getString(R.string.settings_harman_fullscreen_experimental),
                getString(R.string.settings_harman_fullscreen_experimental_description),
                AirPlayPersistence.loadHarmanFullscreen(this)) { enabled ->
                AirPlayPersistence.saveHarmanFullscreen(this, enabled)
            }
            toggle(card, getString(R.string.split_screen_areas), getString(R.string.split_screen_areas_description),
                SplitScreenSettings.enabled(this)) {
                SplitScreenSettings.setEnabled(this, it)
                markReconnectNeeded()
            }
            toggle(card, getString(R.string.carplay_rotation), getString(R.string.carplay_rotation_description),
                CarPlayRotation.enabled(this)) {
                CarPlayRotation.setEnabled(this, it)
                render()
                markReconnectNeeded()
            }
            toggle(card, getString(R.string.side_panel), getString(R.string.side_panel_description), SidePanelSettings.enabled(this)) {
                SidePanelSettings.setEnabled(this, it)
                markReconnectNeeded()
            }
            if (CarPlayRotation.enabled(this)) {
                val pictures = CarPlayRotation.Picture.entries
                choice(card, getString(R.string.carplay_rotation_picture), listOf(
                    getString(R.string.carplay_rotation_smoother),
                    getString(R.string.carplay_rotation_sharper),
                ), pictures.indexOf(CarPlayRotation.picture(this)), reconnects = false) {
                    CarPlayRotation.setPicture(this, pictures[it])
                    markReconnectNeeded()
                }
            }
        }
        // Opt-in controls that can cost sound or video on some head units.
        filteredSection(content, SettingsSection.ADVANCED_MEDIA,
            getString(R.string.settings_advanced_media), R.drawable.ic_dp_advanced) { card ->
            if (Build.VERSION.SDK_INT == 32) {
                toggle(card, getString(R.string.settings_hide_byd_call_popup),
                    getString(R.string.settings_hide_byd_call_popup_description),
                    AirPlayPersistence.loadHideBydCallPopup(this)) {
                    AirPlayPersistence.saveHideBydCallPopup(this, it)
                }
            }
            toggle(card, getString(R.string.efficient_video), getString(R.string.use_hevc_leave_off_for_the_widest_head_unit_compatibility), AirPlayPersistence.loadHevcEnabled(this)) {
                AirPlayPersistence.saveHevcEnabled(this, it)
                markReconnectNeeded()
                render()
            }
            val hevc = AirPlayPersistence.loadHevcEnabled(this)
            val selectedMime = if (hevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
            val mtkDecoder = if (hevc && AirPlayPersistence.loadHevcSoftwareDecoderEnabled(this)) null
                else selectedMtkDecoderName(selectedMime)
            if (mtkDecoder != null) {
                toggle(card, getString(R.string.settings_mtk_decoder_tuning),
                    getString(R.string.settings_mtk_decoder_tuning_description, mtkDecoder),
                    AirPlayPersistence.loadMtkDecoderTuning(this)) {
                    AirPlayPersistence.saveMtkDecoderTuning(this, it)
                    markReconnectNeeded()
                }
            }
            toggle(card, getString(R.string.smooth_video), getString(R.string.smooth_video_description),
                AirPlayPersistence.loadSmoothVideo(this)) {
                AirPlayPersistence.saveSmoothVideo(this, it)
                reconnectIfRunning()
            }
            toggle(card, getString(R.string.settings_direct_video_output),
                getString(R.string.settings_direct_video_output_description),
                AirPlayPersistence.loadDirectVideoOutput(this)) {
                AirPlayPersistence.saveDirectVideoOutput(this, it)
                reconnectIfRunning()
            }
            toggle(card, getString(R.string.settings_low_latency_decoder),
                getString(R.string.settings_low_latency_decoder_description),
                AirPlayPersistence.loadLowLatencyDecoder(this)) {
                AirPlayPersistence.saveLowLatencyDecoder(this, it)
                markReconnectNeeded()
            }
            toggle(card, getString(R.string.call_echo_cancellation), getString(R.string.call_echo_cancellation_description),
                AirPlayPersistence.loadCallEchoCancellation(this)) {
                AirPlayPersistence.saveCallEchoCancellation(this, it)
                markReconnectNeeded()
            }
            toggle(card, getString(R.string.call_voice_filter), getString(R.string.call_voice_filter_description),
                AirPlayPersistence.loadCallVoiceFilter(this)) {
                AirPlayPersistence.saveCallVoiceFilter(this, it)
                markReconnectNeeded()
            }
            // v100 B561: 「手机通话音频路由」原依赖 BYD DiLink 设置区向车机音频层
            // 报备来电,随 BYD 集成删除。B561 的通话音频由车机自带 HFP 链路处理,
            // 本应用不代管,如需强制走 CarPlay 请用上方「蓝牙音频」开关。
            audioFocusControls(card)
            if (resources.getBoolean(R.bool.config_advanced_audio_channel_mapping)) {
                toggle(card, getString(R.string.advanced_audio_channel_mapping),
                    getString(R.string.use_usage_content_type_routing_instead_of_stream_type),
                    AirPlayPersistence.loadAdvancedAudioChannelMapping(this)) {
                    AirPlayPersistence.saveAdvancedAudioChannelMapping(this, it)
                    markReconnectNeeded()
                }
            }
            toggle(card, getString(R.string.main_buffered_audio), getString(R.string.main_buffered_audio_description),
                AirPlayPersistence.loadMainBufferedAudio(this)) {
                AirPlayPersistence.saveMainBufferedAudio(this, it)
                reconnectIfRunning()
            }
            toggle(card, getString(R.string.settings_speech_without_low_rates), getString(R.string.settings_speech_without_low_rates_description),
                AirPlayPersistence.loadSpeechWithout8And16Khz(this)) {
                AirPlayPersistence.saveSpeechWithout8And16Khz(this, it)
                reconnectIfRunning()
            }
            toggle(card, getString(R.string.settings_car_bluetooth_audio), getString(R.string.settings_car_bluetooth_audio_description),
                AirPlayPersistence.loadCarBluetoothAudio(this)) {
                AirPlayPersistence.saveCarBluetoothAudio(this, it)
                // 同步切断档位,免得两个开关各说各话:音频走车机蓝牙 → 不切断(OFF);
                // 音频走 CarPlay → 断 A2DP(AUDIO)。
                AirPlayPersistence.saveBluetoothScope(
                    this,
                    if (it) AirPlayPersistence.BluetoothScope.OFF
                    else AirPlayPersistence.BluetoothScope.AUDIO,
                )
                reconnectIfRunning()
            }
            // ★ v104 B561: 车机侧蓝牙机械切断档位。`/info` 的 disableBluetooth 键拦不住
            //   iPhone 建 BT profile(10-14 实测),所以要车机侧硬切。
            val scopes = AirPlayPersistence.BluetoothScope.entries
            choice(
                card,
                getString(R.string.bluetooth_cut_scope),
                listOf(
                    getString(R.string.bluetooth_cut_off),
                    getString(R.string.bluetooth_cut_audio),
                    getString(R.string.bluetooth_cut_full),
                    getString(R.string.bluetooth_cut_all),
                ),
                scopes.indexOf(
                    AirPlayPersistence.loadBluetoothScopeMigrated(
                        this, AirPlayPersistence.BluetoothScope.AUDIO,
                    ),
                ).coerceAtLeast(0),
                reconnects = false,
            ) { index ->
                AirPlayPersistence.saveBluetoothScope(this, scopes[index])
                render()
            }
            card.addView(
                label(getString(R.string.bluetooth_cut_scope_note), 14, MUTED)
                    .apply { setPadding(0, dp(NOTE_LINE_GAP_DP), 0, 0) },
            )
        }
        filteredSection(content, SettingsSection.CAR_BUTTON,
            getString(R.string.car_button_in_carplay), R.drawable.ic_dp_car) { card -> carButtonCard = card; carButtonControls(card) }
        filteredSection(content, SettingsSection.AUDIO_ROUTING,
            getString(R.string.audio_routing), R.drawable.ic_dp_audio) { card ->
            mediaChannelControl(card)
            navigationChannelControl(card)
            // ★ 上游 #752（0.2.21）：导航播报音量衰减滑块（-20~0 dB，只作用导航
            //   AudioTrack，不动音乐/通话，也不动车机系统音量）。热更新不重连。
            val gainSlider = overlaySliderRow(
                getString(R.string.settings_navigation_attenuation),
                (com.shilapi.xcertplay.media.NavigationAudioGain.MIN_DB..
                    com.shilapi.xcertplay.media.NavigationAudioGain.MAX_DB).toList(),
                AirPlayPersistence.loadNavigationAudioAttenuationDb(this),
            ) { db -> getString(R.string.settings_navigation_attenuation_value, db) }
            val gainTitle = getString(R.string.settings_navigation_attenuation)
            gainSlider.slider.contentDescription = gainTitle + ": " +
                getString(R.string.settings_navigation_attenuation_value,
                    AirPlayPersistence.loadNavigationAudioAttenuationDb(this))
            gainSlider.onSave = { db ->
                AirPlayPersistence.saveNavigationAudioAttenuationDb(this, db)
                CarPlayBackgroundSession.snapshot()?.sink?.setNavigationAttenuationDb(db)
                gainSlider.slider.contentDescription = gainTitle + ": " +
                    getString(R.string.settings_navigation_attenuation_value, db)
            }
            card.addView(gainSlider)
            card.addView(label(getString(R.string.settings_navigation_attenuation_description), 14, MUTED).apply {
                setPadding(0, dp(SETTINGS_BLOCK_GAP_DP), 0, 0)
            })
            // v100 B561: 仪表媒体卡设置放在「音频」分区 —— 媒体卡本质是播放器
            // 的一部分,跟着音频走比塞在仪表屏设置里更符合预期。
            clusterMediaCardControls(card)
            // ★ v105 B561: 导航声道是否申请音频焦点。默认关(= 不抢焦点,叠加播放)。
            toggle(card, getString(R.string.navigation_audio_focus),
                getString(R.string.navigation_audio_focus_description),
                com.shilapi.xcertplay.media.NavigationAudioFocus.requestsFocus(this)) {
                com.shilapi.xcertplay.media.NavigationAudioFocus
                    .saveRequestsFocus(this, it)
            }
            // ★ v124 B561: 导航播报时压低媒体声音。
            //   与上面那个开关不同：那个把决定权交给车机音频策略（不可控），
            //   这个是我们自己压低媒体轨道（所有车机行为一致）。
            toggle(card, getString(R.string.navigation_ducking_media),
                getString(R.string.navigation_ducking_media_description),
                com.shilapi.xcertplay.media.NavigationDuckingSetting.isEnabled(this)) {
                com.shilapi.xcertplay.media.NavigationDuckingSetting
                    .setEnabled(this, it)
                // 立刻生效，不必断开 CarPlay 重连。
                CarPlayBackgroundSession.snapshot()?.sink?.setNavigationDuckingEnabled(it)
            }
            // ===== v106 B561: 音频增强（每项独立、默认全关，互不影响）=====
            com.shilapi.xcertplay.media.AudioEnhancements.let { ae ->
                toggle(card, getString(R.string.audio_enforced_audibility),
                    getString(R.string.audio_enforced_audibility_description),
                    ae.enforcedAudibility(this)) { ae.saveEnforcedAudibility(this, it) }
                toggle(card, getString(R.string.audio_group_volume_diagnostic),
                    getString(R.string.audio_group_volume_diagnostic_description),
                    ae.groupVolumeVerbose(this)) { ae.saveGroupVolumeVerbose(this, it) }
            }
            val bufferPresets = com.shilapi.xcertplay.media.MediaAudioBuffer.presets
            choice(card, getString(R.string.music_buffer), listOf(getString(R.string.s_300_ms_default), getString(R.string.s_500_ms), getString(R.string.s_1000_ms_most_stable)),
                bufferPresets.indexOf(AirPlayPersistence.loadMediaBufferMillis(this)).coerceAtLeast(0)) {
                AirPlayPersistence.saveMediaBufferMillis(this, bufferPresets[it])
            }
        }
        filteredSection(content, SettingsSection.NAVIGATION_WHEEL,
            getString(R.string.settings_navigation_wheel_volume), R.drawable.ic_dp_audio) { card ->
            navigationWheelControls(card)
        }
        // v100 B561: 「氛围灯随音乐律动」(原 BYD AIDL → B561_SmartLightsServer)与
        // 「转向侧视摄像头」两个设置区已随 BYD 集成删除 —— 前者依赖 BYD 灯光服务,
        // 后者依赖 BYD ADB 通道。若日后要接 B561 氛围灯,应新建走
        // B561_SmartLightsServer AIDL 的独立开关,而不是复用这两块。
        filteredSection(content, SettingsSection.LOCATION,
            getString(R.string.location), R.drawable.ic_dp_navigation) { card ->
            toggle(card, getString(R.string.report_location_to_iphone),
                getString(R.string.sends_precise_android_location_as_carplay_gps_data_when_th),
                AirPlayPersistence.loadLocationReportingEnabled(this), save = ::onLocationReportingChanged)
            card.addView(label(getString(R.string.location_reporting_reconnects), 14, MUTED))
        }
        // Cluster video does not require a BYD navigation broadcast receiver.
        filteredSection(content, SettingsSection.CLUSTER_MAP,
            getString(R.string.carplay_map_on_instrument_cluster_experimental), R.drawable.ic_dp_dashboard) { card ->
            // v100 B561: ADB 集群路由(Activity 模式 / Platform 2.1 / OEM 导航锁定)
            // 整块随 BYD 集成删除 —— B561 的仪表屏由用户在下面的「选择仪表显示屏」
            // 显式指定,不走 ADB Activity 转发。
            val adbCluster = false
            val clusterDisplay = ClusterMapPresentation.findDisplay(this)
            val clusterSize = clusterDisplay?.let { ClusterMapPresentation.sizeOf(it) }
            val diLink4 = false
            val clusterMapEnabled = AirPlayPersistence.loadClusterMapEnabled(this)
            // v100 B561: 选屏控件**无条件**显示 —— 它是「用哪块屏」的入口。
            // 若也要求先开总开关才出现,用户就看不到屏列表(死锁)。
            clusterDisplayControl(card, diLink4)
            reconnectingToggle(card, getString(R.string.carplay_map_on_instrument_cluster_experimental),
                if (clusterDisplay != null || adbCluster) getString(R.string.shows_the_iphone_s_cluster_map_on_the_instrument_cluster_c)
                else getString(R.string.shows_the_iphone_s_cluster_map_virtual_stream_description),
                clusterMapEnabled) {
                AirPlayPersistence.saveClusterMapEnabled(this, it)
                render()
            }
            if (clusterMapEnabled) {
                // ★ v116: SuperUni 飞屏开关。
                //   开启时向 SuperUni 申请物理仪表通道(CAST_GATE),由它负责合成导航与心跳;
                //   关闭时退回"自己 Presentation 挂副屏"的老路。
                //   车上没装 SuperUni 时给出明确提示,免得用户以为开关没生效。
                val superUniPresent = CastGateClient.isAvailable(this)
                reconnectingToggle(card, getString(R.string.superuni_flyscreen),
                    if (superUniPresent) getString(R.string.superuni_flyscreen_description)
                    else getString(R.string.superuni_flyscreen_unavailable),
                    AirPlayPersistence.loadSuperUniFlyScreen(this),
                    enabled = superUniPresent) {
                    AirPlayPersistence.saveSuperUniFlyScreen(this, it)
                    render()
                }
                clusterSpeedometerControls(card)
                clusterUltraGaugeControls(card)
                toggle(card, getString(R.string.center_map_card),
                    if (clusterDisplay != null || adbCluster) getString(R.string.center_map_card_description)
                    else getString(R.string.center_map_card_virtual_description),
                    AirPlayPersistence.loadCenterMapOverlay(this)) {
                    AirPlayPersistence.saveCenterMapOverlay(this, it)
                    if (it && !CenterMapOverlay.permitted(this)) openOverlayPermission()
                    render()
                }
                if (AirPlayPersistence.loadCenterMapOverlay(this)) {
                    toggle(card, getString(R.string.center_map_follows_dashboard), getString(R.string.center_map_follows_dashboard_description),
                        AirPlayPersistence.loadCenterMapFollowsDashboard(this)) {
                        AirPlayPersistence.saveCenterMapFollowsDashboard(this, it)
                    }
                    toggle(card, getString(R.string.center_map_auto_hide), getString(R.string.center_map_auto_hide_description),
                        AirPlayPersistence.loadCenterMapAutoHide(this)) {
                        AirPlayPersistence.saveCenterMapAutoHide(this, it)
                    }
                }
                toggle(card, getString(R.string.launcher_map_sharing), getString(R.string.launcher_map_sharing_description),
                    AirPlayPersistence.loadLauncherMapSharing(this)) {
                    AirPlayPersistence.saveLauncherMapSharing(this, it)
                }
                if (AirPlayPersistence.loadCenterMapOverlay(this)) {
                    val overlay = CenterMapOverlay.permitted(this)
                    card.addView(label(if (overlay) getString(R.string.center_map_overlay_allowed)
                        else getString(R.string.center_map_overlay_missing, packageName), 14, if (overlay) MUTED else WARNING))
                    val usage = HomeScreenMonitor.hasAccess(this)
                    card.addView(label(if (usage) getString(R.string.center_map_auto_hide_active)
                        else getString(R.string.center_map_auto_hide_needed), 14, if (usage) MUTED else WARNING))
                    if (!usage) {
                        card.addView(button(getString(R.string.btn_auto_apply_permissions), false) { autoApplyPermissions() }, matchButton(8, 54))
                    }
                }
                // v100 B561: 恒真 —— 这层原先是 `if (clusterDisplay != null || adbCluster)`,
                // 害得「转向卡 / 路口卡 / 三卡预览」在还没选到屏时整块不显示;而选屏控件
                // 也在这一层里,用户被卡在「看不到卡、也选不了屏」的死结。
                // 卡片设置与「有没有屏」解耦:屏由 clusterDisplayControl 让用户显式选。
                if (true) {
                    // v100: DiLink 5.1 自动主题分支已随 BYD 集成删除(恒假占位保持结构)
                    if (false) {
                        // v100: DiLink 5.1 自动主题分支已随 BYD 集成删除(占位保持结构)
                    } else {
                    // v107 B561: 安全区控件**无条件**显示。上游把它挂在 diLink4 分支下
                    // (DiLink4 仪表指纹命中才出现)，但 B561 的 diLink4 恒 false ——
                    // 门控活着等于功能从来点不到。B561 走 HDMI 外接副屏 + 用户选屏，
                    // 默认矩形由 ClusterLayout.defaultSafeAreaRect(标记步进) 给出，
                    // 与 DiLink4ClusterDisplay 的那套是同构替换。
                    clusterSafeAreaControls(card)
                        val sizes = CarPlayClusterDisplay.scalePresets
                        val contents = CarPlayClusterDisplay.Content.entries
                        val content = AirPlayPersistence.loadClusterContent(this)
                        val customCard = CarPlayClusterDisplay.usesCustomTurnCard(content)
                        // v108: 转向卡的 ClusterCardPlacementPreview(FULL) 已整合进
                        // 「统一卡片预览」块；smallWindowPlacementPreview(SMALL) 保留 ——
                        // 小窗卡几何是窗内百分比，与主面板坐标不是一套体系。
                        var smallWindowPlacementPreview: ClusterCardPlacementPreview? = null
                        val officialCardOnly = content == CarPlayClusterDisplay.Content.TURN_CARD
                        choice(card, getString(R.string.dashboard_shows), listOf(
                            getString(R.string.dashboard_content_map),
                            getString(R.string.dashboard_content_turn_card),
                            getString(R.string.dashboard_content_map_with_turn_card),
                            getString(R.string.dashboard_content_map_with_custom_turn_card),
                        ), contents.indexOf(content).coerceAtLeast(0), reconnects = false) {
                            val next = contents[it]
                            val request = ++clusterContentRequestVersion
                            AirPlayPersistence.saveClusterContent(this, next)
                            render()
                            if (content.url != next.url) {
                                // The iPhone's own contents switch live. DiPlay's card over the map, and the
                                // DiLink 5.1 layout (always the map), are set up at connection, so they reconnect.
                                val controller = CarPlayBackgroundSession.snapshot()?.controller
                                if (customCard || CarPlayClusterDisplay.usesCustomTurnCard(next) ||
                                    controller == null) {
                                    markReconnectNeeded()
                                } else controller.showDashboardContent(next.url) { applied ->
                                    runOnUiThread {
                                        if (!applied && request == clusterContentRequestVersion &&
                                            !isFinishing && !isDestroyed &&
                                            AirPlayPersistence.loadClusterContent(this) == next &&
                                            CarPlayBackgroundSession.snapshot()?.controller === controller) {
                                            markReconnectNeeded()
                                        }
                                    }
                                }
                            }
                        }
                        if (customCard) {
                            // v108: 预览框与位置/大小/透明度滑块已整合到下方
                            // 「统一卡片预览」块（三卡共用一个预览 + 一行 chip）。
                            // 这里只留转向卡特有的内容属性：主题与到达信息。
                            choice(card, getString(R.string.turn_card_theme), listOf(
                                getString(R.string.turn_card_theme_auto),
                                getString(R.string.turn_card_theme_day),
                                getString(R.string.turn_card_theme_night),
                            ), AirPlayPersistence.loadClusterTurnCardTheme(this), reconnects = false) {
                                AirPlayPersistence.saveClusterTurnCardTheme(this, it)
                            }
                            // v108: 水平/垂直/大小/透明度/重置位置已移入统一卡片预览块。
                            card.addView(label(getString(R.string.turn_card_overlay_note), 14, MUTED))
                            toggle(card, getString(R.string.turn_card_show_arrival),
                                getString(R.string.turn_card_show_arrival_description),
                                AirPlayPersistence.loadClusterTurnCardShowArrival(this)) {
                                AirPlayPersistence.saveClusterTurnCardShowArrival(this, it)
                            }
                        }
                        // v100 B561: 路口放大图 —— 与转向卡同源(车道级指引 0x5204),
                        // 但版式与几何完全独立,可单独开关/摆放。
                        toggle(card, getString(R.string.cluster_junction_card),
                            getString(R.string.cluster_junction_card_description),
                            AirPlayPersistence.loadClusterJunctionCardEnabled(this)) {
                            AirPlayPersistence.saveClusterJunctionCardEnabled(this, it)
                            render()
                        }
                        // v118 B561: 车道信息卡片 —— 只画 0x5204 车道带的横幅卡。
                        //   与路口放大图是两张卡:路口图带"岔路+车道"的方块版式且
                        //   车道解不出时照样画岔路;这张只有车道带,解不出就整块隐藏。
                        toggle(card, getString(R.string.cluster_lane_card),
                            getString(R.string.cluster_lane_card_description),
                            AirPlayPersistence.loadClusterLaneCardEnabled(this)) {
                            AirPlayPersistence.saveClusterLaneCardEnabled(this, it)
                            render()
                        }
                        // v108: 路口卡的位置/大小/透明度滑块已整合到下方「统一卡片预览」块。
                        // v108 B561: 统一卡片预览 —— 一行 chip 选卡 + 一个预览框
                        // + 选中卡的位置滑块。取代原先「转向卡自带一个预览框 +
                        // 这里三卡预览框」的两个预览框结构；媒体卡/路口卡的
                        // 位置滑块也从各自分区收进来。
                        clusterCardPreviewBlock(card)
                        val turnCard = officialCardOnly
                        if (!diLink4) {
                            card.addView(overlaySliderRow(
                                getString(if (turnCard) R.string.turn_card_size else R.string.cluster_map_size),
                                sizes.sorted(),
                                AirPlayPersistence.loadClusterMapScalePercent(this),
                            ) { v -> getString(when (v) {
                                67 -> R.string.cluster_size_largest
                                83 -> R.string.cluster_size_larger
                                125 -> R.string.cluster_size_smallest
                                else -> R.string.cluster_size_standard }) }
                                .also { it.onSave = { v -> AirPlayPersistence.saveClusterMapScalePercent(this, v) } }
                                .also { it.onCommit = { markReconnectNeeded() } })
                        }
                        // v107 B561: 保存过自定义安全区框时隐藏标记偏移滑块 ——
                        // 自定义框已取代标记偏移算 safeArea(见 clusterDisplayConfig)，
                        // 留着滑块只是死控件。语义与上游 DiLink4 分支一致。
                        if (AirPlayPersistence.loadClusterSafeAreaRect(this) == null) {
                            card.addView(overlaySliderRow(
                                getString(if (turnCard) R.string.turn_card_horizontal else R.string.car_marker_horizontal),
                                CarPlayClusterDisplay.markerXPercents,
                                AirPlayPersistence.loadClusterMarkerXPercent(this),
                            ) { it -> overlayOffsetLabel(it, getString(R.string.marker_left), getString(R.string.marker_right), 50) }
                                .also { it.onSave = { v -> AirPlayPersistence.saveClusterMarkerXPercent(this, v) } }
                                .also { it.onCommit = { markReconnectNeeded() } })
                            card.addView(overlaySliderRow(
                                getString(if (turnCard) R.string.turn_card_vertical else R.string.car_marker_vertical),
                                CarPlayClusterDisplay.markerYPercents,
                                AirPlayPersistence.loadClusterMarkerYPercent(this),
                            ) { it -> overlayOffsetLabel(it, getString(R.string.marker_up), getString(R.string.marker_down), 45) }
                                .also { it.onSave = { v -> AirPlayPersistence.saveClusterMarkerYPercent(this, v) } }
                                .also { it.onCommit = { markReconnectNeeded() } })
                            card.addView(button(getString(if (turnCard) R.string.reset_turn_card_to_centre else R.string.reset_car_marker_to_centre), false) {
                                AirPlayPersistence.saveClusterMarkerXPercent(this, 50)
                                AirPlayPersistence.saveClusterMarkerYPercent(this, 45)
                                render()
                                markReconnectNeeded()
                            }, matchButton(10, 56))
                            card.addView(space(10))
                            choice(card, getString(R.string.cluster_small_window_marker), listOf(
                                getString(R.string.cluster_small_window_off),
                                getString(R.string.cluster_small_window_on),
                                getString(R.string.cluster_small_window_auto),
                            ), AirPlayPersistence.loadClusterSmallWindowMode(this)) {
                                AirPlayPersistence.saveClusterSmallWindowMode(this, it)
                                render()
                                // The description promises an immediate reconnect — the consent
                                // bar made this look broken when switching modes while driving.
                                reconnectIfRunning()
                            }
                            card.addView(label(getString(R.string.cluster_small_window_marker_description), 14, MUTED).apply {
                                setPadding(0, dp(8), 0, dp(6))
                            })
                            if (AirPlayPersistence.loadClusterSmallWindowMode(this) == 2) {
                                // v100 B561: 原「ADB 已授权」提示分支已删除(无 ADB 授权态)。
                                if (!HomeScreenMonitor.hasAccess(this)) {
                                    card.addView(label(getString(R.string.cluster_small_window_access_missing), 14, WARNING)
                                        .apply { setPadding(0, dp(4), 0, dp(4)) })
                                    card.addView(button(getString(R.string.cluster_small_window_grant_access), false) {
                                        runCatching {
                                            openSystem(Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS))
                                        }
                                    }, matchButton(6, 56))
                                } else {
                                    card.addView(label(getString(R.string.cluster_small_window_access_ok), 14, MUTED)
                                        .apply { setPadding(0, dp(4), 0, dp(4)) })
                                }
                            }
                            if (AirPlayPersistence.loadClusterSmallWindowMode(this) != 0) {
                                card.addView(overlaySliderRow(
                                    getString(R.string.cluster_small_window_horizontal),
                                    CarPlayClusterDisplay.markerXPercents,
                                    AirPlayPersistence.loadClusterSmallWindowMarkerXPercent(this),
                                ) { it -> overlayOffsetLabel(it, getString(R.string.marker_left), getString(R.string.marker_right), 50) }
                                    .also { it.onSave = { v -> AirPlayPersistence.saveClusterSmallWindowMarkerXPercent(this, v) } }
                                    .also { it.onCommit = { reconnectIfRunning() } })
                                card.addView(overlaySliderRow(
                                    getString(R.string.cluster_small_window_vertical),
                                    CarPlayClusterDisplay.markerYPercents,
                                    AirPlayPersistence.loadClusterSmallWindowMarkerYPercent(this),
                                ) { it -> overlayOffsetLabel(it, getString(R.string.marker_up), getString(R.string.marker_down), 45) }
                                    .also { it.onSave = { v -> AirPlayPersistence.saveClusterSmallWindowMarkerYPercent(this, v) } }
                                    .also { it.onCommit = { reconnectIfRunning() } })
                                if (customCard) {
                                    choice(card, getString(R.string.cluster_small_window_card_theme), listOf(
                                        getString(R.string.cluster_small_window_card_theme_follow),
                                        getString(R.string.turn_card_theme_day),
                                        getString(R.string.turn_card_theme_night),
                                    ), AirPlayPersistence.loadClusterSmallWindowCardTheme(this), reconnects = false) {
                                        AirPlayPersistence.saveClusterSmallWindowCardTheme(this, it)
                                    }

                                    card.addView(overlaySliderRow(
                                        getString(R.string.cluster_small_window_card_size),
                                        ClusterTurnCardOverlay.sizePercents,
                                        AirPlayPersistence.loadClusterSmallWindowCardSizePercent(this),
                                    ) { it -> getString(R.string.turn_card_overlay_size_option, it) }
                                        .also { it.onSave = { v -> AirPlayPersistence.saveClusterSmallWindowCardSizePercent(this, v); smallWindowPlacementPreview?.invalidate() } })
                                    card.addView(overlaySliderRow(
                                        getString(R.string.cluster_small_window_card_opacity),
                                        ClusterTurnCardOverlay.opacityPercents,
                                        AirPlayPersistence.loadClusterSmallWindowCardOpacityPercent(this),
                                    ) { it -> getString(R.string.turn_card_overlay_opacity_option, it) }
                                        .also { it.onSave = { v -> AirPlayPersistence.saveClusterSmallWindowCardOpacityPercent(this, v) } })
                                    card.addView(overlaySliderRow(
                                        getString(R.string.cluster_small_window_card_horizontal),
                                        ClusterTurnCardOverlay.smallWindowXPercents,
                                        AirPlayPersistence.loadClusterSmallWindowCardXPercent(this),
                                    ) { it -> overlayOffsetLabel(it, getString(R.string.marker_left), getString(R.string.marker_right), 50) }
                                        .also { it.onSave = { v -> AirPlayPersistence.saveClusterSmallWindowCardXPercent(this, v); smallWindowPlacementPreview?.invalidate() } })
                                    card.addView(overlaySliderRow(
                                        getString(R.string.cluster_small_window_card_vertical),
                                        ClusterTurnCardOverlay.smallWindowYPercents,
                                        AirPlayPersistence.loadClusterSmallWindowCardYPercent(this),
                                    ) { it -> overlayOffsetLabel(it, getString(R.string.marker_up), getString(R.string.marker_down), 40) }
                                        .also { it.onSave = { v -> AirPlayPersistence.saveClusterSmallWindowCardYPercent(this, v); smallWindowPlacementPreview?.invalidate() } })
                                }
                                smallWindowPlacementPreview = ClusterCardPlacementPreview(this, ClusterCardPlacementPreview.Mode.SMALL).also {
                                    card.addView(it, LinearLayout.LayoutParams(-1, dp(240)))
                                }
                                card.addView(label(getString(R.string.cluster_small_window_hint), 14, MUTED).apply { setPadding(0, dp(10), 0, 0) })
                            }
                        }
                        if (!diLink4) {
                            toggle(card, getString(R.string.dashboard_map_only_in_small_and_full_navi),
                                getString(R.string.dashboard_map_only_in_small_and_full_navi_description),
                                false) {
                                if (it) markReconnectNeeded()
                            }
                        }
                    }
                }
            }
        }
        // v100 B561: 「BYD 导航」(HUD 箭头 / FCE 集群 / 仪表歌曲投射)整块已随
        // BYD 集成删除。B561 的仪表内容走上面的「仪表显示」区(地图 / 转向卡 /
        // 媒体卡 / 路口放大图),不经过 DiLink 导航广播。
        filteredSection(content, SettingsSection.PERMISSIONS_AND_HELP,
            getString(R.string.permissions_and_connection_help), R.drawable.ic_dp_permissions) { card ->
            card.addView(label(getString(R.string.nearby_devices_connects_your_iphone_microphone_enables_sir), 16, MUTED))
            card.addView(button(getString(R.string.app_permissions), false) { openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }, matchButton(16, 60))
            card.addView(button(getString(R.string.bluetooth_settings), false) { openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }, matchButton(10, 60))
            card.addView(button(getString(R.string.wireless_connection_help), false) { wirelessHelp() }, matchButton(10, 60))
        }
        languageSettings(content)
    }

    private fun about(content: LinearLayout) {
        settingsPageTitle(content, getString(R.string.about_diplay), getString(R.string.carplay_at_home_in_your_car))
        section(content, getString(R.string.about_public_preview_prefix, version())) { card ->
            card.addView(label(getString(R.string.an_independent_carplay_receiver_for_android_head_units_wir), 17, TEXT))
            card.addView(updateRow())
            choice(card, getString(R.string.update_channel), listOf(
                getString(R.string.update_channel_github),
                getString(R.string.update_channel_gitee),
                getString(R.string.update_channel_mirror1),
                getString(R.string.update_channel_mirror2),
            ), AirPlayPersistence.loadUpdateChannel(this), reconnects = false) {
                AirPlayPersistence.saveUpdateChannel(this, it)
            }
            toggle(card, getString(R.string.settings_background_update_checks),
                getString(R.string.settings_background_update_checks_description),
                UpdateAvailability.backgroundChecksEnabled(this)) { UpdateAvailability.saveBackgroundChecksEnabled(this, it) }
        }
        section(content, getString(R.string.backup_title)) { card ->
            card.addView(button(getString(R.string.backup_open), false) { openBackup() }, matchButton(0, 60))
            card.addView(label(getString(R.string.backup_open_description), 14, MUTED).apply { setPadding(0, dp(10), 0, 0) })
        }
        section(content, getString(R.string.made_possible_by_open_source)) { card ->
            card.addView(label(getString(R.string.receiver_based_on_xcertplay_licensed_under_gpl_3_0_diplay), 16, MUTED))
        }
    }

    private enum class UpdateStage { IDLE, CHECKING, AVAILABLE, DOWNLOADING, VERIFYING, READY, FAILED }

    private fun openBackup(from: SettingsCategory = SettingsCategory.ABOUT) {
        backupOpenedFrom = from
        page = "backup"
        render()
    }

    private fun backup(content: LinearLayout) {
        val preferredPrefix = when (AirPlayPersistence.loadWirelessHotspotMode(this)) {
            WirelessHotspotMode.WIFI_P2P -> "p2p"
            WirelessHotspotMode.LOCAL_ONLY_HOTSPOT -> "ap"
            WirelessHotspotMode.MANUAL, WirelessHotspotMode.EXISTING_WIFI -> "wlan"
        }
        val addresses = SettingsBackupServer.addressCandidates(preferredPrefix)
        // The same page serves both; opened from Diagnostics, it says what the driver came for.
        val forReport = backupOpenedFrom == SettingsCategory.DIAGNOSTICS
        content.addView(label(getString(if (forReport) R.string.settings_report_on_phone else R.string.backup_title), 40, TEXT, true))
        var endpoint = backupEndpoint
        if ((backupServer == null || endpoint == null) && addresses.isEmpty()) {
            content.addView(space(SETTINGS_BLOCK_GAP_DP))
            content.addView(label(getString(R.string.backup_no_network), 16, MUTED))
            return
        }
        if (backupServer == null || endpoint == null) {
            val created = SettingsBackupServer(
                page = backupPageHtml(),
                export = { SettingsBackupCodec.export(this, version()) },
                import = { SettingsBackupCodec.import(this, it) },
                report = { diagnosticReportText(applicationContext).toByteArray(Charsets.UTF_8) },
                codePage = assets.open("settings-code.html").use { it.readBytes() },
            )
            created.onCodeEntryClosed = {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed && backupServer === created) render()
                }
            }
            created.onIdle = {
                runOnUiThread {
                    if (isFinishing || isDestroyed || backupServer !== created) return@runOnUiThread
                    stopBackupServer()
                    page = "settings"
                    settingsCategory = backupOpenedFrom
                    render()
                }
            }
            created.onImported = { outcome ->
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    backupSummary = if (outcome.invalid) getString(R.string.backup_import_invalid)
                    else getString(R.string.backup_summary, outcome.applied, outcome.skipped)
                    if (!outcome.invalid && outcome.applied > 0) markReconnectNeeded()
                    render()
                }
            }
            endpoint = created.start()
            if (endpoint == null) {
                created.stop()
                content.addView(space(SETTINGS_BLOCK_GAP_DP))
                content.addView(label(getString(R.string.backup_no_network), 16, MUTED))
                return
            }
            backupServer = created
            backupEndpoint = endpoint
            backupSummary = null
        }
        val active = endpoint
        backupServer?.landing = if (forReport) "#report" else ""
        val qr = ImageView(this).apply {
            val side = dp(240)
            setImageBitmap(SettingsQrCode.encode(active.url(addresses.first()) + if (forReport) "#report" else "", side))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        content.addView(space(SETTINGS_BLOCK_GAP_DP))
        content.addView(card().apply {
            addView(label(getString(if (forReport) R.string.settings_report_on_phone_hint else R.string.backup_page_hint), 16, MUTED))
            addView(qr, LinearLayout.LayoutParams(dp(240) + dp(24), dp(240) + dp(24)).apply {
                topMargin = dp(SETTINGS_BLOCK_GAP_DP); gravity = Gravity.CENTER_HORIZONTAL
            })
            backupSummary?.let {
                addView(label(it, 15, TEXT).apply { setPadding(0, dp(16), 0, 0) })
            }
            // For a computer, or a phone whose camera will not read the code.
            addView(label(
                if (backupServer?.codeEntryOpen == true) {
                    getString(R.string.backup_type_address, active.typedAddress(addresses.first()),
                        active.code.chunked(3).joinToString(" "))
                } else getString(R.string.backup_code_entry_closed),
                16,
                TEXT,
            ).apply { setPadding(0, dp(16), 0, 0) })
            if (addresses.size > 1) {
                addView(label(
                    addresses.drop(1).joinToString("\n") { getString(R.string.backup_other_addresses, it) },
                    14,
                    MUTED,
                ).apply { setPadding(0, dp(16), 0, 0) })
            }
        })
    }

    private fun stopBackupServer() {
        backupServer?.stop()
        backupServer = null
        backupEndpoint = null
        backupSummary = null
    }

    private fun backupPageHtml(): ByteArray = assets.open("settings-backup.html").use { it.readBytes() }

    private fun updateRow(): View {
        val container = column().apply { setPadding(0, dp(12), 0, 0) }
        updateMessage?.let { container.addView(label(it, 14, MUTED)) }
        when (updateStage) {
            UpdateStage.IDLE, UpdateStage.FAILED ->
                container.addView(button(getString(R.string.update_check), false) { checkForUpdates() },
                    matchButton(if (updateMessage == null) 0 else 10, 60))
            UpdateStage.CHECKING -> container.addView(label(getString(R.string.update_checking), 14, MUTED))
            UpdateStage.AVAILABLE -> container.addView(
                button(getString(R.string.update_download), true) { downloadUpdate() },
                matchButton(10, 60))
            UpdateStage.DOWNLOADING, UpdateStage.VERIFYING -> {
                val verifying = updateStage == UpdateStage.VERIFYING
                val percent = updateProgress?.coerceIn(0, 100)
                val title = if (verifying) getString(R.string.update_verifying)
                    else getString(R.string.update_progress_button, percent ?: 0)
                container.addView(FrameLayout(this).apply {
                    addView(button(title, true) { }.apply {
                        isEnabled = false
                        alpha = 0.7f
                        gravity = Gravity.CENTER
                        contentDescription = if (verifying) title
                            else "${getString(R.string.update_downloading)} ${percent ?: 0}%"
                    }, FrameLayout.LayoutParams(-1, -1))
                    addView(ProgressBar(this@DiPlayActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                        max = 100
                        progress = percent ?: 0
                        isIndeterminate = verifying || percent == null
                        progressTintList = ColorStateList.valueOf(ON_ACCENT)
                        progressBackgroundTintList = ColorStateList.valueOf(ON_ACCENT and 0x55ffffff)
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }, FrameLayout.LayoutParams(-1, dp(UPDATE_PROGRESS_BAR_HEIGHT_DP), Gravity.BOTTOM).apply {
                        marginStart = dp(UPDATE_PROGRESS_BAR_INSET_DP)
                        marginEnd = dp(UPDATE_PROGRESS_BAR_INSET_DP)
                        bottomMargin = dp(UPDATE_PROGRESS_BAR_BOTTOM_DP)
                    })
                }, matchButton(10, 60))
            }
            UpdateStage.READY -> {
                container.addView(
                    button(getString(R.string.update_install, updateRelease?.tagName.orEmpty()), true) { installUpdate() },
                    matchButton(10, 60))
                updateSavedPath?.let { container.addView(label(getString(R.string.update_saved_to, it), 13, MUTED).apply { setPadding(0, dp(10), 0, 0) }) }
            }
        }
        return container
    }

    private fun checkForUpdates() {
        updateStage = UpdateStage.CHECKING
        updateMessage = null
        val generation = ++updateGeneration
        render()
        Thread({
            val outcome = runCatching { latestRelease() }
            runOnUiThread {
                if (generation != updateGeneration || isFinishing || isDestroyed) return@runOnUiThread
                outcome.fold(
                    { release ->
                        UpdateAvailability.recordAttempt(this, System.currentTimeMillis())
                        availableUpdate = release
                        if (release == null) {
                            UpdateAvailability.clear(this)
                            updateMessage = getString(R.string.update_up_to_date)
                            updateStage = UpdateStage.IDLE
                        } else {
                            UpdateAvailability.save(this, release)
                            updateRelease = release
                            updateStage = UpdateStage.AVAILABLE
                        }
                        render()
                    },
                    { failure ->
                        Log.w("DiPlay-Update", "update check failed", failure)
                        updateMessage = failure.message ?: getString(R.string.update_failed)
                        updateStage = UpdateStage.FAILED
                        render()
                    },
                )
            }
        }, "diplay-update-check").start()
    }

    private fun latestRelease(): UpdateRelease? {
        return UpdateReleaseLookup.latest(version(), userAgent(), AirPlayPersistence.loadUpdateChannel(this))
    }

    private fun openAvailableUpdate(release: UpdateRelease) {
        updateRelease = release
        updateMessage = null
        updateStage = UpdateStage.AVAILABLE
        page = "settings"
        settingsCategory = SettingsCategory.ABOUT
        render()
    }

    private fun scheduleBackgroundUpdateChecks() {
        runCatching { BackgroundUpdateScheduler.schedule(applicationContext) }
            .onFailure { Log.i("DiPlay-Update", "background update scheduling unavailable", it) }
    }

    private fun downloadUpdate() {
        val selectedRelease = updateRelease ?: return
        updateStage = UpdateStage.DOWNLOADING
        updateSavedPath = null
        updateSavedUri = null
        updateProgress = null
        updateMessage = null
        val generation = ++updateGeneration
        render()
        Thread({
            val outcome = runCatching {
                // A previously verified local copy can be installed even when the release API is offline.
                val selectedCopy = synchronized(UpdateApkCache.lock(selectedRelease)) {
                    UpdateApkCache.verifiedApk(filesDir, selectedRelease)?.takeIf {
                        packageManager.getPackageArchiveInfo(it.absolutePath, 0)?.packageName == packageName
                    }
                }
                if (selectedCopy != null) return@runCatching Triple(selectedRelease, selectedCopy, true)
                // The cached release can be a day old. Download the newest one.
                val release = latestRelease() ?: throw IOException(getString(R.string.update_up_to_date))
                synchronized(UpdateApkCache.lock(release)) {
                    val cached = UpdateApkCache.verifiedApk(filesDir, release)?.takeIf {
                        packageManager.getPackageArchiveInfo(it.absolutePath, 0)?.packageName == packageName
                    }
                    if (cached != null) return@synchronized Triple(release, cached, true)
                    val directory = UpdateApkCache.directory(filesDir, release)
                    if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Update storage is unavailable")
                    val stagedChecksums = File(directory, "${UpdateCatalog.CHECKSUMS_FILE}.partial")
                    val stagedApk = File(directory, ".${release.apkName}.partial.apk")
                    try {
                        UpdateClient.download(release.checksumsUrl, stagedChecksums) { _, _ -> }
                        UpdateClient.download(release.apkUrl, stagedApk) { written, total ->
                            val percent = total?.takeIf { it > 0 }?.let { (written * 100 / it).toInt() } ?: return@download
                            if (percent != updateProgress) {
                                updateProgress = percent
                                refreshUpdateUi(generation)
                            }
                        }
                        updateStage = UpdateStage.VERIFYING
                        refreshUpdateUi(generation)
                        val expected = UpdateChecksums.parse(stagedChecksums.readText())
                        val actual = UpdateChecksums.sha256Hex(stagedApk)
                        if (!UpdateChecksums.matches(expected, release.apkName, actual)) {
                            throw IOException("Checksum mismatch for ${release.apkName}")
                        }
                        val archivePackage = packageManager.getPackageArchiveInfo(stagedApk.absolutePath, 0)?.packageName
                        if (archivePackage != packageName) {
                            throw IOException("Update APK belongs to a different app")
                        }
                        Triple(release, UpdateApkCache.publish(filesDir, release, stagedApk, stagedChecksums), false)
                    } finally {
                        stagedApk.delete()
                        stagedChecksums.delete()
                    }
                }
            }
            val saved = outcome.getOrNull()?.takeUnless { it.third }?.let { UpdateApkExport.copy(applicationContext, it.second) }
            runOnUiThread {
                if (generation != updateGeneration || isFinishing || isDestroyed) return@runOnUiThread
                outcome.fold(
                    { (release, file, _) ->
                        updateRelease = release
                        updateFile = file
                        updateSavedPath = saved?.path
                        updateSavedUri = saved?.uri
                        updateStage = UpdateStage.READY
                        render()
                        installApk(file)
                    },
                    { failure ->
                        Log.w("DiPlay-Update", "update download failed", failure)
                        updateMessage = failure.message ?: getString(R.string.update_failed)
                        updateStage = UpdateStage.FAILED
                        render()
                    },
                )
            }
        }, "diplay-update-download").start()
    }

    private fun installUpdate() {
        // No unknown-sources pre-check: some head units answer its settings page with "Not supported yet".
        // The chosen installer asks for that permission itself.
        installApk(updateFile ?: return)
    }

    private fun installApk(file: File) {
        // The public copy lets file managers such as MT Manager open the APK. The private provider URI is the fallback,
        // including when the driver deleted the public copy.
        // The public copy is outside the app's control, so it must still match the verified file. Hashing two
        // APKs takes seconds on a slow head unit, so it runs off the main thread.
        val savedUri = updateSavedUri
        val generation = updateGeneration
        updateInstallWorker(Runnable {
            val publicUri = savedUri?.takeIf { sameContent(it, file) }
            runOnUiThread {
                if (generation == updateGeneration && !isFinishing && !isDestroyed) startInstaller(file, publicUri)
            }
        })
    }

    private fun startInstaller(file: File, publicUri: Uri?) {
        val uri = publicUri ?: FileProvider.getUriForFile(this, "$packageName.update-apks", file)
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // Ask only when several installers exist. A lone system installer opens directly, as before.
        val installers = packageManager.queryIntentActivities(view, PackageManager.MATCH_DEFAULT_ONLY).size
        val launch = if (installers > 1) Intent.createChooser(view, null) else view
        val outcome = "${if (installers > 1) "chooser" else "direct"} uri=${if (publicUri != null) "public" else "provider"} installers=$installers"
        try {
            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Log.i("DiPlay-Update", "install started: $outcome")
            UpdateAvailability.recordInstall(applicationContext, System.currentTimeMillis(), outcome)
        } catch (failure: RuntimeException) {
            Log.w("DiPlay-Update", "install failed to start: $outcome", failure)
            UpdateAvailability.recordInstall(applicationContext, System.currentTimeMillis(), "$outcome failed=${failure.javaClass.simpleName}")
            updateMessage = getString(R.string.update_failed)
            render()
        }
    }

    private fun sameContent(uri: Uri, verified: File): Boolean = runCatching {
        val copy = contentResolver.openInputStream(uri)?.use { UpdateChecksums.sha256Hex(it) }
        copy != null && copy == UpdateChecksums.sha256Hex(verified)
    }.getOrDefault(false)

    private fun refreshUpdateUi(generation: Int) {
        runOnUiThread {
            if (generation != updateGeneration || isFinishing || isDestroyed ||
                page != "settings" || settingsCategory != SettingsCategory.ABOUT) return@runOnUiThread
            render()
        }
    }

    private fun userAgent() = "DiPlay/${version()}"

    // An opted-in connection prepares the hotspot in the controller instead of stopping at this reminder.
    private fun carHotspotOff(): Boolean =
        AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL &&
            com.shilapi.xcertplay.network.CarHotspotStatus.isEnabled(this) == false &&
            !(CarHotspotSettings.enabled(this) && CarHotspotTethering.permitted(this))

    // v100 B561: bydAdbSettings(BYD ADB 车辆数据授权区)已随 BYD 集成删除。

    // v100 B561: renderBydAdbControls(BYD ADB 开关组)随 BYD 集成删除。

    private fun adbToggle(parent: LinearLayout, title: Int, description: Int, read: () -> Boolean,
        needsAdb: () -> Boolean = { true },
        permissions: () -> List<CarHotspotSetup.Permission> = { emptyList() }, save: (Boolean) -> Unit) {
        val control = toggle(parent, getString(title), getString(description), read(),
            enabled = true) { enabled ->
            if (updatingAdbSwitches || adbSwitchChangePending) return@toggle
            if (enabled && needsAdb()) requestAdbSwitchChange(permissions()) { save(true) }
            else save(enabled)
        }
        adbSwitches[title] = control to read
    }

    private fun requestAdbSwitchChange(permissions: List<CarHotspotSetup.Permission>, save: () -> Unit) {
        if (adbSwitchChangePending || vehicleAdbWorkInProgress()) {
            updateAdbSwitches()
            return
        }
        cancelAutomaticVehicleValidationForUserOperation(
            resumeAfter = automaticVehicleValidationInProgress || automaticVehicleValidationPending,
        )
        val app = applicationContext
        adbSwitchChangePending = true
        updateAdbSwitches()
        adbStatus?.setText(R.string.adb_checking_may_ask)
        if (page == "settings" && bydVehicleAdvancedExpanded) render()
        Thread({
            var access = LocalAdb.Access.UNREACHABLE
            val ready = runCatching {
                access = CarHotspotSetup.grant(app, permissions)
                access == LocalAdb.Access.READY && permissions.all { it.granted(app) }
            }.getOrDefault(false)
            runOnUiThread {
                adbSwitchChangePending = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                val message = if (ready) {
                    if (permissions.isEmpty()) R.string.adb_access_ready else R.string.hotspot_permission_granted
                } else when (access) {
                    LocalAdb.Access.NOT_APPROVED -> R.string.adb_not_approved
                    LocalAdb.Access.UNREACHABLE -> R.string.adb_off
                    LocalAdb.Access.UNSUPPORTED -> R.string.adb_pairing_only
                    else -> R.string.hotspot_permission_failed
                }
                adbStatus?.setText(if (ready) R.string.adb_access_ready else message)
                if (ready) save()
                updateAdbSwitches()
                toast(getString(message))
                runPendingAutomaticVehicleValidation()
                // Re-enable the vehicle controls disabled while this authorization was outstanding.
                if (page == "settings" && bydVehicleAdvancedExpanded) render()
            }
        }, "diplay-adb-switch").start()
    }

    // v100 B561: 自动车辆数据验证随 BYD 集成删除 —— 三个入口变空实现，
    // 保留签名是为了让启动/授权流程的调用点不必再改。
    private fun cancelAutomaticVehicleValidationForUserOperation(resumeAfter: Boolean) = Unit
    private fun runPendingAutomaticVehicleValidation() = Unit
    private fun vehicleAdbWorkInProgress(): Boolean = false

    private fun updateAdbSwitches() {
        updatingAdbSwitches = true
        for ((control, read) in adbSwitches.values) {
            control.isChecked = read()
            control.isEnabled = !adbSwitchChangePending && !vehicleAdbWorkInProgress()
        }
        updatingAdbSwitches = false
    }


    private fun startCarHotspotOnLaunch() {
        if (!startupHotspotEligible()) return
        val app = applicationContext
        Thread({
            val result = CarHotspotTethering.enable(app,
                isCancelled = { startupHotspotCancelled || !startupHotspotEligible() },
                log = { Log.i("DiPlay-Hotspot", it) },
            )
            runOnUiThread {
                if (isFinishing || isDestroyed || result == CarHotspotTethering.Result.CANCELLED) return@runOnUiThread
                hotspotStartupResult = result
                if (page == "home") render()
            }
        }, "diplay-hotspot-startup").start()
    }

    private fun startupHotspotEligible(): Boolean =
        CarHotspotSetup.shouldStartOnLaunch(applicationContext, CarPlayBackgroundSession.hasSession())

    private fun hotspotResultText(result: CarHotspotTethering.Result): String = getString(when (result) {
        CarHotspotTethering.Result.READY -> R.string.hotspot_control_on
        CarHotspotTethering.Result.PERMISSION_REQUIRED -> R.string.hotspot_control_missing
        CarHotspotTethering.Result.UNSUPPORTED -> R.string.hotspot_control_unsupported
        CarHotspotTethering.Result.TIMED_OUT -> R.string.hotspot_control_timeout
        else -> R.string.hotspot_control_failed
    })

    private fun carHotspotOffDialog() {
        appDialogBuilder().setTitle(getString(R.string.car_hotspot_is_off))
            .setMessage(getString(R.string.msg_car_hotspot_connect, AirPlayPersistence.loadManualHotspotSsid(this)))
            .setPositiveButton(getString(R.string.open_car_settings)) { _, _ -> openCarWifiSettings() }
            .setNeutralButton(getString(R.string.connect)) { _, _ -> connect(true) }
            .setNegativeButton(getString(R.string.cancel), null).show()
    }

    // v100 B561: repairBootStart(用 ADB 修开机自启白名单)随 BYD 集成删除。

    private fun openCarWifiSettings() {
        val hotspot = Intent("com.android.settings.WIFI_TETHER_SETTINGS")
        val target = packageManager.resolveActivity(hotspot, 0)?.activityInfo?.packageName
        if (target == null) {
            openSystem(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            return
        }
        if (target == "com.byd.carsettings") {
            runCatching { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)) }
        }
        if (runCatching { startActivity(hotspot) }.isSuccess) return
        openSystem(Intent(Settings.ACTION_WIRELESS_SETTINGS))
    }

    private fun openCarClientWifiSettings() {
        val wifi = Intent(Settings.ACTION_WIFI_SETTINGS)
        if (packageManager.resolveActivity(wifi, 0)?.activityInfo?.packageName == "com.byd.carsettings") {
            runCatching { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)) }
        }
        openSystem(wifi)
    }

    private fun openSetupGuide(fromSettings: Boolean) {
        setupFromSettings = fromSettings
        setupStep = SetupGuide.STEP_CAR
        page = "setup"
        render()
    }

    private fun closeSetupGuide(connectNow: Boolean = false) {
        SetupGuide.markSeen(this)
        page = if (setupFromSettings) "settings" else "home"
        setupFromSettings = false
        setupStep = SetupGuide.STEP_CAR
        render()
        if (connectNow) connect(AirPlayPersistence.loadWirelessEnabled(this))
    }

    private fun dilinkLabel(generation: DiLinkGeneration) = getString(when (generation) {
        DiLinkGeneration.DILINK_3 -> R.string.dilink_3
        DiLinkGeneration.DILINK_4 -> R.string.dilink_4
        DiLinkGeneration.DILINK_5 -> R.string.dilink_5
        DiLinkGeneration.UNKNOWN -> R.string.dilink_not_sure
    })

    private fun setupGuide(content: LinearLayout) {
        val step = setupStep.coerceIn(SetupGuide.STEP_CAR, SetupGuide.STEP_DONE)
        content.addView(label(getString(R.string.setup_step_of, step + 1, SetupGuide.STEP_COUNT), 13, ACCENT, true)
            .apply { letterSpacing = .12f })
        val progress = row().apply { setPadding(0, dp(10), 0, dp(20)) }
        repeat(SetupGuide.STEP_COUNT) { index ->
            progress.addView(View(this).apply {
                background = GradientDrawable().apply {
                    setColor(if (index <= step) ACCENT else BUTTON); cornerRadius = dp(3).toFloat()
                }
            }, LinearLayout.LayoutParams(0, dp(6), 1f).apply { if (index > 0) marginStart = dp(8) })
        }
        content.addView(progress)
        when (step) {
            SetupGuide.STEP_CAR -> setupCarStep(content)
            SetupGuide.STEP_CONNECTION -> setupConnectionStep(content)
            SetupGuide.STEP_IPHONE -> setupIphoneStep(content)
            SetupGuide.STEP_FEATURES -> setupFeaturesStep(content)
            else -> setupDoneStep(content)
        }
        if (step == SetupGuide.STEP_DONE) return
        val footer = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, 0) }
        footer.addView(button(getString(R.string.setup_skip), false) { closeSetupGuide() },
            LinearLayout.LayoutParams(0, dp(60), 1f))
        footer.addView(space(16), LinearLayout.LayoutParams(dp(16), 1))
        footer.addView(button(getString(R.string.setup_next), true) {
            if (step == SetupGuide.STEP_CAR && SetupGuide.applyFirstSetupDefaults(this,
                    DiLinkGeneration.confirmed(this) ?: DiLinkGeneration.detect().generation,
                    phoneChosen = DiPlayPreferences.phoneAddress(this) != null)) markReconnectNeeded()
            setupStep = step + 1; render()
        },
            LinearLayout.LayoutParams(0, dp(60), 1f))
        content.addView(footer)
    }

    private fun setupTitle(content: LinearLayout, title: Int, description: String) {
        content.addView(label(getString(title), 34, TEXT, true))
        content.addView(label(description, 17, MUTED).apply { setPadding(0, dp(8), 0, dp(24)) })
    }

    private fun setupCarStep(content: LinearLayout) {
        val detection = DiLinkGeneration.detect()
        val selected = DiLinkGeneration.confirmed(this) ?: detection.generation
        setupTitle(content, R.string.setup_car_title, when (detection.source) {
            DiLinkGeneration.Source.SYSTEM_VERSION -> getString(R.string.setup_car_detected, dilinkLabel(detection.generation))
            DiLinkGeneration.Source.LIKELY -> getString(R.string.setup_car_likely, dilinkLabel(detection.generation))
            DiLinkGeneration.Source.NONE -> getString(R.string.setup_car_unknown)
        })
        val card = card()
        card.addView(label(getString(R.string.setup_car_choose), 22, TEXT, true))
        val wide = resources.configuration.screenWidthDp >= 850
        val choices = if (wide) row() else column()
        DiLinkGeneration.entries.forEachIndexed { index, generation ->
            val chosen = generation == selected
            choices.addView(button("${if (chosen) "✓  " else ""}${dilinkLabel(generation)}", chosen) {
                DiLinkGeneration.save(this, generation)
                render()
            }, if (wide) LinearLayout.LayoutParams(0, dp(60), 1f).apply {
                topMargin = dp(12); if (index > 0) marginStart = dp(12)
            } else matchButton(12, 60))
        }
        card.addView(choices)
        detection.evidence?.let {
            card.addView(label(getString(R.string.setup_car_evidence, it), 13, MUTED).apply { setPadding(0, dp(16), 0, 0) })
        }
        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(SETTINGS_BLOCK_GAP_DP) })
    }

    private fun setupConnectionStep(content: LinearLayout) {
        setupTitle(content, R.string.setup_connection_title, getString(R.string.setup_connection_description))
        val card = card()
        wirelessLinkControls(card)
        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(SETTINGS_BLOCK_GAP_DP) })
    }

    private fun setupIphoneStep(content: LinearLayout) {
        setupTitle(content, R.string.setup_iphone_title, getString(R.string.keep_bluetooth_and_wi_fi_on_your_iphone_pair_with_the_car))
        val card = card()
        val chosen = DiPlayPreferences.phoneAddress(this) != null
        card.addView(label(if (chosen) DiPlayPreferences.phoneName(this) else getString(R.string.setup_iphone_none), 22,
            if (chosen) READY else TEXT, true))
        card.addView(button("${getString(R.string.choose_iphone_prefix)}${DiPlayPreferences.phoneName(this)}", !chosen) {
            choosePhone()
        }, matchButton(16, 60))
        card.addView(button(getString(R.string.review_app_permissions), false) {
            openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }, matchButton(12, 60))
        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(SETTINGS_BLOCK_GAP_DP) })
    }

    private fun setupFeaturesStep(content: LinearLayout) {
        val generation = DiLinkGeneration.current(this)
        setupTitle(content, R.string.setup_features_title, getString(R.string.setup_features_description,
            dilinkLabel(generation)))
        if (SetupGuide.hasConflictingClusterRoute(generation, AirPlayPersistence.loadAdbClusterEnabled(this))) {
            val warning = card()
            warning.addView(label(getString(R.string.setup_cluster_route_conflict), 16, WARNING))
            warning.addView(button(getString(R.string.setup_cluster_route_turn_off), true) {
                AirPlayPersistence.saveAdbClusterEnabled(this, false)
                markReconnectNeeded()
                render()
            }, matchButton(12, 56))
            content.addView(warning, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(SETTINGS_BLOCK_GAP_DP) })
        }
        val card = card()
        SetupGuide.features(generation).forEach { entry ->
            val before = card.childCount
            when (entry.feature) {
                SetupGuide.Feature.AUTO_CONNECT -> toggle(card, getString(R.string.connect_when_diplay_opens),
                    getString(R.string.default_connection_description), DiPlayPreferences.autoConnect(this)) {
                    DiPlayPreferences.saveAutoConnect(this, it)
                }
                // v100 B561: BYD_NAVIGATION 特性项已随 BYD 集成删除,保留空分支
                // 只是为了让 when 穷尽(BYD_NAVIGATION 不会再出现在 features 里)。
                SetupGuide.Feature.BYD_NAVIGATION -> Unit
                SetupGuide.Feature.LOCATION -> toggle(card, getString(R.string.report_location_to_iphone),
                    getString(R.string.sends_precise_android_location_as_carplay_gps_data_when_th),
                    AirPlayPersistence.loadLocationReportingEnabled(this), save = ::onLocationReportingChanged)
                SetupGuide.Feature.CLUSTER_MAP -> reconnectingToggle(card,
                    getString(R.string.carplay_map_on_instrument_cluster_experimental),
                    getString(R.string.shows_the_iphone_s_cluster_map_on_the_instrument_cluster_c),
                    AirPlayPersistence.loadClusterMapEnabled(this)) {
                    AirPlayPersistence.saveClusterMapEnabled(this, it)
                    render()
                }
                SetupGuide.Feature.DILINK4_ADB_CLUSTER -> reconnectingToggle(card,
                    getString(R.string.adb_cluster_activity_mode), getString(R.string.adb_cluster_activity_description),
                    AirPlayPersistence.loadAdbClusterEnabled(this)) {
                    AirPlayPersistence.saveAdbClusterEnabled(this, it)
                    render()
                }
                // v100 B561: 引导向导里的「BYD 导航」特性项随 BYD 集成删除。
                // v100 B561: 引导向导的「BYD 导航」特性项已随 BYD 集成删除。
                SetupGuide.Feature.CALLS_ON_DASHBOARD -> toggle(card, getString(R.string.carplay_calls_on_dashboard),
                    getString(R.string.carplay_calls_on_dashboard_description),
                    AirPlayPersistence.loadCarPlayCalls(this)) { applyCarPlayCalls(it) }
                SetupGuide.Feature.CALL_KEYS -> toggle(card, getString(R.string.carplay_call_controls_experimental),
                    getString(R.string.carplay_call_controls_experimental_description),
                    AirPlayPersistence.loadCarPlayCallControls(this)) { applyCallKeys(it) }
            }
            if (card.childCount > before) card.addView(setupBadges(entry))
        }
        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(SETTINGS_BLOCK_GAP_DP) })
    }

    private fun setupBadges(entry: SetupGuide.Entry): LinearLayout = row().apply {
        setPadding(0, dp(2), 0, dp(14))
        fun badge(text: String, color: Int) = label(text, 12, color, true).apply {
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT); cornerRadius = dp(10).toFloat(); setStroke(dp(1), color)
            }
            setPadding(dp(10), dp(3), dp(10), dp(3))
        }
        val tested = entry.status == SetupGuide.Status.TESTED
        addView(badge(getString(if (tested) R.string.setup_badge_tested else R.string.setup_badge_experimental),
            if (tested) READY else WARNING), LinearLayout.LayoutParams(-2, -2))
        if (entry.needsAdb) addView(badge(getString(R.string.setup_badge_adb), ACCENT),
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
    }

    private fun setupDoneStep(content: LinearLayout) {
        setupTitle(content, R.string.setup_done_title, getString(R.string.setup_done_description))
        val card = card()
        card.addView(button(getString(R.string.connect_phone), true) { closeSetupGuide(connectNow = true) }, matchButton(0, 60))
        card.addView(button(getString(R.string.settings), false) {
            SetupGuide.markSeen(this)
            setupFromSettings = false
            setupStep = SetupGuide.STEP_CAR
            page = "settings"
            render()
        }, matchButton(12, 60))
        card.addView(button(getString(R.string.done), false) { closeSetupGuide() }, matchButton(12, 60))
        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(SETTINGS_BLOCK_GAP_DP) })
    }

    private fun connectionSetup(content: LinearLayout) {
        content.addView(label(getString(R.string.connection_setup), 34, TEXT, true))
        content.addView(label(getString(R.string.set_up_once_your_details_stay_saved_for_the_next_drive_cha), 17, MUTED).apply { setPadding(0, dp(8), 0, dp(24)) })
        section(content, getString(R.string.s_1_choose_your_connection)) { card -> wirelessLinkControls(card) }
        section(content, getString(R.string.s_2_pair_your_iphone)) { card ->
            card.addView(label(getString(R.string.keep_bluetooth_and_wi_fi_on_your_iphone_pair_with_the_car), 16, MUTED))
            card.addView(button("${getString(R.string.choose_iphone_prefix)}${DiPlayPreferences.phoneName(this)}", false) { choosePhone() }, matchButton(12, 60))
            card.addView(button(getString(R.string.review_app_permissions), false) {
                openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }, matchButton(12, 60))
        }
        section(content, getString(R.string.s_3_connect)) { card ->
            card.addView(label(getString(R.string.return_from_car_settings_to_diplay_then_connect_accept_the), 16, MUTED))
            card.addView(button(getString(R.string.connect_phone), true) { connect(true) }, matchButton(12, 60))
        }
        section(content, getString(R.string.prefer_a_cable)) { card ->
            card.addView(label(getString(R.string.use_a_usb_data_cable_and_the_car_s_usb_data_port_unlock_yo), 16, MUTED))
            card.addView(button(getString(R.string.connect_with_usb), false) { connect(false) }, matchButton(12, 60))
        }
    }

    private fun wirelessLinkControls(parent: LinearLayout) {
        val mode = if (pendingCarHotspotSetup) WirelessHotspotMode.MANUAL else AirPlayPersistence.loadWirelessHotspotMode(this)
        val modes = listOf(WirelessHotspotMode.MANUAL, WirelessHotspotMode.WIFI_P2P, WirelessHotspotMode.EXISTING_WIFI)
        val titles = listOf(getString(R.string.settings_built_in_car_hotspot_recommended), getString(R.string.wifi_direct), getString(R.string.existing_wifi_title))
        val descriptions = listOf(
            getString(R.string.hotspot_mode_manual_desc),
            getString(R.string.hotspot_mode_p2p_desc),
            getString(R.string.existing_wifi_description)
        )
        val wide = resources.configuration.screenWidthDp >= 850
        val choices = if (wide) row().apply { gravity = Gravity.TOP } else column()
        parent.addView(choices)
        modes.forEachIndexed { index, candidate ->
            val option = column()
            choices.addView(option, if (wide) LinearLayout.LayoutParams(0, -2, 1f).apply {
                if (index > 0) marginStart = dp(16)
            } else LinearLayout.LayoutParams(-1, -2))
            option.addView(button("${if (mode == candidate) "✓  " else ""}${titles[index]}", mode == candidate) {
                if (candidate == WirelessHotspotMode.MANUAL) {
                    pendingCarHotspotSetup = true
                    render()
                } else if (candidate == WirelessHotspotMode.EXISTING_WIFI) {
                    askHotspotCredentials(existingWifi = true) { ssid, password, _ ->
                        AirPlayPersistence.saveExistingWifiCredentials(this, ssid, password)
                        pendingCarHotspotSetup = false
                        applyWirelessLink(candidate)
                    }
                } else {
                    pendingCarHotspotSetup = false
                    applyWirelessLink(candidate)
                }
            }, matchButton(12, 60))
            option.addView(label(descriptions[index], 15, MUTED).apply { setPadding(0, dp(6), 0, dp(12)) })
        }
        if (mode == WirelessHotspotMode.MANUAL) {
            parent.addView(label(getString(R.string.hotspot_setup), 22, TEXT, true))
            parent.addView(label(getString(R.string.s_1_open_car_hotspot_settings_turn_the_hotspot_on_and_sele), 16, MUTED).apply { setPadding(0, dp(8), 0, dp(12)) })
            parent.addView(button(getString(R.string.open_car_hotspot_settings), false) { openCarWifiSettings() }, matchButton(0, 60))
            parent.addView(button(if (pendingCarHotspotSetup) getString(R.string.save_hotspot_details_and_use_this_mode) else "${getString(R.string.edit_saved_hotspot_prefix)}${storedSsid()}", false) {
                askHotspotCredentials { ssid, password, security ->
                    saveHotspotCredentials(ssid, password, security)
                    pendingCarHotspotSetup = false
                    applyWirelessLink(WirelessHotspotMode.MANUAL)
                }
            }, matchButton(12, 60))
            // v107 B561: 自动开热点开关。上游把它挂在 BYD 门控下
            // (CarHotspotSetup.isBydHeadUnit，B561 恒 false)，开关永远不显示；
            // 但运行时链路(CarPlayController → CarHotspotTethering 的
            // startTethering 反射 + ADB 兜底)本身不依赖 BYD，放开入口即可用。
            // 生效条件：无线连接 + 手册热点模式(见 CarHotspotSettings.shouldEnable)。
            toggle(
                parent,
                getString(R.string.auto_car_hotspot_title),
                getString(R.string.auto_car_hotspot_description),
                CarHotspotSettings.enabled(this),
            ) { enabled -> CarHotspotSettings.setEnabled(this, enabled) }
            // startTethering 反射需要 WRITE_SETTINGS；平台签名应用通常自带，
            // 缺权限时引导去系统设置开(不改动已保存的热点名/密码)。
            if (!CarHotspotTethering.permitted(this)) {
                parent.addView(label(getString(R.string.auto_car_hotspot_permission_hint), 15, WARNING).apply {
                    setPadding(0, dp(8), 0, 0)
                })
                parent.addView(button(getString(R.string.settings), false) {
                    startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
                }, matchButton(12, 56))
            }
            parent.addView(label(if (pendingCarHotspotSetup) getString(R.string.finish_setup_save_your_hotspot_details_to_use_this_mode) else if (carHotspotOff()) getString(R.string.hotspot_details_off) else getString(R.string.hotspot_details_saved), 15, if (carHotspotOff()) WARNING else MUTED).apply { setPadding(0, dp(12), 0, 0) })
            val join = hotspotJoinControls ?: HotspotJoinControls(this,
                { CarPlayBackgroundSession.hasSession() }, beforeAction = { startupHotspotCancelled = true },
                labelFactory = { label(it, 15, MUTED) }, buttonFactory = { title, click -> button(title, false, click) })
                .also { hotspotJoinControls = it }
            parent.addView(join.build())
        } else if (mode == WirelessHotspotMode.EXISTING_WIFI) {
            parent.addView(label(getString(R.string.existing_wifi_instructions), 16, MUTED))
            parent.addView(button(getString(R.string.open_car_wi_fi_settings), false) { openCarClientWifiSettings() }, matchButton(12, 60))
            parent.addView(button(getString(R.string.existing_wifi_details), false) {
                askHotspotCredentials(existingWifi = true) { ssid, password, _ ->
                    AirPlayPersistence.saveExistingWifiCredentials(this, ssid, password)
                    toast(getString(R.string.saved_for_your_next_connection))
                }
            }, matchButton(12, 60))
        } else {
            parent.addView(label(getString(R.string.turn_the_car_s_wi_fi_switch_on_allow_location_nearby_devic), 16, MUTED))
            wifiDirectChannelControl(parent)
            parent.addView(button(getString(R.string.open_car_wi_fi_settings), false) { openCarClientWifiSettings() }, matchButton(12, 60))
        }
    }

    private fun wifiDirectChannelLabel(channel: Int): String = if (channel == WifiP2pChannels.AUTO) {
        getString(R.string.auto)
    } else if (channel == WifiP2pChannels.AUTO_5_GHZ || channel == WifiP2pChannels.AUTO_2_4_GHZ) {
        getString(R.string.settings_wifi_direct_auto_band,
            getString(if (channel == WifiP2pChannels.AUTO_5_GHZ) R.string.s_5_ghz else R.string.s_2_4_ghz))
    } else {
        getString(R.string.wifi_direct_channel_choice, channel,
            getString(if (channel < 36) R.string.s_2_4_ghz else R.string.s_5_ghz))
    }

    private fun wifiDirectChannelControl(parent: LinearLayout) {
        val summary: (Int) -> String = {
            getString(R.string.wifi_direct_channel_summary, wifiDirectChannelLabel(it))
        }
        val control = button(summary(AirPlayPersistence.loadWifiP2pPreferredChannel(this)), false) {}
        control.setOnClickListener {
            val choices = listOf(WifiP2pChannels.AUTO) + WifiP2pChannels.bandChoices + WifiP2pChannels.channels
            val current = AirPlayPersistence.loadWifiP2pPreferredChannel(this)
            var selection = current
            appDialogBuilder().setTitle(R.string.wifi_direct_channel_title)
                .setSingleChoiceItems(choices.map(::wifiDirectChannelLabel).toTypedArray(),
                    choices.indexOf(current)) { _, which -> selection = choices[which] }
                .setPositiveButton(R.string.save) { _, _ ->
                    if (selection != current) {
                        AirPlayPersistence.saveWifiP2pPreferredChannel(this, selection)
                        control.text = summary(selection)
                        toast(getString(R.string.saved_for_your_next_connection))
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        parent.addView(control, matchButton(12, 60))
        parent.addView(label(getString(R.string.wifi_direct_channel_description), 15, MUTED).apply {
            setPadding(0, dp(6), 0, dp(6))
        })
        parent.addView(label(getString(R.string.settings_wifi_direct_band_note), 15, MUTED).apply {
            setPadding(0, 0, 0, dp(12))
        })
    }

    private fun mediaChannelControl(parent: LinearLayout) {
        val summary: (Int) -> String = {
            getString(R.string.contrib_audio_home_choice_summary, getString(R.string.contrib_audio_home_media_channel_label), channelLabel(it))
        }
        val control = button(summary(AirPlayPersistence.loadMediaAudioChannel(this)), false) {}
        control.setOnClickListener {
            val current = AirPlayPersistence.loadMediaAudioChannel(this)
            showChannelDialog(
                title = getString(R.string.contrib_audio_home_media_channel_label),
                current = current,
                navigation = false,
                onApply = { value -> applyMediaChannel(value, current, control, summary) },
            )
        }
        parent.addView(control, matchButton(12, 60))
    }

    private fun navigationChannelControl(parent: LinearLayout) {
        val summary: (Int) -> String = {
            getString(R.string.contrib_audio_home_choice_summary, getString(R.string.contrib_audio_home_nav_channel_label), channelLabel(it))
        }
        val control = button(summary(AirPlayPersistence.loadNavigationAudioChannel(this)), false) {}
        control.setOnClickListener {
            val current = AirPlayPersistence.loadNavigationAudioChannel(this)
            showChannelDialog(
                title = getString(R.string.contrib_audio_home_nav_channel_label),
                current = current,
                navigation = true,
                onApply = { value -> applyNavigationChannel(value, current, control, summary) },
            )
        }
        parent.addView(control, matchButton(10, 60))
        parent.addView(label(getString(R.string.contrib_audio_home_nav_channel_note), 14, MUTED).apply {
            setPadding(0, dp(8), 0, dp(18))
        })
    }

    private fun showChannelDialog(title: String, current: Int, navigation: Boolean, onApply: (Int) -> Unit) {
        val preview = AudioChannelPreview { channel ->
            toast(getString(R.string.contrib_audio_home_channel_preview_unavailable, channel))
        }
        val channels = AirPlayPersistence.AUDIO_CHANNELS
        val labels = channels.map(Int::toString).toTypedArray()
        var selection = current.coerceIn(channels.first, channels.last)
        appDialogBuilder().setTitle(title)
            .setSingleChoiceItems(labels, selection) { _, which ->
                selection = which
                preview.play(which, navigation)
            }
            .setPositiveButton(if (CarPlayBackgroundSession.hasSession()) getString(R.string.apply_and_reconnect) else getString(R.string.save)) { _, _ ->
                onApply(selection)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .setOnDismissListener { preview.close() }
            .show()
    }

    private fun applyMediaChannel(value: Int, previous: Int, control: Button, summary: (Int) -> String) {
        if (value == previous) return
        AirPlayPersistence.saveMediaAudioChannel(this, value)
        control.text = summary(value)
        // The sink bakes the channel into its AudioTrack, so the session must restart to apply it.
        connect(AirPlayPersistence.loadWirelessEnabled(this))
    }

    private fun applyNavigationChannel(value: Int, previous: Int, control: Button, summary: (Int) -> String) {
        if (value == previous) return
        AirPlayPersistence.saveNavigationAudioChannel(this, value)
        control.text = summary(value)
        connect(AirPlayPersistence.loadWirelessEnabled(this))
    }

    private fun channelLabel(value: Int): String = value.toString()

    private fun storedSsid() = AirPlayPersistence.loadManualHotspotSsid(this)
    private fun storedPassword() = AirPlayPersistence.loadManualHotspotPassphrase(this)
    private fun hotspotError(ssid: String, password: String) =
        com.shilapi.xcertplay.orchestration.ManualHotspotValidation.error(ssid, password)?.let { getString(it.messageResource()) }

    private fun saveHotspotCredentials(ssid: String, password: String, security: ManualHotspotSecurity) {
        AirPlayPersistence.saveManualHotspotSsid(this, ssid)
        AirPlayPersistence.saveManualHotspotPassphrase(this, password)
        AirPlayPersistence.saveManualHotspotSecurity(this, security)
        AirPlayPersistence.saveManualHotspotBand(this, com.shilapi.xcertplay.orchestration.ManualHotspotBand.AUTO)
        AirPlayPersistence.saveManualHotspotChannel(this, 0)
        markReconnectNeeded()
    }

    private fun askHotspotCredentials(existingWifi: Boolean = false, done: (String, String, ManualHotspotSecurity) -> Unit) {
        val dialogContext = appDialogContext()
        val fields = column().apply { setPadding(dp(24), dp(12), dp(24), dp(12)) }
        fields.addView(label(getString(if (existingWifi) R.string.existing_wifi_instructions else R.string.copy_these_from_the_car_s_hotspot_settings_use_5_ghz_if_av), 16, MUTED))
        val ssid = EditText(dialogContext).apply {
            hint = getString(if (existingWifi) R.string.existing_wifi_ssid else R.string.hotspot_name)
            setText(if (existingWifi) AirPlayPersistence.loadExistingWifiSsid(this@DiPlayActivity) else storedSsid())
            setSingleLine()
        }
        val password = EditText(dialogContext).apply {
            hint = getString(if (existingWifi) R.string.existing_wifi_password else R.string.hotspot_password)
            setText(if (existingWifi) AirPlayPersistence.loadExistingWifiPassphrase(this@DiPlayActivity) else storedPassword())
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        ssid.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        password.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        fun hideKeyboard() {
            val token = password.windowToken ?: ssid.windowToken
            (this.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(token, 0)
            ssid.clearFocus(); password.clearFocus()
        }
        ssid.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT) { password.requestFocus(); true } else false
        }
        password.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) { hideKeyboard(); true } else false
        }
        val nameWarning = label(getString(R.string.settings_hotspot_name_non_english), 14, WARNING)
        fun updateNameWarning() {
            nameWarning.visibility = if (!existingWifi &&
                com.shilapi.xcertplay.orchestration.ManualHotspotValidation.nameMayNotReachIphone(ssid.text.toString())
            ) View.VISIBLE else View.GONE
        }
        updateNameWarning()
        ssid.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) = updateNameWarning()
        })
        fields.addView(ssid); fields.addView(nameWarning); fields.addView(password)
        fields.addView(CheckBox(dialogContext).apply {
            text = getString(R.string.show_password)
            setOnCheckedChangeListener { _, checked ->
                password.transformationMethod = if (checked) null else android.text.method.PasswordTransformationMethod.getInstance()
                password.setSelection(password.text.length)
            }
        })
        val securityModes = listOf(ManualHotspotSecurity.WPA2, ManualHotspotSecurity.WPA3_TRANSITION, ManualHotspotSecurity.WPA3)
        val securityTitles = listOf(getString(R.string.wpa2), getString(R.string.wpa3_transition), getString(R.string.wpa3))
        var protectedSecurity = AirPlayPersistence.loadManualHotspotSecurity(this)
            .takeIf { it in securityModes } ?: ManualHotspotSecurity.WPA2
        if (!existingWifi) {
            lateinit var securityControl: Button
            fun updateSecurityControl() {
                val hasPassword = password.text.isNotEmpty()
                val title = if (hasPassword) securityTitles[securityModes.indexOf(protectedSecurity)] else getString(R.string.open)
                securityControl.text = "${getString(R.string.security)} · $title"
                securityControl.isEnabled = hasPassword
            }
            securityControl = button("", false) {
                var pendingSecurity = protectedSecurity
                appDialogBuilder().setTitle(getString(R.string.security))
                    .setSingleChoiceItems(securityTitles.toTypedArray(), securityModes.indexOf(protectedSecurity)) { _, index ->
                        pendingSecurity = securityModes[index]
                    }
                    .setPositiveButton(getString(R.string.save)) { _, _ ->
                        protectedSecurity = pendingSecurity
                        updateSecurityControl()
                    }.setNegativeButton(getString(R.string.cancel), null).show()
            }
            updateSecurityControl()
            password.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: android.text.Editable?) = updateSecurityControl()
            })
            fields.addView(securityControl, matchButton(12, 60))
            fields.addView(label(getString(R.string.saved_for_your_next_connection), 15, MUTED))
        }
        val error = label("", 14, WARNING)
        error.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        fields.addView(error)
        val dialog = appDialogBuilder().setTitle(getString(if (existingWifi) R.string.existing_wifi_details else R.string.car_hotspot_details))
            .setView(ScrollView(dialogContext).apply { addView(fields) })
            .setPositiveButton(getString(R.string.save_details), null).setNegativeButton(getString(R.string.cancel)) { _, _ -> hideKeyboard() }
            .setNeutralButton(getString(R.string.hide_keyboard), null).create()
        dialog.setOnShowListener {
            dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener { hideKeyboard() }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = if (existingWifi) ssid.text.toString() else ssid.text.toString().trim()
                val secret = password.text.toString()
                val problem = hotspotError(name, secret)
                if (problem != null) error.text = problem
                else {
                    val security = if (secret.isEmpty()) ManualHotspotSecurity.OPEN else protectedSecurity
                    hideKeyboard(); dialog.dismiss(); done(name, secret, security)
                }
            }
        }
        dialog.show()
    }

    // "Left 20 %", "Centre · default", "Down 10 %": a signed step reads as a direction and a distance.
    private fun markerStepLabel(step: Int, negative: String, positive: String): String = when {
        step == 0 -> getString(R.string.marker_centre_default)
        step < 0 -> "$negative ${-step * CarPlayClusterDisplay.MARKER_STEP_PERCENT} %"
        else -> "$positive ${step * CarPlayClusterDisplay.MARKER_STEP_PERCENT} %"
    }

    // v100 B561: 原 clusterMusicControls(把歌名/歌手交给 BYD DiLink 导航投射到
    // 仪表)随 BYD 集成删除 —— B561 的仪表媒体卡由 [ClusterGuidanceFeed] 直接喂数据
    // (见「音频路由」分区的媒体卡设置),不走 DiLink 导航广播。

    /**
     * v123: CarPlay Ultra 风格仪表盘的开关。
     *
     * 与「仪表车速表」是**两件独立的事**：那个只画速度，这个画整套
     * （转速表/功率/续航或油量/电量/里程/胎压），对应 CarPlay Ultra 的
     * 车辆状态仪表（参考 LIVI 的 `GAUGE_KEYS`）。
     *
     * 缺哪个数据就少画哪个 —— 纯电车没有油量、燃油车没有电量，
     * 都不画，**绝不编造**。
     */
    private fun clusterUltraGaugeControls(card: LinearLayout) {
        toggle(card, getString(R.string.settings_cluster_ultra_gauge),
            getString(R.string.settings_cluster_ultra_gauge_description),
            AirPlayPersistence.loadClusterUltraGaugeEnabled(this)) {
            AirPlayPersistence.saveClusterUltraGaugeEnabled(this, it)
            render()
        }
        if (AirPlayPersistence.loadClusterUltraGaugeEnabled(this)) {
            card.addView(overlaySliderRow(
                getString(R.string.settings_cluster_ultra_gauge_opacity),
                listOf(10, 20, 30, 40, 50, 60, 70, 80, 90, 100),
                AirPlayPersistence.loadClusterUltraGaugeOpacity(this),
            ) { value -> "$value %" }.also { row ->
                row.onSave = { value -> AirPlayPersistence.saveClusterUltraGaugeOpacity(this, value) }
            })
        }
    }

    private fun clusterSpeedometerControls(card: LinearLayout) {        toggle(card, getString(R.string.settings_cluster_speedometer),
            getString(R.string.settings_cluster_speedometer_description), AirPlayPersistence.loadClusterSpeedometerEnabled(this)) {
            AirPlayPersistence.saveClusterSpeedometerEnabled(this, it)
            render()
        }
        if (AirPlayPersistence.loadClusterSpeedometerEnabled(this)) {
            card.addView(overlaySliderRow(
                getString(R.string.settings_cluster_speedometer_horizontal), ClusterSpeedometerOverlay.xPercents,
                AirPlayPersistence.loadClusterSpeedometerX(this),
            ) { value -> "$value %" }.also { row ->
                row.getChildAt(0).layoutDirection = android.text.TextUtils.getLayoutDirectionFromLocale(resources.configuration.locales[0])
                row.onSave = { value -> AirPlayPersistence.saveClusterSpeedometerX(this, value) }
            })
            card.addView(overlaySliderRow(
                getString(R.string.settings_cluster_speedometer_vertical), ClusterSpeedometerOverlay.yPercents,
                AirPlayPersistence.loadClusterSpeedometerY(this),
            ) { value -> "$value %" }.also { row ->
                row.getChildAt(0).layoutDirection = android.text.TextUtils.getLayoutDirectionFromLocale(resources.configuration.locales[0])
                row.onSave = { value -> AirPlayPersistence.saveClusterSpeedometerY(this, value) }
            })
            card.addView(overlaySliderRow(
                getString(R.string.settings_cluster_speedometer_size), ClusterSpeedometerOverlay.sizePercents,
                AirPlayPersistence.loadClusterSpeedometerSize(this),
            ) { value -> "$value %" }.also { row ->
                row.getChildAt(0).layoutDirection = android.text.TextUtils.getLayoutDirectionFromLocale(resources.configuration.locales[0])
                row.onSave = { value -> AirPlayPersistence.saveClusterSpeedometerSize(this, value) }
            })
            card.addView(overlaySliderRow(
                getString(R.string.settings_cluster_speedometer_opacity), ClusterSpeedometerOverlay.opacityPercents,
                AirPlayPersistence.loadClusterSpeedometerOpacity(this),
            ) { value -> "$value %" }.also { row ->
                row.getChildAt(0).layoutDirection = android.text.TextUtils.getLayoutDirectionFromLocale(resources.configuration.locales[0])
                row.onSave = { value -> AirPlayPersistence.saveClusterSpeedometerOpacity(this, value) }
            })
        }
    }

    /** A 1%-step slider row for overlay placement; every step saves, so the card moves live. */
    private fun overlaySliderRow(title: String, values: List<Int>, current: Int, describe: (Int) -> String): OverlaySliderRow =
        OverlaySliderRow(this, title, values, current, describe)

    private inner class OverlaySliderRow(
        context: android.content.Context,
        title: String,
        private val steps: List<Int>,
        current: Int,
        private val describe: (Int) -> String,
    ) : LinearLayout(context) {
        var onSave: (Int) -> Unit = {}
        /** Fired once when the finger leaves the slider, for saves that need a reconnect. */
        var onCommit: () -> Unit = {}
        val slider: SeekBar

        init {
            orientation = VERTICAL
            val valueView = label(describe(current), 16, ACCENT, true)
            val head = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(12), 0, 0) }
            head.addView(label(title, 16, TEXT, true), LinearLayout.LayoutParams(0, -2, 1f))
            head.addView(valueView)
            addView(head)
            slider = SeekBar(context).apply {
                max = steps.lastIndex
                progress = steps.indexOf(current).coerceIn(steps.indices)
                // ProgressBar.setMinHeight needs API 29; the view minimum keeps the touch target before that.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) minHeight = dp(44) else minimumHeight = dp(44)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        val value = steps[progress.coerceIn(steps.indices)]
                        valueView.text = describe(value)
                        if (fromUser) onSave(value)
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) { onCommit() }
                })
            }
            addView(slider, LinearLayout.LayoutParams(-1, dp(44)))
            // Keep a gap below each slider so stacked rows never touch the next control.
            addView(space(8))
        }
    }

    private fun overlayOffsetLabel(percent: Int, negative: String, positive: String, centre: Int): String {
        val delta = percent - centre
        return when {
            delta == 0 -> getString(R.string.marker_centre_default)
            delta < 0 -> "$negative ${-delta} %"
            else -> "$positive $delta %"
        }
    }

    private fun showClusterAccessSetup() {
        val command = "adb shell appops set $packageName GET_USAGE_STATS allow"
        val body = column().apply { setPadding(dp(24), dp(12), dp(24), dp(12)) }
        body.addView(label(getString(R.string.one_time_setup_on_this_car), 20, TEXT, true))
        body.addView(label(getString(R.string.usage_access_lets_diplay_follow_the_instrument_theme_and_m), 15, MUTED))
        body.addView(label(getString(R.string.s_1_connect_a_computer_with_adb_installed_to_the_car_using), 16, TEXT))
        body.addView(label(command, 16, TEXT).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0, dp(16), 0, dp(16))
        })
        body.addView(button(getString(R.string.copy_command), false) {
            getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(
                android.content.ClipData.newPlainText(getString(R.string.clipboard_usage_access), command))
            toast(getString(R.string.copied_to_the_car_clipboard_run_the_command_on_your_comput))
        }, matchButton(0, 56))
        body.addView(label(getString(R.string.cluster_adb_multi_device, packageName), 14, MUTED))
        body.addView(label(getString(R.string.s_3_tap_check_and_enable_below_this_enables_the_cluster_ma), 16, TEXT))
        val status = label(if (HomeScreenMonitor.hasAccess(this)) getString(R.string.permission_enabled_ready) else getString(R.string.permission_not_enabled), 16, TEXT)
        body.addView(status)
        val dialog = appDialogBuilder().setTitle(getString(R.string.automatic_cluster_map_setup))
            .setView(ScrollView(appDialogContext()).apply { addView(body) })
            .setNegativeButton(getString(R.string.close), null)
            .setPositiveButton(getString(R.string.check_and_enable), null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (HomeScreenMonitor.hasAccess(this)) {
                    AirPlayPersistence.saveClusterMapEnabled(this, true)
                    dialog.dismiss()
                    render()
                    toast(getString(R.string.automatic_map_enabled_open_the_cluster_map_card_or_select))
                    reconnectIfRunning()
                } else {
                    status.text = getString(R.string.still_waiting_for_usage_access_check_that_the_command_ran)
                }
            }
        }
        dialog.show()
    }

    private fun promptEnableUsbAutoConfirm() {
        if (UsbPermissionSetup.Permission.ACCESSIBILITY.granted(this)) {
            toast(getString(R.string.usb_auto_confirm_status_on))
            return
        }
        appDialogBuilder()
            .setTitle(getString(R.string.usb_auto_confirm_title))
            .setMessage(getString(R.string.usb_auto_confirm_dialog_msg))
            .setPositiveButton(getString(R.string.btn_auto_apply_permissions)) { _, _ ->
                autoApplyPermissions()
            }
            .setNeutralButton(getString(R.string.btn_open_accessibility_setting)) { _, _ ->
                if (!UsbAutoConfirmService.openSettings(this)) {
                    toast(getString(R.string.wheel_keys_no_settings))
                }
            }
            .setNegativeButton(getString(R.string.cancel)) { _, _ ->
                render()
            }
            .setOnCancelListener {
                render()
            }
            .show()
    }

    private fun cancelUsbPermissionSetup() {
        val operation = usbPermissionOperation
        usbPermissionOperation = null
        operation?.cancel()
        val dialog = usbPermissionDialog
        usbPermissionDialog = null
        dialog?.dismiss()
    }

    private fun autoApplyPermissions() {
        if (usbPermissionOperation != null || isFinishing || isDestroyed) return
        val operation = usbPermissionOperationFactory(applicationContext)
        usbPermissionOperation = operation
        val progress = appDialogBuilder()
            .setTitle(R.string.auto_grant_title)
            .setMessage(R.string.auto_grant_msg)
            .setNegativeButton(R.string.cancel) { _, _ -> cancelUsbPermissionSetup() }
            .create()
        usbPermissionDialog = progress
        progress.setOnCancelListener { cancelUsbPermissionSetup() }
        progress.setOnDismissListener {
            if (usbPermissionDialog === progress) cancelUsbPermissionSetup()
        }
        progress.show()
        Thread({
            val result = operation.run()
            handler.post {
                if (usbPermissionOperation !== operation || operation.isCancelled || isFinishing || isDestroyed) {
                    return@post
                }
                usbPermissionOperation = null
                usbPermissionDialog = null
                progress.dismiss()
                render()
                if (result.complete) {
                    appDialogBuilder()
                        .setTitle(R.string.auto_grant_success_title)
                        .setMessage(R.string.auto_grant_success_msg)
                        .setPositiveButton(R.string.close, null)
                        .show()
                } else {
                    val reason = when (result.access) {
                        LocalAdb.Access.NOT_APPROVED -> getString(R.string.auto_grant_confirm_msg)
                        LocalAdb.Access.UNSUPPORTED -> getString(R.string.adb_pairing_only)
                        else -> getString(R.string.auto_grant_incomplete)
                    }
                    showManualPermissionDialog(reason)
                }
            }
        }, "diplay-auto-permission").start()
    }

    private fun showManualPermissionDialog(reason: String) {
        val body = column().apply { setPadding(dp(20), dp(10), dp(20), dp(10)) }
        body.addView(label(reason, 15, MUTED).apply { setPadding(0, 0, 0, dp(12)) })

        val autoConfirmOn = UsbPermissionSetup.Permission.ACCESSIBILITY.granted(this)
        body.addView(button(if (autoConfirmOn) getString(R.string.usb_auto_confirm_status_on) else getString(R.string.btn_open_accessibility_setting), false) {
            if (!UsbAutoConfirmService.openSettings(this)) toast(getString(R.string.wheel_keys_no_settings))
        }, matchButton(0, 56))

        val overlayOn = CenterMapOverlay.permitted(this)
        body.addView(button(if (overlayOn) getString(R.string.center_map_overlay_allowed) else getString(R.string.btn_open_overlay_setting), false) {
            openOverlayPermission()
        }, matchButton(10, 56))

        UsbPermissionSetup.snapshot(this).forEach { (permission, granted) ->
            val title = getString(when (permission) {
                UsbPermissionSetup.Permission.ACCESSIBILITY -> R.string.usb_auto_confirm_title
                UsbPermissionSetup.Permission.USAGE -> R.string.center_map_auto_hide
                UsbPermissionSetup.Permission.OVERLAY -> R.string.btn_open_overlay_setting
            })
            body.addView(label("$title: ${getString(if (granted) R.string.permission_enabled_ready else R.string.permission_not_enabled)}", 14, if (granted) MUTED else WARNING))
        }
        val adbCmd = UsbPermissionSetup.manualCommand(packageName)
        body.addView(label(getString(R.string.manual_grant_cmd_hint), 14, MUTED).apply { setPadding(0, dp(12), 0, dp(6)) })
        body.addView(label(adbCmd, 13, TEXT).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(BUTTON)
        })
        body.addView(button(getString(R.string.copy_command), false) {
            getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(
                android.content.ClipData.newPlainText("DiPlay ADB Command", adbCmd)
            )
            toast(getString(R.string.copied_to_the_car_clipboard_run_the_command_on_your_comput))
        }, matchButton(8, 50))

        appDialogBuilder()
            .setTitle(getString(R.string.permissions_and_connection_help))
            .setView(ScrollView(appDialogContext()).apply { addView(body) })
            .setPositiveButton(getString(R.string.close)) { _, _ -> render() }
            .show()
    }

    /** Optional guidance-volume routing through the existing wheel service. */
    private fun navigationWheelControls(card: LinearLayout) {
        toggle(card, getString(R.string.settings_navigation_wheel_volume), getString(R.string.settings_navigation_wheel_volume_description),
            NavigationWheelSettings.enabled(this)) {
            NavigationWheelSettings.setEnabled(this, it)
            render()
        }
        if (!NavigationWheelSettings.enabled(this) || WheelKeyService.connected()) return
        card.addView(label(getString(R.string.settings_navigation_wheel_service_required), 14, WARNING))
        card.addView(button(getString(R.string.wheel_keys_open_settings), false) {
            if (!UsbAutoConfirmService.openSettings(this)) toast(getString(R.string.wheel_keys_no_settings))
        }, matchButton(10, 56))
        card.addView(button(getString(R.string.wheel_keys_enable_adb), false) {
            Thread({
                val access = WheelKeyService.enableOverAdb(this)
                runOnUiThread {
                    if (access != com.shilapi.xcertplay.adb.LocalAdb.Access.READY) {
                        toast(getString(R.string.wheel_keys_adb_failed, access.name))
                    }
                    render()
                }
            }, "diplay-navigation-wheel-enable").start()
        }, matchButton(10, 56))
    }

    /**
     * v113：方控菜单里的「通话按键」段。
     *
     * 接听键默认**下一首**、挂断键默认**上一首**（上游 DiLink 3 的语义）。
     * 两个键位都能换成别的预设，或用「按键学习」绑一个任意键。
     */
    private fun callKeyControls(card: LinearLayout) {
        toggle(card, getString(R.string.carplay_call_controls),
            getString(R.string.carplay_call_controls_description),
            AirPlayPersistence.loadCarPlayCallControlsEnabled(this)) {
            AirPlayPersistence.saveCarPlayCallControlsEnabled(this, it)
        }
        val answerKey = AirPlayPersistence.loadCarPlayCallAnswerKey(this)
        val endKey = AirPlayPersistence.loadCarPlayCallEndKey(this)
        callKeyPicker(card, getString(R.string.call_key_answer), answerKey, endKey, CallKeyTarget.ANSWER)
        callKeyPicker(card, getString(R.string.call_key_end), endKey, answerKey, CallKeyTarget.END)
        card.addView(label(getString(R.string.call_key_description), 14, MUTED))
    }

    private enum class CallKeyTarget { ANSWER, END }

    /**
     * 一个键位选择器。
     *
     * [otherKeyCode] 是另一个键位已占用的键 —— 从选项里剔掉，避免同一个键既是
     * 接听又是挂断（策略里接听优先，那样挂断就永远按不到）。
     */
    private fun callKeyPicker(
        card: LinearLayout,
        title: String,
        keyCode: Int,
        otherKeyCode: Int,
        target: CallKeyTarget,
    ) {
        if (callKeyLearning == target) {
            card.addView(label(getString(R.string.call_key_learn_prompt), 15, WARNING))
            card.addView(button(getString(R.string.call_key_cancel_learn), false) {
                cancelCallKeyLearning()
            }, matchButton(0, 56))
            return
        }
        val presets = CarPlayCallKeyPolicy.Choice.entries.filter { it.keyCode != otherKeyCode }
        val options = presets.map { callKeyLabel(it.keyCode) } + getString(R.string.call_key_choice_custom)
        val button = button("$title · ${callKeyLabel(keyCode)}", false) {}
        button.setOnClickListener {
            appDialogBuilder().setTitle(title)
                .setSingleChoiceItems(options.toTypedArray(), -1) { dialog, which ->
                    dialog.dismiss()
                    if (which < presets.size) saveCallKey(target, presets[which].keyCode)
                    else startCallKeyLearning(target)
                }
                .setNegativeButton(getString(R.string.cancel), null).show()
        }
        card.addView(button, matchButton(0, 60))
        card.addView(space(12))
    }

    /** 键位在界面上显示的名字；自定义键显示 keyCode。 */
    private fun callKeyLabel(keyCode: Int): String = when (CarPlayCallKeyPolicy.choiceOf(keyCode)) {
        CarPlayCallKeyPolicy.Choice.NEXT -> getString(R.string.wheel_key_role_next)
        CarPlayCallKeyPolicy.Choice.PREVIOUS -> getString(R.string.wheel_key_role_previous)
        CarPlayCallKeyPolicy.Choice.CALL -> getString(R.string.call_key_choice_call)
        CarPlayCallKeyPolicy.Choice.ENDCALL -> getString(R.string.call_key_choice_endcall)
        CarPlayCallKeyPolicy.Choice.HEADSET_HOOK -> getString(R.string.call_key_choice_headset)
        CarPlayCallKeyPolicy.Choice.PLAY_PAUSE -> getString(R.string.call_key_choice_play_pause)
        CarPlayCallKeyPolicy.Choice.BYD_ANSWER -> getString(R.string.call_key_choice_byd_answer)
        CarPlayCallKeyPolicy.Choice.BYD_HANG_UP -> getString(R.string.call_key_choice_byd_hangup)
        null -> getString(R.string.call_key_choice_custom_value, keyCode)
    }

    /** 进入「按下要绑定的键」状态。 */
    private fun startCallKeyLearning(target: CallKeyTarget) {
        // 与 Siri 键的学习流程互斥 —— 两个都在等键会互相抢。
        cancelKeyLearning()
        callKeyLearning = target
        CarPlayCallKeyLearning.start { keyCode ->
            callKeyLearning = null
            handler.removeCallbacks(endCallKeyLearning)
            saveCallKey(target, keyCode)
        }
        handler.postDelayed(endCallKeyLearning, WheelKeyService.LEARNING_TIMEOUT_MILLIS)
        render()
    }

    private fun cancelCallKeyLearning() {
        handler.removeCallbacks(endCallKeyLearning)
        if (callKeyLearning == null) return
        callKeyLearning = null
        CarPlayCallKeyLearning.stop()
        render()
    }

    private fun saveCallKey(target: CallKeyTarget, keyCode: Int) {
        when (target) {
            CallKeyTarget.ANSWER -> AirPlayPersistence.saveCarPlayCallAnswerKey(this, keyCode)
            CallKeyTarget.END -> AirPlayPersistence.saveCarPlayCallEndKey(this, keyCode)
        }
        render()
    }

    /** Steering-wheel keys for the dashboard map zoom and the CarPlay joystick: the switches, the key service and the keys. */
    // One place for the key service setup, above every feature that needs it.
    private fun wheelKeysSettings(card: LinearLayout) {
        // The joystick and map zoom use BYD's media and custom keys.
        val byd = CarHotspotSetup.isBydHeadUnit(this)
        val zoomAvailable = wheelMapZoomAvailable()
        val vehicleKeysOn = WheelZoomSettings.joystick(this) ||
            (zoomAvailable && WheelZoomSettings.enabled(this))
        if (WheelZoomSettings.siriKey(this) || vehicleKeysOn) wheelKeyServiceControls(card)
        siriKeyControls(card)
        // Any head unit: a paired keyboard, remote or rotary controller works alongside touch.
        toggle(card, getString(R.string.settings_external_controller), getString(R.string.settings_external_controller_description),
            AirPlayPersistence.loadExternalController(this)) { AirPlayPersistence.saveExternalController(this, it) }
        // Keep previously configured controls reachable even if package detection misses the car.
        if (byd || zoomAvailable || WheelZoomSettings.joystick(this)) wheelKeyControls(card)
    }

    // Map zoom only works where the dashboard map card used to show these controls.
    private fun wheelMapZoomAvailable(): Boolean {
        if (!AirPlayPersistence.loadClusterMapEnabled(this)) return false
        val adbCluster = false
        if (false && !adbCluster) return false
        return adbCluster || ClusterMapPresentation.findDisplay(this) != null
    }

    private fun wheelKeyControls(card: LinearLayout) {
        val zoomAvailable = wheelMapZoomAvailable()
        if (zoomAvailable) {
            toggle(card, getString(R.string.wheel_map_zoom), getString(R.string.wheel_map_zoom_description),
                WheelZoomSettings.enabled(this)) {
                WheelZoomSettings.setEnabled(this, it)
                render()
            }
        }
        toggle(card, getString(R.string.wheel_joystick), getString(R.string.wheel_joystick_description),
            WheelZoomSettings.joystick(this)) {
            WheelZoomSettings.setJoystick(this, it)
            render()
        }
        val zoom = zoomAvailable && WheelZoomSettings.enabled(this)
        val joystick = WheelZoomSettings.joystick(this)
        if (!zoom && !joystick) return
        if (zoom) {
            val behaviours = WheelZoomSettings.Behaviour.entries
            choice(card, getString(R.string.wheel_zoom_behaviour),
                listOf(getString(R.string.wheel_zoom_behaviour_toggle), getString(R.string.wheel_zoom_behaviour_timed)),
                behaviours.indexOf(WheelZoomSettings.behaviour(this)), reconnects = false) {
                WheelZoomSettings.setBehaviour(this, behaviours[it])
            }
        }
        if (joystick) {
            toggle(card, getString(R.string.wheel_joystick_auto_off), getString(R.string.wheel_joystick_auto_off_description),
                WheelZoomSettings.joystickAutoOff(this)) { WheelZoomSettings.setJoystickAutoOff(this, it) }
        }
        for (role in WheelZoomSettings.Role.entries) {
            // A key can serve both: the mode key goes back in the joystick, the zoom keys move it.
            val zoomName = when (role) {
                WheelZoomSettings.Role.MODE -> R.string.wheel_key_role_mode
                WheelZoomSettings.Role.ZOOM_IN -> R.string.wheel_key_role_zoom_in
                WheelZoomSettings.Role.ZOOM_OUT -> R.string.wheel_key_role_zoom_out
                else -> null
            }.takeIf { zoom }
            val joystickName = when (role) {
                WheelZoomSettings.Role.MODE -> R.string.wheel_key_role_back
                WheelZoomSettings.Role.ZOOM_IN, WheelZoomSettings.Role.PREVIOUS -> R.string.wheel_key_role_previous
                WheelZoomSettings.Role.ZOOM_OUT, WheelZoomSettings.Role.NEXT -> R.string.wheel_key_role_next
                WheelZoomSettings.Role.JOYSTICK -> R.string.wheel_key_role_joystick
                WheelZoomSettings.Role.SELECT -> R.string.wheel_key_role_select
                WheelZoomSettings.Role.SIRI -> null
            }.takeIf { joystick }
            val names = listOfNotNull(zoomName, joystickName)
            if (names.isEmpty()) continue
            wheelKeyAssignButton(card, role, names.joinToString(" · ") { getString(it) })
        }
    }

    /** A wheel key that opens Siri on any car whose key reaches the key service. */
    private fun siriKeyControls(card: LinearLayout) {
        toggle(card, getString(R.string.wheel_siri_key), getString(R.string.wheel_siri_key_description),
            WheelZoomSettings.siriKey(this)) {
            WheelZoomSettings.setSiriKey(this, it)
            render()
        }
        if (!WheelZoomSettings.siriKey(this)) return
        wheelKeyAssignButton(card, WheelZoomSettings.Role.SIRI, getString(R.string.wheel_key_role_siri))
    }

    private fun wheelKeyServiceControls(card: LinearLayout) {
        val connected = WheelKeyService.connected()
        card.addView(label(getString(when {
            connected -> R.string.wheel_keys_service_on
            WheelKeyService.enabledInSettings(this) -> R.string.wheel_keys_service_starting
            else -> R.string.wheel_keys_service_off
        }), 14, if (connected) MUTED else WARNING))
        if (!connected) {
            card.addView(actionButton(getString(R.string.wheel_keys_enable_adb), false) {
                Thread({
                    val access = WheelKeyService.enableOverAdb(this)
                    runOnUiThread {
                        if (access != com.shilapi.xcertplay.adb.LocalAdb.Access.READY) {
                            toast(getString(R.string.wheel_keys_adb_failed, access.name))
                        }
                        render()
                    }
                }, "diplay-wheel-keys-enable").start()
            }, matchButton(10, 56))
            card.addView(button(getString(R.string.wheel_keys_open_settings), false) {
                if (!UsbAutoConfirmService.openSettings(this)) toast(getString(R.string.wheel_keys_no_settings))
            }, matchButton(10, 56))
        }
    }

    private fun wheelKeyAssignButton(card: LinearLayout, role: WheelZoomSettings.Role, name: String) {
        fun current(key: WheelKey? = WheelZoomSettings.key(this, role)) =
            getString(R.string.wheel_key_assign, name, key?.toString() ?: getString(R.string.wheel_key_none))
        lateinit var assign: android.widget.Button
        assign = button(current(), false) {
            val cancelled = { runOnUiThread {
                assign.text = current()
                applyPendingAppearanceRender()
            } }
            val refused = { taken: WheelZoomSettings.Role ->
                runOnUiThread {
                    assign.text = current()
                    toast(getString(R.string.wheel_key_in_use, getString(wheelKeyRoleName(taken))))
                    applyPendingAppearanceRender()
                }
            }
            val started = WheelKeyService.learn(role, cancelled, refused) { _, key -> runOnUiThread {
                assign.text = current(key)
                applyPendingAppearanceRender()
            } } ||
                // Without the service the Siri key is learnt from this window, so only keys that reach apps.
                (role == WheelZoomSettings.Role.SIRI && learnInWindow(role, cancelled, refused) {
                    assign.text = current(it)
                    applyPendingAppearanceRender()
                })
            if (started) assign.text = getString(R.string.wheel_key_press, name)
            else toast(getString(R.string.wheel_keys_service_off))
        }
        card.addView(assign, matchButton(10, 56))
    }

    private fun wheelKeyRoleName(role: WheelZoomSettings.Role): Int = when (role) {
        WheelZoomSettings.Role.MODE -> R.string.wheel_key_role_mode
        WheelZoomSettings.Role.ZOOM_IN -> R.string.wheel_key_role_zoom_in
        WheelZoomSettings.Role.ZOOM_OUT -> R.string.wheel_key_role_zoom_out
        WheelZoomSettings.Role.JOYSTICK -> R.string.wheel_key_role_joystick
        WheelZoomSettings.Role.PREVIOUS -> R.string.wheel_key_role_previous
        WheelZoomSettings.Role.NEXT -> R.string.wheel_key_role_next
        WheelZoomSettings.Role.SELECT -> R.string.wheel_key_role_select
        WheelZoomSettings.Role.SIRI -> R.string.wheel_key_role_siri
    }

    private class WindowKeyLearning(
        val role: WheelZoomSettings.Role,
        val cancelled: () -> Unit,
        val refused: (WheelZoomSettings.Role) -> Unit,
        val done: (WheelKey) -> Unit,
    )

    private fun learnInWindow(role: WheelZoomSettings.Role, cancelled: () -> Unit,
        refused: (WheelZoomSettings.Role) -> Unit, done: (WheelKey) -> Unit): Boolean {
        cancelKeyLearning()
        windowLearning = WindowKeyLearning(role, cancelled, refused, done)
        handler.postDelayed(endWindowLearning, WheelKeyService.LEARNING_TIMEOUT_MILLIS)
        return true
    }

    private fun cancelKeyLearning() {
        WheelKeyService.cancelLearning()
        handler.removeCallbacks(endWindowLearning)
        val cancelled = windowLearning?.cancelled
        windowLearning = null
        cancelled?.invoke()
        applyPendingAppearanceRender()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return super.dispatchKeyEvent(event)
        // v113: 正在学按键时，先把这个键收下（无障碍服务没开时走这条路）。
        if (CarPlayCallKeyLearning.offer(event.keyCode)) return true
        val action = windowLearningPresses.filter(
            Triple(event.deviceId, event.keyCode, event.scanCode),
            event.action == KeyEvent.ACTION_DOWN, event.repeatCount == 0,
        ) {
            val learning = windowLearning
            if (learning == null || event.keyCode == KeyEvent.KEYCODE_BACK) return@filter WheelZoomKeys.Action.PASS
            if (inCall(this) || !WheelZoomSettings.siriKey(this)) {
                cancelKeyLearning()
                return@filter WheelZoomKeys.Action.PASS
            }
            handler.removeCallbacks(endWindowLearning)
            windowLearning = null
            val key = WheelKey.of(event)
            val taken = WheelZoomSettings.conflict(this, learning.role, key)
            if (taken != null) {
                Log.i(WheelKeyService.TAG, "${learning.role} key $key refused: it is the $taken key (learnt without the service)")
                learning.refused(taken)
            } else {
                WheelZoomSettings.assign(this, learning.role, key)
                Log.i(WheelKeyService.TAG, "${learning.role} key is now $key (learnt without the service)")
                learning.done(key)
            }
            WheelZoomKeys.Action.CONSUME
        }
        return action != WheelZoomKeys.Action.PASS || super.dispatchKeyEvent(event)
    }

    /** The 0.2.9 Dashboard song setting, shown once: in the BYD navigation card, or under Advanced vehicle data. */
    // v100 B561: clusterSongSwitch(把歌曲名交给 BYD DiLink 导航投射到仪表)随
    // BYD 集成删除 —— 仪表媒体卡见「音频路由」分区。


    /**
     * v100 B561: 方向盘按键在 CarPlay 通话中挂断/接听。
     *
     * BYD 版本把开关写进 DiLink 设置区、再靠 ADB 授权读取按键上报;B561 无 DiLink
     * 设置区,改为本地 prefs —— 按键上报仍由 [WheelKeyService] 的无障碍通道提供。
     */
    private fun applyCallKeys(enabled: Boolean) {
        AirPlayPersistence.saveCarPlayCallControls(this, enabled)
        markReconnectNeeded()
    }

    private fun applyCarPlayCalls(enabled: Boolean) {
        AirPlayPersistence.saveCarPlayCalls(this, enabled)
    }

    // v100 B561: BYD 车辆数据探测(ADB vehicle-data / 电池 / 轮胎 / 档位 / 加速踏板)
    // 与「车辆数据 → 高级」整块设置已随 BYD 集成删除。B561 无对应数据源,
    // 车机侧车辆信息由车机自身/CAN 提供,不由本应用代测。

    // The cluster screen is described at connection time, so a running session reconnects over
    // its current link. The position choices need no call: getString(R.string.apply_and_reconnect) already does it.
    /** 定位权限是否已授予（通用 Android 权限，与 BYD 无关）。 */
    private fun hasPreciseLocation(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /**
     * 长安欧尚 B561:仪表屏选择控件。
     *
     * 列出除主屏外的所有可用屏幕,供用户指定 CarPlay 导航/媒体信息的出图目标。
     *
     * ## 为什么 B561 必须有它,而 0.2.14 原版没有
     *
     * 0.2.14 靠 `DiLink4ClusterDisplay.matches(name, w, h)` 判定仪表屏,那是
     * **比亚迪专属指纹**(`name == "DiLink4"` 且 1920×720)。B561 的仪表是 HDMI
     * 外接副屏,屏名与尺寸都不匹配 —— `diLink4` 恒 false,`findDisplay()` 也没有
     * 候选,于是界面上只剩「DiLink4 仪表视频(ADB,实验性)」,**没有选屏入口**。
     *
     * 0.2.13 的做法是 `ClusterStreamProfile.matches()` 直接 `= false`:
     * 刻意不做指纹匹配,凡是 B561 一律走"枚举副屏 + 用户选屏"。本方法把那套逻辑
     * 搬回新版,并在 [diLink4] 为真时让位给官方控件(两套不并存)。
     *
     * @param diLink4 官方 DiLink4 路径已接管时为真 —— 此时不重复显示选屏控件。
     */
    private fun clusterDisplayControl(parent: LinearLayout, diLink4: Boolean) {
        // DiLink 路径下屏是确定的(指纹匹配出来的),让用户手选只会与官方逻辑打架。
        if (diLink4) return

        val candidates = com.shilapi.xcertplay.cluster.ClusterDisplayCatalog.candidates(this)

        toggle(
            parent, "自动选择仪表屏",
            "开启后自动使用检测到的第一块副屏;关闭后可手动指定。",
            com.shilapi.xcertplay.cluster.ClusterDisplayCatalog.autoFollow(this),
        ) { enabled ->
            com.shilapi.xcertplay.cluster.ClusterDisplayCatalog.setAutoFollow(this, enabled)
            render()
        }

        if (candidates.isEmpty()) {
            parent.addView(
                label(
                    "未检测到副屏。请确认仪表屏已点亮并接入 HDMI 后,重新进入此页面。",
                    14, MUTED,
                ).apply { setPadding(0, dp(12), 0, dp(4)) },
            )
            return
        }

        val selected = com.shilapi.xcertplay.cluster.ClusterDisplayCatalog.selectedId(this)
        val resolved = com.shilapi.xcertplay.cluster.ClusterDisplayCatalog.resolve(this)
        val currentLabel = resolved?.displayId?.let { id ->
            candidates.firstOrNull { it.displayId == id }?.describe()
        } ?: "未选择"

        val control = button("目标仪表屏: $currentLabel", false) {}
        control.setOnClickListener {
            val labels = candidates.map { it.describe() }.toTypedArray()
            val current = candidates.indexOfFirst { it.displayId == selected }.takeIf { it >= 0 } ?: 0
            AlertDialog.Builder(this)
                .setTitle("选择仪表屏")
                .setSingleChoiceItems(labels, current) { dialog, which ->
                    val picked = candidates[which]
                    // ★ 显式选屏必须同时关掉 autoFollow ——
                    //   resolve() 只在 !autoFollow() 时才读 selectedId,
                    //   忘了关就是"选了没反应"。
                    com.shilapi.xcertplay.cluster.ClusterDisplayCatalog
                        .setAutoFollow(this, false)
                    com.shilapi.xcertplay.cluster.ClusterDisplayCatalog
                        .setSelectedId(this, picked.displayId)
                    dialog.dismiss()
                    render()
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        }
        parent.addView(control, matchButton(10, 60))

        parent.addView(
            label("共检测到 ${candidates.size} 块副屏。切换后需重新连接 iPhone 生效。", 14, MUTED)
                .apply { setPadding(0, dp(8), 0, dp(4)) },
        )
    }

    private fun clusterSafeAreaControls(card: LinearLayout) {
        val rect = AirPlayPersistence.loadClusterSafeAreaRect(this)
            ?: com.shilapi.xcertplay.ClusterLayout.defaultSafeAreaRect(
                markerXPercent = AirPlayPersistence.loadClusterMarkerXPercent(this),
                markerYPercent = AirPlayPersistence.loadClusterMarkerYPercent(this))
        card.addView(label(getString(R.string.safe_area_mapping_summary,
            rect.width, rect.height, rect.left, rect.top, 1920, 720), 14, MUTED))
        card.addView(actionButton(getString(R.string.cluster_safe_area_edit), false) {
            openClusterSafeAreaEditor()
        }, matchButton(10, 56))
        card.addView(button(getString(R.string.cluster_safe_area_reset), false) {
            AirPlayPersistence.clearClusterSafeAreaRect(this)
            render()
            markReconnectNeeded()
        }, matchButton(10, 56))
        card.addView(label(getString(R.string.cluster_safe_area_hint), 14, MUTED))
    }

    private fun openClusterSafeAreaEditor() {
        clusterSafeAreaDialog?.dismiss()
        val previewOwner = Any()
        val initial = AirPlayPersistence.loadClusterSafeAreaRect(this)
            ?: com.shilapi.xcertplay.ClusterLayout.defaultSafeAreaRect(
                markerXPercent = AirPlayPersistence.loadClusterMarkerXPercent(this),
                markerYPercent = AirPlayPersistence.loadClusterMarkerYPercent(this))
        val editor = SafeAreaEditorView(this).apply {
            setBackgroundColor(SURFACE)
            setRect(initial, 1920, 720)
        }
        // A standalone dialog gives the weighted preview an exact available height.
        // AlertDialog's wrap-content custom panel can collapse it to zero.
        val dialog = appDialog().apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
        val panel = column().apply {
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setBackgroundColor(SURFACE)
        }
        panel.addView(label(getString(R.string.cluster_safe_area_edit), 18, TEXT, true))
        panel.addView(label(getString(R.string.cluster_safe_area_live_hint), 14, MUTED))
        panel.addView(ClusterSafeAreaPreviewFrame(this, editor), LinearLayout.LayoutParams(-1, 0, 1f))
        val actions = row()
        actions.addView(button(getString(R.string.cancel), false) { dialog.dismiss() },
            LinearLayout.LayoutParams(0, dp(56), 1f))
        actions.addView(button(getString(if (CarPlayBackgroundSession.hasSession())
            R.string.apply_and_reconnect else R.string.save), true) {
            editor.currentRectForSource()?.let { AirPlayPersistence.saveClusterSafeAreaRect(this, it) }
            dialog.dismiss()
            render()
            reconnectIfRunning()
        }, LinearLayout.LayoutParams(0, dp(56), 1f))
        panel.addView(actions)
        dialog.setContentView(panel, ViewGroup.LayoutParams(-1, -1))
        editor.onRectChanged = { previewOwner.hashCode() }
        clusterSafeAreaDialog = dialog
        dialog.setOnDismissListener {
            editor.onRectChanged = null
            if (clusterSafeAreaDialog === dialog) clusterSafeAreaDialog = null
        }
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.9f).toInt(),
            (resources.displayMetrics.heightPixels * 0.85f).toInt())
        // v100 B561: 原 ClusterActivityOutput 安全区预览转发已随 ADB 集群路由删除。
    }

    private fun reconnectIfRunning() {
        if (CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
    }

    // Saved without dropping CarPlay; the reconnect bar lets the driver choose when.
    private fun markReconnectNeeded() {
        CarPlayBackgroundSession.snapshot()?.controller?.let(PendingReconnect::mark)
        refreshReconnectBar()
    }

    private fun refreshReconnectBar() {
        reconnectBar?.visibility =
            if (PendingReconnect.isPending(CarPlayBackgroundSession.snapshot()?.controller)) View.VISIBLE else View.GONE
    }

    private fun reconnectBar(): View = row().apply {
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(RAIL_SELECTED, RAIL_SELECTED_BORDER)
        setPadding(dp(20), dp(10), dp(12), dp(10))
        addView(label(getString(R.string.settings_reconnect_pending), 16, TEXT), LinearLayout.LayoutParams(0, -2, 1f))
        addView(button(getString(R.string.settings_reconnect_now), true) {
            // No clear(): if connect() stops early (hotspot off, a permission prompt), the change is still pending.
            reconnectIfRunning()
        }, LinearLayout.LayoutParams(-2, dp(52)).apply { marginStart = dp(12) })
        reconnectBar = this
        refreshReconnectBar()
    }

    private fun onLocationReportingChanged(enabled: Boolean) {
        if (enabled && !hasPreciseLocation()) {
            // Keep the switch off until precise location is actually granted.
            render()
            locationPermission.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ))
            return
        }
        applyLocationReporting(enabled)
    }

    private fun applyLocationReporting(enabled: Boolean) {
        if (AirPlayPersistence.loadLocationReportingEnabled(this) == enabled) return
        AirPlayPersistence.saveLocationReportingEnabled(this, enabled)
        render()
        // Location support is advertised during iAP2 identification, so both enabling and
        // disabling it require a new session. The host also refreshes its location service type.
        if (CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
        else toast(getString(R.string.saved_for_your_next_connection))
    }

    private fun applyWirelessLink(mode: WirelessHotspotMode) {
        startupHotspotCancelled = true
        AirPlayPersistence.saveWirelessHotspotMode(this, mode)
        render()
        toast(getString(R.string.saved_for_your_next_connection))
    }

    private fun textInput(title: String, current: String, secret: Boolean, save: (String) -> Unit) {
        val input = EditText(appDialogContext()).apply {
            setText(current)
            setSingleLine()
            inputType = if (secret) {
                android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                android.text.InputType.TYPE_CLASS_TEXT
            }
        }
        appDialogBuilder().setTitle(title).setView(input)
            .setPositiveButton(getString(R.string.save)) { _, _ -> save(input.text.toString().let { if (secret) it else it.trim() }) }
            .setNegativeButton(getString(R.string.cancel), null).show()
    }

    private fun resolutionSettingControl(
        parent: LinearLayout,
        titleId: Int,
        hintId: Int,
        range: IntRange,
        default: Int,
        summaryId: Int,
        load: () -> Int,
        reconnects: Boolean = false,
        save: (Int) -> Unit,
    ) {
        val title = getString(titleId)
        fun summary() = getString(R.string.contrib_audio_home_choice_summary, title, getString(summaryId, load()))
        val control = button(summary(), false) {}
        control.setOnClickListener {
            val fields = column().apply { setPadding(dp(24), dp(8), dp(24), dp(8)) }
            val input = EditText(appDialogContext()).apply {
                setSingleLine()
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText(load().toString())
            }
            fields.addView(input)
            fields.addView(label(getString(hintId), 14, MUTED))
            val dialog = appDialogBuilder().setTitle(title).setView(fields)
                .setPositiveButton(getString(if (reconnects && CarPlayBackgroundSession.hasSession()) R.string.apply_and_reconnect else R.string.save), null)
                .setNegativeButton(getString(R.string.cancel), null)
                .setNeutralButton(getString(R.string.resolution_reset_defaults), null).create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val value = input.text.toString().trim().toIntOrNull()
                    if (value == null || value !in range) {
                        input.error = getString(R.string.resolution_number_error, range.first, range.last)
                    } else {
                        val changed = value != load()
                        save(value)
                        control.text = summary()
                        dialog.dismiss()
                        if (changed && reconnects && CarPlayBackgroundSession.hasSession()) {
                            connect(AirPlayPersistence.loadWirelessEnabled(this))
                        }
                    }
                }
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    input.setText(default.toString())
                    input.error = null
                }
            }
            showResolutionSettingsDialog(dialog, fields)
        }
        parent.addView(control, matchButton(0, 60))
        parent.addView(space(12))
    }

    private fun showResolutionSettingsDialog(dialog: AlertDialog, fields: LinearLayout) {
        dialog.show()
        // AlertDialog replaces the custom view's parameters with MATCH_PARENT. Keep numeric
        // content at its natural height, including on vendor dialog layouts with weighted panels.
        fields.layoutParams = fields.layoutParams.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
        val decor = dialog.window?.decorView ?: return
        var panel = fields.parent as? ViewGroup
        while (panel != null && panel !== decor) {
            val params = panel.layoutParams
            if (params is LinearLayout.LayoutParams && params.weight > 0f) {
                panel.layoutParams = params.apply {
                    weight = 0f
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                }
                break
            }
            panel = panel.parent as? ViewGroup
        }
        dialog.window?.let { window ->
            window.setLayout(window.attributes.width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        // Run another traversal after the platform has finished its initial button measurement.
        decor.post { if (dialog.isShowing) decor.requestLayout() }
    }

    private fun nightDelaySettingControl(
        parent: LinearLayout,
        titleId: Int,
        hintId: Int,
        range: IntRange,
        default: Int,
        summaryId: Int,
        load: () -> Int,
        reconnects: Boolean = false,
        save: (Int) -> Unit,
    ) {
        val title = getString(titleId)
        fun summary() = getString(R.string.contrib_audio_home_choice_summary, title, getString(summaryId, load()))
        val control = button(summary(), false) {}
        control.setOnClickListener {
            val fields = column().apply { setPadding(dp(24), dp(8), dp(24), dp(8)) }
            val input = EditText(appDialogContext()).apply {
                setSingleLine()
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText(load().toString())
            }
            fields.addView(input)
            fields.addView(label(getString(hintId), 14, MUTED))
            val dialog = appDialogBuilder().setTitle(title).setView(fields)
                .setPositiveButton(getString(if (reconnects && CarPlayBackgroundSession.hasSession()) R.string.apply_and_reconnect else R.string.save), null)
                .setNegativeButton(getString(R.string.cancel), null)
                .setNeutralButton(getString(R.string.ambient_light_reset_defaults), null).create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val value = input.text.toString().trim().toIntOrNull()
                    if (value == null || value !in range) {
                        input.error = getString(R.string.custom_number_error, range.first, range.last)
                    } else {
                        val changed = value != load()
                        save(value)
                        control.text = summary()
                        dialog.dismiss()
                        if (changed && reconnects && CarPlayBackgroundSession.hasSession()) {
                            connect(AirPlayPersistence.loadWirelessEnabled(this))
                        }
                    }
                }
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    input.setText(default.toString())
                    input.error = null
                }
            }
            showNightModeSettingsDialog(dialog, fields)
        }
        parent.addView(control, matchButton(0, 60))
        parent.addView(space(12))
    }

    private fun ambientLightThresholdControl(parent: LinearLayout) {
        val title = getString(R.string.ambient_light_threshold_title)
        fun summary(): String = getString(
            R.string.contrib_audio_home_choice_summary,
            title,
            getString(R.string.ambient_light_threshold_summary, AirPlayPersistence.loadAmbientLightThreshold(this).lux),
        )

        val control = button(summary(), false) {}
        control.setOnClickListener {
            val fields = column().apply { setPadding(dp(24), dp(8), dp(24), dp(8)) }
            val input = EditText(appDialogContext()).apply {
                setSingleLine()
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText(AirPlayPersistence.loadAmbientLightThreshold(this@DiPlayActivity).lux.toString())
            }
            fields.addView(input)
            fields.addView(label(getString(R.string.ambient_light_threshold_hint), 14, MUTED))
            val dialog = appDialogBuilder()
                .setTitle(title)
                .setView(fields)
                .setPositiveButton(getString(R.string.save), null)
                .setNegativeButton(getString(R.string.cancel), null)
                .setNeutralButton(getString(R.string.ambient_light_reset_defaults), null)
                .create()

            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener saveThreshold@{
                    val lux = input.text.toString().trim().toIntOrNull()
                    if (lux == null || !AmbientLightThreshold.isValid(lux)) {
                        input.error = getString(R.string.ambient_light_threshold_error)
                        return@saveThreshold
                    }
                    AirPlayPersistence.saveAmbientLightThreshold(this, AmbientLightThreshold(lux))
                    control.text = summary()
                    dialog.dismiss()
                }
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    input.setText(AmbientLightThreshold.DEFAULT_LUX.toString())
                    input.error = null
                }
            }
            showNightModeSettingsDialog(dialog, fields)
        }
        parent.addView(control, matchButton(0, 60))
        parent.addView(space(12))
    }

    private fun showNightModeSettingsDialog(dialog: AlertDialog, fields: LinearLayout) {
        dialog.show()
        // AlertDialog replaces the custom view's parameters with MATCH_PARENT. Keep numeric
        // content at its natural height, including on vendor dialog layouts with weighted panels.
        fields.layoutParams = fields.layoutParams.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
        val decor = dialog.window?.decorView ?: return
        var panel = fields.parent as? ViewGroup
        while (panel != null && panel !== decor) {
            val params = panel.layoutParams
            if (params is LinearLayout.LayoutParams && params.weight > 0f) {
                panel.layoutParams = params.apply {
                    weight = 0f
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                }
                break
            }
            panel = panel.parent as? ViewGroup
        }
        dialog.window?.let { window ->
            window.setLayout(window.attributes.width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        // Run another traversal after the platform has finished its initial button measurement.
        decor.post { if (dialog.isShowing) decor.requestLayout() }
    }

    /** Where CarPlay puts its dock; between the two fixed edges it moves at once, otherwise on reconnect. */
    private fun carPlayDockControl(card: LinearLayout) {
        val docks = CarPlayDock.entries
        choice(card, getString(R.string.carplay_dock), listOf(
            getString(R.string.carplay_dock_automatic),
            getString(R.string.carplay_dock_driver_side),
            getString(R.string.carplay_dock_bottom),
        ), docks.indexOf(CarPlayDock.load(this)), reconnects = false) {
            val from = CarPlayDock.load(this)
            val to = docks[it]
            CarPlayDock.save(this, to)
            val session = CarPlayBackgroundSession.snapshot()
            val areas = session?.display?.viewAreas
            val target = to.edge?.let { areas?.withDock(it) }
            if (CarPlayDock.movesLive(from, to) && areas != null && target != null) {
                if (session.controller.showViewArea(target)) areas.use(target)
            } else {
                markReconnectNeeded()
            }
        }
        card.addView(label(getString(R.string.carplay_dock_hint), 14, MUTED).apply { setPadding(0, 0, 0, dp(18)) })
    }

    private fun carButtonControls(parent: LinearLayout) {
        val custom = AirPlayPersistence.loadCustomAirPlayIconFile(this)?.let { BitmapFactory.decodeFile(it.absolutePath) }
        val preview = row().apply { gravity = Gravity.CENTER_VERTICAL }
        preview.addView(ImageView(this).apply {
            setImageBitmap(custom ?: BitmapFactory.decodeResource(resources, R.raw.ic_car_home))
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(SURFACE, BORDER)
            clipToOutline = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(72), dp(72)).apply { marginEnd = dp(16) })
        val text = column()
        text.addView(label(getString(R.string.car_button_icon), 18, TEXT, true))
        text.addView(label(getString(if (custom != null) R.string.car_button_icon_custom else R.string.default_icon), 14, MUTED).apply { setPadding(0, dp(6), 0, 0) })
        preview.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        parent.addView(preview)
        parent.addView(button(getString(R.string.choose_image), false) {
            launchCarButtonImagePicker(
                openDocument = { iconDocumentPicker.launch(arrayOf("image/*")) },
                getContent = { iconPicker.launch("image/*") },
                documentPickerIsSystem = documentPickerIsSystem(),
            ).onFailure { toast(getString(R.string.this_head_unit_has_no_image_picker)) }
        }, matchButton(16, 60))
        if (custom != null) parent.addView(button(getString(R.string.default_icon), false) {
            AirPlayPersistence.clearCustomAirPlayIcon(this)
            refreshCarButton()
            carButtonSaved()
        }, matchButton(10, 60))
        val name = AirPlayPersistence.loadOemLabel(this)
        parent.addView(button("${getString(R.string.car_button_name)} · $name", false) {
            textInput(getString(R.string.car_button_name), name, secret = false) {
                AirPlayPersistence.saveOemLabel(this, it)
                refreshCarButton()
                carButtonSaved()
            }
        }, matchButton(10, 60))
        parent.addView(label(getString(R.string.car_button_description), 14, MUTED).apply { setPadding(0, dp(12), 0, 0) })
    }

    private fun carButtonSaved() {
        markReconnectNeeded()
        if (CarPlayBackgroundSession.hasSession()) toast(getString(R.string.car_button_saved_next_connection))
    }

    // Rebuilds only this card: render() would scroll the page back to the top.
    private fun refreshCarButton() {
        val card = carButtonCard ?: return
        card.removeViews(1, card.childCount - 1)
        carButtonControls(card)
        normalizeSpacing(card)
    }

    private fun carPlaySizeControl(parent: LinearLayout) {
        val sizes = com.shilapi.xcertplay.airplay.CarPlaySize.entries
        val current = com.shilapi.xcertplay.airplay.CarPlaySize.fromWidthMillimeters(AirPlayPersistence.loadWidthPhysicalMm(this))
        choice(parent, getString(R.string.carplay_size), sizes.map { it.localizedLabel(this) }, sizes.indexOf(current)) {
            AirPlayPersistence.saveWidthPhysicalMm(this, sizes[it].widthMillimeters)
        }
        parent.addView(label(getString(R.string.changes_the_size_of_carplay_icons_and_text_applying_a_size), 14, MUTED).apply {
            setPadding(0, 0, 0, dp(18))
        })
    }

    private fun scheduleTimeControl(parent: LinearLayout, start: Boolean) {
        val title = getString(if (start) R.string.carplay_night_start else R.string.carplay_night_end)
        fun labelFor(minute: Int) = "$title · ${String.format(Locale.getDefault(), "%02d:%02d", minute / 60, minute % 60)}"
        val initial = AirPlayPersistence.loadCarPlayNightSchedule(this)
        val control = button(labelFor(if (start) initial.startMinute else initial.endMinute), false) {}
        control.setOnClickListener {
            val current = AirPlayPersistence.loadCarPlayNightSchedule(this)
            val selected = if (start) current.startMinute else current.endMinute
            TimePickerDialog(this, appDialogTheme(), { _, hour, minute ->
                val updatedMinute = hour * 60 + minute
                val other = if (start) current.endMinute else current.startMinute
                if (updatedMinute == other) {
                    toast(getString(R.string.carplay_night_schedule_same_time))
                } else {
                    val updated = if (start) current.copy(startMinute = updatedMinute)
                        else current.copy(endMinute = updatedMinute)
                    AirPlayPersistence.saveCarPlayNightSchedule(this, updated)
                    control.text = labelFor(updatedMinute)
                    handler.post(::checkForAppearanceChange)
                }
            }, selected / 60, selected % 60, true).show()
        }
        parent.addView(control, matchButton(0, 60))
        parent.addView(space(12))
    }

    private fun connect(wireless: Boolean) {
        startupHotspotCancelled = true
        if (wireless && pendingCarHotspotSetup) { toast(getString(R.string.save_your_hotspot_details_in_connection_setup_first)); page = "connection"; render(); return }
        if (setupError != null) { toast(setupError!!); return }
        if (wireless && AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL &&
            hotspotError(storedSsid(), storedPassword()) != null) {
            pendingCarHotspotSetup = true
            page = "connection"
            render()
            toast(getString(R.string.save_the_name_and_password_from_the_car_s_hotspot_settings))
            return
        }
        if (wireless && carHotspotOff()) { carHotspotOffDialog(); return }
        if (wireless && DiPlayPreferences.phoneAddress(this) == null) {
            pendingWireless = true; choosePhone(); return
        }
        val preferences = getSharedPreferences("diplay", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !preferences.getBoolean("notification_asked", false)) {
            preferences.edit().putBoolean("notification_asked", true).apply()
            notificationTransport = wireless
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        val open = {
            AirPlayPersistence.saveWirelessEnabled(this, wireless)
            openProjection()
        }
        if (CarPlayBackgroundSession.hasSession()) CarPlayBackgroundSession.stop { runOnUiThread { open() } }
        else open()
    }
    private fun openProjection() {
        startActivity(Intent(this, CarPlayHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }
    private fun choosePhone() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT); return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            appDialogBuilder().setTitle(getString(R.string.turn_on_bluetooth))
                .setMessage(getString(R.string.enable_the_car_s_bluetooth_and_pair_your_iphone_first))
                .setPositiveButton(getString(R.string.open_bluetooth)) { _, _ -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                .setNegativeButton(getString(R.string.later), null).show(); return
        }
        val devices = runCatching { adapter.bondedDevices.sortedBy { it.name ?: "" } }.getOrDefault(emptyList())
        if (devices.isEmpty()) {
            appDialogBuilder().setTitle(getString(R.string.pair_your_iphone))
                .setMessage(getString(R.string.on_your_iphone_open_settings_bluetooth_and_pair_with_the_c))
                .setPositiveButton(getString(R.string.open_bluetooth)) { _, _ -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                .setNegativeButton(getString(R.string.got_it), null).show(); return
        }
        appDialogBuilder().setTitle(getString(R.string.choose_your_iphone))
            .setItems(devices.map { device ->
                val name = device.name ?: getString(R.string.paired_device)
                if (devices.count { it.name == device.name } > 1) "$name · ${device.address.takeLast(5)}" else name
            }.toTypedArray()) { _, index ->
                val device = devices[index]
                DiPlayPreferences.savePhone(this, device.address, device.name ?: "iPhone")
                val start = pendingWireless; pendingWireless = false
                render()
                if (start) connect(true)
            }.setNeutralButton(getString(R.string.pair_another)) { _, _ -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            .setNegativeButton(getString(R.string.cancel)) { _, _ -> pendingWireless = false }.show()
    }

    private fun wirelessHelp() {
        appDialogBuilder().setTitle(getString(R.string.wireless_connection_help))
            .setMessage(getString(R.string.pair_your_iphone_with_the_car_s_bluetooth_keep_wi_fi_on_an))
            .setPositiveButton(getString(R.string.got_it), null)
            .setNeutralButton(getString(R.string.reset_carplay_wi_fi)) { _, _ ->
                confirmWirelessReset()
            }.show()
    }

    private fun handleWirelessRecovery() {
        if (page != "wireless-recovery") return
        page = "home"; render()
        confirmWirelessReset()
    }

    private fun confirmWirelessReset() {
        appDialogBuilder().setTitle(getString(R.string.reset_carplay_wi_fi_2))
            .setMessage(getString(R.string.this_ends_the_existing_wi_fi_direct_connection_including_o))
            .setPositiveButton(getString(R.string.reset_and_connect)) { _, _ ->
                CarPlayBackgroundSession.stop { runOnUiThread { resetWirelessGroup() } }
            }.setNegativeButton(getString(R.string.cancel), null).show()
    }

    private fun resetWirelessGroup() {
        val manager = getSystemService(android.net.wifi.p2p.WifiP2pManager::class.java)
        if (manager == null) { toast(getString(R.string.this_head_unit_does_not_support_wi_fi_direct)); return }
        val channel = manager.initialize(this, mainLooper, null)
        try {
            manager.requestGroupInfo(channel) { group ->
                if (group == null) { channel.closeCompat(); connect(true); return@requestGroupInfo }
                manager.removeGroup(channel, object : android.net.wifi.p2p.WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        val deadline = android.os.SystemClock.elapsedRealtime() + 4000
                        fun waitUntilRemoved() {
                            manager.requestGroupInfo(channel) { remaining ->
                                when {
                                    remaining == null -> { channel.closeCompat(); if (!isFinishing && !isDestroyed) connect(true) }
                                    android.os.SystemClock.elapsedRealtime() >= deadline -> {
                                        channel.closeCompat(); toast(getString(R.string.wi_fi_direct_is_still_busy_close_the_other_projection_app))
                                    }
                                    else -> handler.postDelayed({ waitUntilRemoved() }, 200)
                                }
                            }
                        }
                        waitUntilRemoved()
                    }
                    override fun onFailure(reason: Int) { channel.closeCompat(); toast(getString(R.string.could_not_reset_wi_fi_direct_close_the_other_projection_ap)) }
                })
            }
        } catch (_: SecurityException) {
            channel.closeCompat(); permissionHelp(getString(R.string.wireless_permissions), getString(R.string.allow_nearby_devices_and_on_older_android_versions_locatio))
        }
    }

    private fun refreshStatus() {
        val running = CarPlayBackgroundSession.hasSession()
        status?.text = when {
            setupError != null -> getString(R.string.setup_needs_attention)
            CarPlayBackgroundSession.active -> getString(R.string.carplay_connected)
            running -> getString(R.string.connecting_to_your_iphone)
            DiPlayPreferences.phoneAddress(this) != null -> "${getString(R.string.status_ready_for_prefix)}${DiPlayPreferences.phoneName(this)}"
            else -> getString(R.string.ready_when_you_are)
        }
        if (lastRunning != running) {
            connectButton?.text = if (running) getString(R.string.open_carplay) else getString(R.string.connect_phone)
            disconnectButton?.visibility = if (running) View.VISIBLE else View.GONE
            disconnectButton?.isEnabled = true
            lastRunning = running
        }
        connectButton?.isEnabled = setupError == null
        refreshReconnectBar()
        refreshReadiness()
    }
    // v100 B561: authorizeClusterRouting(ADB 集群路由授权)随 BYD 集成删除。

    private fun reportFileName() = "DiPlay-${SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())}.txt"

    private fun saveReportToDownloads() {
        if (exportInProgress) return
        if (DiagnosticExportStore.needsStoragePermission(this)) {
            runCatching { reportStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) }
                .onFailure { exportDiagnostics() }
        } else exportDiagnostics()
    }

    private fun chooseReportDestination() {
        // Some head units omit or disable DocumentsUI. Launch itself can throw, before
        // the result callback and the background writer's exception handler ever run.
        if (exportInProgress) return
        runCatching { export.launch(reportFileName()) }.onFailure { exportDiagnostics() }
    }

    private fun exportDiagnostics(uri: Uri? = null) {
        if (exportInProgress) return
        exportInProgress = true
        exportButton?.apply { isEnabled = false; text = getString(R.string.saving_report) }
        val appContext = applicationContext
        val fileName = reportFileName()
        Thread({
            val result = runCatching {
                val report = diagnosticReportText(appContext)
                val savedReport = if (uri != null) {
                    DiagnosticExportStore.write(appContext.contentResolver, uri, report)
                    DiagnosticExportStore.SavedReport(uri)
                } else DiagnosticExportStore.saveWithoutPicker(appContext, fileName, report)
                savedReport to report
            }
            runOnUiThread {
                exportInProgress = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                exportButton?.apply { isEnabled = true; text = getString(R.string.save_diagnostic_report) }
                if (result.isSuccess) {
                    val (savedReport, report) = result.getOrThrow()
                    appDialogBuilder().setTitle(getString(R.string.diagnostic_report_saved))
                        .setMessage(when {
                            savedReport.savedInApp -> getString(R.string.diagnostic_report_saved_in_app)
                            savedReport.savedPath != null -> getString(R.string.diagnostic_report_saved_to_path, savedReport.savedPath)
                            uri == null -> "Downloads/DiPlay/$fileName"
                            else -> getString(R.string.your_report_was_saved_to_the_selected_location)
                        })
                        .setPositiveButton(getString(R.string.view_diagnostic_report)) { _, _ -> showDiagnosticReport(report) }
                        .setNegativeButton(getString(R.string.done), null)
                        .setNeutralButton(getString(R.string.share)) { _, _ ->
                            runCatching {
                                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"; putExtra(Intent.EXTRA_STREAM, savedReport.uri)
                                    clipData = android.content.ClipData.newRawUri(getString(R.string.report_clip_label), savedReport.uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }, getString(R.string.share_diagnostic_report)))
                            }.onFailure { showDiagnosticReport(report) }
                        }.show()
                } else {
                    appDialogBuilder().setTitle(getString(R.string.could_not_save_the_report))
                        .setMessage(getString(R.string.check_that_storage_is_available_or_choose_another_save_loc))
                        .setPositiveButton(getString(R.string.choose_location)) { _, _ -> chooseReportDestination() }
                        .setNegativeButton(getString(R.string.close), null).show()
                }
            }
        }, "diplay-export").start()
    }

    /** The text of a diagnostic report. It reads the session logs, so call it off the main thread. */
    private fun diagnosticReportText(appContext: Context): String = buildString {
        appendLine("DiPlay ${version()} · private beta diagnostic report")
        appendLine("Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
        appendLine("Head unit: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Connection: ${if (AirPlayPersistence.loadWirelessEnabled(appContext)) "wireless" else "USB"}")
        AirPlayPersistence.loadUsbChargingCurrent(appContext).let {
            appendLine("USB charging offer: ${it.key} (${it.milliAmps} mA)")
        }
        appendLine("Authentication: local experimental beta identity; no remote fallback")
        appendLine("CarPlay setup: ${if (setupError == null) "ready" else "authentication unavailable"}")
        appendLine("Saved video preference (may differ from active session): ${if (AirPlayPersistence.loadHevcEnabled(appContext)) "HEVC" else "H.264"}; ${AirPlayPersistence.loadFps(appContext)} fps")
        appendLine("Media-key audio focus: ${if (AirPlayPersistence.loadDisableMediaKeyAudioFocus(appContext)) "disabled" else "enabled"}")
        appendLine("MTK decoder tuning preference: ${if (AirPlayPersistence.loadMtkDecoderTuning(appContext)) "enabled" else "disabled"}")
        appendLine("CarPlay size: ${com.shilapi.xcertplay.airplay.CarPlaySize.fromWidthMillimeters(AirPlayPersistence.loadWidthPhysicalMm(appContext)).label}")
        appendLine("Saved resolution preference (may differ from active session): ${AirPlayPersistence.loadDisplayScalePercent(appContext)}%")
        appendLine("Session: ${if (CarPlayBackgroundSession.active) "active" else if (CarPlayBackgroundSession.hasSession()) "connecting" else "stopped"}")
        appendLine(PhoneWakeDiagnostics.report(appContext))
        appendLine("Head-unit board: ${Build.BOARD}; hardware: ${Build.HARDWARE}; build: ${Build.DISPLAY}")
        appendLine()
        appendLine("--- Current cluster display diagnostics (even when disabled) ---")
        appendLine(ClusterMapPresentation.diagnosticReport(appContext))
        // v100 B561: 原「ADB 集群路由」「独立 HUD 兼容」「BYD 车辆探测」三段诊断
        // 随 BYD 集成删除 —— 仪表显示走用户显式选屏(见上面的 cluster display diagnostics)。
        appendLine()
        appendLine("--- Last display negotiation (timestamps distinguish it from current settings) ---")
        appendLine(DisplayDiagnosticSnapshot.report(appContext))
        appendLine()
        appendLine("--- Last received boot and app-launch result ---")
        appendLine(StartupDiagnosticSnapshot.report(appContext))
        appendLine("Startup settings: openAfterBoot=${AirPlayPersistence.loadAutoStartOnBoot(appContext)} " +
            "connectWhenOpened=${DiPlayPreferences.autoConnect(appContext)} " +
            "connectOnSelectedPhoneBluetooth=${DiPlayPreferences.connectOnPhoneBluetooth(appContext)}")
        appendLine(WheelKeyService.report(appContext))
        appendLine()
        appendLine(UpdateAvailability.report(appContext, System.currentTimeMillis()))
        appendLine()
        appendLine("--- Recent own-app process exits (Android 11+) ---")
        appendLine(ProcessExitDiagnostics.report(appContext))
        appendLine()
        for (name in SessionLogFile.REPORT_NAMES) {
            val file = File(appContext.filesDir, "logs/$name")
            if (file.isFile) {
                appendLine("--- $name ---")
                file.useLines { lines -> lines.forEach { line -> DiagnosticRedactor.redact(line)?.let { appendLine(it) } } }
            }
        }
    }

    /** Read-only turn camera probe (phase A): user-triggered, deadline-bounded, nothing auto-sent. */
    /** Bumped per run and on destroy: a late result from an older run is discarded (Rework R5). */

    // v100 B561: runTurnCameraProbe(转向侧视摄像头探测)随 BYD ADB 通道一起删除。

    private fun showTurnCameraProbeReport(report: String) {
        val body = column().apply {
            setPadding(dp(SETTINGS_DIALOG_PADDING_DP), dp(SETTINGS_DIALOG_GAP_DP), dp(SETTINGS_DIALOG_PADDING_DP), dp(SETTINGS_DIALOG_GAP_DP))
        }
        body.addView(label(report, 13, TEXT).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        })
        appDialogBuilder().setTitle(getString(R.string.settings_turn_camera_probe_title))
            .setView(ScrollView(appDialogContext()).apply { addView(body) })
            .setPositiveButton(getString(R.string.settings_turn_camera_probe_copy)) { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                    getString(R.string.settings_turn_camera_probe_title), report))
            }
            .setNegativeButton(getString(R.string.close), null).show()
    }

    private fun showDiagnosticReport(report: String) {
        val body = column().apply {
            setPadding(dp(SETTINGS_DIALOG_PADDING_DP), dp(SETTINGS_DIALOG_GAP_DP), dp(SETTINGS_DIALOG_PADDING_DP), dp(SETTINGS_DIALOG_GAP_DP))
        }
        body.addView(label(getString(R.string.diagnostic_report_copy_hint), 14, MUTED))
        body.addView(label(report, 13, TEXT).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        })
        appDialogBuilder().setTitle(getString(R.string.view_diagnostic_report))
            .setView(ScrollView(appDialogContext()).apply { addView(body) })
            .setPositiveButton(getString(R.string.close), null).show()
    }
    private fun permissionHelp(title: String, body: String) {
        appDialogBuilder().setTitle(title).setMessage(body).setPositiveButton(getString(R.string.app_settings)) { _, _ ->
            openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }.setNegativeButton(getString(R.string.later), null).show()
    }
    private fun openSystem(intent: Intent) { runCatching { startActivity(intent) }.onFailure { toast(getString(R.string.open_this_setting_from_your_car_s_settings_app)) } }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }

    private fun playTestTone(streamType: Int) {
        toneStop?.let { handler.removeCallbacks(it) }
        toneStop = null
        testToneTrack?.let { runCatching { it.stop(); it.release() } }
        testToneTrack = null
        var candidate: AudioTrack? = null
        val track = try {
            val pcm = assets.open("navigation_test.pcm").use { it.readBytes() }
            AudioTrack(streamType, 44100, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT, pcm.size, AudioTrack.MODE_STREAM).also {
                candidate = it
                check(it.state == AudioTrack.STATE_INITIALIZED)
                check(it.write(pcm, 0, pcm.size) == pcm.size)
                it.play()
            }
        } catch (error: Exception) {
            val state = candidate?.state ?: AudioTrack.STATE_UNINITIALIZED
            candidate?.let { runCatching { it.release() } }
            Log.w("DiPlay", "playTestTone streamType=$streamType unavailable", error)
            toast(getString(R.string.audio_stream_unavailable, streamType, state))
            return
        }
        Log.i("DiPlay", "playTestTone streamType=$streamType state=${track.state} playState=${track.playState}")
        testToneTrack = track
        val stop = Runnable {
            track.stop()
            track.release()
            if (testToneTrack === track) testToneTrack = null
            toneStop = null
        }
        toneStop = stop
        handler.postDelayed(stop, 4500)
    }

    private val channelButtons = mutableListOf<Button>()

    private fun paintChannel(index: Int, selected: Boolean) {
        val target = channelButtons.getOrNull(index) ?: return
        target.isSelected = selected
        target.setTextColor(if (selected) ON_ACCENT else TEXT)
        target.background = android.graphics.drawable.RippleDrawable(
            ColorStateList.valueOf(RIPPLE),
            buttonShape(if (selected) ACCENT else SURFACE, if (selected) ACCENT else BORDER, target.height),
            null
        )
    }

    private fun channelSelector(): ViewGroup {
        channelButtons.clear()
        val grid = GridLayout(this).apply {
            columnCount = 7
            rowCount = 3
            setPadding(0, dp(8), 0, dp(8))
        }
        for (i in 0..20) {
            val btn = Button(this).apply {
                text = i.toString()
                isAllCaps = false
                textSize = 16f
                minHeight = dp(48)
                stateListAnimator = null
                setOnClickListener {
                    val previous = navigationStreamType
                    navigationStreamType = i
                    if (previous != i) {
                        paintChannel(previous, false)
                        paintChannel(i, true)
                    }
                    playTestTone(i)
                }
            }
            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = dp(48)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
            grid.addView(btn, params)
            channelButtons.add(btn)
            paintChannel(i, i == navigationStreamType)
        }
        return grid
    }
    private fun version() = packageManager.getPackageInfo(packageName, 0).versionName ?: "0.1.0-beta.1"
    private fun languageSettings(content: LinearLayout) {
        filteredSection(content, SettingsSection.LANGUAGE,
            getString(R.string.language_section_title), R.drawable.ic_dp_language) { card ->
            card.addView(label(getString(R.string.language_hint), 14, MUTED))
            val current = AppLocale.preference(this)
            val languageButton = button("${getString(R.string.language_app_language)} · ${AppLocale.displayName(this, current)}", false) { }
            languageButton.setOnClickListener { AppLocale.showPicker(this) }
            card.addView(languageButton, matchButton(12, 60))
        }
    }

    private fun section(parent: LinearLayout, title: String, icon: Int? = null, build: (LinearLayout) -> Unit) {
        searchIndexSink?.add(title)
        val card = card()
        val heading = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(16)) }
        if (icon != null) heading.addView(ImageView(this).apply {
            setImageResource(icon); imageTintList = ColorStateList.valueOf(ACCENT)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(12) })
        heading.addView(label(title, 22, TEXT, true,
            centreGlyphs = icon != null && usesChineseGlyphAlignment(resources.configuration.locales[0].language, title)),
            LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(heading)
        build(card)
        if (page == "settings") normalizeSpacing(card)
        parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(SETTINGS_BLOCK_GAP_DP) })
    }
    private fun audioFocusControls(parent: LinearLayout) {
        toggle(parent, getString(R.string.settings_disable_media_key_audio_focus),
            getString(R.string.settings_disable_media_key_audio_focus_description),
            AirPlayPersistence.loadDisableMediaKeyAudioFocus(this)) {
            AirPlayPersistence.saveDisableMediaKeyAudioFocus(this, it)
            markReconnectNeeded()
        }
        val enabled = AirPlayPersistence.loadAudioFocusEnabled(this)
        val dependent = column().apply { visibility = if (enabled) View.VISIBLE else View.GONE }
        toggle(parent, getString(R.string.contrib_audio_home_toggle_audio_focus),
            getString(R.string.contrib_audio_home_toggle_audio_focus_desc), enabled) {
            AirPlayPersistence.saveAudioFocusEnabled(this, it)
            markReconnectNeeded()
            dependent.visibility = if (it) View.VISIBLE else View.GONE
        }
        toggle(dependent, getString(R.string.audio_focus_auto_yield), getString(R.string.audio_focus_auto_yield_desc),
            AirPlayPersistence.loadAudioFocusAutoYield(this)) {
            AirPlayPersistence.saveAudioFocusAutoYield(this, it)
            markReconnectNeeded()
        }
        parent.addView(dependent)
    }


    private fun filteredSection(
        parent: LinearLayout,
        key: SettingsSection,
        title: String,
        icon: Int? = null,
        build: (LinearLayout) -> Unit,
    ) {
        if (settingsSectionFilter?.contains(key) == false) return
        section(parent, title, icon, build)
    }
    // Routing changes affect the live cluster surface as soon as the host resumes.
    // Ask before saving them instead of promising to defer only part of the change.
    private fun reconnectingToggle(parent: LinearLayout, title: String, description: String,
        value: Boolean, enabled: Boolean = true, save: (Boolean) -> Unit) {
        toggle(parent, title, description, value, enabled) { selected ->
            if (!CarPlayBackgroundSession.hasSession()) {
                save(selected)
            } else {
                appDialogBuilder().setTitle(title).setMessage(description)
                    .setPositiveButton(R.string.apply_and_reconnect) { _, _ ->
                        save(selected)
                        reconnectIfRunning()
                    }
                    .setNegativeButton(R.string.cancel) { _, _ -> render() }
                    .setOnCancelListener { render() }
                    .show()
            }
        }
    }

    private fun toggle(parent: LinearLayout, title: String, description: String, value: Boolean, enabled: Boolean = true, save: (Boolean) -> Unit): Switch {
        searchIndexSink?.add(title)
        val result = SettingsWidgets.createSwitchRow(
            context = this,
            label = title,
            description = description,
            checked = value,
            theme = SettingsTheme.card(palette),
            contentDescription = title,
            enabled = enabled,
            onChanged = save,
        )
        parent.addView(result.rowView)
        return result.switch
    }
    // v100 B561: 氛围灯配置标签(BYD 专属)已删除。

    // v100 B561: showAmbientConfiguration(氛围灯配置页)随 BYD 集成删除。

    // [announcesReconnect] labels a choice whose [save] reconnects by itself.
    private fun choice(parent: LinearLayout, title: String, options: List<String>, current: Int, reconnects: Boolean = true,
        announcesReconnect: Boolean = reconnects, enabled: Boolean = true, save: (Int) -> Unit) {
        var selection = current
        val button = button("$title · ${options[selection]}", false) {}.apply { isEnabled = enabled }
        button.setOnClickListener {
            var pendingSelection = selection
            appDialogBuilder().setTitle(title)
                .setSingleChoiceItems(options.toTypedArray(), selection) { _, index -> pendingSelection = index }
                .setPositiveButton(getString(if (announcesReconnect && CarPlayBackgroundSession.hasSession()) R.string.apply_and_reconnect else R.string.save)) { _, _ ->
                    if (pendingSelection != selection) {
                        selection = pendingSelection
                        save(selection)
                        button.text = "$title · ${options[selection]}"
                        if (reconnects && CarPlayBackgroundSession.hasSession()) {
                            connect(AirPlayPersistence.loadWirelessEnabled(this))
                        }
                    }
                }.setNegativeButton(getString(R.string.cancel), null).show()
        }
        parent.addView(button, matchButton(0, 60)); parent.addView(space(12))
    }
    private fun card() = column().apply { background = rounded(SURFACE, BORDER); setPadding(dp(24), dp(24), dp(24), dp(24)) }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(-1, -2) }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(-1, -2) }
    private class SettingsSearchEditText(context: android.content.Context) : EditText(context) {
        private val glyphBounds = android.graphics.Rect()
        private var measuredHint: String? = null
        private var measuredHintHeight = -1
        private var measuredHintTextSize = -1f
        private var measuredHintBaseline = 0

        override fun onDraw(canvas: android.graphics.Canvas) {
            val value: CharSequence = if (text.isNullOrEmpty()) hint ?: "" else text
            val drawingBaseline = if (text.isNullOrEmpty() && value.isNotEmpty()) hintBaseline(value) else baseline
            val offset = if (baseline >= 0) {
                val contentCentre = paddingTop + (height - paddingTop - paddingBottom) / 2f
                contentCentre - alignedTextCentreY(this, value, glyphBounds, drawingBaseline)
            } else 0f
            // Text, selection and cursor move together.
            val checkpoint = canvas.save()
            canvas.translate(0f, offset)
            super.onDraw(canvas)
            canvas.restoreToCount(checkpoint)
        }

        /**
         * TextView.baseline describes the editable layout, even while it draws a hint, and a hint in
         * a fallback font can sit differently. Measured once per hint, height and text size rather
         * than on every frame of the blinking cursor.
         */
        private fun hintBaseline(value: CharSequence): Int {
            if (value.toString() != measuredHint || height != measuredHintHeight || paint.textSize != measuredHintTextSize) {
                // The editor scrolls horizontally rather than wrapping: lay the hint out on one line.
                val hintWidth = maxOf(
                    (width - compoundPaddingLeft - compoundPaddingRight).coerceAtLeast(1),
                    kotlin.math.ceil(android.text.Layout.getDesiredWidth(value, paint).toDouble()).toInt(),
                )
                val hintLayout = android.text.StaticLayout.Builder.obtain(value, 0, value.length, paint, hintWidth)
                    .setMaxLines(1).setIncludePad(includeFontPadding).apply {
                        if (Build.VERSION.SDK_INT >= 28) setUseLineSpacingFromFallbacks(isFallbackLineSpacing)
                    }.build()
                measuredHintBaseline = extendedPaddingTop +
                    ((height - extendedPaddingTop - extendedPaddingBottom - hintLayout.height).coerceAtLeast(0) / 2) +
                    hintLayout.getLineBaseline(0)
                measuredHint = value.toString()
                measuredHintHeight = height
                measuredHintTextSize = paint.textSize
            }
            return measuredHintBaseline
        }
    }

    private class HeaderTitleView(context: android.content.Context) : TextView(context) {
        private val glyphBounds = android.graphics.Rect()

        override fun onDraw(canvas: android.graphics.Canvas) {
            val value = text.toString()
            paint.getTextBounds(value, 0, value.length, glyphBounds)
            val checkpoint = canvas.save()
            if (layout?.lineCount == 1 && !glyphBounds.isEmpty && baseline >= 0) {
                val textCentre = alignedTextCentreY(this, value, glyphBounds)
                val contentCentre = paddingTop + (height - paddingTop - paddingBottom) / 2f
                canvas.translate(0f, contentCentre - textCentre)
            }
            super.onDraw(canvas)
            canvas.restoreToCount(checkpoint)
        }
    }

    private fun label(value: String, size: Int, color: Int, bold: Boolean = false, centreGlyphs: Boolean = false) =
        (if (centreGlyphs) HeaderTitleView(this) else TextView(this)).apply {
        text = value; textSize = size.toFloat(); setTextColor(color); gravity = Gravity.CENTER_VERTICAL
        typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.create("sans-serif", Typeface.NORMAL)
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    private fun headerIconSize(compact: Boolean) = dp(if (compact) 20 else 24)

    private fun headerButton(title: String, icon: Int, compact: Boolean, click: () -> Unit) = button(title, false, click).apply {
        alignVisibleGlyphs = false
        if (compact) textSize = 16f
        includeFontPadding = false
        setTextColor(TEXT)
        setSingleLine(true)
        gravity = Gravity.CENTER
        val iconSize = headerIconSize(compact)
        val drawable = getDrawable(icon)?.mutate()?.apply {
            setTint(TEXT)
            setBounds(0, 0, iconSize, iconSize)
        }
        setCompoundDrawablesRelative(drawable, null, null, null)
        compoundDrawablePadding = dp(8)
        doOnLayout {
            // Use the same text alignment reference as other buttons in this locale.
            if (baseline >= 0 && text.isNotEmpty()) {
                val glyphCentre = textAlignmentCentreY()
                val drawableCentre = paddingTop + (height - paddingTop - paddingBottom) / 2f
                val offset = (glyphCentre - drawableCentre).roundToInt()
                drawable?.setBounds(0, offset, iconSize, iconSize + offset)
                // Move the aligned icon/text group to the button's visual centre.
                contentOffsetY = drawableCentre - glyphCentre
                invalidate()
            }
        }
    }

    private fun layoutCornerRadius(): Float {
        val compact = isCompactLayout && (page != "settings" || !SettingsLayoutPreferences.isActive(this))
        return dp(if (compact) 44 else 52) * 20f / 56
    }

    private fun buttonShape(color: Int, stroke: Int, heightPx: Int = 0) = rounded(color, stroke).apply {
        cornerRadius = if (heightPx > 0) minOf(dp(24).toFloat(), heightPx * 20f / 56) else layoutCornerRadius()
    }

    private fun buttonFocusRing() = focusRing(
        (layoutCornerRadius() / resources.displayMetrics.density).roundToInt(),
    )

    private fun button(title: String, primary: Boolean, click: () -> Unit) = SettingButton(this, MUTED, ACCENT).apply {
        isAllCaps = false; textSize = 18f; setTextColor(if (primary) ON_ACCENT else TEXT)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(RIPPLE), buttonShape(if (primary) ACCENT else BUTTON, if (primary) ACCENT else BORDER), null)
        setPadding(dp(16), 0, dp(16), 0); minHeight = dp(56); stateListAnimator = null
        compoundDrawablePadding = dp(12)
        foreground = buttonFocusRing()
        text = title
        searchIndexSink?.add(title.substringBefore(VALUE_SEPARATOR))
        setOnClickListener { click() }
        doOnLayout {
            val shape = buttonShape(if (primary) ACCENT else BUTTON, if (primary) ACCENT else BORDER, height)
            background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(RIPPLE), shape, null)
            foreground = focusRing((shape.cornerRadius / resources.displayMetrics.density).roundToInt())
        }
    }

    private fun iconButton(icon: Int, description: String, click: () -> Unit) = ImageButton(this).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(ACCENT)
        contentDescription = description
        scaleType = ImageView.ScaleType.CENTER
        setPadding(dp(12), dp(12), dp(12), dp(12))
        minimumWidth = dp(48)
        minimumHeight = dp(48)
        stateListAnimator = null
        background = android.graphics.drawable.RippleDrawable(
            ColorStateList.valueOf(RIPPLE),
            buttonShape(BUTTON, BORDER),
            null,
        )
        foreground = buttonFocusRing()
        setOnClickListener { click() }
    }

    // For an action whose label happens to contain the separator, such as "Turn on … · ADB".
    private fun actionButton(title: String, primary: Boolean, click: () -> Unit) =
        button(title, primary, click).apply { action = true; text = title }

    /**
     * Text in the "Title · Value" form opens a choice, so it reads as a setting row:
     * start-aligned, muted value, chevron. Any other text stays a centred action button.
     * The plain text is unchanged, so callers and tests keep matching "Title · Value".
     */
    private class SettingButton(
        context: android.content.Context,
        private val muted: Int,
        private val accent: Int,
    ) : Button(context) {
        var action = false
        var contentOffsetY = 0f
        var alignVisibleGlyphs = true
        private val glyphBounds = android.graphics.Rect()

        fun textAlignmentCentreY(): Float {
            return alignedTextCentreY(this, text, glyphBounds)
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            var offset = contentOffsetY
            if (alignVisibleGlyphs) {
                offset = 0f
                // Wrapped labels keep Android's multiline layout. Measure single-line labels
                // on every draw so changing a setting value also updates its visual centre.
                if (layout?.lineCount == 1 && baseline >= 0) {
                    val textCentre = textAlignmentCentreY()
                    val contentCentre = paddingTop + (height - paddingTop - paddingBottom) / 2f
                    offset = contentCentre - textCentre
                }
                // The chevron is already centred by TextView; compensate for the text shift.
                compoundDrawablesRelative.filterNotNull().forEach { drawable ->
                    val bounds = drawable.bounds
                    val top = -offset.roundToInt()
                    drawable.setBounds(bounds.left, top, bounds.right, top + bounds.height())
                }
            }
            val checkpoint = canvas.save()
            canvas.translate(0f, offset)
            super.onDraw(canvas)
            canvas.restoreToCount(checkpoint)
        }

        override fun setText(text: CharSequence?, type: BufferType?) {
            val split = if (action) -1 else text?.indexOf(VALUE_SEPARATOR) ?: -1
            if (text == null || split <= 0) {
                super.setText(text, type)
                gravity = Gravity.CENTER
                setCompoundDrawablesRelative(null, null, null, null)
                return
            }
            val styled = android.text.SpannableString(text).apply {
                setSpan(android.text.style.ForegroundColorSpan(muted), split, text.length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            super.setText(styled, BufferType.SPANNABLE)
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setCompoundDrawablesRelativeWithIntrinsicBounds(null, null,
                context.getDrawable(R.drawable.ic_dp_chevron)?.mutate()?.apply { setTint(accent) }, null)
        }
    }
    // Remote and D-pad users need to see where they are; touch mode never shows it.
    private fun focusRing(radiusDp: Int = 20) = android.graphics.drawable.StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
            setColor(Color.TRANSPARENT); cornerRadius = dp(radiusDp).toFloat(); setStroke(dp(3), FOCUS_RING)
        })
    }

    private fun rounded(color: Int, stroke: Int) = GradientDrawable().apply { setColor(color); cornerRadius = layoutCornerRadius() * 1.35f; setStroke(dp(1), stroke) }
    private fun matchButton(top: Int = 0, height: Int = 68) = LinearLayout.LayoutParams(-1, dp(height)).apply { topMargin = dp(top) }
    private fun space(height: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(height)); tag = SPACER }

    /**
     * One vertical rhythm for every card, whatever margins each control builder chose:
     * buttons are one height with one gap, a note under a button closes its group with a larger gap,
     * and the last item adds nothing to the card's own padding.
     */
    private fun normalizeSpacing(container: LinearLayout, isCard: Boolean = true) {
        if (container.orientation != LinearLayout.VERTICAL) return
        for (i in container.childCount - 1 downTo 0) {
            if (container.getChildAt(i).tag == SPACER) container.removeViewAt(i)
        }
        fun isNote(view: View?) = view is TextView && view !is Button
        fun isToggleRow(view: View?) = view is LinearLayout && view.orientation == LinearLayout.HORIZONTAL &&
            (0 until view.childCount).any { view.getChildAt(it) is Switch }
        fun isControl(view: View?) = view is Button || isToggleRow(view)
        var previous: View? = null
        var last: View? = null
        var noteFollowsButton = false
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            val next = container.getChildAt(i + 1)
            val lp = child.layoutParams as? LinearLayout.LayoutParams ?: continue
            when {
                child is Button -> {
                    lp.height = dp(CONTROL_HEIGHT_DP); lp.topMargin = 0
                    lp.bottomMargin = dp(if (isNote(next)) NOTE_ATTACH_GAP_DP else CONTROL_GAP_DP)
                }
                // Toggle rows carried their own vertical padding, which doubled the gap after a button.
                isToggleRow(child) -> {
                    child.setPadding(child.paddingLeft, 0, child.paddingRight, 0)
                    lp.topMargin = 0
                    lp.bottomMargin = dp(if (isNote(next)) NOTE_ATTACH_GAP_DP else CONTROL_GAP_DP)
                }
                child is TextView -> {
                    if (!isNote(previous)) noteFollowsButton = isControl(previous)
                    child.setPadding(child.paddingLeft, 0, child.paddingRight, 0)
                    lp.topMargin = 0
                    // Lines of one note stay together; the gap after the last line closes the group.
                    lp.bottomMargin = dp(when {
                        isNote(next) -> NOTE_LINE_GAP_DP
                        noteFollowsButton -> GROUP_GAP_DP
                        else -> CONTROL_GAP_DP
                    })
                }
                child is LinearLayout && child.orientation == LinearLayout.VERTICAL -> {
                    normalizeSpacing(child, isCard = false)
                    lp.topMargin = 0; lp.bottomMargin = 0
                }
                // Any other row (an icon preview, a status line) gets the same gap; the card heading keeps its padding.
                i > 0 || !isCard -> { lp.topMargin = 0; lp.bottomMargin = dp(CONTROL_GAP_DP) }
            }
            child.layoutParams = lp
            if (child.visibility != View.GONE) last = child
            previous = child
        }
        // A nested group keeps its trailing gap so it does not touch the control after it.
        if (isCard && !(last is LinearLayout && last.orientation == LinearLayout.VERTICAL)) {
            (last?.layoutParams as? LinearLayout.LayoutParams)?.let { it.bottomMargin = 0; last.layoutParams = it }
        }
    }
    // Rounded, not truncated: below 160 dpi dp(1) became 0 and every border vanished.
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    private fun startAppearanceUpdates() {
        appearanceUpdatesResumed = true
        appearanceObserverRemoval?.invoke()
        appearanceObserverRemoval = AppAppearanceRuntime.observeHost {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                checkForAppearanceChange()
            } else {
                handler.post { if (appearanceUpdatesResumed) checkForAppearanceChange() }
            }
        }
        handler.removeCallbacks(appearancePoll)
        handler.post(appearancePoll)
        applyPendingAppearanceRender()
    }

    private fun checkForAppearanceChange() {
        if (refreshAppearance()) {
            requestAppearanceRender()
        }
    }

    private fun requestAppearanceRender(focusAppearanceButton: Boolean = false) {
        appearanceRenderPending = true
        appearanceButtonFocusPending = appearanceButtonFocusPending || focusAppearanceButton
        if (!appearanceUpdatesResumed || shouldDeferAppearanceRender(
                windowKeyLearning = windowLearning != null,
                serviceKeyLearning = WheelKeyService.isLearning(),
            )
        ) return
        appearanceRenderPending = false
        val restoreAppearanceButton = appearanceButtonFocusPending
        appearanceButtonFocusPending = false
        render(
            appearanceOnly = true,
            preferredFocusTag = APPEARANCE_BUTTON_TAG.takeIf { restoreAppearanceButton },
        )
    }

    private fun applyPendingAppearanceRender() {
        if (!appearanceRenderPending) return
        handler.post {
            if (appearanceRenderPending && appearanceUpdatesResumed &&
                !shouldDeferAppearanceRender(
                    windowKeyLearning = windowLearning != null,
                    serviceKeyLearning = WheelKeyService.isLearning(),
                )
            ) {
                requestAppearanceRender()
            }
        }
    }

    private fun refreshAppearance(): Boolean {
        val resolved = resolveAppNightNow()
        if (resolved == appNight && palette === DiPlayPalette.of(resolved)) return false
        appNight = resolved
        palette = DiPlayPalette.of(resolved)
        return true
    }

    // The system draws the next launch's starting window before any app code runs. API 33 is the first
    // release that lets the app choose it; older head units keep the dark frame. Auto stays dark: the time
    // of the next launch is unknown, and a dark frame in daylight is safer than a bright frame at night.
    private fun rememberLaunchAppearance() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val light = AirPlayPersistence.loadAppAppearance(this) == AppAppearance.LIGHT
        splashScreen.setSplashScreenTheme(if (light) R.style.Theme_Xcertplay_Light else R.style.Theme_Xcertplay)
    }

    private fun applyWindowAppearance() {
        window.setBackgroundDrawable(ColorDrawable(palette.background))
        window.statusBarColor = palette.systemBar
        window.navigationBarColor = palette.systemBar
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = palette.systemBarIconsAreDark
            isAppearanceLightNavigationBars = palette.systemBarIconsAreDark
        }
        applyFullscreenMode()
    }

    private val BG get() = palette.background
    private val SURFACE get() = palette.surface
    private val BUTTON get() = palette.button
    private val BORDER get() = palette.outline
    private val ACCENT get() = palette.accent
    private val ON_ACCENT get() = palette.onAccent
    private val RAIL_SELECTED get() = palette.railSelected
    private val RAIL_SELECTED_BORDER get() = palette.railSelectedOutline
    private val TEXT get() = palette.primaryText
    private val MUTED get() = palette.secondaryText
    private val WARNING get() = palette.warning
    private val READY get() = palette.success
    private val RIPPLE get() = palette.ripple
    private val FOCUS_RING get() = palette.focusRing

    companion object {
        private fun alignedTextCentreY(
            view: TextView,
            text: CharSequence,
            bounds: android.graphics.Rect,
            drawingBaseline: Int = view.baseline,
        ): Float {
            if (usesChineseGlyphAlignment(view.resources.configuration.locales[0].language, text)) {
                val value = text.toString()
                view.paint.getTextBounds(value, 0, value.length, bounds)
                return drawingBaseline + (bounds.top + bounds.bottom) / 2f
            }
            // Keep the baseline stable when labels contain descenders or accents.
            val metrics = view.paint.fontMetrics
            return drawingBaseline + (metrics.ascent + metrics.descent) / 2f
        }

        private fun usesChineseGlyphAlignment(language: String, text: CharSequence): Boolean {
            return language == "zh" && text.isNotBlank() &&
                text.none { it in 'A'..'Z' || it in 'a'..'z' }
        }

        internal fun isLauncherIntent(intent: Intent): Boolean =
            intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER) &&
                !intent.hasExtra("page")

        private const val BYD_VEHICLE_TAG = "DiPlay-BYD13"
        private const val VEHICLE_VALIDATION_RETRY_MILLIS = 500L
        private const val ADB_KEY_SAVE_WAIT_MILLIS = 500L
        private const val APPEARANCE_POLL_MILLIS = 2_000L
        private const val APPEARANCE_BUTTON_TAG = "app_appearance_button"
        private const val SETTINGS_SEARCH_BUTTON_TAG = "settings_search_button"
        private const val SETTINGS_HEADER_COMPACT_GAP_DP = 8
        private const val SETTINGS_HEADER_GAP_DP = 12
        private const val SETTINGS_SEARCH_RESULTS_GAP_DP = 6
        private const val SETTINGS_SEARCH_RESULTS_SHORT_HEIGHT_DP = 200
        private const val SETTINGS_SEARCH_RESULTS_HEIGHT_DP = 380
        private const val VALUE_SEPARATOR = " · "
        private const val SEARCH_HIGHLIGHT_MILLIS = 900L
        private const val SPACER = "spacer"
        private const val CONTROL_HEIGHT_DP = 60
        private const val CONTROL_GAP_DP = 12
        private const val GROUP_GAP_DP = 20
        private const val NOTE_LINE_GAP_DP = 4
        private const val NOTE_ATTACH_GAP_DP = 6
        private const val SETTINGS_BLOCK_GAP_DP = 16
        private const val SETTINGS_BUTTON_GAP_DP = 10
        private const val SETTINGS_DIALOG_PADDING_DP = 24
        private const val SETTINGS_DIALOG_GAP_DP = 12
        private const val UPDATE_PROGRESS_BAR_HEIGHT_DP = 4
        private const val UPDATE_PROGRESS_BAR_INSET_DP = 16
        private const val UPDATE_PROGRESS_BAR_BOTTOM_DP = 7
    }
}
