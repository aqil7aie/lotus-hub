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
import org.mozilla.geckoview.WebRequestError

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

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
        // delegate they silently do nothing when tapped.
        session.promptDelegate = object : PromptDelegate {
            override fun onChoicePrompt(
                session: GeckoSession,
                prompt: ChoicePrompt
            ): GeckoResult<PromptResponse>? {
                val result = GeckoResult<PromptResponse>()
                // GeckoView 158's Choice has no children/optgroup member,
                // so the choices are used as delivered. ST's settings
                // selects are flat lists, which is all this needs.
                val choices = prompt.choices
                val labels = choices.map { it.label }.toTypedArray()
                val checked = choices.indexOfFirst { it.selected }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(prompt.title)
                    .setSingleChoiceItems(labels, checked) { dialog, which ->
                        result.complete(prompt.confirm(arrayOf(choices[which])))
                        dialog.dismiss()
                    }
                    .setNegativeButton("إلغاء") { dialog, _ ->
                        result.complete(prompt.dismiss())
                        dialog.dismiss()
                    }
                    .setOnCancelListener {
                        result.complete(prompt.dismiss())
                    }
                    .show()
                return result
            }
        }

        session.progressDelegate = object : ProgressDelegate {
            override fun onPageStop(session: GeckoSession, success: Boolean) {
                if (success) {
                    hideAddressRow()
                    statusView.visibility = View.GONE
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
    }
}
