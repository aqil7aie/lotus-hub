// Lotus Shell select bridge (v0.4.4).
//
// Why this exists: GeckoView hands the app a Choice snapshot for a
// tapped <select>, and for some SillyTavern/Neconyan selects that
// snapshot arrives with blank labels and no selection, while the page
// itself displays the right values. The system WebView draws select
// popups from the live DOM, so it never hits this; GeckoView needs the
// app to draw the popup, so the app must read the live DOM itself.
// This content script does exactly that: on a select tap it reads the
// select's real options (text, value, selected, disabled, optgroup)
// from the DOM and sends them to the app, which shows the dialog.
// On choice, the app sends the index back and the script applies it
// exactly as a native selection would: selectedIndex + input/change
// events, so the page's own handlers run unchanged.
//
// Only single, enabled selects are intercepted; <select multiple> and
// disabled selects are left to the native prompt path.
(function () {
    if (window.__lotusSelectBridge) return;
    window.__lotusSelectBridge = true;

    var port = null;
    try {
        port = browser.runtime.connectNative("lotus_shell");
    } catch (e) {
        return;
    }

    var counter = 0;
    var selects = new Map();

    function labelFor(select) {
        var aria = select.getAttribute("aria-label");
        if (aria && aria.trim()) return aria.trim();
        if (select.id) {
            var lab = document.querySelector('label[for="' + select.id + '"]');
            if (lab && lab.textContent.trim()) return lab.textContent.trim();
        }
        return select.id || "اختر من القائمة";
    }

    function readOptions(select) {
        var out = [];
        for (var i = 0; i < select.options.length; i++) {
            var o = select.options[i];
            var group = null;
            if (o.parentElement && o.parentElement.tagName === "OPTGROUP") {
                group = o.parentElement.getAttribute("label") || null;
            }
            out.push({
                text: o.text || "",
                value: o.value || "",
                selected: !!o.selected,
                disabled: !!o.disabled,
                group: group
            });
        }
        return out;
    }

    function attach(select) {
        if (!select || select.tagName !== "SELECT") return;
        if (select.multiple || select.disabled) return;
        if (select.dataset.lotusSelectAttached) return;
        select.dataset.lotusSelectAttached = "1";
        var id = ++counter;
        select.dataset.lotusSelectId = String(id);
        selects.set(id, select);
        select.addEventListener("mousedown", function (ev) {
            if (ev.button !== 0) return;
            ev.preventDefault();
            ev.stopPropagation();
            port.postMessage({
                type: "select-open",
                id: id,
                label: labelFor(select),
                options: readOptions(select),
                selectedIndex: select.selectedIndex
            });
        }, true);
    }

    port.onMessage.addListener(function (msg) {
        if (!msg || msg.type !== "select-choice") return;
        var select = selects.get(msg.id);
        if (!select) return;
        var idx = msg.index;
        if (typeof idx !== "number" || idx < 0 || idx >= select.options.length) return;
        select.selectedIndex = idx;
        select.dispatchEvent(new Event("input", { bubbles: true }));
        select.dispatchEvent(new Event("change", { bubbles: true }));
    });

    document.querySelectorAll("select").forEach(attach);

    var obs = new MutationObserver(function (mutations) {
        mutations.forEach(function (m) {
            m.addedNodes.forEach(function (node) {
                if (node.nodeType !== 1) return;
                if (node.tagName === "SELECT") attach(node);
                if (node.querySelectorAll) {
                    node.querySelectorAll("select").forEach(attach);
                }
            });
        });
    });
    obs.observe(document.documentElement, { childList: true, subtree: true });
})();
