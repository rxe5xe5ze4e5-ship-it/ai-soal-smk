package com.example.aisoal

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.ai.edge.litertlm.*
import java.io.File

class MainActivity : Activity() {

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private lateinit var status: TextView
    private lateinit var answer: TextView
    private lateinit var input: EditText
    private lateinit var sendBtn: Button

    private val pickCode = 101
    private val systemText = "Kamu adalah asisten belajar untuk siswa SMK. " +
        "Jawab dalam bahasa Indonesia. Selesaikan soal langkah demi langkah, " +
        "tulis rumus yang dipakai, lalu periksa kembali hasil akhir sebelum menjawab. " +
        "Jika kamu tidak yakin, katakan terus terang."

    private fun modelFile() = File(filesDir, "model.litertlm")

    private fun setStatus(text: String) {
        runOnUiThread { status.text = text }
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
        status.text = if (modelFile().exists())
            "Model sudah ada. Ketuk 'Muat model'."
        else
            "Belum ada model. Ketuk 'Pilih file model'."
        root.addView(status, LinearLayout.LayoutParams(full, wrap))

        val pickBtn = Button(this)
        pickBtn.text = "Pilih file model"
        pickBtn.setOnClickListener {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.type = "*/*"
            startActivityForResult(i, pickCode)
        }
        root.addView(pickBtn, LinearLayout.LayoutParams(full, wrap))

        val loadBtn = Button(this)
        loadBtn.text = "Muat model"
        loadBtn.setOnClickListener { loadModel() }
        root.addView(loadBtn, LinearLayout.LayoutParams(full, wrap))

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

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != pickCode || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        Thread {
            try {
                setStatus("Menyalin model... mohon tunggu, jangan tutup aplikasi.")
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
                modelFile().delete()
                tmp.renameTo(modelFile())
                setStatus("Model tersalin (${total / (1024 * 1024)} MB). Ketuk 'Muat model'.")
            } catch (t: Throwable) {
                setStatus("Gagal menyalin: ${t.message}")
            }
        }.start()
    }

    private fun loadModel() {
        if (!modelFile().exists()) {
            setStatus("Model belum dipilih.")
            return
        }
        Thread {
            try {
                setStatus("Memuat model... bisa sampai satu menit.")
                conversation?.close()
                conversation = null
                engine?.close()
                val e = Engine(
                    EngineConfig(
                        modelPath = modelFile().absolutePath,
                        backend = Backend.CPU(),
                        cacheDir = cacheDir.path
                    )
                )
                e.initialize()
                engine = e
                setStatus("Model siap. Silakan ketik soal.")
            } catch (t: Throwable) {
                setStatus("Gagal memuat: ${t.message}")
            }
        }.start()
    }

    private fun ask() {
        val e = engine
        if (e == null) {
            setStatus("Model belum dimuat.")
            return
        }
        val question = input.text.toString().trim()
        if (question.isEmpty()) return
        try {
            conversation?.close()
            val conv = e.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(systemText),
                    samplerConfig = SamplerConfig(topK = 20, topP = 0.9, temperature = 0.3)
                )
            )
            conversation = conv
            answer.text = ""
            sendBtn.isEnabled = false
            conv.sendMessageAsync(question, object : MessageCallback {
                override fun onMessage(message: Message) {
                    runOnUiThread { answer.append(message.toString()) }
                }
                override fun onDone() {
                    runOnUiThread { sendBtn.isEnabled = true }
                }
                override fun onError(throwable: Throwable) {
                    runOnUiThread {
                        answer.append("\n[Error: ${throwable.message}]")
                        sendBtn.isEnabled = true
                    }
                }
            })
        } catch (t: Throwable) {
            answer.text = "Gagal: ${t.message}"
            sendBtn.isEnabled = true
        }
    }
}
