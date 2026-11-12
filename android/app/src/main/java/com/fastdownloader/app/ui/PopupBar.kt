package com.fastdownloader.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fastdownloader.app.browser.BrowserTab
import com.fastdownloader.app.browser.Omnibox
import com.fastdownloader.app.engine.AdBlock
import com.fastdownloader.app.engine.Engine

/** The site part of an address, as the shield and per-site lists use it ("" when there is none). */
private fun siteOf(url: String): String =
    AdBlock.hostOf(url.lowercase())?.removePrefix("www.")?.removePrefix("m.") ?: ""

/**
 * Above the bottom bar after the page tried to open a pop-up that was stopped: open it this once,
 * or allow pop-ups on this site from now on (listed under Per-site Shields in Settings).
 */
@Composable
fun PopupBar(tab: BrowserTab, onOpen: (String) -> Unit) {
    val target = tab.blockedPopup ?: return
    if (tab.home) return
    val site = siteOf(tab.url)
    if (site.isEmpty()) return
    val c = colors
    BarRow {
        Icon(Icons.Shield, null, tint = c.accent, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text("Pop-up blocked", style = Type.strong, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(siteOf(target).ifEmpty { site }, style = Type.small, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (target.startsWith("http")) {
            LineButton("Open") { tab.blockedPopup = null; onOpen(target) }
            Spacer(Modifier.width(8.dp))
        }
        SolidButton("Allow") {
            tab.blockedPopup = null
            Engine.updateSettings { s ->
                if (s.popupAllow.any { Omnibox.matchesSite(site, it) }) s else s.copy(popupAllow = s.popupAllow + site)
            }
        }
        Spacer(Modifier.width(4.dp))
        Icon(
            Icons.Close, "Dismiss", tint = c.muted,
            modifier = Modifier.size(32.dp).clickable { tab.blockedPopup = null }.padding(6.dp),
        )
    }
}
