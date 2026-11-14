package com.musicsync.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.media.MediaPlayer
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

class MainActivity : Activity() {

    private val port = 50010
    private val red = "#C62828"
    private val green = "#2E7D32"

    @Volatile private var running = true
    @Volatile private var player: MediaPlayer? = null
    @Volatile private var hostFile: File? = null
    @Volatile private var recvFile: File? = null
    private var serverSocket: ServerSocket? = null
    private var adjMs = 0

    private lateinit var ipInput: EditText
    private lateinit var songLabel: TextView
    private lateinit var status: TextView
    private lateinit var adjLabel: TextView
    private lateinit var progressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        startServer()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(t: String, size: Float, color: String, bold: Boolean = false) =
        TextView(this).apply {
            text = t
            textSize = size
            setTextColor(Color.parseColor(color))
            gravity = Gravity.CENTER
            if (bold) setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(6), 0, dp(6))
        }

    private fun button(t: String, color: String, onClick: () -> Unit) =
        Button(this).apply {
            text = t
            textSize = 16f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor(color))
            setOnClickListener { onClick() }
        }

    private fun addFull(parent: LinearLayout, v: View, top: Int = 10) {
        parent.addView(v, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(top) })
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(40), dp(20), dp(24))
            setBackgroundColor(Color.parseColor("#FFF8E1"))
        }

        root.addView(text("Music Sync", 28f, "#4A148C", true))
        root.addView(text(
            "രണ്ട് ഫോണിലും ആപ്പ് തുറന്ന് ഒരേ WiFi-യിൽ ആയിരിക്കണം.\n" +
            "പാട്ട് ഇടുന്ന ഫോണിൽ മറ്റേ ഫോണിന്റെ IP കൊടുക്കുക.", 14f, "#555555"))
        root.addView(text("ഈ ഫോണിന്റെ IP: ${localIp()}", 16f, "#4A148C", true))

        ipInput = EditText(this).apply {
            hint = "മറ്റേ ഫോണിന്റെ IP (ഉദാ: 192.168.1.8)"
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            keyListener = android.text.method.DigitsKeyListener.getInstance("0123456789.")
        }
        addFull(root, ipInput)

        addFull(root, button("1. പാട്ട് തിരഞ്ഞെടുക്കുക", "#6A1B4D") { pickSong() }, 16)
        songLabel = text("പാട്ട് തിരഞ്ഞെടുത്തിട്ടില്ല", 14f, "#333333")
        root.addView(songLabel)

        addFull(root, button("2. മറ്റേ ഫോണിലേക്ക് അയക്കുക", "#1565C0") { sendSong() })
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        addFull(root, progressBar, 8)

        addFull(root, button("▶  3. രണ്ടിലും ഒരേ സമയം പ്ലേ ചെയ്യുക", green) { playBoth() }, 12)
        addFull(root, button("⏹  നിർത്തുക", red) { stopBoth() })

        status = text("തയ്യാർ. മറ്റേ ഫോണിൽ നിന്ന് പാട്ട് സ്വീകരിക്കാൻ ആപ്പ് തുറന്നിരുന്നാൽ മതി.", 16f, green)
        addFull(root, status, 16)

        // sync fine-tuning
        root.addView(text("ശബ്ദം ഒരേ സമയത്തല്ലെങ്കിൽ ക്രമീകരിക്കുക:", 13f, "#555555"))
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER }
        row.addView(button("−", "#757575") { adjMs = (adjMs - 25).coerceAtLeast(-500); updateAdj() },
            LinearLayout.LayoutParams(dp(64), LinearLayout.LayoutParams.WRAP_CONTENT))
        adjLabel = text("", 15f, "#333333")
        row.addView(adjLabel, LinearLayout.LayoutParams(dp(160), LinearLayout.LayoutParams.WRAP_CONTENT))
        row.addView(button("+", "#757575") { adjMs = (adjMs + 25).coerceAtMost(500); updateAdj() },
            LinearLayout.LayoutParams(dp(64), LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(row)
        root.addView(text("(ഈ ഫോണിലെ ശബ്ദം മുമ്പിലാണെങ്കിൽ + അമർത്തുക, പിന്നിലാണെങ്കിൽ −)", 12f, "#777777"))
        updateAdj()

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun updateAdj() {
        adjLabel.text = (if (adjMs > 0) "+" else "") + "$adjMs ms"
    }

    @Suppress("DEPRECATION")
    private fun localIp(): String {
        return try {
            val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            val ip = wifi.connectionInfo.ipAddress
            if (ip == 0) "WiFi കണക്ട് ചെയ്തിട്ടില്ല" else
                "${ip and 0xff}.${ip shr 8 and 0xff}.${ip shr 16 and 0xff}.${ip shr 24 and 0xff}"
        } catch (e: Exception) { "അറിയില്ല" }
    }

    private fun setStatus(t: String, color: String = green) {
        runOnUiThread {
            status.text = t
            status.setTextColor(Color.parseColor(color))
        }
    }

    private fun showProgress(p: Int) = runOnUiThread { progressBar.progress = p }

    // ---------- Pick song ----------
    private fun pickSong() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
        }
        startActivityForResult(i, 1)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == 1 && resultCode == RESULT_OK && uri != null) {
            setStatus("പാട്ട് എടുക്കുന്നു...")
            Thread {
                try {
                    val f = File(cacheDir, "host_song")
                    contentResolver.openInputStream(uri)!!.use { i ->
                        f.outputStream().use { o -> i.copyTo(o) }
                    }
                    hostFile = f
                    val name = queryName(uri)
                    runOnUiThread { songLabel.text = "🎵 $name" }
                    setStatus("പാട്ട് തയ്യാർ. ഇനി 'അയക്കുക' അമർത്തുക.")
                } catch (e: Exception) {
                    setStatus("പിശക്: ${e.message}", red)
                }
            }.start()
        }
    }

    private fun queryName(uri: android.net.Uri): String {
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return c.getString(idx)
                }
            }
        } catch (_: Exception) {}
        return "പാട്ട്"
    }

    // ---------- Host actions ----------
    private fun targetIp(): String? {
        val ip = ipInput.text.toString().trim()
        if (ip.isEmpty()) {
            setStatus("മറ്റേ ഫോണിന്റെ IP ടൈപ്പ് ചെയ്യുക", red)
            return null
        }
        return ip
    }

    private fun sendSong() {
        val f = hostFile
        if (f == null) { setStatus("ആദ്യം പാട്ട് തിരഞ്ഞെടുക്കുക", red); return }
        val ip = targetIp() ?: return
        showProgress(0)
        setStatus("അയക്കുന്നു...", "#1565C0")
        Thread {
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(ip, port), 5000)
                    val out = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 65536))
                    val inp = DataInputStream(s.getInputStream())
                    val total = f.length()
                    out.writeUTF("FILE")
                    out.writeLong(total)
                    val buf = ByteArray(65536)
                    var sent = 0L
                    var lastPct = -1
                    f.inputStream().use { fi ->
                        while (true) {
                            val n = fi.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            sent += n
                            val pct = (sent * 100 / total).toInt()
                            if (pct != lastPct) { lastPct = pct; showProgress(pct) }
                        }
                    }
                    out.flush()
                    inp.readUTF()
                }
                setStatus("അയച്ചു ✓ ഇനി പ്ലേ അമർത്തുക")
            } catch (e: Exception) {
                setStatus("അയക്കാൻ പറ്റിയില്ല: ${e.message}\nIP ശരിയാണോ, മറ്റേ ഫോണിൽ ആപ്പ് തുറന്നിട്ടുണ്ടോ?", red)
            }
        }.start()
    }

    private fun playBoth() {
        val f = hostFile
        if (f == null) { setStatus("ആദ്യം പാട്ട് തിരഞ്ഞെടുക്കുക", red); return }
        val ip = targetIp() ?: return
        setStatus("തയ്യാറാക്കുന്നു...", "#1565C0")
        Thread {
            try {
                stopLocal()
                val mp = MediaPlayer().apply {
                    setDataSource(f.absolutePath)
                    prepare()
                    setOnCompletionListener { setStatus("പാട്ട് കഴിഞ്ഞു") }
                }
                Socket().use { s ->
                    s.tcpNoDelay = true
                    s.connect(InetSocketAddress(ip, port), 5000)
                    val out = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
                    val inp = DataInputStream(BufferedInputStream(s.getInputStream()))

                    out.writeUTF("PLAY")
                    out.flush()
                    if (inp.readUTF() != "READY") {
                        mp.release()
                        throw Exception("മറ്റേ ഫോണിൽ പാട്ട് ഇല്ല. ആദ്യം 'അയക്കുക' അമർത്തുക.")
                    }

                    // clock offset measurement (nanoseconds)
                    val n = 20
                    out.writeInt(n)
                    out.flush()
                    var bestRtt = Long.MAX_VALUE
                    var offset = 0L
                    repeat(n) {
                        val t0 = SystemClock.elapsedRealtimeNanos()
                        out.writeLong(t0)
                        out.flush()
                        val tr = inp.readLong()
                        val t1 = SystemClock.elapsedRealtimeNanos()
                        val rtt = t1 - t0
                        if (rtt < bestRtt) {
                            bestRtt = rtt
                            offset = tr - (t0 + t1) / 2
                        }
                    }

                    val hostTarget = SystemClock.elapsedRealtimeNanos() + 1_500_000_000L
                    out.writeLong(hostTarget + offset)
                    out.flush()

                    waitUntil(hostTarget + adjMs * 1_000_000L)
                    mp.start()
                    player = mp
                }
                setStatus("▶ രണ്ടിലും പ്ലേ ചെയ്യുന്നു")
            } catch (e: Exception) {
                setStatus("പിശക്: ${e.message}", red)
            }
        }.start()
    }

    private fun stopBoth() {
        stopLocal()
        setStatus("നിർത്തി")
        val ip = ipInput.text.toString().trim()
        if (ip.isEmpty()) return
        Thread {
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(ip, port), 3000)
                    val out = DataOutputStream(s.getOutputStream())
                    out.writeUTF("STOP")
                    out.flush()
                }
            } catch (_: Exception) {}
        }.start()
    }

    private fun stopLocal() {
        val p = player
        player = null
        if (p != null) {
            try { if (p.isPlaying) p.stop() } catch (_: Exception) {}
            try { p.release() } catch (_: Exception) {}
        }
    }

    private fun waitUntil(targetNanos: Long) {
        while (true) {
            val d = targetNanos - SystemClock.elapsedRealtimeNanos()
            if (d <= 0) break
            if (d > 3_000_000L) Thread.sleep((d - 2_000_000L) / 1_000_000L)
        }
    }

    // ---------- Receiver (every phone always listens) ----------
    private fun startServer() {
        Thread {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(port))
                serverSocket = ss
                while (running) {
                    val s = ss.accept()
                    try {
                        handle(s)
                    } catch (e: Exception) {
                        setStatus("പിശക്: ${e.message}", red)
                    } finally {
                        try { s.close() } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                if (running) setStatus("Server പിശക്: ${e.message}", red)
            }
        }.start()
    }

    private fun handle(s: Socket) {
        s.tcpNoDelay = true
        val inp = DataInputStream(BufferedInputStream(s.getInputStream(), 65536))
        val out = DataOutputStream(BufferedOutputStream(s.getOutputStream()))

        when (inp.readUTF()) {
            "FILE" -> {
                val total = inp.readLong()
                showProgress(0)
                setStatus("പാട്ട് സ്വീകരിക്കുന്നു...", "#1565C0")
                val f = File(cacheDir, "recv_song")
                val buf = ByteArray(65536)
                var left = total
                var lastPct = -1
                f.outputStream().use { fo ->
                    while (left > 0) {
                        val n = inp.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (n < 0) throw EOFException("കണക്ഷൻ മുറിഞ്ഞു")
                        fo.write(buf, 0, n)
                        left -= n
                        val pct = ((total - left) * 100 / total).toInt()
                        if (pct != lastPct) { lastPct = pct; showProgress(pct) }
                    }
                }
                recvFile = f
                out.writeUTF("OK")
                out.flush()
                setStatus("പാട്ട് കിട്ടി ✓ പ്ലേ ചെയ്യാൻ കാത്തിരിക്കുന്നു")
            }

            "PLAY" -> {
                stopLocal()
                val f = recvFile
                if (f == null) {
                    out.writeUTF("NOFILE")
                    out.flush()
                    return
                }
                val mp = MediaPlayer().apply {
                    setDataSource(f.absolutePath)
                    prepare()
                    setOnCompletionListener { setStatus("പാട്ട് കഴിഞ്ഞു") }
                }
                out.writeUTF("READY")
                out.flush()
                val n = inp.readInt()
                repeat(n) {
                    inp.readLong()
                    out.writeLong(SystemClock.elapsedRealtimeNanos())
                    out.flush()
                }
                val target = inp.readLong()
                waitUntil(target)
                mp.start()
                player = mp
                setStatus("▶ പ്ലേ ചെയ്യുന്നു")
            }

            "STOP" -> {
                stopLocal()
                setStatus("നിർത്തി")
            }
        }
    }

    override fun onDestroy() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        stopLocal()
        super.onDestroy()
    }
}
