package com.laylapro.text

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.laylapro.MainActivity

/**
 * "Обсудить с LaylaPro" — второй пункт системного меню "Обработать текст"
 * (см. [RememberActivity] для парного пункта "Запомнить"). В отличие от
 * RememberActivity, здесь пользователь хочет продолжить разговор, а не просто
 * сохранить факт — поэтому просто перебрасываем текст в MainActivity как
 * готовое к отправке сообщение чата.
 */
class ChatActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val text = intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()

        val forwardIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(MainActivity.EXTRA_PREFILLED_MESSAGE, text)
        }
        startActivity(forwardIntent)
        finish()
    }
}
