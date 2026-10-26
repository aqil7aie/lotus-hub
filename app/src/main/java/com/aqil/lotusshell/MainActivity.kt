package com.aqil.lotusshell

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.NavigationDelegate
import org.mozilla.geckoview.GeckoSession.ProgressDelegate
import org.mozilla.geckoview.GeckoSession.PromptDelegate
import org.mozilla.geckoview.GeckoSession.PromptDelegate.ChoicePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptResponse
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebRequestError
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lotus Shell v0 skeleton: a GeckoView shell that loads one self-hosted
 * SillyTavern / Neconyan address and remembers it.
 *
 * Deliberately small: no third-party dependencies besides GeckoView itself.
 * v0.4: no auto-load on launch (saved address prefilled only); the row
 * hides only on successful load (onPageStop success), not on load start.
 * v0.4.1 (smoothness attempt): GeckoView exposes no renderer-speed pref
 * to apps, so the shell uses the real levers it has — input auto-zoom
 * off (no zoom jump + reflow when tapping the chat box), automatic font
 * size adjustment off (web layout keeps its own sizes, no system-scale
 * reflow), and mobile viewport + mobile user agent locked in so ST serves
 * its lighter mobile layout. If the hitch stays, it is the phone GPU +
 * ST's heavy page, not the shell — accepted as-is per Aqil's word.
 * v0.4.2 (dropdown fix): GeckoView has NO built-in UI — a page's native
 * <select> menus only appear if the app implements PromptDelegate. The
 * shell never did, so every ST select (API provider, connection profile,
 * model lists) silently did nothing when tapped. Now onChoicePrompt
 * shows the options as a native single-choice list dialog and confirms
 * the tapped item back to the page. Single-choice only; ST's selects
 * are single, and <select multiple> is not used in its settings.
 * v0.4.4 (select bridge, the real fix): Gecko's Choice snapshot
 * arrives blank for some ST/Neconyan selects (Neconyan's API-type
 * select: 2 options, blank labels, no selection) while the page shows
 * the right values — the snapshot cannot be trusted for those. A
 * bundled WebExtension content script (assets/select-bridge) now
 * intercepts select taps, reads the real options from the live DOM
 * (text, falling back to value, then number; optgroup as prefix; true
 * selected index), and the app shows that dialog instead; the choice
 * returns through the port and the script applies it as a native
 * selection (selectedIndex + input/change events). The PromptDelegate
 * path stays as fallback for selects the script skips.
 * v0.4.5 (fullscreen): sticky immersive mode — status bar and
 * navigation bar hidden so ST uses the whole screen, re-applied on
 * window focus; plus the "null" group-prefix cleanup (JS null group
 * labels arrived as the literal string "null"). Built on v0.4.4,
 * which Aqil keeps installed as the fallback if anything breaks.
 * v0.4.6 (fullscreen fix): v0.4.5's LAYOUT_* flags caused two
 * regressions — a black void strip at the top (LAYOUT_STABLE reserves
 * the status-bar area even when bars are hidden) and the keyboard
 * covering the composer again (broken adjustResize). Dropped all
 * LAYOUT_* flags; the hide flags alone give the full screen now that
 * the bars take no space, restoring v0.4's geometry.
 * v0.4.7 (behind the camera): Android's default cutout mode
 * letterboxes the window below the camera cutout — the black void's
 * second half. SHORT_EDGES (API 28+) lets ST draw behind the camera
 * like TauriTavern's edge-to-edge WebView does.
 * v0.4.8 (strip gone, not recolored): v0.4.7 only fixed the window
 * half — the window may now reach the cutout, but ST's page never
 * declares viewport-fit=cover, so Gecko keeps the page below the
 * cutout and the strip shows the view background: black in v0.4.5
 * (window background) became white in v0.4.7 (GeckoView's default
 * white background). Two halves now: a document_start content
 * script adds viewport-fit=cover to the page's viewport meta before
 * first layout, so the page itself extends to the top edge; and the
 * GeckoView background is forced black, so no unpainted area can
 * ever show white again.
 * v0.4.9 (the extension-update lesson): 0.4.8's strip stayed black
 * on Aqil's phone, which proves 0.4.8 IS installed (0.4.7 showed
 * white) but its viewport script never ran — a bundled GeckoView
 * extension does not refresh when the app updates; the old installed
 * extension keeps running and the new script never loads. The layout
 * file was audited and is clean (no fitsSystemWindows, no margins;
 * the GeckoView fills the window), so the strip lives inside
 * GeckoView. Three levers at once: (1) a NEW extension id, forcing
 * a fresh install of the viewport script (the stale one stays but
 * is inert for selects — the DOM attach flag is shared, so only one
 * script ever attaches); (2) the same viewport patch injected
 * natively on every successful page load, so the fix no longer
 * depends on extension-update semantics at all; (3) true
 * edge-to-edge decor (setDecorFitsSystemWindows(false), API 30+) —
 * TauriTavern's enableEdgeToEdge call — so the decor itself stops
 * insetting content away from the cutout.
 * TODO (next phases, in order):
 *  1. Multi-server list (SillyTavern + Neconyan + more, one tap to switch).
 *  2. Full Arabic strings in strings.xml.
 *  3. Keep-alive: foreground service + content-script visibility handling.
 *  4. Reply notifications: listen for the ST MESSAGE_RECEIVED event.
 *  5. Downloads routed to the Downloads folder.
 *  6. Burn-in guard overlay for long waits.
 *  7. In-app updates with signature + SHA-256 checks (Tavernium-style).
 */
