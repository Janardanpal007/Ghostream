package com.fastdownloader.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.fastdownloader.app.browser.BrowserStore
import com.fastdownloader.app.browser.BrowserTab
import com.fastdownloader.app.browser.Login
import com.fastdownloader.app.browser.Passwords
import com.fastdownloader.app.browser.PendingLogin
import org.json.JSONObject

/**
 * Asks for the fingerprint (or the screen lock) before passwords are shown or filled. Once passed, the vault stays
 * open for [Passwords.UNLOCK_MS]. A phone with no screen lock at all can't protect them: they open with a reminder.
 */
fun unlockPasswords(ctx: Context, reason: String, onOk: () -> Unit) {
    if (Passwords.unlocked) return onOk()
    val act = ctx.findActivity() as? FragmentActivity ?: return
    val can = BiometricManager.from(act).canAuthenticate(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
    if (can == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED || can == BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE ||
        can == BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE || can == BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED
    ) {
        Toast.makeText(act, "Add a fingerprint or screen lock in your phone's settings to protect your passwords", Toast.LENGTH_LONG).show()
        Passwords.unlock()
        return onOk()
    }
    val prompt = BiometricPrompt(
        act, ContextCompat.getMainExecutor(act),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                Passwords.unlock()
                onOk()
            }
        },
    )
    prompt.authenticate(
        BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock passwords")
            .setSubtitle(reason)
            .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
            .build(),
    )
}

/** Copies without showing the text in the keyboard's clipboard preview (Android 13 and later). */
private fun copySecret(ctx: Context, label: String, text: String) {
    val clip = ClipData.newPlainText(label, text)
    if (Build.VERSION.SDK_INT >= 33) clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    if (Build.VERSION.SDK_INT < 33) Toast.makeText(ctx, "$label copied", Toast.LENGTH_SHORT).show()
}

/** Fills [login] into the page's sign-in form (top page only, the one whose address the app shows). */
fun fillLogin(web: WebView, login: Login) {
    val q = { s: String -> JSONObject.quote(s) }
    web.evaluateJavascript("window.__fdFill ? window.__fdFill(${q(login.username)}, ${q(login.password)}) : ''", null)
    Passwords.used(login.id)
}

/**
 * Above the bottom bar: "Save password?" after signing in, or "Sign in as …" on a sign-in page that has saved
 * sign-ins (filled after the fingerprint).
 */
