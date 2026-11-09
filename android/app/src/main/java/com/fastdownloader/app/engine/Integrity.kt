package com.fastdownloader.app.engine

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.fastdownloader.app.BuildConfig
import java.security.MessageDigest

/**
 * Is this the official Ghostream? Release builds are signed with the Ghostream key, which only the developer has.
 * A copy that someone unpacked, changed and signed again carries another certificate, and refuses to run.
 */
object Integrity {
    /** SHA-256 of the Ghostream release signing certificate (CN=Ghostream). */
    private const val OFFICIAL_CERT = "db1d41723785a6aecc18a084c7ad8b796fd5431f5c1958ec70b940023707a407"
    const val WEBSITE = "https://janardanpal007.github.io/Ghostream/"

    @Volatile private var result: Boolean? = null

    /** Debug builds (signed with the developer's debug key) always pass. */
    fun official(ctx: Context): Boolean = result ?: (BuildConfig.DEBUG || OFFICIAL_CERT in certificates(ctx)).also { result = it }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Suppress("DEPRECATION")
    private fun certificates(ctx: Context): List<String> = runCatching {
        val pm = ctx.packageManager
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
            when {
                info == null -> emptyArray()
                info.hasMultipleSigners() -> info.apkContentsSigners
                else -> info.signingCertificateHistory
            }
        } else {
            pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES).signatures
        }
        signatures.orEmpty().map { sha256(it.toByteArray()) }
    }.getOrDefault(emptyList())
}
