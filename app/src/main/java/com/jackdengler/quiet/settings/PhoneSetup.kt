package com.jackdengler.quiet.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.jackdengler.quiet.data.Prefs
import com.jackdengler.quiet.ui.LocalAccent
import com.jackdengler.quiet.ui.QrCode
import com.jackdengler.quiet.ui.QuietFonts
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * A tiny web page served by the TV on the home network while the "Set up from your phone"
 * screen is open. The phone scans a QR code, fills in a form, and the TV saves it. Nothing goes
 * through the internet; the page needs a one-time token from the QR code.
 */
class SetupServer(private val prefs: Prefs, private val onSaved: (String) -> Unit) {
    val token: String = (1..10).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
    private var socket: ServerSocket? = null
    val port: Int get() = socket?.localPort ?: 0

    fun start() {
        val s = runCatching { ServerSocket(PREFERRED_PORT) }.getOrElse { ServerSocket(0) }
        socket = s
        thread(name = "quiet-setup", isDaemon = true) {
            while (!s.isClosed) {
                val client = runCatching { s.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { client.use { handle(it) } } }
            }
        }
    }

    fun stop() {
        runCatching { socket?.close() }
    }

    private fun handle(c: Socket) {
        c.soTimeout = 15_000
        val input = c.getInputStream()
        val requestLine = readLine(input) ?: return
        var length = 0
        while (true) {
            val h = readLine(input) ?: break
            if (h.isEmpty()) break
            if (h.startsWith("Content-Length:", ignoreCase = true)) length = h.substringAfter(':').trim().toIntOrNull() ?: 0
        }
        val (method, target) = requestLine.split(" ").let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "/") }
        val query = parseForm(target.substringAfter('?', ""))
        val html = if (query["k"] != token) {
            page("This link has expired", "<p>Open <b>Set up from your phone</b> on the TV again and scan the new code.</p>")
        } else if (method == "POST") {
            val body = ByteArray(length.coerceAtMost(16_384))
            var read = 0
            while (read < body.size) {
                val n = input.read(body, read, body.size - read)
                if (n < 0) break
                read += n
            }
            val summary = save(parseForm(String(body, 0, read)))
            onSaved(summary)
            page("Saved", "<p>$summary</p><p>Look at your TV. You can close this page.</p>")
        } else {
            page("Quiet Scores setup", form())
        }
        val bytes = html.toByteArray()
        c.getOutputStream().apply {
            write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(bytes)
            flush()
        }
    }

    /** Applies what the phone sent; a league field may be a full ESPN link or just the number. */
    private fun save(f: Map<String, String>): String {
        val done = ArrayList<String>()
        for (n in 1..2) {
            val link = f["l$n"].orEmpty().trim()
            val team = f["t$n"].orEmpty().trim()
            val league = LEAGUE.find(link)?.groupValues?.get(1) ?: link.takeIf { it.all(Char::isDigit) }.orEmpty()
            val teamId = TEAM.find(link)?.groupValues?.get(1) ?: team.takeIf { it.all(Char::isDigit) }.orEmpty()
            if (league.isNotEmpty()) {
                if (n == 1) prefs.espnLeague = league else prefs.espnLeague2 = league
                done += "League $n"
            }
            if (teamId.isNotEmpty()) {
                if (n == 1) prefs.espnTeam = teamId else prefs.espnTeam2 = teamId
            }
        }
        f["s2"]?.trim()?.takeIf { it.isNotEmpty() }?.let { prefs.espnS2 = it; done += "espn_s2" }
        f["swid"]?.trim()?.takeIf { it.isNotEmpty() }?.let { prefs.espnSwid = it; done += "SWID" }
        return if (done.isEmpty()) "Nothing to save." else "Saved ${done.joinToString(", ")}."
    }

    private fun form(): String {
        fun field(name: String, label: String, value: String, hint: String) = """
            <label>$label<input name="$name" value="${esc(value)}" placeholder="${esc(hint)}" autocapitalize="off" autocorrect="off"></label>
        """
        return """
        <form method="post" action="/save?k=$token">
          <h2>League 1</h2>
          ${field("l1", "League link or ID", prefs.espnLeague, "Paste your team page link from the ESPN site")}
          ${field("t1", "Team ID", prefs.espnTeam, "Filled in from the link if it has teamId=")}
          <h2>League 2 (optional)</h2>
          ${field("l2", "League link or ID", prefs.espnLeague2, "Paste your team page link")}
          ${field("t2", "Team ID", prefs.espnTeam2, "")}
          <h2>Private league (optional)</h2>
          <p class="note">Easier: choose <b>Sign in to ESPN</b> on the TV. Fill these only if you already have the cookies.</p>
          ${field("s2", "espn_s2", "", if (prefs.espnS2.isBlank()) "" else "Already saved")}
          ${field("swid", "SWID", "", if (prefs.espnSwid.isBlank()) "" else "Already saved")}
          <button>Save to TV</button>
        </form>
        """
    }

    private fun page(title: String, content: String) = """
        <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>$title</title><style>
          body{font-family:-apple-system,system-ui,sans-serif;background:#0f1319;color:#f2efe9;margin:0;padding:22px;max-width:560px}
          h1{font-weight:300;font-size:30px;margin:4px 0 18px} h2{font-size:15px;letter-spacing:.08em;text-transform:uppercase;color:#f2c45c;margin:26px 0 8px}
          label{display:block;font-size:14px;color:#c9c5be;margin:10px 0}
          input{display:block;width:100%;box-sizing:border-box;margin-top:6px;padding:13px;font-size:16px;border-radius:12px;border:1px solid #39404d;background:#1b212b;color:#fff}
          button{margin-top:24px;width:100%;padding:15px;font-size:17px;font-weight:600;border:0;border-radius:14px;background:#f2c45c;color:#20180a}
          .note{font-size:13px;color:#9aa3b2}
        </style></head><body><h1>$title</h1>$content</body></html>
    """.trimIndent()

    companion object {
        private const val PREFERRED_PORT = 8765
        private const val ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"
        private val random = SecureRandom()
        private val LEAGUE = Regex("""leagueId=(\d+)""")
        private val TEAM = Regex("""teamId=(\d+)""")

        private fun esc(s: String) = s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")

        private fun parseForm(s: String): Map<String, String> = s.split('&').filter { '=' in it }.associate {
            val (k, v) = it.split('=', limit = 2)
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }

        private fun readLine(input: InputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) return if (sb.isEmpty()) null else sb.toString()
                if (b == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(b.toChar())
                if (sb.length > 8_192) return sb.toString()
            }
        }

        /** The TV's address on the home network. */
        fun localAddress(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress }?.hostAddress
        }.getOrNull()
    }
}

