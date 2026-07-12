package com.laylapro.vision

import android.graphics.Bitmap

/**
 * Модуль 11 (Слой 4) — компьютерное зрение: анализ фото и, главное, скриншотов
 * экрана (Screen Understanding), см. ТЗ, Часть II, п.11.
 *
 * STUB: полная реализация требует:
 *  1) снимок экрана через MediaProjection API;
 *  2) локальную VLM (например, Phind-3B / Qwen2-VL) через ONNX Runtime Mobile / MediaPipe;
 *  3) OCR + детектор UI-элементов, превращающий картинку в дерево доступности (UI Tree).
 *
 * Пока что MVP делегирует текстовый анализ облачной модели (multimodal-запрос
 * через API Layer, если понадобится), а не локальному инференсу.
 */
interface VisionEngine {
    suspend fun analyzeScreen(screenshot: Bitmap): ScreenAnalysisResult
}

data class ScreenAnalysisResult(
    val ocrText: String,
    val clickableElements: List<UiElementBoundingBox>,
)

data class UiElementBoundingBox(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

class NotImplementedVisionEngine : VisionEngine {
    override suspend fun analyzeScreen(screenshot: Bitmap): ScreenAnalysisResult {
        throw NotImplementedError(
            "Vision Engine ещё не подключён к локальной VLM. " +
                "См. TODO в KDoc VisionEngine.kt — нужны MediaProjection + ONNX Runtime."
        )
    }
}
