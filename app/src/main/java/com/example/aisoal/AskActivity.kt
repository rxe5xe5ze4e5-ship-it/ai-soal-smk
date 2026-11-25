package com.example.aisoal

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class AskActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val full = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(48, 32, 48, 32)

        val title = TextView(this)
        title.text = "Tulis atau tempel soal"
        title.textSize = 18f
        root.addView(title, LinearLayout.LayoutParams(full, wrap))

        val input = EditText(this)
        input.hint = "Ketik atau tempel soal di sini"
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        input.minLines = 6
        input.maxLines = 12
        input.gravity = Gravity.TOP or Gravity.START
        root.addView(input, LinearLayout.LayoutParams(full, wrap))

        val btn = Button(this)
        btn.text = "Kirim"
        btn.setOnClickListener {
            val q = input.text.toString().trim()
            if (q.isNotEmpty()) {
                val i = Intent(this, ChatService::class.java)
                i.action = ChatService.ACTION_ASK_TEXT
                i.putExtra(ChatService.EXTRA_Q, q.take(12000))
                startForegroundService(i)
                finish()
            }
        }
        root.addView(btn, LinearLayout.LayoutParams(full, wrap))

        setContentView(root)
        window.setLayout(full, wrap)
    }
}
