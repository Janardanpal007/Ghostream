package com.fastdownloader.app.engine

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build

/**
 * What this phone can play smoothly, read from its hardware, so YouTube picks a quality the phone can keep up with.
 * Phones with a 90 Hz or 120 Hz screen still get 60 fps video, so the limit is the video decoder, not the screen.
 */
object DeviceProfile {
    private const val VP9 = "video/x-vnd.on2.vp9"
    private const val AVC = "video/avc"
    private const val FULL_HD_WIDTH = 1920
    private const val FULL_HD_HEIGHT = 1080
    private const val SMOOTH_FPS = 60

    /** The YouTube quality for "Best on Wi-Fi": 1080p if the phone decodes it in hardware at 60 fps, else 720p. */
    val wifiQuality: String by lazy { if (hardwareDecodes(VP9) || hardwareDecodes(AVC)) "hd1080" else "hd720" }

    /** A one-line summary of the phone, for the Settings screen and bug reports. */
    fun describe(): String = listOf(
        Build.MANUFACTURER.replaceFirstChar { it.uppercase() },
        Build.MODEL,
        "Android ${Build.VERSION.RELEASE}",
        "1080p60 in hardware: ${if (hardwareDecodes(VP9) || hardwareDecodes(AVC)) "yes" else "no"}",
    ).joinToString(" · ")

    /** True when a hardware decoder for [mime] plays 1080p at 60 fps. */
    private fun hardwareDecodes(mime: String): Boolean = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.any { info ->
        !info.isEncoder && isHardware(info) && supportsSmoothFullHd(info, mime)
    }

    private fun isHardware(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) !info.isSoftwareOnly
        else !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android.")

    private fun supportsSmoothFullHd(info: MediaCodecInfo, mime: String): Boolean {
        val types = info.supportedTypes.filter { it.equals(mime, ignoreCase = true) }
        return types.any { type ->
            runCatching {
                info.getCapabilitiesForType(type).videoCapabilities
                    ?.areSizeAndRateSupported(FULL_HD_WIDTH, FULL_HD_HEIGHT, SMOOTH_FPS.toDouble()) == true
            }.getOrDefault(false)
        }
    }
}
