// Lotus Shell page zoom (v0.5.3).
//
// Why this exists: GeckoView exposes no public page-zoom API.
// fontSizeFactor is text-only, displayDensityOverride has no
// runtime setter, and PanZoomController only handles gestures.
// So the shell scales the page itself: a CSS `zoom` on the
// document element, which scales layout + text + images
// together. CSS zoom is supported in Firefox 126+ (bug
// 390936); GeckoView 158 is far newer, so it works here.
// Applied to the root element on purpose: zoom on a non-root
// element does not scale rem-based font sizes, but on the
// root it does (bug 1835345).
//
// The app pushes the current factor as soon as this port
// connects, so a fresh page load lands at the right zoom,
// and again on every +/- tap. The factor lives in the app
// (SharedPreferences), never here, so there is nothing to
// keep in sync across navigations.
(function () {
    if (window.__lotusZoom) return;
    window.__lotusZoom = true;

    var current = 1;

    function apply(factor) {
        current = factor;
        var el = document.documentElement;
        if (el) el.style.zoom = String(factor);
    }

    var port = null;
    try {
        port = browser.runtime.connectNative("lotus_zoom");
    } catch (e) {
        return;
    }

    port.onMessage.addListener(function (msg) {
        if (!msg || msg.type !== "zoom-set") return;
        var f = msg.factor;
        if (typeof f !== "number" || !(f > 0)) return;
        apply(f);
    });

    // document_start normally has documentElement already; if
    // it does not, apply once the document exists.
    if (!document.documentElement) {
        document.addEventListener("DOMContentLoaded", function () {
            apply(current);
        }, { once: true });
    }
})();
