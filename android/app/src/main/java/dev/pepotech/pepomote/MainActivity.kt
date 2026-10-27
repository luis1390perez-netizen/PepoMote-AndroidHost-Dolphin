package dev.pepotech.pepomote

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dev.pepotech.pepomote.control.AppPrefs
import dev.pepotech.pepomote.control.ButtonState
import dev.pepotech.pepomote.control.LocaleHelper
import dev.pepotech.pepomote.control.UiSounds
import dev.pepotech.pepomote.net.PairStore
import dev.pepotech.pepomote.net.Pairing
import dev.pepotech.pepomote.net.PartyStore
import dev.pepotech.pepomote.service.DsuForegroundService
import dev.pepotech.pepomote.service.DsuState
import dev.pepotech.pepomote.service.LinkFailure
import dev.pepotech.pepomote.service.LaunchAction
import dev.pepotech.pepomote.service.LinkForegroundService
import dev.pepotech.pepomote.service.LinkState
import dev.pepotech.pepomote.service.PadScreen
import dev.pepotech.pepomote.service.Route
import dev.pepotech.pepomote.service.ScreenLink
import dev.pepotech.pepomote.service.UiLink
import dev.pepotech.pepomote.service.alive
import dev.pepotech.pepomote.ui.screens.HomeStatus
import dev.pepotech.pepomote.ui.screens.HomeTone
import dev.pepotech.pepomote.ui.screens.ControllerLandscapeScreen
import dev.pepotech.pepomote.ui.screens.ControllerScreen
import dev.pepotech.pepomote.ui.screens.DolphinAndroidScreen
import dev.pepotech.pepomote.ui.screens.GamePadScreen
import dev.pepotech.pepomote.ui.screens.HomeScreen
import dev.pepotech.pepomote.ui.screens.NunchukScreen
import dev.pepotech.pepomote.ui.screens.OnboardingScreen
import dev.pepotech.pepomote.ui.screens.PairScreen
import dev.pepotech.pepomote.ui.screens.SettingsScreen
import dev.pepotech.pepomote.ui.theme.PepoMoteTheme

internal enum class Screen { Onboarding, Home, Pair, Controller, Nunchuk, Settings, DolphinAndroid, DsuController }

class MainActivity : ComponentActivity() {

    internal var currentScreen by mutableStateOf(Screen.Home)
    internal var controllerDolphinOnly by mutableStateOf(false)
    internal var linkRole by mutableStateOf(LinkState.ROLE_WIIMOTE)
    internal var pairReason by mutableStateOf<String?>(null)
    internal var pairVersion by mutableIntStateOf(0)

    internal fun openPair(reason: String? = null) {
        pairReason = reason
        currentScreen = Screen.Pair
    }

    internal fun onLinkFailed(failure: UiLink.Failed) {
        LinkState.clearFailure()
        val pc = LinkFailure.pcLabel(PairStore.load(this)?.pcName, getString(R.string.your_pc))
        if (LinkFailure.needsNewQr(failure.code)) {
            openPair(getString(R.string.re_pair_reason, pc))
        } else if (LinkFailure.offerAnotherPc(failure.code, PairStore.all(this).size)) {
            openPair(getString(R.string.pc_not_responding, pc))
        } else {
            Toast.makeText(this, getString(R.string.error_prefix, failure.msg), Toast.LENGTH_LONG).show()
            LinkState.pendingMode = null
            currentScreen = Screen.Home
        }
    }

    private fun linkIsNunchuk(): Boolean =
        LinkState.flow.value.alive && LinkState.role == LinkState.ROLE_NUNCHUK

    internal fun openController(mode: String, dolphinOnly: Boolean) {
        controllerDolphinOnly = dolphinOnly
        linkRole = LinkState.ROLE_WIIMOTE
        when {
            LinkState.flow.value.alive && !linkIsNunchuk() -> {
                LinkState.requestMode(mode)
                currentScreen = Screen.Controller
            }

            PairStore.load(this) != null -> {
                LinkState.requestMode(mode)
                LinkForegroundService.start(this)
                currentScreen = Screen.Controller
            }

            else -> {
                LinkState.requestMode(mode)
                openPair()
            }
        }
    }

    internal fun openPad() {
        controllerDolphinOnly = false
        linkRole = LinkState.ROLE_WIIMOTE
        when {
            PairStore.load(this) == null -> openPair()

            else -> {
                if (linkIsNunchuk()) LinkForegroundService.start(this)
                currentScreen = Screen.Controller
            }
        }
    }

    internal fun onSavedPcChosen(p: Pairing) {
        PairStore.select(this, p.token)
        pairVersion++
        pairReason = null
        if (linkRole == LinkState.ROLE_WIIMOTE) LinkState.pendingMode?.let { LinkState.requestMode(it) }
        LinkForegroundService.start(this, linkRole)
        currentScreen = if (linkRole == LinkState.ROLE_NUNCHUK) Screen.Nunchuk else Screen.Controller
    }

    internal fun onForgetPc(p: Pairing) {
        PairStore.forget(this, p.token)
        pairVersion++
    }

