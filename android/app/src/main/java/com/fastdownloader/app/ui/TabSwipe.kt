package com.fastdownloader.app.ui

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

private const val SWIPE_DISTANCE_PX = 120f

/**
 * Swiping sideways over a bar: left asks for the next tab (+1), right for the previous one (-1). One swipe moves one
 * tab, once the finger has travelled far enough, so a small slip while tapping does nothing.
 */
fun Modifier.swipeTabs(onSwipe: (Int) -> Unit): Modifier = pointerInput(Unit) {
    var travelled = 0f
    detectHorizontalDragGestures(
        onDragStart = { travelled = 0f },
        onDragEnd = {
            when {
                travelled <= -SWIPE_DISTANCE_PX -> onSwipe(1)
                travelled >= SWIPE_DISTANCE_PX -> onSwipe(-1)
            }
        },
        onDragCancel = { travelled = 0f },
        onHorizontalDrag = { _, delta -> travelled += delta },
    )
}