class MainActivity : Activity() {
    private lateinit var geckoView: GeckoView
    private lateinit var session: GeckoSession
    private lateinit var urlInput: EditText
    private lateinit var addressRow: LinearLayout
    private lateinit var statusView: TextView
    private var sessionCanGoBack = false

    // v0.4.4 select bridge: the content script reads a tapped select's
    // real options from the live DOM (text, value, selected, disabled,
    // optgroup) because Gecko's Choice snapshot arrives blank for some
    // ST/Neconyan selects. The app shows the dialog and sends the chosen
    // index back through the port; the script applies it as a native
    // selection (selectedIndex + input/change events).
    private var selectPort: WebExtension.Port? = null
    private var jsSelectDialogOpen = false
    private var jsSelectDialog: AlertDialog? = null
    private var pendingSelectPort: WebExtension.Port? = null
    private var pendingSelectId: Int = -1
    private var nativeSelectDialog: AlertDialog? = null
    private var nativePromptResult: GeckoResult<PromptResponse>? = null
    private var nativePrompt: ChoicePrompt? = null

    private val selectPortDelegate = object : WebExtension.PortDelegate {
        override fun onPortMessage(message: Any, port: WebExtension.Port) {
            if (message !is JSONObject) return
            if (message.optString("type") != "select-open") return
            runOnUiThread { showJsSelectDialog(message, port) }
        }

        override fun onDisconnect(port: WebExtension.Port) {
            if (port == selectPort) selectPort = null
            if (port == pendingSelectPort) {
                pendingSelectPort = null
                pendingSelectId = -1
                jsSelectDialog?.dismiss()
                jsSelectDialog = null
                jsSelectDialogOpen = false
            }
        }
    }

    private val selectMessageDelegate = object : WebExtension.MessageDelegate {
        override fun onConnect(port: WebExtension.Port) {
            selectPort = port
            port.setDelegate(selectPortDelegate)
        }

        override fun onMessage(
            nativeApp: String,
            message: Any,
            sender: WebExtension.MessageSender
        ): GeckoResult<Any>? {
            return null
        }
    }

