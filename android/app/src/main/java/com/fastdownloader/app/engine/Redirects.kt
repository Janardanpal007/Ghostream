package com.fastdownloader.app.engine

import kotlin.io.encoding.Base64

/**
 * Stand-in resources for filter rules with $redirect= (uBlock Origin's resource names and aliases).
 * A blocked ad script answered with a harmless stand-in keeps pages that check for it working; unknown
 * names return null and the request is simply blocked. Script stand-ins live in assets/redirects/.
 *
 * Plain Kotlin (no Android classes), so it can be unit-tested on the PC.
 */
object Redirects {
    /** What to answer with: inline [bytes], or the file [asset] under assets/redirects/. */
    class Resource(val mime: String, val bytes: ByteArray? = null, val asset: String? = null)

    private val GIF_1X1 = Base64.decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7")
    private val PNG_1X1 = Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=")

    private fun text(mime: String, body: String) = Resource(mime, body.toByteArray())
    private fun asset(file: String) = Resource("application/javascript", asset = file)

    private val RESOURCES: Map<String, Resource> = buildMap {
        fun add(r: Resource, vararg names: String) = names.forEach { put(it, r) }
        add(text("application/javascript", "(function(){})();"), "noop.js", "noopjs", "noop-js")
        add(text("text/plain", ""), "noop.txt", "nooptext", "noop-txt", "empty")
        add(text("text/html", "<!DOCTYPE html><html><head></head><body></body></html>"), "noop.html", "noopframe", "noop-html")
        add(text("text/css", ""), "noop.css", "noopcss")
        add(text("application/json", "{}"), "noop.json", "noopjson")
        add(Resource("image/gif", GIF_1X1), "1x1.gif", "1x1-transparent.gif")
        add(Resource("image/png", PNG_1X1), "2x2.png", "2x2-transparent.png", "3x2.png", "3x2-transparent.png", "32x32.png", "32x32-transparent.png")
        add(asset("google-ima.js"), "google-ima.js", "google-ima3")
        add(asset("googletagservices_gpt.js"), "googletagservices_gpt.js", "googletagservices.com/gpt.js", "googletagservices-gpt")
        add(asset("googlesyndication_adsbygoogle.js"), "googlesyndication_adsbygoogle.js", "googlesyndication.com/adsbygoogle.js", "googlesyndication-adsbygoogle")
        add(
            asset("google-analytics.js"),
            "google-analytics_analytics.js", "google-analytics.com/analytics.js", "googletagmanager_gtm.js",
            "googletagmanager.com/gtm.js", "google-analytics_ga.js", "google-analytics.com/ga.js", "googletagmanager-gtm",
        )
        add(
            asset("anti-adblock.js"),
            "nofab.js", "fuckadblock.js-3.2.0", "fuckadblock.js", "nobab.js", "bab-defuser.js", "nobab2.js",
            "prebid-ads.js", "amazon_apstag.js", "amazon-adsystem.com/aax2/amzn_ads.js", "scorecardresearch_beacon.js",
        )
    }

    /** The stand-in for a rule's redirect name, or null if Ghostream has none (then the request is just blocked). */
    fun find(name: String): Resource? = RESOURCES[name.trim().lowercase()]
}
