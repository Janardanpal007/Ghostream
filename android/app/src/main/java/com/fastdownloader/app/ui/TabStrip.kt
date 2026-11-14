package com.fastdownloader.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fastdownloader.app.browser.BrowserTab
import com.fastdownloader.app.browser.Omnibox
import com.fastdownloader.app.browser.TabManager

/**
 * A row of desktop-style tabs under the address bar (normal or private ones, whichever the current tab is): tap to
 * switch, × to close, + for a new tab. Scrolls to keep the current tab in view.
 */
@Composable
fun TabStrip(manager: TabManager, onNew: (Boolean) -> Unit) {
    val c = colors
    val current = manager.current ?: return
    val private = current.private
    val shown = manager.tabs.filter { it.private == private }
    val list = rememberLazyListState()
    LaunchedEffect(current.id, shown.size) {
        val i = shown.indexOfFirst { it.id == current.id }
        if (i >= 0) list.animateScrollToItem(i)
    }
    val bar = if (private) c.privateBg else c.bg
    Row(Modifier.fillMaxWidth().background(bar).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        LazyRow(
            Modifier.weight(1f), state = list,
            contentPadding = PaddingValues(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(shown, key = { it.id }) { tab ->
                TabChip(tab, selected = tab.id == current.id, private = private, onClick = { manager.select(tab) }, onClose = { manager.close(tab) })
            }
        }
        Box(
            Modifier.padding(end = 8.dp).size(32.dp).clip(Shape.control).clickable { onNew(private) },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Add, "New tab", tint = if (private) c.privateFg else c.muted, modifier = Modifier.size(20.dp)) }
    }
}

@Composable
private fun TabChip(tab: BrowserTab, selected: Boolean, private: Boolean, onClick: () -> Unit, onClose: () -> Unit) {
    val c = colors
    val fg = if (private) c.privateFg else c.text
    val fill = when {
        selected && private -> Color.White.copy(alpha = 0.14f)
        selected -> c.panel2
        private -> Color.White.copy(alpha = 0.05f)
        else -> c.panel
    }
    val title = when {
        tab.home -> "New tab"
        tab.title.isNotBlank() -> tab.title
        else -> Omnibox.displayHost(tab.url)
    }
    Row(
        Modifier.height(32.dp).widthIn(min = 96.dp, max = 168.dp).clip(Shape.pill).background(fill)
            .border(1.dp, if (selected) c.accent else Color.Transparent, Shape.pill)
            .clickable(onClick = onClick).padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (tab.home) {
            Icon(Icons.Search, null, tint = fg.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
        } else {
            SiteAvatar(tab.url, 18.dp)
        }
        Spacer(Modifier.width(6.dp))
        Text(
            title, style = Type.small, color = if (selected) fg else fg.copy(alpha = 0.75f),
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
        )
        Box(Modifier.size(28.dp).clip(Shape.pill).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
            Icon(Icons.Close, "Close tab", tint = fg.copy(alpha = 0.6f), modifier = Modifier.size(14.dp))
        }
    }
}