    private fun showJsSelectDialog(json: JSONObject, port: WebExtension.Port) {
        // Duplicate delivery guard: the same tap must never open two
        // dialogs (both delegate registrations may report one port).
        if (jsSelectDialogOpen) return
        val id = json.optInt("id", -1)
        val options: JSONArray = json.optJSONArray("options") ?: JSONArray()
        if (options.length() == 0) return
        // If the native prompt for this same tap already opened, replace
        // it: the DOM reading is authoritative, the snapshot is not.
        nativeSelectDialog?.let { dlg ->
            if (dlg.isShowing) dlg.dismiss()
            nativeSelectDialog = null
        }
        nativePromptResult?.let { res ->
            nativePrompt?.let { pr -> res.complete(pr.dismiss()) }
            nativePromptResult = null
            nativePrompt = null
        }
        val labels = Array(options.length()) { i ->
            val o = options.optJSONObject(i)
            val text = o?.optString("text")?.takeIf { it.isNotBlank() }
            val value = o?.optString("value")?.takeIf { it.isNotBlank() }
            // Gecko's port JSON turns a JS null group label into the
            // literal string "null": treat it as blank, like a real
            // blank, so rows show just the provider name.
            val group = o?.optString("group")
                ?.takeIf { it.isNotBlank() && it != "null" }
            val base = text ?: value ?: "خيار ${i + 1}"
            if (group != null) "$group — $base" else base
        }
        var checked = json.optInt("selectedIndex", -1)
        if (checked < 0 || checked >= options.length()) {
            checked = -1
            for (i in 0 until options.length()) {
                if (options.optJSONObject(i)?.optBoolean("selected") == true) {
                    checked = i
                    break
                }
            }
        }
        val title = json.optString("label").takeIf { it.isNotBlank() }
            ?: "اختر من القائمة"
        jsSelectDialogOpen = true
        pendingSelectPort = port
        pendingSelectId = id
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(labels, checked) { dlg, which ->
                val reply = JSONObject()
                reply.put("type", "select-choice")
                reply.put("id", id)
                reply.put("index", which)
                port.postMessage(reply)
                jsSelectDialogOpen = false
                pendingSelectPort = null
                pendingSelectId = -1
                dlg.dismiss()
            }
            .setNegativeButton("إلغاء") { dlg, _ ->
                jsSelectDialogOpen = false
                pendingSelectPort = null
                pendingSelectId = -1
                dlg.dismiss()
            }
            .setOnCancelListener {
                jsSelectDialogOpen = false
                pendingSelectPort = null
                pendingSelectId = -1
            }
            .show()
        jsSelectDialog = dialog
    }

