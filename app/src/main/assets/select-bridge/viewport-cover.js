// Lotus Shell viewport cover (v0.4.8). Runs at document_start, before
// the page's first layout.
//
// Why: the window may reach the camera cutout (SHORT_EDGES), but ST's
// viewport meta declares no viewport-fit, so Gecko keeps the page
// below the cutout and the strip shows the view background — black in
// v0.4.5, white in v0.4.7. Adding viewport-fit=cover makes the page
// itself use the full window, so its top bar sits at the very top
// edge and the strip disappears instead of changing color.
//
// This script is deliberately separate from the select bridge: it
// touches no port, no selects, and cannot break them. It patches the
// page's own viewport meta the moment it lands in the DOM (a
// MutationObserver microtask runs before the first rendering step),
// and re-applies at DOMContentLoaded as a safety net in case it was
// delivered late.
(function () {
    function apply() {
        try {
            var meta = document.querySelector('meta[name="viewport"]');
            if (!meta) return false;
            var c = meta.getAttribute("content") || "";
            if (!/viewport-fit\s*=/.test(c)) {
                meta.setAttribute(
                    "content",
                    (c ? c.replace(/,\s*$/, "") + ", " : "") + "viewport-fit=cover"
                );
            }
            return true;
        } catch (e) {
            return false;
        }
    }

    if (apply()) return;

    var done = false;
    function once() {
        if (!done && apply()) done = true;
    }

    try {
        if (document.documentElement) {
            var obs = new MutationObserver(function () {
                once();
                if (done) obs.disconnect();
            });
            obs.observe(document.documentElement, { childList: true, subtree: true });
        }
    } catch (e) {
        // Fall through to the DOMContentLoaded safety net below.
    }
    document.addEventListener("DOMContentLoaded", once, { once: true });
})();
