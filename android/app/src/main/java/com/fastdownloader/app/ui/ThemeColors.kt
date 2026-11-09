package com.fastdownloader.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp

/** A colour theme: its colour in the light and the dark theme. "classic" keeps black/white buttons with a blue accent. */
data class ThemeColor(val id: String, val label: String, val light: Color, val dark: Color)

const val CLASSIC = "classic"

val THEME_COLORS = listOf(
    ThemeColor(CLASSIC, "Classic", Color(0xFF0E1116), Color(0xFFF3F5F9)),
    ThemeColor("blue", "Blue", Color(0xFF1D6FE0), Color(0xFF5AA9FF)),
    ThemeColor("violet", "Violet", Color(0xFF6D4AFF), Color(0xFFA493FF)),
    ThemeColor("teal", "Teal", Color(0xFF0E7C86), Color(0xFF2DD4BF)),
    ThemeColor("green", "Green", Color(0xFF138A43), Color(0xFF4ADE80)),
    ThemeColor("orange", "Orange", Color(0xFFC2560A), Color(0xFFFB923C)),
    ThemeColor("pink", "Pink", Color(0xFFC81E73), Color(0xFFF472B6)),
    ThemeColor("red", "Red", Color(0xFFC62828), Color(0xFFF87171)),
)

private val ON_LIGHT_ACCENT = Color.White
private val ON_DARK_ACCENT = Color(0xFF0B0D12)
private const val SOFT_ALPHA = 0.14f

/** The palette with the chosen colour on buttons, switches, chips and highlights. */
fun Palette.withColor(id: String, dark: Boolean): Palette {
    val t = THEME_COLORS.firstOrNull { it.id == id && it.id != CLASSIC } ?: return this
    val a = if (dark) t.dark else t.light
    return copy(
        accent = a,
        accentSoft = if (dark) a.copy(alpha = SOFT_ALPHA) else a.copy(alpha = SOFT_ALPHA).compositeOver(Color.White),
        solidBg = a,
        solidFg = if (dark) ON_DARK_ACCENT else ON_LIGHT_ACCENT,
    )
}

/** Settings › Appearance: a row of colour swatches. */
@Composable
fun ThemeColorPicker(selected: String, dark: Boolean, onPick: (String) -> Unit) {
    val c = colors
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text("Colour", style = Type.strong, color = c.text, modifier = Modifier.padding(horizontal = 16.dp))
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            for (t in THEME_COLORS) {
                val on = t.id == selected
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onPick(t.id) }) {
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(if (dark) t.dark else t.light)
                            .border(if (on) 3.dp else 1.dp, if (on) c.text else c.border, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (on) Icon(Icons.Check, t.label, tint = if (dark) ON_DARK_ACCENT else ON_LIGHT_ACCENT, modifier = Modifier.size(18.dp))
                    }
                    Text(t.label, style = Type.small, color = if (on) c.text else c.muted, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
    Divider()
}
