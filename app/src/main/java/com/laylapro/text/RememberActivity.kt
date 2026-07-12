package com.laylapro.text

import androidx.activity.ComponentActivity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.laylapro.LaylaApplication
import kotlinx.coroutines.launch

/**
 * "Layla, запомни это!" — невидимая trampoline-активность, всплывающая в системном
 * контекстном меню при выделении текста в ЛЮБОМ приложении (см. анализ архитектуры
 * Layla, §4/§5: явный, а не автоматический, жест пополнения долгосрочной памяти).
 *
 * В отличие от [ChatActivity] (которая открывает диалог), эта активность НЕ открывает
 * UI вообще — сохраняет текст напрямую в [com.laylapro.memory.MemorySystem] (для
 * ранжирования Recency+Importance) И в [com.laylapro.knowledge.KnowledgeIndex] (Этап 2,
 * чтобы RagEngine мог найти этот факт по семантической близости в ЛЮБОМ будущем разговоре,
 * а не только через explicit-путь MemorySystem) — сразу закрывается, показав короткий Toast.
 */
class RememberActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val text = intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        if (text.isNullOrBlank()) {
            finish()
            return
        }

        val app = application as LaylaApplication
        lifecycleScope.launch {
            app.memorySystem.rememberExplicit(
                sessionId = "global", // см. InMemoryMemorySystem: LONG_TERM с importance>=0.9 видно из любой сессии
                text = text,
                tags = listOf("process_text_menu"),
            )
            app.knowledgeIndex.index(text, metadata = mapOf("source" to "process_text_menu"))
            Toast.makeText(this@RememberActivity, "LaylaPro запомнила это", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
