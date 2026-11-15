package com.fastdownloader.app.ui

import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.fastdownloader.app.engine.Engine
import kotlinx.coroutines.delay

/**
 * The fingerprint / screen-lock gate in front of private tabs ("Lock private tabs" in Settings). The browser is
 * unlocked once per visit to the app: leaving the app locks it again, and a private tab on screen then stays
 * covered until the phone's lock is passed.
 */
object PrivateLock {
    private const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    /** True after the lock was passed, until the app leaves the screen. */
    var unlocked by mutableStateOf(false)
        private set
    private var asking = false

    /** The phone has a fingerprint, face or screen lock to ask for. */
    fun available(ctx: Context): Boolean =
        Build.VERSION.SDK_INT >= 23 && BiometricManager.from(ctx).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    /** The app left the screen: private tabs need the lock again. */
    fun relock() { unlocked = false }

    /** Shows the system prompt unless one is already open. */
    fun ask(act: FragmentActivity) {
        if (unlocked || asking) return
        if (!available(act)) { unlocked = true; return } // the phone's lock was removed since the switch was turned on
        asking = true
        val prompt = BiometricPrompt(
            act, ContextCompat.getMainExecutor(act),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { asking = false; unlocked = true }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { asking = false }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock private tabs")
                .setAllowedAuthenticators(AUTHENTICATORS)
                .build(),
        )
    }
}

/** How often the gate checks whether a private tab came on screen (the tab state lives in BrowserScreen). */
private const val WATCH_MS = 250L

/** Covers the screen while a private tab is showing and the lock hasn't been passed. */
@Composable
fun PrivateLockGate(visible: Boolean) {
    val settings by Engine.settingsFlow.collectAsState()
    val act = LocalContext.current as? FragmentActivity ?: return
    var privateShowing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            privateShowing = BrowserChrome.activePrivate
            delay(WATCH_MS)
        }
    }
    val covered = settings.lockPrivateTabs && visible && privateShowing && !PrivateLock.unlocked
    LaunchedEffect(covered) { if (covered) PrivateLock.ask(act) }
    if (!covered) return
    Box(Modifier.fillMaxSize().background(colors.bg).clickable { PrivateLock.ask(act) }, contentAlignment = Alignment.Center) {
        Column(Modifier.padding(32.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Private tabs are locked", color = colors.text)
            Text("Tap to unlock", color = colors.muted)
        }
    }
}
