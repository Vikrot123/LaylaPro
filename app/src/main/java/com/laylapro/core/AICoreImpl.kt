package com.laylapro.core

import com.laylapro.api.ApiLayerException
import com.laylapro.conversation.ConversationEngine
import com.laylapro.conversation.Message
import com.laylapro.conversation.Role
import com.laylapro.emotion.EmotionEngine
import com.laylapro.learning.LearningSystem
import com.laylapro.memory.MemoryEntry
import com.laylapro.memory.MemorySystem
import com.laylapro.memory.MemoryType
import com.laylapro.personality.PersonalityEngine
import com.laylapro.planning.PlanningEngine
import com.laylapro.prompt.PromptContext
import com.laylapro.prompt.PromptEngine
import com.laylapro.reasoning.ReasoningEngine
import com.laylapro.router.ModelRouter
import com.laylapro.router.TaskCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharedFlow

/**
 * Модуль 1 / 2 (AI Kernel + Cognitive Core) — реализация оркестратора.
 *
 * Поток обработки: классификация у Reasoning Engine -> граф шагов от Planning Engine ->
 * контекст из Memory System -> (если план требует реальных действий на устройстве)
 * выполнение шагов через [ToolExecutor] -> сборка промпта через [PromptEngine] ->
 * выбор модели через [ModelRouter] -> генерация ответа -> Conversation Engine -> память.
 *
 * ВАЖНО: этот класс теперь не строит промпт сам (это [PromptEngine]) и не завязан на
 * конкретный [com.laylapro.api.ApiLayer] (это решает [ModelRouter] по категории задачи) —
 * то есть все три ответственности, которые в проанализированной архитектуре Layla были
 * смешаны в одном классе, здесь разнесены по трём модулям (см. Blueprint, §11 рекомендаций).
 */
class AICoreImpl(
    private val reasoningEngine: ReasoningEngine,
    private val planningEngine: PlanningEngine,
    private val memorySystem: MemorySystem,
    private val personalityEngine: PersonalityEngine,
    private val emotionEngine: EmotionEngine,
    private val conversationEngine: ConversationEngine,
    private val promptEngine: PromptEngine,
    private val modelRouter: ModelRouter,
    private val learningSystem: LearningSystem,
    private val toolExecutor: ToolExecutor = NoOpToolExecutor,
    private val ragEngine: com.laylapro.rag.RagEngine? = null,
) : AICore {

    override val eventBus: SharedFlow<CoreEvent> = EventBus.events

    override suspend fun processInput(input: UserInput): CoreResponse {
        val text = when (val payload = input.payload) {
            is InputPayload.Text -> payload.content
            is InputPayload.Voice -> throw ApiLayerException("Voice payload требует Voice Engine (STT) — см. модуль 10")
            is InputPayload.MultiModal -> payload.text
        }

        EventBus.tryPublish(CoreEvent.UserMessageReceived(input.sessionId, text))

        return try {
            // 1. Эмоциональный анализ входа (Emotion Engine)
            val emotion = emotionEngine.analyze(text)

            // 2. Контекст из памяти (Memory System)
            val memoryContext = memorySystem.queryRelevantContext(input.sessionId)

            // 3. Рассуждение — классификация интента (Reasoning Engine, свою модель выбирает сам)
            val reasoning = reasoningEngine.evaluateContext(text, memoryContext)

            // 4. Планирование через Function Calling (Planning Engine сам решает,
            // нужно ли дёргать DeviceControl/AndroidIntegration, см. ToolCatalog).
            val plan = planningEngine.buildPlan(goal = text, availableTools = emptyList())

            // 4b. Выполняем шаги плана, которые реально что-то делают на устройстве
            // (всё, кроме тривиального ConversationEngine/respond) через ToolExecutor,
            // который Runtime Core подключает как мост к Dispatcher.
            val actionableSteps = plan.steps.filter { it.moduleName != "ConversationEngine" }
            val toolResults = if (actionableSteps.isNotEmpty()) {
                toolExecutor.execute(actionableSteps)
            } else {
                emptyMap()
            }

            // 5. Обучающий патч из предыдущих исправлений пользователя (Learning System)
            val learningOverride = learningSystem.getPromptOverride()

            // 5b. Этап 2, пункт 4 (RAG Engine): релевантные знания из долгосрочной базы,
            // а не только из истории ЭТОЙ сессии (см. п.2 выше). Опционален (null-safe) —
            // Vector Memory/Knowledge Index могут быть ещё не подключены на более ранних
            // этапах разработки без поломки AICoreImpl (Liskov-совместимая деградация).
            val ragContext = ragEngine?.retrieve(text).orEmpty()

            // 6. Сборка системного промпта — ЕДИНСТВЕННОЕ место, где это происходит (Prompt Engine)
            val systemPrompt = promptEngine.buildSystemPrompt(
                PromptContext(
                    predictedIntent = reasoning.predictedIntent,
                    confidence = reasoning.confidence,
                    emotion = emotion,
                    learningOverride = learningOverride,
                    memoryContext = memoryContext,
                    toolResults = toolResults,
                    actionableSteps = actionableSteps,
                    ragContext = ragContext,
                )
            )

            // 7. Model Router выбирает лучший движок для финального ответа (сейчас — облачный
            // Claude; когда появится локальный движок из Этапа 6, это изменится без правок здесь).
            val routing = modelRouter.route(TaskCategory.CHAT)
            val answer = routing.apiLayer.complete(systemPrompt = systemPrompt, userMessage = text)

            // 8. Обновление диалоговой сессии и памяти
            conversationEngine.updateSessionContext(input.sessionId, Message(Role.USER, text))
            conversationEngine.updateSessionContext(input.sessionId, Message(Role.ASSISTANT, answer))
            memorySystem.save(
                MemoryEntry(sessionId = input.sessionId, rawText = "Пользователь: $text"),
                MemoryType.SHORT_TERM,
            )
            memorySystem.save(
                MemoryEntry(sessionId = input.sessionId, rawText = "LaylaPro: $answer"),
                MemoryType.SHORT_TERM,
            )

            EventBus.tryPublish(CoreEvent.AssistantResponded(input.sessionId, answer))
            CoreResponse(sessionId = input.sessionId, text = answer)
        } catch (e: CancellationException) {
            // Патч 1: структурированная конкурентность Kotlin должна уметь отменить этот
            // suspend-вызов (пользователь ушёл с экрана, viewModelScope отменён, истёк
            // родительский таймаут) — если проглотить CancellationException вместе с обычными
            // ошибками ниже, отмена перестанет распространяться корректно, и вызывающий код
            // (RuntimeManager.submitUserMessage) продолжит работать так, будто ничего не
            // отменялось. Поведение для НЕотменённых ошибок ниже не меняется.
            throw e
        } catch (e: Exception) {
            EventBus.tryPublish(CoreEvent.ErrorOccurred("AICore", e.message ?: "unknown error"))
            CoreResponse(
                sessionId = input.sessionId,
                text = "Не получилось обработать запрос: ${e.message}",
                isError = true,
            )
        }
    }
}
