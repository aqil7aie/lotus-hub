package com.aqil.lotusshell

import android.app.Activity
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
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.NavigationDelegate
import org.mozilla.geckoview.GeckoSession.ProgressDelegate
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebRequestError

/**
 * Lotus Shell v0 skeleton: a GeckoView shell that loads one self-hosted
 * SillyTavern / Neconyan address and remembers it.
 *
 * Deliberately small: no third-party dependencies besides GeckoView itself.
 * v0.2: address row hides on load (full-page view, the way Tavernium
 * behaves); back with no page history brings it back; load errors show
 * an Arabic message instead of silent white.
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

        val runtime = GeckoRuntime.create(this)
        val settings = GeckoSessionSettings.Builder().allowJavascript(true).build()
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

        session.progressDelegate = object : ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                hideAddressRow()
                statusView.visibility = View.GONE
            }
        }

        val saved = prefs.getString(KEY_URL, null)
        if (!saved.isNullOrBlank()) {
            urlInput.setText(saved)
            session.loadUri(normalize(saved))
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
