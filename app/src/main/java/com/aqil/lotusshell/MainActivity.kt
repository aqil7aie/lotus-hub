package com.aqil.lotusshell

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
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
 * GeckoView. Levers shipped: (1) a NEW extension id, forcing
 * a fresh install of the viewport script (the stale one stays but
 * is inert for selects — the DOM attach flag is shared, so only one
 * script ever attaches); (2) true edge-to-edge decor
 * (setDecorFitsSystemWindows(false), API 30+) — TauriTavern's
 * enableEdgeToEdge call — so the decor itself stops insetting
 * content away from the cutout. (A native viewport patch was
 * planned as a third lever but dropped before release:
 * GeckoSession exposes no JS-eval API, loadUri only.)
 * v0.5.0 (Lotus Hub): visible rename only — app label Lotus Hub
 * (package stays com.aqil.lotusshell, so the saved address
 * survives the update) + first real launcher icon (orange lotus
 * with hub nodes, Aqil's pick) + decorFits REVERTED: 0.4.9's
 * decor call broke the composer again (keyboard covered it),
 * so 0.5.0 returns to 0.4.7's geometry. Aqil tests strip AND
 * composer together on 0.5.0.
 * v0.5.0B (sub-version, Aqil's rule: internal fixes do NOT take
 * a new minor number until stable): 0.5.0 proved decorFits was
 * the strip-killer (strip back without it, composer fixed
 * without it). So decorFits(false) is BACK, and the composer is
 * decoupled from it — a WindowInsets listener sets the
 * GeckoView's bottom margin to the keyboard height, shrinking
 * the view so Gecko resizes its viewport and the composer
 * rises. Strip and composer must now both pass together, or
 * the pair is still open.
 * v0.5.1B (Hub fixes, from Aqil's 0.5.1 verdict): long-press
 * offers Edit + Delete + Cancel (edit replaces in place); legacy
 * migration runs ONCE so a deleted card never resurrects;
 * the form is always clean (no stale typing, no "saved by
 * itself"); the save button is labeled Save; Connect
 * (replacing Test) probes and shows green only for a real
 * connection, its message sits above the buttons; a
 * nameless card shows its URL once, with a globe icon;
 * deleting the last card returns to the form.
 * v0.5.1C (Connect fix, researched before building per
 * Aqil's order 2026-10-26 17:16): Android 9+ blocks
 * cleartext HTTP by default, so the probe's
 * HttpURLConnection was refused while GeckoView (its
 * own network stack) loaded the same URL fine — hence
 * ENTER worked and Connect failed on a running server.
 * Manifest now sets usesCleartextTraffic=true (this app
 * exists for local http servers), and a failed probe
 * reports its real cause instead of "no response".
 * v0.5.2 (the complete version, Aqil's order 2026-10-26
 * 17:58: a clean number, no B/C suffix — the hint is a
 * NEW feature, so it takes a new minor): the parked
 * coach hint above the first server card. Small English
 * writing: long-press for options, single tap to enter.
 * Its dismissal conditions are HIDDEN (no counters, no
 * explanation — not a session popup like HTML games):
 * one long-press fulfills the first condition, one
 * tap-and-enter fulfills the second, and only then does
 * the hint vanish FOREVER. It returns only when app
 * data is wiped (fresh prefs), because both conditions
 * live in SharedPreferences. Grid mode only: no hint
 * over the empty-state form, and none while a server
 * is open.
 * v0.5.1 (Hub, Aqil's pick): black home grid of saved-server
 * cards (name-labeled, one tap opens, long-press deletes) +
 * add-server form (name, server, optional user/password;
 * Save saves even if offline and never navigates, Connect
 * probes reachability only, Enter opens without saving; form
 * has Back to the grid; + top-right reopens the form) +
 * file upload via PromptDelegate.onFilePrompt (system chooser).
 * Hub UI is English per Aqil's 0.5.1 order; the older native
 * dialogs stay Arabic for now.
 * TODO (next phases, in order):
 *  1. (done in 0.5.1) Hub grid + add-server form + file upload.
 *  2. Full Arabic strings in strings.xml (superseded for the
 *     Hub itself, which Aqil ordered in English).
 *  3. Keep-alive: foreground service + content-script visibility handling.
 *  4. Reply notifications: listen for the ST MESSAGE_RECEIVED event.
 *  5. Downloads routed to the Downloads folder.
 *  6. Burn-in guard overlay for long waits.
 *  7. In-app updates with signature + SHA-256 checks (Tavernium-style).
 */
class MainActivity : Activity() {
    private lateinit var geckoView: GeckoView
    private lateinit var session: GeckoSession
    private lateinit var hubView: LinearLayout
    private lateinit var hubHint: TextView
    private lateinit var hubGridScroll: ScrollView
    private lateinit var hubGridList: LinearLayout
    private lateinit var hubFormScroll: ScrollView
    private lateinit var nameInput: EditText
    private lateinit var serverInput: EditText
    private lateinit var userInput: EditText
    private lateinit var passInput: EditText
    private lateinit var saveButton: Button
    private lateinit var connectButton: Button
    private lateinit var enterButton: Button
    private lateinit var backButton: Button
    private lateinit var addButton: Button
    private lateinit var formMessage: TextView
    private var sessionCanGoBack = false
    private var inServerView = false
    private var formShown = false
    // v0.5.1B: index of the card being edited, or -1 for a new
    // server. Saving with a valid index replaces that card in
    // place (even if its URL changed); otherwise it upserts by URL.
    private var editingIndex = -1

    // v0.5.1 Hub: saved servers, one per card. Stored as a JSON
    // array in prefs (KEY_SERVERS); user/pass are optional and live
    // in app-private prefs only. Legacy single address (KEY_URL)
    // migrates into one card on first Hub load, so the saved
    // address survives the 0.5.1 update.
    private data class HubServer(
        val name: String,
        val url: String,
        val user: String,
        val pass: String
    )

    private val servers = mutableListOf<HubServer>()

    // v0.5.1 file upload: the pending FilePrompt while the system
    // chooser is open (same no-built-in-UI family as selects:
    // GeckoView draws no file picker itself).
    private var filePromptResult: GeckoResult<PromptResponse>? = null
    private var filePrompt: PromptDelegate.FilePrompt? = null

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
        // v0.5.0B: decorFits RESTORED, composer decoupled from it.
        // 0.5.0 proved the trade-off both ways: decorFits(false)
        // (0.4.9) killed the strip but broke the composer, and
        // reverting it (0.5.0) fixed the composer but brought the
        // strip back — so decorFits WAS the strip-killer, and the
        // composer must no longer depend on it. The composer is
        // now handled by hand: the IME listener below shrinks
        // the GeckoView by the keyboard height (see
        // applyImeMargin), which resizes Gecko's viewport and
        // lifts the composer above the keyboard. Strip and
        // composer no longer fight over one flag. API 30+ only.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        }
        enableImmersive()

        geckoView = findViewById(R.id.geckoview)
        hubView = findViewById(R.id.hubView)
        hubHint = findViewById(R.id.hubHint)
        hubGridScroll = findViewById(R.id.hubGridScroll)
        hubGridList = findViewById(R.id.hubGridList)
        hubFormScroll = findViewById(R.id.hubFormScroll)
        nameInput = findViewById(R.id.nameInput)
        serverInput = findViewById(R.id.serverInput)
        userInput = findViewById(R.id.userInput)
        passInput = findViewById(R.id.passInput)
        saveButton = findViewById(R.id.saveButton)
        connectButton = findViewById(R.id.connectButton)
        enterButton = findViewById(R.id.enterButton)
        backButton = findViewById(R.id.backButton)
        addButton = findViewById(R.id.addButton)
        formMessage = findViewById(R.id.formMessage)
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        loadServers(prefs)

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

        // v0.5.0B: manual IME handling (the composer's second half).
        // With decorFits(false) the window no longer shrinks when
        // the keyboard opens, so adjustResize cannot lift the
        // composer — that was the whole 0.4.9 breakage. Instead,
        // every window-inset change sets the GeckoView's bottom
        // margin to the keyboard height: the view itself shrinks,
        // Gecko resizes its viewport to the new view size, and
        // the page's bottom-anchored composer rises above the
        // keyboard. Keyboard closed -> inset 0 -> margin 0, full
        // height again. Insets are NOT consumed, so Gecko still
        // receives them through its own channels. Platform API,
        // API 30+ only; older phones keep the previous behaviour.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            geckoView.setOnApplyWindowInsetsListener { _, insets ->
                val imeBottom = insets.getInsets(
                    android.view.WindowInsets.Type.ime()
                ).bottom
                val params = geckoView.layoutParams
                    as ViewGroup.MarginLayoutParams
                if (params.bottomMargin != imeBottom) {
                    params.bottomMargin = imeBottom
                    geckoView.layoutParams = params
                }
                insets
            }
        }

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
                // v0.5.1: no address row anymore — a failed load
                // returns to the Hub so the server can be edited,
                // tested, or re-entered from its card.
                if (inServerView) {
                    Toast.makeText(
                        this@MainActivity,
                        "Failed to load — check the server is running.",
                        Toast.LENGTH_LONG
                    ).show()
                    showHub(showForm = false)
                }
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

            // v0.5.1 file upload: GeckoView draws no file picker
            // itself, so <input type=file> (ST's attach button)
            // silently did nothing — the same family as the select
            // defect. Open the system chooser; the chosen URIs go
            // back through prompt.confirm. Multiple allowed so one
            // picker covers single and multiple inputs; camera
            // capture inputs fall back to the chooser too.
            override fun onFilePrompt(
                session: GeckoSession,
                prompt: PromptDelegate.FilePrompt
            ): GeckoResult<PromptResponse>? {
                val result = GeckoResult<PromptResponse>()
                filePromptResult = result
                filePrompt = prompt
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = prompt.mimeTypes?.firstOrNull()
                        ?.takeIf { it.isNotBlank() } ?: "*/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
                @Suppress("DEPRECATION")
                startActivityForResult(
                    Intent.createChooser(intent, "Choose file"),
                    REQ_FILE
                )
                return result
            }
        }

        session.progressDelegate = object : ProgressDelegate {
            override fun onPageStop(session: GeckoSession, success: Boolean) {
                if (success) {
                    // v0.4.9: no native JS injection here — GeckoSession
                    // exposes no evaluateJavascript (verified against
                    // the 158 AAR: loadUri only). The viewport-cover
                    // fix rides on the fresh extension id's
                    // document_start content script instead.
                } else if (inServerView) {
                    Toast.makeText(
                        this@MainActivity,
                        "Failed to load — check the server is running.",
                        Toast.LENGTH_LONG
                    ).show()
                    showHub(showForm = false)
                }
            }
        }

        // v0.5.1 Hub wiring. Empty home = the add form itself; once
        // a server is saved the home is the card grid with + top-right.
        addButton.setOnClickListener { showHub(showForm = true) }
        backButton.setOnClickListener {
            // Back discards the draft: returning to the form
            // later must show clean fields, never stale typing.
            clearForm()
            showHub(showForm = false)
        }
        saveButton.setOnClickListener { saveFromForm(prefs) }
        connectButton.setOnClickListener { connectCheck() }
        enterButton.setOnClickListener { enterFromForm() }
        showHub(showForm = servers.isEmpty())

    }

    // ---- v0.5.1 Hub ----

    private fun loadServers(prefs: android.content.SharedPreferences) {
        servers.clear()
        val raw = prefs.getString(KEY_SERVERS, null)
        if (!raw.isNullOrBlank()) {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                servers.add(
                    HubServer(
                        o.optString("name"),
                        o.optString("url"),
                        o.optString("user"),
                        o.optString("pass")
                    )
                )
            }
        }
        // Legacy migration, RUN ONCE (v0.5.1B fix): the single
        // pre-0.5.1 saved address becomes one card (empty name ->
        // the address is its label). The old code re-ran this
        // whenever the list was empty, so a deleted card rose
        // from the dead on the next launch. The flag below
        // closes that resurrection for good.
        if (!prefs.getBoolean(KEY_MIGRATED, false)) {
            if (servers.isEmpty()) {
                val legacy = prefs.getString(KEY_URL, null)
                if (!legacy.isNullOrBlank()) {
                    servers.add(HubServer("", legacy, "", ""))
                }
            }
            prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
            persistServers(prefs)
        }
    }

    private fun persistServers(prefs: android.content.SharedPreferences) {
        val arr = JSONArray()
        for (s in servers) {
            val o = JSONObject()
            o.put("name", s.name)
            o.put("url", s.url)
            o.put("user", s.user)
            o.put("pass", s.pass)
            arr.put(o)
        }
        prefs.edit().putString(KEY_SERVERS, arr.toString()).apply()
    }

    private fun displayName(server: HubServer): String {
        return server.name.takeIf { it.isNotBlank() } ?: server.url
    }

    private fun iconFor(server: HubServer): String {
        val hay = (server.name + " " + server.url).lowercase()
        return when {
            // Neconyan is a CAT (neko/nyan), not a fox —
            // its own splash screen shows a pixel cat.
            "neconyan" in hay || "nyan" in hay -> "🐱"
            "bunny" in hay -> "🐰"
            "tavern" in hay -> "💬"
            "opencode" in hay -> "</>"
            // Nameless card (migrated legacy address): a globe,
            // not the URL's first letter — "H" from "http://"
            // meant nothing.
            server.name.isBlank() -> "🌐"
            else -> server.name.firstOrNull()?.uppercase() ?: "?"
        }
    }

    // v0.5.1B: a fresh form is always clean. Stale typing
    // surviving a Back, a save, or a delete was the "it saved
    // by itself" confusion: the fields kept the old values.
    private fun clearForm() {
        nameInput.setText("")
        serverInput.setText("")
        userInput.setText("")
        passInput.setText("")
        formMessage.text = ""
        editingIndex = -1
    }

    private fun showFormForEdit(server: HubServer, index: Int) {
        showHub(showForm = true)
        nameInput.setText(server.name)
        serverInput.setText(server.url)
        userInput.setText(server.user)
        passInput.setText(server.pass)
        formMessage.text = ""
        editingIndex = index
    }

    // v0.5.2 coach hint: two HIDDEN conditions, both in
    // prefs (so only wiping app data brings the hint back).
    // The user never sees counters or explanations — the
    // quiet disappearance after both gestures is the
    // teaching. Long-press sets the first, entering a
    // server sets the second; the hint shows only while
    // at least one is still unmet.
    private fun hintDone(prefs: android.content.SharedPreferences): Boolean {
        return prefs.getBoolean(KEY_HINT_LONG, false) &&
            prefs.getBoolean(KEY_HINT_ENTER, false)
    }

    private fun refreshHint() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        hubHint.visibility =
            if (!formShown && servers.isNotEmpty() && !hintDone(prefs)) {
                View.VISIBLE
            } else {
                View.GONE
            }
    }

    private fun showHub(showForm: Boolean) {
        formShown = showForm
        inServerView = false
        geckoView.visibility = View.GONE
        hubView.visibility = View.VISIBLE
        refreshHint()
        if (showForm) {
            // New-server form only: an edit fills the fields
            // right after this via showFormForEdit.
            clearForm()
            hubGridScroll.visibility = View.GONE
            hubFormScroll.visibility = View.VISIBLE
            addButton.visibility = View.GONE
            // Back only exists when there is a grid to go back to;
            // an empty home IS the form, so there is nowhere back.
            backButton.visibility =
                if (servers.isNotEmpty()) View.VISIBLE else View.GONE
        } else {
            hubFormScroll.visibility = View.GONE
            hubGridScroll.visibility = View.VISIBLE
            addButton.visibility = View.VISIBLE
            refreshHub()
        }
    }

    private fun refreshHub() {
        hubGridList.removeAllViews()
        var row: LinearLayout? = null
        for ((index, server) in servers.withIndex()) {
            if (index % 2 == 0) {
                row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
                hubGridList.addView(row)
            }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 32, 32, 32)
                val bg = android.graphics.drawable.GradientDrawable()
                bg.setColor(android.graphics.Color.parseColor("#222222"))
                bg.setStroke(2, android.graphics.Color.parseColor("#FF8C42"))
                bg.cornerRadius = 32f
                background = bg
                val lp = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
                lp.setMargins(16, 16, 16, 16)
                layoutParams = lp
                gravity = android.view.Gravity.CENTER_HORIZONTAL
            }
            val icon = TextView(this).apply {
                text = iconFor(server)
                textSize = 40f
                setTextColor(android.graphics.Color.parseColor("#FF8C42"))
                gravity = android.view.Gravity.CENTER
            }
            val name = TextView(this).apply {
                text = displayName(server)
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(android.graphics.Color.WHITE)
                gravity = android.view.Gravity.CENTER
            }
            val addr = TextView(this).apply {
                text = server.url
                textSize = 12f
                setTextColor(android.graphics.Color.parseColor("#AAAAAA"))
                gravity = android.view.Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            card.addView(icon)
            card.addView(name)
            // v0.5.1B: a nameless card showed the URL twice
            // (label line + address line, same text). One line
            // only when there is no name.
            if (server.name.isNotBlank()) {
                card.addView(addr)
            }
            card.setOnClickListener { openServer(server) }
            // v0.5.1B: long-press offers Edit AND Delete AND
            // Cancel — 0.5.1 only deleted, so a typo meant
            // delete + retype all. Cancel sits beside them
            // (Aqil's order 2026-10-26 16:52).
            card.setOnLongClickListener {
                val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                // v0.5.2: the press itself fulfills the hint's
                // FIRST hidden condition — even if the dialog
                // ends in Cancel. Re-evaluate the hint at once:
                // if entering already happened, it vanishes now.
                prefs.edit().putBoolean(KEY_HINT_LONG, true).apply()
                refreshHint()
                AlertDialog.Builder(this)
                    .setTitle(displayName(server))
                    .setItems(arrayOf("Edit", "Delete")) { _, which ->
                        if (which == 0) {
                            showFormForEdit(server, servers.indexOf(server))
                        } else {
                            AlertDialog.Builder(this)
                                .setTitle("Delete server?")
                                .setMessage(displayName(server))
                                .setPositiveButton("Delete") { dlg, _ ->
                                    val removedUrl = server.url
                                    servers.remove(server)
                                    if (prefs.getString(KEY_URL, null) == removedUrl) {
                                        prefs.edit().remove(KEY_URL).apply()
                                    }
                                    persistServers(prefs)
                                    dlg.dismiss()
                                    // Empty home IS the form, not
                                    // a blank grid.
                                    if (servers.isEmpty()) {
                                        showHub(showForm = true)
                                    } else {
                                        refreshHub()
                                    }
                                }
                                .setNegativeButton("Cancel") { dlg, _ ->
                                    dlg.dismiss()
                                }
                                .show()
                        }
                    }
                    .setNegativeButton("Cancel") { dlg, _ ->
                        dlg.dismiss()
                    }
                    .show()
                true
            }
            row?.addView(card)
        }
        // Odd card out: pad the last row so cards keep half width.
        if (servers.size % 2 == 1) {
            row?.addView(
                View(this),
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            )
        }
        refreshHint()
    }

    private fun openServer(server: HubServer) {
        val url = withUserInfo(normalize(server.url), server.user, server.pass)
        // v0.5.2: entering a server fulfills the hint's
        // SECOND hidden condition — from a card tap and
        // from Enter alike, since both demonstrate entering.
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_URL, normalize(server.url))
            .putBoolean(KEY_HINT_ENTER, true)
            .apply()
        inServerView = true
        hubView.visibility = View.GONE
        geckoView.visibility = View.VISIBLE
        session.loadUri(url)
    }

    private fun withUserInfo(url: String, user: String, pass: String): String {
        if (user.isBlank()) return url
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return url
        val scheme = url.substring(0, schemeEnd + 3)
        val rest = url.substring(schemeEnd + 3)
        val info = if (pass.isBlank()) user else "$user:$pass"
        return "$scheme$info@$rest"
    }

    private fun readForm(): HubServer {
        return HubServer(
            nameInput.text.toString().trim(),
            normalize(serverInput.text.toString().trim()),
            userInput.text.toString().trim(),
            passInput.text.toString().trim()
        )
    }

    private fun setFormMessage(text: String, ok: Boolean) {
        formMessage.text = text
        formMessage.setTextColor(
            if (ok) android.graphics.Color.parseColor("#4CAF50")
            else android.graphics.Color.parseColor("#F44336")
        )
    }

    // Save (v0.5.1B: labeled Save, not Connect — the button's
    // job IS saving, and calling it anything else hid it).
    // Deliberately NOT gated on reachability (Aqil's order): a
    // failed connection must never cost the typed details —
    // save first, test separately. Saving never navigates:
    // it reports Saved and stays on the grid.
    private fun saveFromForm(prefs: android.content.SharedPreferences) {
        if (serverInput.text.toString().isBlank()) {
            setFormMessage("Server address is required.", false)
            return
        }
        val server = readForm()
        if (editingIndex in servers.indices) {
            // Editing: replace in place, even if the URL changed.
            servers[editingIndex] = server
        } else {
            val existing = servers.indexOfFirst { it.url == server.url }
            if (existing >= 0) servers[existing] = server else servers.add(server)
        }
        persistServers(prefs)
        clearForm()
        Toast.makeText(this, "Saved.", Toast.LENGTH_SHORT).show()
        showHub(showForm = false)
    }

    // Connect = reachability ONLY (v0.5.1B amendment: Connect
    // REPLACES Test — Aqil's order). It never opens the
    // server and never saves: it probes, and shows GREEN only
    // for a real connection (any HTTP response, even 404/401,
    // means the server is alive; an exception/timeout means
    // it is not). It is NOT a Termux/terminal command: it
    // asks the server itself over HTTP — no shell runs
    // anywhere. The result carries the HTTP status and is
    // also toasted, so it cannot hide below the fold.
    private fun connectCheck() {
        if (serverInput.text.toString().isBlank()) {
            setFormMessage("Server address is required.", false)
            return
        }
        val url = readForm().url
        setFormMessage("Connecting…", true)
        Thread {
            var code: Int? = null
            var failure: String? = null
            try {
                val conn = java.net.URL(url).openConnection()
                    as java.net.HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.requestMethod = "GET"
                conn.instanceFollowRedirects = false
                conn.connect()
                code = conn.responseCode
            } catch (e: Exception) {
                code = null
                // v0.5.1C: NEVER swallow the cause again —
                // the generic "no response" hid the real
                // defect (cleartext policy) for two versions.
                failure = e.message ?: e.javaClass.simpleName
            }
            val resultText = if (code != null) {
                "Connection successful — HTTP $code."
            } else if (failure != null) {
                "Connection failed — $failure"
            } else {
                "Connection failed — no response."
            }
            runOnUiThread {
                setFormMessage(resultText, code != null)
                Toast.makeText(this, resultText, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    // Enter = OPEN ONLY: the filled-in server loads without
    // being saved. Saving is Save's job, not Enter's.
    private fun enterFromForm() {
        if (serverInput.text.toString().isBlank()) {
            setFormMessage("Server address is required.", false)
            return
        }
        openServer(readForm())
    }

    private fun normalize(raw: String): String {
        var url = raw
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://$url"
        }
        return url
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_FILE) return
        val result = filePromptResult
        val prompt = filePrompt
        filePromptResult = null
        filePrompt = null
        if (result == null || prompt == null) return
        if (resultCode == RESULT_OK && data != null) {
            val uris = mutableListOf<Uri>()
            val clip = data.clipData
            if (clip != null) {
                for (i in 0 until clip.itemCount) {
                    uris.add(clip.getItemAt(i).uri)
                }
            } else if (data.data != null) {
                uris.add(data.data!!)
            }
            if (uris.isNotEmpty()) {
                result.complete(prompt.confirm(applicationContext, uris.toTypedArray()))
                return
            }
        }
        result.complete(prompt.dismiss())
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (inServerView) {
            if (sessionCanGoBack) {
                session.goBack()
            } else {
                showHub(showForm = false)
            }
        } else if (formShown && servers.isNotEmpty()) {
            showHub(showForm = false)
        } else {
            super.onBackPressed()
        }
    }

    companion object {
        private const val PREFS = "lotus-shell-prefs"
        private const val KEY_URL = "server_url"
        private const val KEY_SERVERS = "hub_servers_v1"
        private const val KEY_MIGRATED = "hub_migrated_v1"
        private const val KEY_HINT_LONG = "hub_hint_longpress_v1"
        private const val KEY_HINT_ENTER = "hub_hint_enter_v1"
        private const val REQ_FILE = 1001
    }
}
