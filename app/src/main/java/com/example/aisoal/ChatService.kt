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
        const val CHANNEL_RESULT = "ai_soal_jawaban"
        const val NOTIF_ID = 1
        const val RESULT_ID = 2
        const val KEY_TEXT = "key_text"
        const val EXTRA_Q = "extra_q"
        const val ACTION_ASK = "com.example.aisoal.ASK"
        const val ACTION_ASK_TEXT = "com.example.aisoal.ASK_TEXT"
        const val ACTION_STOP = "com.example.aisoal.STOP"
    }

    private val lock = Any()
    @Volatile private var stopped = false
    private var lastUpdate = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "AI Soal SMK", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULT, "Jawaban akhir", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    override fun onDestroy() {
        stopped = true
        getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_STOP -> {
                stopNow()
                return START_NOT_STICKY
            }
            ACTION_ASK, ACTION_ASK_TEXT -> {
                stopped = false
                val q: String? = if (action == ACTION_ASK_TEXT) {
                    intent?.getStringExtra(EXTRA_Q)?.trim()
                } else if (intent != null) {
                    RemoteInput.getResultsFromIntent(intent)
                        ?.getCharSequence(KEY_TEXT)?.toString()?.trim()
                } else {
                    null
                }
                startAsForeground("Menjawab...")
                if (q.isNullOrEmpty()) {
                    show("Ketik soal lewat tombol Tanya atau Soal panjang.")
                } else {
                    answer(q.take(12000))
                }
            }
            else -> {
                stopped = false
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

    private fun stopNow() {
        synchronized(lock) {
            stopped = true
            stopForeground(STOP_FOREGROUND_REMOVE)
            val nm = getSystemService(NotificationManager::class.java)
            nm.cancel(NOTIF_ID)
            nm.cancel(RESULT_ID)
        }
        Llm.unloadWhenIdle()
        stopSelf()
    }

    private fun clean(s: String): String =
        s.replace("\\times", "×")
            .replace("\\cdot", "×")
            .replace("\\div", "÷")
            .replace("\\(", "")
            .replace("\\)", "")
            .replace("\\[", "")
            .replace("\\]", "")
            .replace("\$", "")
            .replace("**", "")

    private fun extractFinal(text: String): String {
        val m = Regex("JAWABAN AKHIR\\s*:?\\s*(.+)", RegexOption.IGNORE_CASE)
            .findAll(text).lastOrNull()
        val found = m?.groupValues?.get(1)?.trim()
        if (!found.isNullOrEmpty()) return found
        return text.lines().map { it.trim() }.lastOrNull { it.isNotEmpty() } ?: ""
    }

    private fun answer(q: String) {
        val sb = StringBuilder()
        val qShort = if (q.length > 120) q.take(120) + "…" else q
        show("Menjawab: $qShort")
        Llm.ask(
            q,
            chunk = { c ->
                sb.append(c)
                val now = System.currentTimeMillis()
                if (now - lastUpdate > 1200) {
                    lastUpdate = now
                    show(clean(sb.toString()))
                }
            },
            finished = {
                val text = clean(sb.toString())
                var finalAnswer = extractFinal(text)
                if (finalAnswer.isEmpty()) finalAnswer = "(tidak ada jawaban)"
                val body = text.lines()
                    .filterNot { it.trim().startsWith("JAWABAN AKHIR", ignoreCase = true) }
                    .joinToString("\n")
                    .trim()
                val result = "Jawaban akhir: $finalAnswer\n\nSoal: $qShort\n\n$body"
                Llm.lastAnswer = result
                show(result)
                showResult(finalAnswer, qShort)
            },
            failed = { t -> show("Gagal: ${t.message}") }
        )
    }

    private fun showResult(finalAnswer: String, q: String) {
        synchronized(lock) {
            if (stopped) return
            val openPi = PendingIntent.getActivity(
                this, 3,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val n = NotificationCompat.Builder(this, CHANNEL_RESULT)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Jawaban akhir")
                .setContentText(finalAnswer)
                .setStyle(NotificationCompat.BigTextStyle().bigText("Soal: $q\n\nJawaban: $finalAnswer"))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setContentIntent(openPi)
                .setAutoCancel(true)
                .build()
            getSystemService(NotificationManager::class.java).notify(RESULT_ID, n)
        }
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
        synchronized(lock) {
            if (stopped) return
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, build(text))
        }
    }

    private fun build(text: String): Notification {
        val remote = RemoteInput.Builder(KEY_TEXT).setLabel("Ketik soal").build()
        val askPi = PendingIntent.getService(
            this, 0,
            Intent(this, ChatService::class.java).setAction(ACTION_ASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        val askAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send, "Tanya", askPi
        ).addRemoteInput(remote).build()

        val longPi = PendingIntent.getActivity(
            this, 4,
            Intent(this, AskActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, ChatService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openPi = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("AI Soal SMK")
            .setContentText(text.take(100))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openPi)
            .addAction(askAction)
            .addAction(android.R.drawable.ic_menu_edit, "Soal panjang", longPi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Matikan", stopPi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }
}
