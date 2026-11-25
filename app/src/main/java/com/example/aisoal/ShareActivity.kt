package com.example.aisoal

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast

class ShareActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            handle(intent)
        } catch (t: Throwable) {
            Toast.makeText(this, "Gagal membaca soal: ${t.message}", Toast.LENGTH_LONG).show()
        }
        finishAndRemoveTask()
    }

    private fun handle(i: Intent?) {
        if (i == null) return
        var text: String? = null
        var imagePath: String? = null

        when (i.action) {
            Intent.ACTION_PROCESS_TEXT -> {
                text = i.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            }
            Intent.ACTION_SEND -> {
                text = i.getStringExtra(Intent.EXTRA_TEXT)
                val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) {
                    i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    i.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                if (uri != null) imagePath = ImageUtil.save(this, uri)
            }
        }

        val q = text?.trim() ?: ""
        if (q.isEmpty() && imagePath == null) {
            Toast.makeText(this, "Tidak ada soal yang bisa dibaca.", Toast.LENGTH_LONG).show()
            return
        }

        val s = Intent(this, ChatService::class.java)
        s.action = ChatService.ACTION_ASK_TEXT
        s.putExtra(ChatService.EXTRA_Q, q.take(12000))
        if (imagePath != null) s.putExtra(ChatService.EXTRA_IMAGE, imagePath)
        startForegroundService(s)
        Toast.makeText(this, "Soal dikirim. Lihat notifikasi.", Toast.LENGTH_SHORT).show()
    }
}
