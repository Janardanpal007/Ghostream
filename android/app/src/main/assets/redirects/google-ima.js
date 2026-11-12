// Ghostream stand-in for Google's IMA video ad SDK: every ad request ends in "no ads", so the player goes
// straight to the video instead of waiting for an ad that was blocked.
(function () {
    if (window.google && google.ima && google.ima.AdsLoader) return;
    var n = function () {};
    function Target() { this.l = {}; }
    Target.prototype.addEventListener = function (t, f) {
        [].concat(t).forEach(function (k) { (this.l[k] = this.l[k] || []).push(f); }, this);
    };
    Target.prototype.removeEventListener = function (t, f) {
        [].concat(t).forEach(function (k) { this.l[k] = (this.l[k] || []).filter(function (g) { return g !== f; }); }, this);
    };
    Target.prototype.fire = function (e) {
        (this.l[e.type] || []).slice().forEach(function (f) { try { f.call(null, e); } catch (x) {} });
    };
    function AdError() {}
    AdError.prototype.getErrorCode = function () { return 1009; };
    AdError.prototype.getVastErrorCode = function () { return 303; };
    AdError.prototype.getMessage = function () { return "No ads"; };
    AdError.prototype.getType = function () { return "adLoadError"; };
    AdError.prototype.getInnerError = function () { return null; };
    AdError.prototype.toString = function () { return "AdError 1009: No ads"; };
    function AdErrorEvent(ctx) { this.type = "adError"; this.ctx = ctx; }
    AdErrorEvent.prototype.getError = function () { return new AdError(); };
    AdErrorEvent.prototype.getUserRequestContext = function () { return this.ctx || {}; };
    var settings = { getLocale: function () { return "en"; }, getPlayerType: function () { return ""; } };
    ["setAutoPlayAdBreaks", "setCompanionBackfill", "setDisableCustomPlaybackForIOS10Plus", "setLocale", "setNumRedirects",
        "setPlayerType", "setPlayerVersion", "setPpid", "setSessionId", "setVpaidMode", "setVpaidAllowed",
        "setFeatureFlags", "setCookiesEnabled"].forEach(function (k) { settings[k] = n; });
    function AdsLoader() { Target.call(this); }
    AdsLoader.prototype = Object.create(Target.prototype);
    AdsLoader.prototype.requestAds = function (req, ctx) {
        var self = this;
        setTimeout(function () { self.fire(new AdErrorEvent(ctx)); }, 5);
    };
    AdsLoader.prototype.contentComplete = n;
    AdsLoader.prototype.destroy = n;
    AdsLoader.prototype.getSettings = function () { return settings; };
    function AdDisplayContainer() {}
    AdDisplayContainer.prototype.initialize = n;
    AdDisplayContainer.prototype.destroy = n;
    var eventTypes = {};
    ["ALL_ADS_COMPLETED", "CLICK", "COMPLETE", "CONTENT_PAUSE_REQUESTED", "CONTENT_RESUME_REQUESTED", "LOADED", "STARTED",
        "SKIPPED", "PAUSED", "RESUMED", "AD_PROGRESS", "AD_BREAK_READY", "LOG", "USER_CLOSE", "IMPRESSION",
        "FIRST_QUARTILE", "MIDPOINT", "THIRD_QUARTILE", "VOLUME_CHANGED", "DURATION_CHANGE", "LINEAR_CHANGED",
        "AD_METADATA", "INTERACTION", "SKIPPABLE_STATE_CHANGED"].forEach(function (k) { eventTypes[k] = k.toLowerCase(); });
    window.google = window.google || {};
    window.google.ima = {
        AdsLoader: AdsLoader,
        AdDisplayContainer: AdDisplayContainer,
        AdsRequest: function () {},
        AdsRenderingSettings: function () {},
        ImaSdkSettings: function () {},
        CompanionAdSelectionSettings: function () {},
        AdErrorEvent: { Type: { AD_ERROR: "adError" } },
        AdError: { ErrorCode: {}, Type: { AD_LOAD: "adLoadError", AD_PLAY: "adPlayError" } },
        AdEvent: { Type: eventTypes },
        AdsManagerLoadedEvent: { Type: { ADS_MANAGER_LOADED: "adsManagerLoaded" } },
        ViewMode: { NORMAL: "normal", FULLSCREEN: "fullscreen" },
        UiElements: { AD_ATTRIBUTION: "adAttribution", COUNTDOWN: "countdown" },
        OmidAccessMode: {},
        OmidVerificationVendor: {},
        settings: settings,
        VERSION: "3.0.0",
    };
})();
