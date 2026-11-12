// Ghostream stand-in for Google Analytics (analytics.js / ga.js) and Tag Manager: nothing is sent, but the callbacks
// pages wait on (hitCallback, eventCallback) still run, so links and forms that wait for tracking keep working.
(function () {
    var w = window;
    var n = function () {};
    var later = function (f) { if (typeof f === "function") setTimeout(function () { try { f(); } catch (e) {} }, 1); };
    var tracker = { get: n, set: n, send: n };
    var ga = function () {
        var last = arguments[arguments.length - 1];
        if (typeof last === "function") { try { last(tracker); } catch (e) {} }
        else if (last && typeof last === "object") later(last.hitCallback);
    };
    ga.create = ga.getByName = function () { return tracker; };
    ga.getAll = function () { return [tracker]; };
    ga.remove = n;
    ga.loaded = true;
    var name = w.GoogleAnalyticsObject || "ga";
    var queued = (w[name] && w[name].q) || [];
    w[name] = ga;
    queued.forEach(function (args) { ga.apply(null, args); });
    w._gaq = w._gaq || [];
    w._gat = w._gat || { _getTracker: function () { return { _trackPageview: n, _trackEvent: n, _setAccount: n }; } };
    var dl = w.dataLayer;
    if (dl && typeof dl.push === "function") {
        dl.forEach(function (o) { if (o) later(o.eventCallback); });
        var push = dl.push;
        dl.push = function (o) { if (o) later(o.eventCallback); return push.apply(this, arguments); };
    }
})();
