// Ghostream stand-in for AdSense: adsbygoogle.push() works and ad slots are marked done, so pages don't detect a blocker.
(function () {
    var stub = { push: function () {}, loaded: true, length: 0 };
    try { Object.defineProperty(window, "adsbygoogle", { value: stub, writable: true, configurable: true }); }
    catch (e) { window.adsbygoogle = stub; }
    var mark = function () {
        document.querySelectorAll("ins.adsbygoogle").forEach(function (el) {
            el.setAttribute("data-adsbygoogle-status", "done");
            el.style.setProperty("display", "none", "important");
        });
    };
    if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", mark); else mark();
})();
