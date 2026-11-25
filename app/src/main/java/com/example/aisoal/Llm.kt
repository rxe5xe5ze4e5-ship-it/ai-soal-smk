package com.example.aisoal

import android.content.Context
import com.google.ai.edge.litertlm.*
import java.io.File

object Llm {
    private var engine: Engine? = null
    private var conversation: Conversation? = null

    @Volatile var busy = false
    @Volatile var lastAnswer: String = ""
    @Volatile private var unloadPending = false

    private const val SYSTEM = "Kamu adalah asisten belajar untuk siswa SMK. " +
        "Jawab dalam bahasa Indonesia, langsung ke inti tanpa menyapa atau basa-basi. " +
        "Secara default jawab SINGKAT: maksimal 5 baris berisi rumus dan langkah penting saja. " +
        "Jika siswa meminta penjelasan atau langkah rinci, barulah jelaskan lebih panjang. " +
        "Jangan gunakan format markdown atau LaTeX (jangan pakai tanda dolar, tanda bintang, atau garis miring terbalik). " +
        "Tulis rumus dengan teks biasa, misalnya 2 + 2 x 2 = 6. " +
        "Jika kamu tidak yakin, atau hitungannya terlalu besar untuk dihitung dengan andal, katakan terus terang. " +
        "Akhiri jawabanmu dengan satu baris terakhir persis berformat: JAWABAN AKHIR: <jawaban singkat beserta satuan>"

    fun modelFile(ctx: Context) = File(ctx.filesDir, "model.litertlm")

    fun isReady() = engine != null

    @Synchronized
    fun load(ctx: Context) {
        if (engine != null) return
        val f = modelFile(ctx)
        if (!f.exists()) throw IllegalStateException("Model belum dipilih.")
        val e = Engine(
            EngineConfig(
                modelPath = f.absolutePath,
                backend = Backend.CPU(),
                cacheDir = ctx.cacheDir.path
            )
        )
        e.initialize()
        engine = e
    }

    @Synchronized
    fun unload() {
        conversation?.close()
        conversation = null
        engine?.close()
        engine = null
    }

    fun unloadWhenIdle() {
        if (busy) {
            unloadPending = true
        } else {
            Thread { unload() }.start()
        }
    }

    private fun afterDone() {
        if (unloadPending) {
            unloadPending = false
            Thread { unload() }.start()
        }
    }

    fun ask(
        question: String,
        chunk: (String) -> Unit,
        finished: () -> Unit,
        failed: (Throwable) -> Unit
    ) {
        val e = engine
        if (e == null) {
            failed(IllegalStateException("Model belum dimuat."))
            return
        }
        if (busy) {
            failed(IllegalStateException("Masih menjawab soal sebelumnya."))
            return
        }
        try {
            conversation?.close()
            val conv = e.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(SYSTEM),
                    samplerConfig = SamplerConfig(topK = 20, topP = 0.9, temperature = 0.3)
                )
            )
            conversation = conv
            busy = true
            conv.sendMessageAsync(question, object : MessageCallback {
                override fun onMessage(message: Message) {
                    chunk(message.toString())
                }
                override fun onDone() {
                    busy = false
                    finished()
                    afterDone()
                }
                override fun onError(throwable: Throwable) {
                    busy = false
                    failed(throwable)
                    afterDone()
                }
            })
        } catch (t: Throwable) {
            busy = false
            failed(t)
        }
    }
}
