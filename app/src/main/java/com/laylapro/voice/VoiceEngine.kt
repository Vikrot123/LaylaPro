package com.laylapro.voice

import android.content.Context
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Модуль 10 (Слой 4) — STT/TTS (см. ТЗ, Часть II, п.10).
 *
 * В ТЗ описана офлайн-реализация через whisper.cpp (JNI) и MeloTTS/VITS —
 * это даёт полный оффлайн-режим и контроль над качеством, но требует
 * компиляции нативных библиотек под ARM64.
 *
 * Для MVP используется штатный Android SpeechRecognizer/TextToSpeech —
 * тот же контракт, чтобы позже безболезненно подменить реализацию на whisper.cpp,
 * не трогая остальную систему.
 *
 * TODO(offline): добавить `WhisperCppVoiceEngine : VoiceEngine`, оборачивающую
 * JNI-мост (см. пример сигнатуры extern "C" JNIEXPORT processSTT в ТЗ) и включаемую
 * автоматически, когда Infrastructure Layer (модуль 18) сообщает, что офлайн-модель доступна.
 */
interface VoiceEngine {
    fun listen(): Flow<String>
    fun speak(text: String)
    fun stop()
}

class AndroidVoiceEngine(private val context: Context) : VoiceEngine {

    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("ru", "RU")
            }
        }
    }

    override fun listen(): Flow<String> = callbackFlow {
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        val intent = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: android.os.Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!text.isNullOrBlank()) trySend(text)
                close()
            }

            override fun onError(error: Int) {
                close(RuntimeException("Ошибка распознавания речи, код $error"))
            }

            override fun onReadyForSpeech(params: android.os.Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: android.os.Bundle?) {}
            override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
        })

        recognizer.startListening(intent)
        awaitClose { recognizer.destroy() }
    }

    override fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "layla_utterance")
    }

    override fun stop() {
        tts?.stop()
    }
}
