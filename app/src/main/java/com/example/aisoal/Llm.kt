package com.example.aisoal

import android.content.Context
import com.google.ai.edge.litertlm.*
import java.io.File

object Llm {
    private var engine: Engine? = null
    private var conversation: Conversation? = null

    @Volatile var busy = false
    @Volatile var lastAnswer: String = ""

    private const val SYSTEM = "Kamu adalah asisten belajar untuk siswa SMK. " +
        "Jawab dalam bahasa Indonesia. Selesaikan soal langkah demi langkah, " +
        "tulis rumus yang dipakai, lalu periksa kembali hasil akhir sebelum menjawab. " +
        "Jika kamu tidak yakin, katakan terus terang."

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
                }
                override fun onError(throwable: Throwable) {
                    busy = false
                    failed(throwable)
                }
            })
        } catch (t: Throwable) {
            busy = false
            failed(t)
        }
    }
}
