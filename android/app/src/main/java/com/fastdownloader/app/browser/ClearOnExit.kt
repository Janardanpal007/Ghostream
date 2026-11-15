package com.fastdownloader.app.browser

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView

/**
 * "Clear history, cookies and tabs when you leave": wipes what browsing left behind (history, saved tabs, cookies,
 * cache, site storage, private-tab data). Downloads, bookmarks and settings are not touched.
 */
object ClearOnExit {
    private const val TAG = "ClearOnExit"

    /** Runs the wipe on the main thread, while the app is closing. */
    fun run(ctx: Context) {
        BrowserStore.wipeAll()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        clearCache(ctx)
        PrivateProfile.wipe(retry = false)
    }

    /** The WebView cache is shared by every WebView, so a throwaway one can empty it. */
    private fun clearCache(ctx: Context) {
        try {
            val w = WebView(ctx.applicationContext)
            w.clearCache(true)
            w.destroy()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not clear the web cache", e)
        }
    }
}
