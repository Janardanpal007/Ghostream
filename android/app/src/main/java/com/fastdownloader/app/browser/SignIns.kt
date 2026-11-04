package com.fastdownloader.app.browser

import android.webkit.CookieManager

/**
 * "Clear cookies" without signing out of Google and YouTube: their sign-in cookies are read first and put back
 * after everything is cleared. (Clearing history never touches cookies, so it never signs anyone out.)
 */
object SignIns {
    /** Where the sign-in cookies are read from, and the domain they're put back for. */
    private val SITES = listOf(
        "https://accounts.google.com/" to ".google.com",
        "https://www.google.com/" to ".google.com",
        "https://www.youtube.com/" to ".youtube.com",
        "https://accounts.youtube.com/" to ".youtube.com",
    )

    fun clearCookiesKeepingSignIns() {
        val cm = CookieManager.getInstance()
        // url → name → value; a name read from several pages of one site is kept once.
        val kept = SITES.map { (url, domain) ->
            Triple(url, domain, cm.getCookie(url).orEmpty().split(';').mapNotNull { pair ->
                val i = pair.indexOf('=')
                if (i <= 0) null else pair.substring(0, i).trim() to pair.substring(i + 1).trim()
            })
        }
        cm.removeAllCookies {
            val seen = HashSet<String>()
            kept.forEach { (url, domain, cookies) ->
                cookies.forEach { (name, value) ->
                    // __Host- cookies belong to one host with no Domain; the rest to the whole site.
                    val host = name.startsWith("__Host-")
                    if (!seen.add((if (host) url else domain) + "|" + name)) return@forEach
                    val sameSite = if (name.contains("3P")) "; SameSite=None" else ""
                    val attrs = if (host) "; Path=/; Secure$sameSite" else "; Domain=$domain; Path=/; Secure; Max-Age=34560000$sameSite"
                    cm.setCookie(url, "$name=$value$attrs")
                }
            }
            cm.flush()
        }
    }
}