@Composable
fun LoginBar(tab: BrowserTab, web: WebView?) {
    val ctx = LocalContext.current
    val c = colors
    val vault by Passwords.vault.collectAsState()
    val file = tab.passwordFile
    if (file != null) {
        val count = remember(file) { (parseCsv(file.second).size - 1).coerceAtLeast(0) }
        BarRow {
            Icon(Icons.Key, null, tint = c.accent, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(if (count == 1) "Import 1 password?" else "Import $count passwords?", style = Type.strong, color = c.text)
                Text("Exported by ${file.first}", style = Type.small, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            LineButton("No") { tab.passwordFile = null }
            Spacer(Modifier.width(8.dp))
            SolidButton("Import") {
                unlockPasswords(ctx, "Import passwords") {
                    val n = runCatching { importPasswords(file.second) }.getOrElse { -1 }
                    tab.passwordFile = null
                    Toast.makeText(ctx, if (n < 0) "Couldn't read the passwords" else if (n == 1) "1 password imported" else "$n passwords imported", Toast.LENGTH_LONG).show()
                }
            }
        }
        return
    }
    val save = tab.savePrompt
    if (save != null) {
        BarRow {
            Icon(Icons.Key, null, tint = c.accent, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(if (save.update != null) "Update saved password?" else "Save password?", style = Type.strong, color = c.text)
                Text(listOf(save.username.ifBlank { "No user name" }, save.site).joinToString(" · "), style = Type.small, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (save.update == null) LineButton("Never") { Passwords.never(save.site); tab.savePrompt = null }
            else LineButton("No") { tab.savePrompt = null }
            Spacer(Modifier.width(8.dp))
            SolidButton(if (save.update != null) "Update" else "Save") {
                Passwords.save(save)
                tab.savePrompt = null
                Toast.makeText(ctx, "Password saved", Toast.LENGTH_SHORT).show()
            }
        }
        return
    }
    if (tab.loginForm.isEmpty() || tab.fillDismissed || tab.home || web == null) return
    val logins = remember(vault, tab.url) { Passwords.forSite(tab.url) }
    if (logins.isEmpty()) return
    var choose by remember(tab.url) { mutableStateOf(false) }
    // Tapped into the sign-in box: straight to the fingerprint (one saved sign-in) or the list to pick from.
    LaunchedEffect(tab.fillRequest) {
        if (tab.fillRequest == tab.fillHandled) return@LaunchedEffect
        tab.fillHandled = tab.fillRequest
        if (logins.size == 1) unlockPasswords(ctx, "Sign in as ${logins[0].username.ifBlank { logins[0].site }}") { fillLogin(web, logins[0]) }
        else choose = true
    }
    Column(Modifier.fillMaxWidth().background(c.panel)) {
        Divider()
        if (choose) logins.forEach { l ->
            Row(
                Modifier.fillMaxWidth().clickable {
                    choose = false
                    unlockPasswords(ctx, "Sign in as ${l.username.ifBlank { l.site }}") { fillLogin(web, l) }
                }.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Key, null, tint = c.muted, modifier = Modifier.size(18.dp))
                Text(l.username.ifBlank { "(no user name)" }, style = Type.body, color = c.text, modifier = Modifier.padding(start = 12.dp))
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Key, null, tint = c.accent, modifier = Modifier.size(20.dp))
            Column(
                Modifier.weight(1f).clickable { if (logins.size > 1) choose = !choose }.padding(horizontal = 12.dp),
            ) {
                Text(if (logins.size == 1) "Sign in as ${logins[0].username.ifBlank { logins[0].site }}" else "${logins.size} saved sign-ins", style = Type.strong, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (logins.size == 1) "With your fingerprint" else "Tap to choose", style = Type.small, color = c.muted)
            }
            SolidButton("Fill") {
                if (logins.size > 1) choose = true
                else unlockPasswords(ctx, "Sign in as ${logins[0].username.ifBlank { logins[0].site }}") { fillLogin(web, logins[0]) }
            }
            RoundIcon(Icons.Close, "Not now") { tab.fillDismissed = true }
        }
    }
}

@Composable
private fun BarRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(colors.panel)) {
        Divider()
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

/** Settings rows: the saved passwords (behind the fingerprint), offering to save, and importing. */
@Composable
fun PasswordSettings() {
    val ctx = LocalContext.current
    val c = colors
    val vault by Passwords.vault.collectAsState()
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable { unlockPasswords(ctx, "See your saved passwords") { open = true } }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Key, null, tint = c.text, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text("Saved passwords", style = Type.strong, color = c.text)
            Text("${vault.logins.size} saved · encrypted on this phone, opened with your fingerprint", style = Type.small, color = c.muted)
        }
        Icon(Icons.Lock, null, tint = c.muted, modifier = Modifier.size(18.dp))
    }
    Divider()
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Offer to save passwords", style = Type.strong, color = c.text)
            Text("After you sign in on a site", style = Type.small, color = c.muted)
        }
        FdSwitch(vault.offer) { Passwords.setOffer(it) }
    }
    Divider()
    ImportButtons()
    Divider()
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text("New phone?", style = Type.strong, color = c.text)
        Text(
            "Bookmarks, history, tabs, settings and saved passwords are backed up to your Google account, end-to-end " +
                "encrypted with your screen lock. Sign in with the same Google account on the new phone and they come back. " +
                "Needs Backup by Google One on (phone Settings → Google → Backup) and a screen lock.",
            style = Type.small, color = c.muted,
        )
    }
    if (open) PasswordsScreen(onClose = { open = false })
}

/** Import from Google (and other password managers) and bookmarks from Chrome, each from the file they export. */
@Composable
fun ImportButtons() {
    val ctx = LocalContext.current
    val c = colors
    var help by remember { mutableStateOf(false) }
    val pickCsv = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) unlockPasswords(ctx, "Import passwords") {
            val n = runCatching { importPasswords(ctx, uri) }.getOrElse { -1 }
            Toast.makeText(
                ctx,
                if (n < 0) "Couldn't read that file" else "$n passwords imported. Delete the exported file now: it isn't encrypted.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }
    val pickHtml = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val n = runCatching { importBookmarks(ctx, uri) }.getOrElse { -1 }
            Toast.makeText(ctx, if (n < 0) "Couldn't read that file" else "$n bookmarks imported", Toast.LENGTH_SHORT).show()
        }
    }
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text("Import", style = Type.strong, color = c.text)
        Text(
            "Google: one tap below, then Export on Google's page. Or pick a file: passwords exported from Chrome, Proton Pass, Bitwarden and others (.csv), bookmarks from Chrome (.html).",
            style = Type.small, color = c.muted,
        )
        Spacer(Modifier.height(10.dp))
        SolidButton("Import passwords from Google", Modifier.fillMaxWidth()) {
            // Opens Google Password Manager in the browser; its Export button hands the file straight to the app.
            runCatching {
                ctx.startActivity(
                    android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse("https://passwords.google.com/options"))
                        .setPackage(ctx.packageName),
                )
            }
            Toast.makeText(ctx, "Tap \"Export passwords\", then Import when the app asks", Toast.LENGTH_LONG).show()
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LineButton("Passwords", Modifier.weight(1f)) { pickCsv.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel", "application/octet-stream")) }
            LineButton("Bookmarks", Modifier.weight(1f)) { pickHtml.launch(arrayOf("text/html", "text/*", "application/octet-stream")) }
            LineButton("How?") { help = !help }
        }
        Spacer(Modifier.height(8.dp))
        LineButton("Share bookmarks (to Chrome, Edge, Firefox…)", Modifier.fillMaxWidth()) { shareBookmarks(ctx) }
        if (help) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Google passwords: \"Import passwords from Google\" opens Google Password Manager, signed in as you. Tap Export passwords " +
                    "and confirm with your Google password; the app then offers to import them, and no file is left on the phone.\n\n" +
                    "From a file: in the other app, export passwords (.csv), pick the file with Passwords above, then delete the file.\n\n" +
                    "Chrome bookmarks: on a computer, Chrome → Bookmarks → Bookmark manager → ⋮ → Export bookmarks. " +
                    "Copy the .html file to the phone and pick it with Bookmarks above.",
                style = Type.small, color = c.text,
            )
        }
    }
}

