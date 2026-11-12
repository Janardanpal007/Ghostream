// Ghostream stand-in for ad-blocker detectors (FuckAdBlock, BlockAdBlock, SniffAdBlock, prebid "ads.js" checks) and
// small ad helpers (Amazon apstag, comScore): every check reports "no ad blocker".
(function () {
    var w = window;
    var n = function () {};
    var later = function (f) { if (typeof f === "function") setTimeout(function () { try { f(); } catch (e) {} }, 1); };
    function Detector() {}
    var p = Detector.prototype;
    p.check = p.clearEvent = p.emitEvent = n;
    p.on = function (detected, f) { if (!detected) later(f); return this; };
    p.onDetected = function () { return this; };
    p.onNotDetected = function (f) { later(f); return this; };
    p.setOption = function () { return this; };
    p.options = { set: function () { return this; }, get: n };
    var one = new Detector();
    ["FuckAdBlock", "BlockAdBlock", "SniffAdBlock"].forEach(function (k) { w[k] = Detector; });
    ["fuckAdBlock", "blockAdBlock", "sniffAdBlock"].forEach(function (k) { w[k] = one; });
    w.canRunAds = true;
    w.isAdBlockActive = false;
    w.apstag = {
        _Q: [], init: n, setDisplayBids: n, renderImp: n,
        fetchBids: function (cfg, cb) { if (typeof cb === "function") setTimeout(function () { cb([]); }, 1); },
        targetingKeys: function () { return []; },
    };
    w.COMSCORE = { beacon: n, purge: function () { w._comscore = []; } };
})();
