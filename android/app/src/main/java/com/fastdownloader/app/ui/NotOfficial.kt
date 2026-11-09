package com.fastdownloader.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fastdownloader.app.R
import com.fastdownloader.app.engine.Integrity

/** Shown instead of the app when this copy wasn't signed by Ghostream (someone changed it and signed it again). */
@Composable
fun NotOfficial() {
    val c = colors
    val ctx = LocalContext.current
    Column(
        Modifier.fillMaxSize().background(c.bg).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            painterResource(R.drawable.ic_launcher_foreground), null,
            Modifier.size(72.dp).clip(Shape.card).background(colorResource(R.color.icon_background)),
        )
        Spacer(Modifier.height(20.dp))
        Text("This isn't the official Ghostream", style = Type.title, color = c.text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text(
            "Someone changed this copy of the app and signed it again, so it may contain ads or harmful code. " +
                "Uninstall it and download Ghostream from the official website.",
            style = Type.body, color = c.muted, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        SolidButton("Open the official website") {
            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Integrity.WEBSITE)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
}
