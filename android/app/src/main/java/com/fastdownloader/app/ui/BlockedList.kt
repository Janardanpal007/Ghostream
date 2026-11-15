package com.fastdownloader.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fastdownloader.app.browser.BlockKind
import com.fastdownloader.app.browser.BlockedItem
import com.fastdownloader.app.browser.BrowserTab

/** How many hosts the Shields sheet lists before "Show all". */
private const val TOP_HOSTS = 8

/** One line of the list: a host, the kind of thing blocked from it, and how many times. */
private data class BlockedHost(val host: String, val kind: BlockKind, val count: Int)

/** Groups blocked items by host (and kind), most blocked first. */
private fun groupBlocked(items: List<BlockedItem>): List<BlockedHost> =
    items.groupingBy { it.host to it.kind }.eachCount()
        .map { (key, n) -> BlockedHost(key.first, key.second, n) }
        .sortedWith(compareByDescending<BlockedHost> { it.count }.thenBy { it.host })

/** What Shields stopped on this page, by site, so the blocker can be seen working. */
@Composable
fun BlockedList(tab: BrowserTab) {
    val c = colors
    var all by remember { mutableStateOf(false) }
    val rows = groupBlocked(tab.blockedItems.toList())
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("BLOCKED ON THIS PAGE", style = Type.label, color = c.muted)
        if (rows.isEmpty()) {
            Text("Nothing blocked on this page yet", style = Type.small, color = c.muted)
            return@Column
        }
        (if (all) rows else rows.take(TOP_HOSTS)).forEach { BlockedRow(it) }
        if (rows.size > TOP_HOSTS) Text(
            if (all) "Show fewer" else "Show all (${rows.size})",
            style = Type.strong, color = c.accent,
            modifier = Modifier.clickable { all = !all }.padding(vertical = 4.dp),
        )
    }
}

@Composable
private fun BlockedRow(row: BlockedHost) {
    val c = colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(row.host, style = Type.body, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(row.kind.label, style = Type.small, color = c.muted)
        }
        Spacer(Modifier.width(12.dp))
        Text("${row.count}", style = Type.strong, color = c.ok)
    }
}
