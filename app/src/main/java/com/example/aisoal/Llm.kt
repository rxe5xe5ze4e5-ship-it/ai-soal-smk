package com.example.aisoal

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import com.google.ai.edge.litertlm.*
import java.io.File
import java.io.FileOutputStream

object Llm {
    private var engine: Engine? = null
    private var conversation: Conversation? = null

    @Volatile var busy = false
    @Volatile var lastAnswer: String = ""
    @Volatile private var unloadPending = false
    @Volatile private var visionLoaded = false

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

    fun hasVision() = visionLoaded

    @Synchronized
    fun load(ctx: Context, vision: Boolean = false) {
        if (engine != null && (!vision || visionLoaded)) return
        if (busy) throw IllegalStateException("Masih menjawab soal sebelumnya.")
        val f = modelFile(ctx)
        if (!f.exists()) throw IllegalStateException("Model belum dipilih.")
        if (engine != null) {
            conversation?.close()
            conversation = null
            engine?.close()
            engine = null
            visionLoaded = false
        }
        val cfg = if (vision) {
            EngineConfig(
                modelPath = f.absolutePath,
                backend = Backend.CPU(),
                visionBackend = Backend.CPU(),
                cacheDir = ctx.cacheDir.path
            )
        } else {
            EngineConfig(
                modelPath = f.absolutePath,
                backend = Backend.CPU(),
                cacheDir = ctx.cacheDir.path
            )
        }
        val e = Engine(cfg)
        e.initialize()
        engine = e
        visionLoaded = vision
    }

    @Synchronized
    fun unload() {
        conversation?.close()
        conversation = null
        engine?.close()
        engine = null
        visionLoaded = false
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
        failed: (Throwable) -> Unit,
        imagePath: String? = null
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
            val callback = object : MessageCallback {
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
            }
            if (imagePath != null) {
                conv.sendMessageAsync(
                    Contents.of(Content.ImageFile(imagePath), Content.Text(question)),
                    callback
                )
            } else {
                conv.sendMessageAsync(question, callback)
            }
        } catch (t: Throwable) {
            busy = false
            failed(t)
        }
    }
}

object ImageUtil {
    fun save(ctx: Context, uri: Uri): String? {
        return try {
            val bounds = BitmapFactory.Options()
            bounds.inJustDecodeBounds = true
            ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            var sample = 1
            val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
            while (maxSide / sample > 1280) sample *= 2

            val opts = BitmapFactory.Options()
            opts.inSampleSize = sample
            var bmp = ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return null

            val rot = try {
                ctx.contentResolver.openInputStream(uri)?.use { ins ->
                    when (ExifInterface(ins).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270
                        else -> 0
                    }
                } ?: 0
            } catch (t: Throwable) {
                0
            }
            if (rot != 0) {
                val m = Matrix()
                m.postRotate(rot.toFloat())
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }

            val out = File(ctx.cacheDir, "soal.jpg")
            FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            out.absolutePath
        } catch (t: Throwable) {
            null
        }
    }
}
