package com.fastdownloader.app.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

private const val TAG = "ReaderSpeech"
private const val NO_BLOCK = -1

/**
 * Reads the reader view's blocks aloud with Android's text-to-speech. [current] is the block being spoken (or the
 * one paused on), so the page can highlight it and scroll to it. Android's engine can't pause mid-sentence, so
 * pause stops and play starts again from the start of the current block. Call [release] when the reader closes.
 */
class ReaderSpeech(context: Context, private val blocks: List<String>) {
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null

    var current by mutableIntStateOf(NO_BLOCK)
        private set
    var speaking by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            main.post { if (status == TextToSpeech.SUCCESS) prepare() else fail("engine start failed ($status)") }
        }
    }

    private fun prepare() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                val index = utteranceId?.substringBefore(':')?.toIntOrNull() ?: return
                main.post { current = index }
            }
            override fun onDone(utteranceId: String?) {
                if (utteranceId?.substringBefore(':')?.toIntOrNull() == blocks.lastIndex && utteranceId.endsWith(LAST_CHUNK)) {
                    main.post { speaking = false; current = NO_BLOCK }
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { main.post { fail("speech error") } }
        })
    }

    private fun fail(reason: String) {
        Log.w(TAG, reason)
        failed = true
        speaking = false
    }

    /** Starts at the block being paused on, or the first one. */
    fun play() {
        val engine = tts ?: return fail("no engine")
        val from = if (current in blocks.indices) current else 0
        engine.stop()
        speaking = true
        var queued = 0
        for (i in from until blocks.size) {
            val chunks = blocks[i].chunked(MAX_CHUNK)
            chunks.forEachIndexed { j, text ->
                val id = "$i:" + if (j == chunks.lastIndex) LAST_CHUNK else j.toString()
                if (engine.speak(text, TextToSpeech.QUEUE_ADD, null, id) == TextToSpeech.SUCCESS) queued++
            }
        }
        if (queued == 0) fail("nothing could be queued")
    }

    fun pause() {
        tts?.stop()
        speaking = false
    }

    fun stop() {
        tts?.stop()
        speaking = false
        current = NO_BLOCK
    }

    fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        main.removeCallbacksAndMessages(null)
    }

    private companion object {
        const val LAST_CHUNK = "end"
        const val MAX_CHUNK = 3500
    }
}
