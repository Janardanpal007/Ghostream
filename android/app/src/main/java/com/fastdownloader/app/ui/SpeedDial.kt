package com.fastdownloader.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fastdownloader.app.browser.Bookmark
import com.fastdownloader.app.browser.BrowserStore
import com.fastdownloader.app.browser.Omnibox

private const val PER_ROW = 4

/** What the Speed Dial editor is doing: adding a new tile, or changing [tile]. */
private sealed interface Editing {
    data object Add : Editing
    data class Change(val tile: Bookmark) : Editing
}

/**
 * The start page's Speed Dial: tiles the user picks. Tap opens; long press opens a small editor (rename, move,
 * remove); the last tile adds one.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SpeedDialGrid(onOpen: (String) -> Unit) {
    val c = colors
    val tiles by BrowserStore.speedDials.collectAsState()
    var editing by remember { mutableStateOf<Editing?>(null) }
    val cells: List<Bookmark?> = tiles + null // null: the "Add" tile
    Column(Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
        Label("Speed Dial", Modifier.padding(start = 4.dp, bottom = 10.dp))
        cells.chunked(PER_ROW).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { tile ->
                    Column(
                        Modifier.weight(1f).clip(Shape.control)
                            .combinedClickable(
                                onClick = { if (tile == null) editing = Editing.Add else onOpen(tile.url) },
                                onLongClick = { editing = tile?.let { Editing.Change(it) } ?: Editing.Add },
                            )
                            .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (tile == null) {
                            Box(
                                Modifier.size(48.dp).clip(Shape.control).border(1.dp, c.border, Shape.control),
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Add, "Add to Speed Dial", tint = c.muted) }
                        } else {
                            SiteAvatar(tile.url, 48.dp)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            tile?.title ?: "Add", style = Type.small, color = if (tile == null) c.muted else c.text,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
                repeat(PER_ROW - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
    editing?.let { SpeedDialEditor(it) { editing = null } }
}

@Composable
private fun SpeedDialEditor(editing: Editing, onDone: () -> Unit) {
    val c = colors
    val tile = (editing as? Editing.Change)?.tile
    var name by remember { mutableStateOf(tile?.title ?: "") }
    var address by remember { mutableStateOf(tile?.url ?: "") }
    AlertDialog(
        onDismissRequest = onDone,
        containerColor = c.panel,
        title = { Text(if (tile == null) "Add to Speed Dial" else "Edit tile", style = Type.heading, color = c.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Field("Name", name, KeyboardType.Text) { name = it }
                if (tile == null) {
                    Field("Address", address, KeyboardType.Uri) { address = it }
                } else {
                    Text(Omnibox.displayHost(tile.url), style = Type.small, color = c.muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { BrowserStore.moveSpeedDial(tile.url, -1); onDone() }) { Text("Move left", color = c.text) }
                        TextButton(onClick = { BrowserStore.moveSpeedDial(tile.url, 1); onDone() }) { Text("Move right", color = c.text) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (tile == null) {
                    // Typed words, not an address, are left out: a tile needs a site.
                    Omnibox.resolve(address, "duckduckgo")?.takeIf { address.isNotBlank() && !address.trim().contains(' ') }
                        ?.let { url -> BrowserStore.addSpeedDial(url, name.ifBlank { Omnibox.displayHost(url) }) }
                } else {
                    BrowserStore.renameSpeedDial(tile.url, name)
                }
                onDone()
            }) { Text(if (tile == null) "Add" else "Save", color = c.accent) }
        },
        dismissButton = {
            if (tile != null) {
                TextButton(onClick = { BrowserStore.removeSpeedDial(tile.url); onDone() }) { Text("Remove", color = c.err) }
            } else {
                TextButton(onClick = onDone) { Text("Cancel", color = c.text) }
            }
        },
    )
}

@Composable
private fun Field(label: String, value: String, type: KeyboardType, onChange: (String) -> Unit) {
    val c = colors
    Column {
        Text(label, style = Type.small, color = c.muted)
        Spacer(Modifier.height(4.dp))
        BasicTextField(
            value, onChange, singleLine = true, textStyle = Type.body.copy(color = c.text), cursorBrush = SolidColor(c.accent),
            keyboardOptions = KeyboardOptions(keyboardType = type),
            modifier = Modifier.fillMaxWidth().clip(Shape.control).background(c.panel2).padding(10.dp),
        )
    }
}
