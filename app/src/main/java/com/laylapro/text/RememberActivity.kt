package com.laylapro.text

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.laylapro.LaylaApplication
import kotlinx.coroutines.launch

/** Explicit PROCESS_TEXT entry for user-requested long-term memory. */
class RememberActivity : ComponentActivity() {
    companion object {
        private const val MAX_PROCESS_TEXT_CHARS = 20_000
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent?.action != Intent.ACTION_PROCESS_TEXT) {
            finish()
            return
        }

        val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
            ?.toString()
            ?.trim()
            ?.take(MAX_PROCESS_TEXT_CHARS)

        if (text.isNullOrBlank()) {
            finish()
            return
        }

        val app = application as LaylaApplication
        lifecycleScope.launch {
            app.memorySystem.rememberExplicit(
                sessionId = "global",
                text = text,
                tags = listOf("process_text_menu"),
            )
            app.knowledgeIndex.index(text, metadata = mapOf("source" to "process_text_menu"))
            Toast.makeText(this@RememberActivity, "LaylaPro запомнила это", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
