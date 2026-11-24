package com.example.aisoal

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var answer: TextView
    private lateinit var input: EditText
    private lateinit var sendBtn: Button

    private val pickCode = 101
    private val notifCode = 102
    @Volatile private var copying = false

    private fun setStatus(text: String) {
        runOnUiThread { status.text = text }
    }

    private fun addButton(root: LinearLayout, label: String, action: () -> Unit) {
        val b = Button(this)
        b.text = label
        b.setOnClickListener { action() }
        root.addView(
            b,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(32, 64, 32, 32)
        val full = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

        status = TextView(this)
        status.textSize = 16f
        status.text = "Menyiapkan..."
        root.addView(status, LinearLayout.LayoutParams(full, wrap))

        addButton(root, "Pilih file model") { pickModel() }
        addButton(root, "Muat model") { loadInBackground() }
        addButton(root, "Aktifkan notifikasi AI") { enableNotif() }
        addButton(root, "Matikan notifikasi AI") {
            stopService(Intent(this, ChatService::class.java))
        }

        input = EditText(this)
        input.hint = "Ketik soal di sini"
        root.addView(input, LinearLayout.LayoutParams(full, wrap))

        sendBtn = Button(this)
        sendBtn.text = "Kirim"
        sendBtn.setOnClickListener { ask() }
        root.addView(sendBtn, LinearLayout.LayoutParams(full, wrap))

        answer = TextView(this)
        answer.textSize = 16f
        val scroll = ScrollView(this)
        scroll.addView(answer)
        root.addView(scroll, LinearLayout.LayoutParams(full, 0, 1f))

        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        if (Llm.lastAnswer.isNotEmpty() && answer.text.isEmpty()) {
            answer.text = Llm.lastAnswer
        }
        loadInBackground()
    }

    private fun loadInBackground() {
        if (copying) return
        if (Llm.isReady()) {
            setStatus("Model siap. Silakan ketik soal.")
            return
        }
        if (!Llm.modelFile(this).exists()) {
            setStatus("Belum ada model. Ketuk 'Pilih file model'.")
            return
        }
        Thread {
            try {
                setStatus("Memuat model... bisa sampai satu menit.")
                Llm.load(this)
                setStatus("Model siap. Silakan ketik soal.")
            } catch (t: Throwable) {
                setStatus("Gagal memuat: ${t.message}")
            }
        }.start()
    }

    private fun pickModel() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        startActivityForResult(i, pickCode)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != pickCode || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        copying = true
        Thread {
            try {
                setStatus("Menyalin model... mohon tunggu, jangan tutup aplikasi.")
                Llm.unload()
                val tmp = File(filesDir, "model.tmp")
                var total = 0L
                var lastShown = 0L
                contentResolver.openInputStream(uri)!!.use { ins ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(1 shl 20)
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            total += n
                            if (total - lastShown > 100L * 1024 * 1024) {
                                lastShown = total
                                setStatus("Menyalin model: ${total / (1024 * 1024)} MB")
                            }
                        }
                    }
                }
                val target = Llm.modelFile(this)
                target.delete()
                tmp.renameTo(target)
                copying = false
                setStatus("Model tersalin (${total / (1024 * 1024)} MB). Memuat...")
                loadInBackground()
            } catch (t: Throwable) {
                copying = false
                setStatus("Gagal menyalin: ${t.message}")
            }
        }.start()
    }

    private fun enableNotif() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), notifCode)
            return
        }
        startForegroundService(Intent(this, ChatService::class.java))
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == notifCode) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                startForegroundService(Intent(this, ChatService::class.java))
            } else {
                setStatus("Izin notifikasi ditolak. Aktifkan di Pengaturan aplikasi.")
            }
        }
    }

    private fun ask() {
        val q = input.text.toString().trim()
        if (q.isEmpty()) return
        answer.text = ""
        sendBtn.isEnabled = false
        Llm.ask(
            q,
            chunk = { c -> runOnUiThread { answer.append(c) } },
            finished = { runOnUiThread { sendBtn.isEnabled = true } },
            failed = { t ->
                runOnUiThread {
                    answer.append("\n[Error: ${t.message}]")
                    sendBtn.isEnabled = true
                }
            }
        )
    }
}