/**
 * Bookmarks as the standard bookmarks.html every browser imports (Chrome, Edge, Firefox, Safari), handed to the
 * share menu: Drive, email, Nearby Share and so on. History stays on the phone.
 */
fun shareBookmarks(ctx: Context) {
    val list = BrowserStore.bookmarks.value
    if (list.isEmpty()) { Toast.makeText(ctx, "No bookmarks yet", Toast.LENGTH_SHORT).show(); return }
    fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    val html = buildString {
        append("<!DOCTYPE NETSCAPE-Bookmark-file-1>\n<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">\n")
        append("<TITLE>Bookmarks</TITLE>\n<H1>Bookmarks</H1>\n<DL><p>\n")
        append("    <DT><H3>Ghostream</H3>\n    <DL><p>\n")
        list.forEach { b ->
            append("        <DT><A HREF=\"").append(esc(b.url)).append("\" ADD_DATE=\"").append(b.addedAt / 1000).append("\">")
                .append(esc(b.title.ifBlank { b.url })).append("</A>\n")
        }
        append("    </DL><p>\n</DL><p>\n")
    }
    val dir = java.io.File(ctx.cacheDir, "exports").apply { mkdirs() }
    val file = java.io.File(dir, "bookmarks.html").apply { writeText(html) }
    val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
    val send = android.content.Intent(android.content.Intent.ACTION_SEND)
        .setType("text/html")
        .putExtra(android.content.Intent.EXTRA_STREAM, uri)
        .putExtra(android.content.Intent.EXTRA_SUBJECT, "Bookmarks")
        .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(android.content.Intent.createChooser(send, "Share ${list.size} bookmarks"))
}

/** Reads a password export (.csv). Columns are found by their names, which differ a little between apps. */
fun importPasswords(ctx: Context, uri: Uri): Int =
    importPasswords(ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error("unreadable"))

fun importPasswords(text: String): Int {
    val rows = parseCsv(text.removePrefix("﻿"))
    if (rows.size < 2) return 0
    val head = rows[0].map { it.trim().lowercase() }
    fun col(vararg names: String) = names.firstNotNullOfOrNull { n -> head.indexOf(n).takeIf { it >= 0 } }
    val urlCol = col("url", "login_uri", "website", "origin", "uri") ?: error("no url column")
    val userCol = col("username", "login_username", "login", "email", "user name")
    val passCol = col("password", "login_password") ?: error("no password column")
    var n = 0
    rows.drop(1).forEach { r ->
        val url = r.getOrNull(urlCol)?.trim().orEmpty()
        val pass = r.getOrNull(passCol).orEmpty()
        val user = userCol?.let { r.getOrNull(it) }?.trim().orEmpty()
        val site = Passwords.siteOf(if (url.contains("://")) url else "https://$url") ?: return@forEach
        if (pass.isEmpty()) return@forEach
        Passwords.save(PendingLogin(site, user, pass, System.currentTimeMillis()))
        n++
    }
    return n
}

/** CSV with quoted fields, doubled quotes and line breaks inside quotes. */
fun parseCsv(text: String): List<List<String>> {
    val rows = mutableListOf<List<String>>()
    var row = mutableListOf<String>()
    val f = StringBuilder()
    var quoted = false
    var i = 0
    while (i < text.length) {
        val ch = text[i]
        if (quoted) {
            if (ch == '"' && i + 1 < text.length && text[i + 1] == '"') { f.append('"'); i++ }
            else if (ch == '"') quoted = false
            else f.append(ch)
        } else when (ch) {
            '"' -> quoted = true
            ',' -> { row.add(f.toString()); f.clear() }
            '\r' -> {}
            '\n' -> { row.add(f.toString()); f.clear(); rows.add(row); row = mutableListOf() }
            else -> f.append(ch)
        }
        i++
    }
    if (f.isNotEmpty() || row.isNotEmpty()) { row.add(f.toString()); rows.add(row) }
    return rows.filter { r -> r.any { it.isNotBlank() } }
}

/** Reads a bookmarks file exported by Chrome (or any browser: they all use the same HTML layout). */
fun importBookmarks(ctx: Context, uri: Uri): Int {
    val html = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error("unreadable")
    val found = Regex("""<A\s[^>]*HREF="([^"]+)"[^>]*>(.*?)</A>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        .findAll(html)
        .map { m -> m.groupValues[1] to android.text.Html.fromHtml(m.groupValues[2], android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim() }
        .filter { (url, _) -> url.startsWith("http") }
        .toList()
    return BrowserStore.addBookmarks(found)
}

/** The saved passwords: search, show, copy, delete, and the sites that are never saved. */
@Composable
private fun PasswordsScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val c = colors
    val vault by Passwords.vault.collectAsState()
    var query by remember { mutableStateOf("") }
    var shown by remember { mutableStateOf(setOf<String>()) }
    var confirm by remember { mutableStateOf<Login?>(null) }
    // Locks again after a while, as the fingerprint asked for.
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(5_000)
            if (!Passwords.unlocked) onClose()
        }
    }
    BackHandler(onBack = onClose)
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().bottomBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIcon(Icons.Back, "Back", onClick = onClose)
                Text("Passwords", style = Type.heading, color = c.text, modifier = Modifier.weight(1f).padding(start = 8.dp))
                LineButton("Lock") { Passwords.lock(); onClose() }
            }
            Row(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().background(c.panel, Shape.control).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Search, null, tint = c.muted, modifier = Modifier.size(18.dp))
                Box(Modifier.weight(1f).padding(start = 10.dp)) {
                    if (query.isEmpty()) Text("Search sites and user names", style = Type.body, color = c.muted)
                    BasicTextField(query, { query = it }, singleLine = true, textStyle = Type.body.copy(color = c.text), cursorBrush = SolidColor(c.text), modifier = Modifier.fillMaxWidth())
                }
            }
            val list = vault.logins.filter { query.isBlank() || it.site.contains(query, true) || it.username.contains(query, true) }.sortedBy { it.site }
            LazyColumn(Modifier.weight(1f).padding(top = 8.dp)) {
                if (list.isEmpty()) item {
                    Text(
                        if (vault.logins.isEmpty()) "No passwords yet. Sign in on a site and tap Save, or import them in Settings." else "Nothing matches.",
                        style = Type.small, color = c.muted, modifier = Modifier.padding(16.dp),
                    )
                }
                items(list, key = { it.id }) { l ->
                    val visible = l.id in shown
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text(l.site, style = Type.strong, color = c.text)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(l.username.ifBlank { "(no user name)" }, style = Type.small, color = c.muted, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            RoundIcon(Icons.Copy, "Copy user name", size = 36.dp) { copySecret(ctx, "User name", l.username) }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (visible) l.password else "••••••••••", style = Type.small, color = c.text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            RoundIcon(if (visible) Icons.EyeOff else Icons.Eye, if (visible) "Hide password" else "Show password", size = 36.dp) {
                                shown = if (visible) shown - l.id else shown + l.id
                            }
                            RoundIcon(Icons.Copy, "Copy password", size = 36.dp) { copySecret(ctx, "Password", l.password) }
                            RoundIcon(Icons.Delete, "Delete", size = 36.dp, tint = c.err) { confirm = l }
                        }
                        if (confirm == l) Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LineButton("Delete ${l.site}", color = c.err) { Passwords.delete(l.id); confirm = null }
                            LineButton("Keep") { confirm = null }
                        }
                    }
                    Divider()
                }
                if (vault.never.isNotEmpty()) {
                    item { Label("Never saved for", Modifier.padding(start = 16.dp, top = 20.dp, bottom = 6.dp)) }
                    items(vault.never, key = { "never:$it" }) { site ->
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(site, style = Type.body, color = c.text, modifier = Modifier.weight(1f))
                            RoundIcon(Icons.Close, "Offer to save again for $site", size = 36.dp) { Passwords.allowAgain(site) }
                        }
                    }
                }
            }
        }
    }
}
