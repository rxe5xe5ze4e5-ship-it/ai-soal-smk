package com.example.aisoal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput

class ChatService : Service() {

    companion object {
        const val CHANNEL = "ai_soal"
        const val NOTIF_ID = 1
        const val KEY_TEXT = "key_text"
        const val ACTION_ASK = "com.example.aisoal.ASK"
        const val ACTION_STOP = "com.example.aisoal.STOP"
    }

    private var lastUpdate = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "AI Soal SMK", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_ASK -> {
                val q = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(KEY_TEXT)?.toString()?.trim()
                startAsForeground("Menjawab...")
                if (!q.isNullOrEmpty()) answer(q) else show("Ketik soal lewat tombol Tanya.")
            }
            else -> {
                startAsForeground("Memuat model...")
                Thread {
                    try {
                        Llm.load(this)
                        show("Model siap. Ketuk 'Tanya' untuk mengetik soal.")
                    } catch (t: Throwable) {
                        show("Gagal memuat: ${t.message}")
                    }
                }.start()
            }
        }
        return START_NOT_STICKY
    }

    private fun answer(q: String) {
        val sb = StringBuilder()
        show("Menjawab: $q")
        Llm.ask(
            q,
            chunk = { c ->
                sb.append(c)
                val now = System.currentTimeMillis()
                if (now - lastUpdate > 800) {
                    lastUpdate = now
                    show(sb.toString())
                }
            },
            finished = {
                val result = "Soal: $q\n\n" + sb.toString().ifEmpty { "(Tidak ada jawaban)" }
                Llm.lastAnswer = result
                show(result)
            },
            failed = { t -> show("Gagal: ${t.message}") }
        )
    }

    private fun startAsForeground(text: String) {
        val n = build(text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun show(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, build(text))
    }

    private fun build(text: String): Notification {
        val remote = RemoteInput.Builder(KEY_TEXT).setLabel("Ketik soal").build()
        val askPi = PendingIntent.getService(
            this, 0,
            Intent(this, ChatService::class.java).setAction(ACTION_ASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        val action = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send, "Tanya", askPi
        ).addRemoteInput(remote).build()
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, ChatService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openPi = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("AI Soal SMK")
            .setContentText(text.take(100))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openPi)
            .addAction(action)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Matikan", stopPi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }
}