    internal fun openNunchuk() {
        linkRole = LinkState.ROLE_NUNCHUK
        when {
            linkIsNunchuk() -> currentScreen = Screen.Nunchuk

            PairStore.load(this) != null -> {
                LinkForegroundService.start(this, LinkState.ROLE_NUNCHUK)
                currentScreen = Screen.Nunchuk
            }

            else -> openPair()
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    internal fun toggleLanguage() {
        AppPrefs.setLang(this, LocaleHelper.otherCode(this))
        recreate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        try {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    DsuForegroundService.start(this)
                } catch (_: Exception) {}
            }, 1500)
        } catch (_: Exception) {}

        enableEdgeToEdge()
        UiSounds.init(this)
        if (!AppPrefs.onboarded(this)) currentScreen = Screen.Onboarding
        setContent {
            PepoMoteTheme {
                Root(this)
            }
        }
        if (savedInstanceState == null) {
            intent?.data?.let { onPairContent(it.toString()) }
            handleLaunchIntent(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.data?.let { onPairContent(it.toString()) }
        handleLaunchIntent(intent)
    }

    private fun handleLaunchIntent(intent: Intent?) {
        val action = LaunchAction.parse(intent?.action) ?: return
        if (!AppPrefs.onboarded(this)) return
        when (action) {
            LaunchAction.Pointer -> openController(LinkState.MODE_POINTER, dolphinOnly = false)
            LaunchAction.Dolphin -> openController(LinkState.MODE_DOLPHIN, dolphinOnly = true)
            LaunchAction.WiiU -> openController(LinkState.MODE_CEMU, dolphinOnly = false)
            LaunchAction.Nunchuk -> openNunchuk()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        enableEdgeToEdge()
    }

    override fun onStart() {
        super.onStart()
        ScreenLink.setForeground(true)
    }

    override fun onStop() {
        ScreenLink.setForeground(false)
        super.onStop()
    }

    internal fun onPairContent(contents: String) {
        PartyStore.parseInviteUrl(contents)?.let { invite ->
            PartyStore.remember(this, invite)
            DsuForegroundService.stop(this)
            DsuForegroundService.start(this)
            currentScreen = Screen.DolphinAndroid
            return
        }
        val pairing = PairStore.parsePairUrl(contents)
        if (pairing == null) {
            Toast.makeText(this, getString(R.string.qr_not_pepomote), Toast.LENGTH_LONG).show()
            return
        }
        PairStore.save(this, pairing)
        pairVersion++
        pairReason = null
        if (linkRole == LinkState.ROLE_WIIMOTE) LinkState.pendingMode?.let { LinkState.requestMode(it) }
        LinkForegroundService.start(this, linkRole)
        currentScreen = if (linkRole == LinkState.ROLE_NUNCHUK) Screen.Nunchuk else Screen.Controller
    }

    private var volumeHeld = 0

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val bit = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> ButtonState.A
            KeyEvent.KEYCODE_VOLUME_DOWN -> ButtonState.B
            else -> return super.dispatchKeyEvent(event)
        }
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (currentScreen == Screen.Controller && AppPrefs.volDownIsB(this)) {
                if (event.repeatCount == 0) {
                    volumeHeld = volumeHeld or bit
                    ButtonState.set(bit, true)
                    if (bit == ButtonState.A) UiSounds.pop() else UiSounds.blip()
                    window.decorView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
                return true
            }

            KeyEvent.ACTION_UP -> if (volumeHeld and bit != 0) {
                volumeHeld = volumeHeld and bit.inv()
                ButtonState.set(bit, false)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
}

@Composable
private fun Root(activity: MainActivity) {
    val context = LocalContext.current
    val link by LinkState.flow.collectAsState()

    LaunchedEffect(link) {
        (link as? UiLink.Failed)?.let { activity.onLinkFailed(it) }
    }

    androidx.activity.compose.BackHandler(
        enabled = activity.currentScreen != Screen.Home &&
            activity.currentScreen != Screen.Onboarding
    ) {
        activity.currentScreen = Screen.Home
    }

    val qrLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents ?: return@rememberLauncherForActivityResult
        activity.onPairContent(contents)
    }

    fun scanQr() {
        qrLauncher.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt(context.getString(R.string.scan_prompt))
                .setBeepEnabled(false)
                .setOrientationLocked(true)
                .setCaptureActivity(PortraitCaptureActivity::class.java)
        )
    }

    val padIntent by LinkState.intent.collectAsState()
    val wantLandscape = activity.currentScreen == Screen.Controller && Route.route(link, padIntent) == PadScreen.GamePad
    LaunchedEffect(wantLandscape) {
        activity.requestedOrientation =
            if (wantLandscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    AnimatedContent(
        targetState = activity.currentScreen,
        transitionSpec = { screenTransition(initialState, targetState) },
        label = "screen"
    ) { screen ->
        when (screen) {
            Screen.Onboarding -> OnboardingScreen(onDone = {
                AppPrefs.setOnboarded(context)
                activity.currentScreen = Screen.Home
            })

            Screen.Home -> {
                val l = link
                val pcName = PairStore.load(context)?.pcName
                val status = when (l) {
                    is UiLink.Connected -> HomeStatus(HomeTone.On, stringResource(R.string.status_connected_to, l.pcName))
                    is UiLink.Connecting -> HomeStatus(HomeTone.Busy, stringResource(R.string.status_connecting))
                    is UiLink.Reconnecting -> HomeStatus(HomeTone.Busy, stringResource(R.string.status_reconnecting, l.pcName))
                    else -> HomeStatus(
                        HomeTone.Off,
