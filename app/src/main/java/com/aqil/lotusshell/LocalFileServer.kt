package com.aqil.lotusshell

import java.io.File
import java.io.FileInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

// v0.5.4B: a tiny embedded static file server for local HTML
// games. Why it exists: the v0.5.4 file:// route hit two
// Gecko walls that no app code can move.
//   (1) Link clicks fail: a file:// directory listing loaded
//       without a trailing slash resolves its relative file
//       links to the PARENT directory (Bugzilla 264618), so
//       tapping a game navigates to a path that does not exist
//       while typing the full URL directly works. The app had
//       no onLoadRequest override to normalise it.
//   (2) Zoom is dead on file:// pages: Firefox 153+ requires
//       an explicit user-granted file-scheme permission for an
//       extension to run content scripts on file:// URLs, off
//       by default, desktop-only toggle (Firefox 153 release
//       notes, bug 2034168; Firefox blocks file:/// access,
//       CVE-2020-6809). GeckoView 158 exposes no API to grant
//       it (WebExtensionController has setAllowedInPrivate-
//       Browsing and addOptionalPermissions only — no file
//       access method). So the v0.5.4 zoom-file extension can
//       never inject into a game page, whatever its match.
// The fix is to stop loading games as file:// at all: the app
// serves its own files dir over http://127.0.0.1 and loads
// games through it. Then a game page is an http:// URL, the
// PROVEN http(s) zoom extension applies (+/- works), and link
// clicks are ordinary http navigations (the server's own
// listing always ends directory URLs with '/', so relative
// links resolve correctly). The user still types file://
// URLs in the URL bar — MainActivity translates them to the
// local http:// URL transparently at load time.
// Scope: the app's OWN external files dir only (the games
// Aqil puts there himself). No permission needed for it.
// Bound to 127.0.0.1 only, never 0.0.0.0. Path traversal is
// blocked by canonicalising and checking the root prefix.
internal class LocalFileServer(private val root: File) {

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r).apply { isDaemon = true }
    }

    val isRunning: Boolean
        get() = serverSocket?.isClosed == false

    val baseUrl: String?
        get() {
            val s = serverSocket ?: return null
            if (s.isClosed) return null
            return "http://127.0.0.1:${s.localPort}"
        }

    fun start(): Boolean {
        if (isRunning) return true
        return try {
            val socket = ServerSocket(
                0, 50, InetAddress.getByName("127.0.0.1")
            )
            serverSocket = socket
            val t = Thread({
                while (!socket.isClosed) {
                    try {
                        val client = socket.accept()
                        pool.execute { handle(client) }
                    } catch (e: Exception) {
                        // Socket closed by stop(): the loop ends.
                        if (!socket.isClosed) {
                            // Transient accept failure: keep going.
                        }
                    }
                }
            }, "lotus-local-file-server").apply { isDaemon = true }
            t.start()
            acceptThread = t
            true
        } catch (e: Exception) {
            false
        }
    }

    fun stop() {
        try {
            serverSocket?.close()
        } catch (e: Exception) {
        }
        serverSocket = null
        acceptThread = null
        pool.shutdownNow()
    }

    // Translate a file:// URL (or a bare absolute path) that
    // points inside the served root to the local http:// URL.
    // Returns null when the server is not running, the URL is
    // not a file URL/path, or the path escapes the root.
    fun toHttpUrl(url: String): String? {
        val base = baseUrl ?: return null
        val rawPath: String = when {
            url.startsWith("file://") -> {
                // file:///storage/... or file://localhost/...
                var p = url.removePrefix("file://")
                if (p.startsWith("localhost/")) {
                    p = p.removePrefix("localhost")
                }
                p
            }
            url.startsWith("/") -> url
            else -> return null
        }
        val decoded = try {
            URLDecoder.decode(rawPath, "UTF-8")
        } catch (e: Exception) {
            rawPath
        }
        // The file:// URL carries the ABSOLUTE path; the
        // served root is a prefix of it. Strip the root to
        // get the path relative to the served dir — do NOT
        // append the absolute path to the root (that would
        // build a nested path that does not exist).
        val file = File(decoded).canonicalFile
        if (!isUnderRoot(file)) return null
        val rel = file.relativeTo(root.canonicalFile).path
            .replace(File.separatorChar, '/')
        val encoded = rel.split('/').filter { it.isNotEmpty() }
            .joinToString("/") { seg ->
                URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
            }
        val suffix = if (file.isDirectory) "/" else ""
        return if (encoded.isEmpty()) "$base/" else "$base/$encoded$suffix"
    }

    private fun isUnderRoot(file: File): Boolean {
        val rootPath = root.canonicalPath
        val path = file.canonicalPath
        return path == rootPath || path.startsWith(rootPath + File.separator)
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 10_000
            val reader = client.getInputStream().bufferedReader(Charsets.UTF_8)
            val out = client.getOutputStream()
            val requestLine = reader.readLine() ?: return
            // Consume headers until the blank line.
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
            val parts = requestLine.split(" ")
            if (parts.size < 2 || parts[0] != "GET") {
                sendText(out, 405, "Method Not Allowed", "Only GET is supported.")
                return
            }
            val rawTarget = parts[1].substringBefore("?")
            val decoded = try {
                URLDecoder.decode(rawTarget, "UTF-8")
            } catch (e: Exception) {
                rawTarget
            }
            val file = File(root, decoded.removePrefix("/")).canonicalFile
            if (!isUnderRoot(file) || !file.exists()) {
                sendText(out, 404, "Not Found", "No such file: ${htmlEscape(decoded)}")
                return
            }
            if (file.isDirectory) {
                // Directory URLs must end with '/' or relative
                // links resolve to the parent (Bugzilla 264618)
                // — the same trap that broke file:// taps.
                if (!rawTarget.endsWith("/")) {
                    val loc = rawTarget + "/"
                    val body = "Redirecting to $loc"
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    val head = "HTTP/1.1 301 Moved Permanently\r\n" +
                        "Location: $loc\r\n" +
                        "Content-Type: text/plain; charset=utf-8\r\n" +
                        "Content-Length: ${bytes.size}\r\n" +
                        "Connection: close\r\n\r\n"
                    out.write(head.toByteArray(Charsets.UTF_8))
                    out.write(bytes)
                    out.flush()
                    return
                }
                sendListing(out, rawTarget, file)
            } else {
                sendFile(out, file)
            }
        } catch (e: Exception) {
            // Client went away mid-request: nothing to do.
        } finally {
            try {
                client.close()
            } catch (e: Exception) {
            }
        }
    }

    private fun sendText(
        out: java.io.OutputStream,
        code: Int,
        status: String,
        text: String
    ) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code $status\r\n" +
            "Content-Type: text/plain; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun sendFile(out: java.io.OutputStream, file: File) {
        val mime = mimeFor(file.name)
        val head = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: $mime\r\n" +
            "Content-Length: ${file.length()}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.UTF_8))
        FileInputStream(file).use { input ->
            val buf = ByteArray(32 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
        }
        out.flush()
    }

    private fun sendListing(
        out: java.io.OutputStream,
        urlPath: String,
        dir: File
    ) {
        val dateFmt = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US)
        val sb = StringBuilder()
        sb.append("<!doctype html><html><head><meta charset=\"utf-8\">")
        sb.append("<title>Index of ")
        sb.append(htmlEscape(urlPath))
        sb.append("</title></head><body>")
        sb.append("<h1>Index of ")
        sb.append(htmlEscape(urlPath))
        sb.append("</h1>")
        if (urlPath != "/") {
            sb.append("<a href=\"../\">Up to higher level directory</a><br><br>")
        }
        sb.append("<table><tr><th>Name</th><th>Last Modified</th><th>Size</th></tr>")
        val children = dir.listFiles()?.sortedWith(
            compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase(Locale.US) }
        ) ?: emptyList()
        for (child in children) {
            val name = child.name
            val encoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
            val href = if (child.isDirectory) "$encoded/" else encoded
            val size = if (child.isDirectory) "-" else formatSize(child.length())
            sb.append("<tr><td><a href=\"")
            sb.append(href)
            sb.append("\">")
            sb.append(htmlEscape(name))
            sb.append(if (child.isDirectory) "/" else "")
            sb.append("</a></td><td>")
            sb.append(dateFmt.format(Date(child.lastModified())))
            sb.append("</td><td>")
            sb.append(size)
            sb.append("</td></tr>")
        }
        sb.append("</table></body></html>")
        val bytes = sb.toString().toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        }
    }

    private fun htmlEscape(s: String): String {
        return s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }

    private fun mimeFor(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
        return when (ext) {
            "html", "htm" -> "text/html; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "js", "mjs" -> "text/javascript; charset=utf-8"
            "json" -> "application/json; charset=utf-8"
            "txt" -> "text/plain; charset=utf-8"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            "webp" -> "image/webp"
            "ico" -> "image/x-icon"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "wasm" -> "application/wasm"
            "pdf" -> "application/pdf"
            else -> "application/octet-stream"
        }
    }
}