/** Shows the QR code for the phone setup page and confirms when the phone saves. */
@Composable
fun PhoneSetupScreen(prefs: Prefs, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    var status by remember { mutableStateOf("Waiting for your phone…") }
    // Started here (not in an effect) so its port is known when the QR code is drawn.
    val server = remember { SetupServer(prefs) { summary -> status = "$summary You can press Back." }.also { it.start() } }
    DisposableEffect(server) {
        onDispose { server.stop() }
    }
    val address = remember { SetupServer.localAddress() }
    val url = address?.let { "http://$it:${server.port}/?k=${server.token}" }
    val accent by LocalAccent.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focus.requestFocus() }
    }
    val body = TextStyle(fontFamily = QuietFonts.Manrope, fontSize = 22.sp, lineHeight = 32.sp, color = Color.White.copy(alpha = 0.88f))
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)).focusRequester(focus).focusable(),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (url != null) QrCode(url, 320)
            Spacer(Modifier.width(60.dp))
            Column(Modifier.width(760.dp)) {
                Text(
                    "Set up from your phone",
                    style = TextStyle(fontFamily = QuietFonts.InstrumentSerif, fontStyle = FontStyle.Italic, fontSize = 50.sp, color = Color.White),
                )
                Spacer(Modifier.height(16.dp))
                if (url == null) {
                    Text("The TV isn't on a network right now. Connect it to Wi-Fi and try again.", style = body)
                } else {
                    Text("1. Make sure your phone is on the same Wi-Fi as the TV.", style = body)
                    Text("2. Point the phone's camera at the code and tap the link.", style = body)
                    Text("3. Paste your ESPN team page link and tap Save to TV.", style = body)
                    Spacer(Modifier.height(14.dp))
                    Text("Or type: $url", style = body.copy(fontSize = 18.sp, color = Color.White.copy(alpha = 0.6f)))
                    Spacer(Modifier.height(22.dp))
                    Text(status, style = body.copy(color = accent))
                }
            }
        }
    }
}
