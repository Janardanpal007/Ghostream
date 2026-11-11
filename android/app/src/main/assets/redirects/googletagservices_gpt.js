// Ghostream stand-in for Google Publisher Tag: the googletag API exists and its command queue runs, but no ads load.
(function () {
    var n = function () {};
    var self = function () { return this; };
    var none = function () { return []; };
    var slot = {
        addService: self, defineSizeMapping: self, setTargeting: self, clearTargeting: self, setCollapseEmptyDiv: self,
        setClickUrl: self, setForceSafeFrame: self, setSafeFrameConfig: self, setConfig: self, updateTargetingFromMap: self,
        getSlotElementId: function () { return ""; }, getAdUnitPath: function () { return ""; },
        getTargeting: none, getTargetingKeys: none, getSizes: none, getResponseInformation: function () { return null; },
    };
    var service = {
        addEventListener: self, removeEventListener: self, enableSingleRequest: self, enableLazyLoad: self,
        collapseEmptyDivs: self, disableInitialLoad: self, enableAsyncRendering: self, setTargeting: self,
        clearTargeting: self, setPrivacySettings: self, setRequestNonPersonalizedAds: self, setCentering: self,
        updateCorrelator: self, set: self, setConfig: self, refresh: n, clear: n,
        get: function () { return null; }, getSlots: none, getTargeting: none, getTargetingKeys: none,
    };
    var g = window.googletag || {};
    var queued = Array.isArray(g.cmd) ? g.cmd : [];
    g.apiReady = true;
    g.pubadsReady = true;
    g.pubads = g.companionAds = g.content = function () { return service; };
    g.defineSlot = g.defineOutOfPageSlot = function () { return slot; };
    g.destroySlots = g.display = g.enableServices = g.disablePublisherConsole = g.openConsole = g.setConfig = n;
    g.sizeMapping = function () {
        var m = { addSize: function () { return m; }, build: none };
        return m;
    };
    g.cmd = { push: function () { for (var i = 0; i < arguments.length; i++) try { arguments[i](); } catch (e) {} return 1; } };
    window.googletag = g;
    queued.forEach(function (f) { try { f(); } catch (e) {} });
})();
