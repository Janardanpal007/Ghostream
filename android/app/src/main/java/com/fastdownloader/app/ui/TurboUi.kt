package com.fastdownloader.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fastdownloader.app.engine.Engine
import com.fastdownloader.app.engine.Turbo
import java.time.LocalDate

/** Small "TURBO" mark next to something that needs Ghostream Turbo. */
@Composable
fun TurboTag(modifier: Modifier = Modifier) {
    val c = colors
    Text(
        "TURBO",
        style = Type.label.copy(fontSize = 9.sp),
        color = c.accent,
        modifier = modifier.padding(horizontal = 6.dp).clip(Shape.control).background(c.accentSoft).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

object TurboNudge {
    /** Tapping something locked: say what it needs and where to get it. */
    fun show(ctx: Context, what: String) {
        Toast.makeText(ctx, "$what need Ghostream Turbo · Settings › Ghostream Turbo", Toast.LENGTH_LONG).show()
    }

    fun openPage(ctx: Context) {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Turbo.PAGE)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

/** One line for the Settings home: what plan this phone is on. */
fun turboSummary(): String = when (val r = Turbo.current()) {
    is Turbo.Result.Valid -> "Turbo is on · " + until(r)
    is Turbo.Result.Invalid -> "Free · tap to unlock 2K/4K and faster downloads"
}

private fun until(r: Turbo.Result.Valid) = if (r.lifetime) "lifetime" else "until " + LocalDate.ofEpochDay(r.expiresDay)

/** "Kq7#mXp2@w" → "Kq7#m-Xp2@w", easier to read back. */
private fun grouped(key: String) = if (key.length == Turbo.KEY_LENGTH) key.substring(0, 5) + "-" + key.substring(5) else key

private val PERKS = listOf(
    "Paste any link" to "Paste or share a link from any app; free downloads start in the Browser tab, or from a magnet link or .torrent file",
    "YouTube in 2K, 4K and Highest" to "Free plays up to 1080p",
    "Video downloads above 1080p" to "2K, 4K and 8K where the site has them",
    "Up to 32 parts per file" to "Free: ${Turbo.FREE_PARTS} parts",
    "Up to 10 downloads at once" to "Free: ${Turbo.FREE_AT_ONCE} at once",
    "Up to 1000 torrent connections" to "Free: ${Turbo.FREE_TORRENT_CONNECTIONS}",
)

/** Settings › Ghostream Turbo: the plan, what Turbo adds, and the key. */
@Composable
fun TurboPage() {
    val c = colors
    val ctx = LocalContext.current
    val s by Engine.settingsFlow.collectAsState()
    val current = Turbo.current(s)
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var text by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Section("Your plan") {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            when (current) {
                is Turbo.Result.Valid -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Ghostream Turbo", style = Type.heading, color = c.text)
                        TurboTag()
                    }
                    Text("Key ${grouped(s.turboKey)} · ${until(current)}", style = Type.small, color = c.muted)
                }
                is Turbo.Result.Invalid -> {
                    Text("Ghostream (free)", style = Type.heading, color = c.text)
                    val why = if (s.turboHash.isNotBlank()) current.reason + ". " else ""
                    Text(why + "Everything you use today stays free. Turbo adds the extras below.", style = Type.small, color = c.muted)
                }
            }
        }
    }

    Section("What Turbo adds") {
        PERKS.forEachIndexed { i, (title, note) ->
            if (i > 0) Divider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Check, null, tint = if (current is Turbo.Result.Valid) c.accent else c.muted, modifier = Modifier.size(18.dp))
                Column(Modifier.padding(start = 12.dp)) {
                    Text(title, style = Type.strong, color = c.text)
                    Text(note, style = Type.small, color = c.muted)
                }
            }
        }
    }

    if (current is Turbo.Result.Valid) {
        Section("Key") {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LineButton("Remove key") { Turbo.deactivate(); message = null }
            }
        }
        return
    }

    fun activate(key: String) {
        busy = true
        message = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { Turbo.activate(key) }
            busy = false
            when (r) {
                is Turbo.Result.Valid -> {
                    text = ""
                    Toast.makeText(ctx, "Turbo is on. Thank you for supporting Ghostream!", Toast.LENGTH_LONG).show()
                }
                is Turbo.Result.Invalid -> message = r.reason
            }
        }
    }

    Section("Have a key?") {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Label("Paste or type your 10-character key")
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { t -> text = t.filter { ch -> ch in Turbo.KEY_CHARS }.take(Turbo.KEY_LENGTH); message = null },
                modifier = Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("XXXXX-XXXXX", style = Type.heading, color = c.muted) },
                textStyle = Type.heading.copy(color = c.text, letterSpacing = 3.sp), colors = fieldColors(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            Text("Copy the key from your email and tap Paste key. Activating needs internet once.", style = Type.small, color = c.muted, modifier = Modifier.padding(top = 6.dp))
            message?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = Type.small, color = c.err)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Pastes the copied key and, when it's a full key, activates it straight away.
                SolidButton("Paste key", enabled = !busy) {
                    val pasted = Turbo.findKey(clipboard.getText()?.text.orEmpty())
                    if (pasted.isEmpty()) {
                        message = "Copy your key from the email first, then tap Paste key"
                    } else {
                        text = pasted
                        activate(pasted)
                    }
                }
                LineButton(if (busy) "Checking…" else "Activate") {
                    if (!busy && text.length == Turbo.KEY_LENGTH) activate(text) else if (!busy) message = Turbo.WRONG_LENGTH
                }
            }
        }
    }

    Section("Get Turbo") {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Pay by UPI on the Ghostream website and send the form. Your key arrives by email.", style = Type.body, color = c.muted)
            Spacer(Modifier.height(12.dp))
            SolidButton("Get Ghostream Turbo", icon = Icons.Speed) { TurboNudge.openPage(ctx) }
        }
    }
}