    // v0.4.6: the whole screen belongs to the app — status bar
    // (network, battery, clock) and navigation bar stay hidden so ST
    // gets every pixel. Sticky immersive: a swipe shows the bars
    // briefly, then they hide again by themselves. Re-applied whenever
    // the window regains focus (dialogs and swipes can drop the flags).
    // NOTE — no LAYOUT_* flags, deliberately (v0.4.5 lesson): LAYOUT_STABLE
    // lays content out AS IF the bars were visible, so it reserved the
    // status-bar strip as a black void even with the bars hidden, and
    // the layout flags broke the keyboard resize that v0.4's
    // adjustResize had fixed (composer covered by the keyboard instead
    // of jumping above it). With the bars hidden, they take no space,
    // so the plain hide flags already give the full screen, and the
    // window geometry is exactly v0.4's proven one.
    private fun enableImmersive() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
            )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableImmersive()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // v0.4.7: draw behind the camera cutout. Android's default
        // cutout mode letterboxes the window below the cutout in
        // portrait — that full-width strip is exactly the black void
        // Aqil saw in v0.4.5. SHORT_EDGES lets the window extend into
        // the cutout on the short edges (top in portrait), so ST fills
        // the whole screen like TauriTavern does (its AndroidWindowLayout
        // runs edge-to-edge with the WebView at full window size and
        // zero insets in immersive mode). API 28+ only; older phones
        // keep the previous behaviour.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            val attrs = window.attributes
            attrs.layoutInDisplayCutoutMode =
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = attrs
        }
        // v0.4.9: true edge-to-edge decor (TauriTavern's
        // enableEdgeToEdge). The hide flags alone leave the decor
        // fitting system windows, which can still inset content away
        // from the cutout; opting out lays the content under the bars
        // and the cutout for real. API 30+ only.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        }
        enableImmersive()

        geckoView = findViewById(R.id.geckoview)
        urlInput = findViewById(R.id.serverUrl)
        addressRow = findViewById(R.id.addressRow)
        statusView = findViewById(R.id.statusView)
        val goButton: Button = findViewById(R.id.goButton)
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        // Smoothness levers (see KDoc): input auto-zoom off, automatic
        // font-size adjustment off; mobile viewport + mobile UA locked in.
        val runtimeSettings = GeckoRuntimeSettings.Builder()
            .inputAutoZoomEnabled(false)
            .automaticFontSizeAdjustment(false)
            .build()
        val runtime = GeckoRuntime.create(this, runtimeSettings)
        val settings = GeckoSessionSettings.Builder()
            .allowJavascript(true)
            .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
            .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            .build()
        session = GeckoSession(settings)
        session.open(runtime)
        geckoView.setSession(session)
        // v0.4.8: never show white. GeckoView's default background is
        // white, so any area the page does not paint (the cutout strip
        // before the viewport fix lands, or during load) showed as a
        // white band. Black is the only acceptable fallback.
        geckoView.setBackgroundColor(android.graphics.Color.BLACK)

        // v0.4.4 select bridge: install the bundled content-script
        // extension. If it fails, the PromptDelegate fallback below stays
        // in place, so selects keep working as in v0.4.3.
        // v0.4.9: NEW extension id. The v0.4.4 id's installed copy
        // never refreshes on app update, so the v0.4.8 viewport script
        // never loaded on Aqil's phone; a new id forces a fresh
        // install. The stale extension stays installed but cannot
        // double-attach selects (the DOM attach flag is shared).
        runtime.webExtensionController.ensureBuiltIn(
            "resource://android/assets/select-bridge/",
            "lotus-shell-bridge@lotus-shell"
        ).accept(
            { extension ->
                extension?.let { ext ->
                    session.webExtensionController.setMessageDelegate(
                        ext, selectMessageDelegate, "lotus_shell"
                    )
                    ext.setMessageDelegate(selectMessageDelegate, "lotus_shell")
                }
            },
            { /* bridge unavailable: native fallback stays */ }
        )

        session.navigationDelegate = object : NavigationDelegate {
            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                sessionCanGoBack = canGoBack
            }

            override fun onLoadError(
                session: GeckoSession,
                uri: String?,
                error: WebRequestError
            ): GeckoResult<String>? {
                statusView.text = "تعذر فتح $uri — تأكد أن السيرفر شغال والعنوان صحيح."
                statusView.visibility = View.VISIBLE
                showAddressRow()
                return null
            }
        }

        // Native <select> menus are content prompts: without this
        // delegate they silently do nothing when tapped. This is the
        // FALLBACK path (v0.4.2/v0.4.3): the select bridge above handles
        // taps first with real DOM texts, and this path only runs when
        // the bridge did not (extension missing, or a select the script
        // skipped, e.g. <select multiple>).
        session.promptDelegate = object : PromptDelegate {
            override fun onChoicePrompt(
                session: GeckoSession,
                prompt: ChoicePrompt
            ): GeckoResult<PromptResponse>? {
                val result = GeckoResult<PromptResponse>()
                // The bridge already opened its dialog for this tap:
                // dismiss the native prompt silently, never a 2nd dialog.
                if (jsSelectDialogOpen) {
                    result.complete(prompt.dismiss())
                    return result
                }
                // GeckoView 158's Choice has no children/optgroup member,
                // so the choices are used as delivered.
                val choices = prompt.choices
                if (choices.isEmpty()) {
                    // Visible diagnostic: the select arrived with zero
                    // options. Showing the fact beats a dialog that
                    // never opens.
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(prompt.title)
                        .setMessage("القائمة وصلت فارغة من الصفحة (٠ خيار).")
                        .setPositiveButton("حسناً") { dialog, _ ->
                            result.complete(prompt.dismiss())
                            dialog.dismiss()
                        }
                        .setOnCancelListener {
                            result.complete(prompt.dismiss())
                        }
                        .show()
                    return result
                }
                // Some ST selects arrive with blank labels: the rows
                // exist but carry no text. Fall back to numbered rows
                // so selection still works, and mark the selected row.
                val checked = choices.indexOfFirst { it.selected }
                val labels = choices.mapIndexed { i, choice ->
                    val text = choice.label?.takeIf { it.isNotBlank() }
                    if (text != null) text
                    else if (i == checked) "خيار ${i + 1} (المحدد حالياً)"
                    else "خيار ${i + 1}"
                }.toTypedArray()
                nativePromptResult = result
                nativePrompt = prompt
                val dialog = AlertDialog.Builder(this@MainActivity)
                    .setTitle(prompt.title)
                    .setSingleChoiceItems(labels, checked) { dlg, which ->
                        result.complete(prompt.confirm(arrayOf(choices[which])))
                        nativePromptResult = null
                        nativePrompt = null
                        nativeSelectDialog = null
                        dlg.dismiss()
                    }
                    .setNegativeButton("إلغاء") { dlg, _ ->
                        result.complete(prompt.dismiss())
                        nativePromptResult = null
                        nativePrompt = null
                        nativeSelectDialog = null
                        dlg.dismiss()
                    }
                    .setOnCancelListener {
                        result.complete(prompt.dismiss())
                        nativePromptResult = null
                        nativePrompt = null
                        nativeSelectDialog = null
                    }
                    .show()
                nativeSelectDialog = dialog
                return result
            }
        }

        session.progressDelegate = object : ProgressDelegate {
            override fun onPageStop(session: GeckoSession, success: Boolean) {
                if (success) {
                    hideAddressRow()
                    statusView.visibility = View.GONE
                    // v0.4.9: native backup of the viewport-cover
                    // script — the same patch, injected from the app
                    // on every successful load, so the fix no longer
                    // depends on extension-update semantics.
                    session.evaluateJavascript(VIEWPORT_COVER_JS)
                } else {
                    showAddressRow()
                    if (statusView.visibility == View.GONE) {
                        statusView.text = "تعذر تحميل الصفحة — تأكد أن السيرفر شغال والعنوان صحيح."
                        statusView.visibility = View.VISIBLE
                    }
                }
            }
        }

        // Saved address is prefilled only — never auto-loaded. The address
        // row must stay visible until the user enters an address and it
        // loads successfully (hide only on enter + working server).
        val saved = prefs.getString(KEY_URL, null)
        if (!saved.isNullOrBlank()) {
            urlInput.setText(saved)
        }

        goButton.setOnClickListener { loadFromInput(prefs) }
        urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                loadFromInput(prefs)
                true
            } else {
                false
            }
        }
    }

    private fun loadFromInput(prefs: android.content.SharedPreferences) {
        val raw = urlInput.text.toString().trim()
        if (raw.isBlank()) return
        val url = normalize(raw)
        prefs.edit().putString(KEY_URL, url).apply()
        session.loadUri(url)
    }

    private fun normalize(raw: String): String {
        var url = raw
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://$url"
        }
        return url
    }

    private fun hideAddressRow() {
        addressRow.visibility = View.GONE
    }

    private fun showAddressRow() {
        addressRow.visibility = View.VISIBLE
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (sessionCanGoBack) {
            session.goBack()
        } else if (addressRow.visibility == View.GONE) {
            showAddressRow()
        } else {
            super.onBackPressed()
        }
    }

    companion object {
        private const val PREFS = "lotus-shell-prefs"
        private const val KEY_URL = "server_url"
        // Same patch as assets/select-bridge/viewport-cover.js, kept
        // as a native backup: if the page's viewport meta lacks
        // viewport-fit, add cover so the page paints into the cutout.
        private const val VIEWPORT_COVER_JS = """
            (function(){try{var m=document.querySelector('meta[name="viewport"]');if(!m){m=document.createElement('meta');m.setAttribute('name','viewport');m.setAttribute('content','width=device-width, initial-scale=1, viewport-fit=cover');(document.head||document.documentElement).appendChild(m);return;}var c=m.getAttribute('content')||'';if(!/viewport-fit\s*=/.test(c)){m.setAttribute('content',(c?c.replace(/,\s*$/,'')+', ':'')+'viewport-fit=cover');}}catch(e){}})();
        """
    }
}
