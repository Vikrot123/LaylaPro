package com.laylapro.infra

/**
 * Модуль 18 (Слой 7) — выделение ресурсов GPU/NPU под локальные нейросети
 * (см. ТЗ, Часть II, п.18: llama.cpp / ExecuTorch / MediaPipe LLM Inference API,
 * .gguf модели, Vulkan backend, Android NNAPI).
 *
 * STUB: подключение нативных библиотек и офлайн-моделей выходит за рамки MVP
 * (требует .so-сборки под ARM64 и веса моделей весом в сотни МБ-ГБ).
 * Здесь зафиксирован только контракт, чтобы Reasoning/Planning Engine могли
 * позже переключаться между "облако" и "локально" прозрачно.
 */
interface InfrastructureLayer {
    fun isLocalModelAvailable(): Boolean
    fun loadModel(ggufPath: String, useGpu: Boolean = true)
    fun unloadModel()
}

class NotAvailableInfrastructureLayer : InfrastructureLayer {
    override fun isLocalModelAvailable(): Boolean = false

    override fun loadModel(ggufPath: String, useGpu: Boolean) {
        throw NotImplementedError(
            "Локальный инференс не подключён. TODO: интегрировать llama.cpp/ExecuTorch " +
                "через JNI, как описано в ТЗ (модуль 18)."
        )
    }

    override fun unloadModel() = Unit
}
