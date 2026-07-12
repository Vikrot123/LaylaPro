package com.laylapro

import android.app.Application
import com.laylapro.accessibility.LaylaAccessibilityService
import com.laylapro.api.AnthropicApiClient
import com.laylapro.api.ApiLayer
import com.laylapro.conversation.ConversationEngine
import com.laylapro.conversation.ConversationEngineImpl
import com.laylapro.core.AICore
import com.laylapro.core.AICoreImpl
import com.laylapro.core.ToolExecutor
import com.laylapro.device.DeviceControlLayer
import com.laylapro.device.DeviceControlLayerImpl
import com.laylapro.emotion.EmotionEngine
import com.laylapro.emotion.EmotionEngineImpl
import com.laylapro.infra.InfrastructureLayer
import com.laylapro.infra.NotAvailableInfrastructureLayer
import com.laylapro.embedding.EmbeddingEngine
import com.laylapro.embedding.HashingEmbeddingEngine
import com.laylapro.knowledge.HybridKnowledgeIndex
import com.laylapro.knowledge.InMemoryKnowledgeBase
import com.laylapro.knowledge.KnowledgeBase
import com.laylapro.knowledge.KnowledgeIndex
import com.laylapro.rag.DefaultRagEngine
import com.laylapro.rag.RagEngine
import com.laylapro.vectormemory.InMemoryVectorMemory
import com.laylapro.vectormemory.VectorMemory
import com.laylapro.learning.LearningSystem
import com.laylapro.learning.LearningSystemImpl
import com.laylapro.memory.InMemoryMemorySystem
import com.laylapro.memory.MemorySystem
import com.laylapro.monitoring.MonitoringLayer
import com.laylapro.personality.PersonalityEngine
import com.laylapro.personality.PersonalityEngineImpl
import com.laylapro.planning.PlanningEngine
import com.laylapro.planning.PlanningEngineImpl
import com.laylapro.prompt.PromptEngine
import com.laylapro.prompt.PromptEngineImpl
import com.laylapro.reasoning.ReasoningEngine
import com.laylapro.reasoning.ReasoningEngineImpl
import com.laylapro.router.DeviceResources
import com.laylapro.router.ModelRouter
import com.laylapro.router.ModelRouterFactory
import com.laylapro.runtime.Command
import com.laylapro.runtime.CommandResult
import com.laylapro.runtime.Dispatcher
import com.laylapro.runtime.DispatcherToolExecutor
import com.laylapro.runtime.ModuleDescriptor
import com.laylapro.runtime.ModuleRegistry
import com.laylapro.runtime.ModuleState
import com.laylapro.runtime.RecoveryManager
import com.laylapro.runtime.ResourceManager
import com.laylapro.runtime.RuntimeManager
import com.laylapro.security.SecurityLayer
import com.laylapro.security.SecurityLayerImpl
import com.laylapro.util.SecureStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Композиционный корень LaylaPro.
 *
 * Полноценный DI-фреймворк (Hilt/Koin) сюда напрашивается сам собой, но для MVP
 * модули собираются вручную здесь — так проще проследить полный граф зависимостей
 * между всеми 20 модулями ТЗ на старте проекта.
 */
class LaylaApplication : Application() {

    lateinit var secureStorage: SecureStorage
        private set

    lateinit var apiLayer: ApiLayer
        private set

    /**
     * Патч 4: та же самая ссылка на движок, что и [apiLayer], но конкретного типа —
     * нужна только для [com.laylapro.api.ApiKeyValidationResult]-проверки на экране
     * настроек. Не создаёт второй экземпляр движка и не меняет контракт [ApiLayer] —
     * добавлена намеренно узкая, специфичная для Anthropic возможность, которую не
     * стоит тянуть в общий интерфейс (локальный движок в будущем может не иметь
     * понятия "API-ключ" вовсе).
     */
    lateinit var anthropicApiClient: com.laylapro.api.AnthropicApiClient
        private set

    lateinit var memorySystem: MemorySystem
        private set

    lateinit var knowledgeBase: KnowledgeBase
        private set

    // --- Этап 2: Embedding Engine / Vector Memory / Knowledge Index / RAG Engine ---
    lateinit var embeddingEngine: EmbeddingEngine
        private set

    lateinit var vectorMemory: VectorMemory
        private set

    lateinit var knowledgeIndex: KnowledgeIndex
        private set

    lateinit var ragEngine: RagEngine
        private set

    lateinit var memoryConsolidation: com.laylapro.consolidation.MemoryConsolidation
        private set

    lateinit var reflectionEngine: com.laylapro.reflection.ReflectionEngine
        private set

    lateinit var selfImprovementEngine: com.laylapro.selfimprovement.SelfImprovementEngine
        private set

    lateinit var skillEngine: com.laylapro.skill.SkillEngine
        private set

    lateinit var personalityEngine: PersonalityEngine
        private set

    lateinit var emotionEngine: EmotionEngine
        private set

    lateinit var reasoningEngine: ReasoningEngine
        private set

    lateinit var planningEngine: PlanningEngine
        private set

    lateinit var promptEngine: PromptEngine
        private set

    lateinit var modelRouter: ModelRouter
        private set

    lateinit var infrastructureLayer: InfrastructureLayer
        private set

    lateinit var learningSystem: LearningSystem
        private set

    lateinit var securityLayer: SecurityLayer
        private set

    lateinit var conversationEngine: ConversationEngine
        private set

    lateinit var deviceControlLayer: DeviceControlLayer
        private set

    lateinit var aiCore: AICore
        private set

    /** Том 98: единственная точка входа для UI и всех внешних вызовов в систему. */
    lateinit var runtimeManager: RuntimeManager
        private set

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()

        // Слой 7: Безопасность — хранилище ключей (Android Keystore) поднимается первым
        secureStorage = SecureStorage(this)

        // Слой 6: API Layer — облачный провайдер (Anthropic Claude по умолчанию)
        val anthropicClient = AnthropicApiClient(secureStorage)
        apiLayer = anthropicClient
        anthropicApiClient = anthropicClient // Патч 4: тот же экземпляр, конкретный тип — см. KDoc поля выше

        // Том 98: ядро Runtime Core поднимается ДО AI Core, чтобы Planning Engine
        // мог реально вызывать DeviceControl/AndroidIntegration через Function Calling
        // с первого же сообщения пользователя (единственный путь — через Dispatcher).
        val moduleRegistry = ModuleRegistry()
        val recoveryManager = RecoveryManager()
        val dispatcher = Dispatcher(recoveryManager, moduleRegistry)
        registerDeviceControlModule(dispatcher, moduleRegistry)
        registerAndroidIntegrationModule(dispatcher, moduleRegistry)
        val toolExecutor: ToolExecutor = DispatcherToolExecutor(dispatcher)

        // Слой 2: Память и обучение
        memorySystem = InMemoryMemorySystem()
        knowledgeBase = InMemoryKnowledgeBase()

        // Этап 2: Embedding Engine (feature hashing — честная локальная заглушка под
        // будущую нейросетевую модель, см. TODO в EmbeddingEngine.kt) -> Vector Memory
        // (brute-force cosine similarity) -> Knowledge Index (гибрид вектор+keyword) ->
        // RAG Engine (пороговая фильтрация для AICoreImpl).
        embeddingEngine = HashingEmbeddingEngine()
        vectorMemory = InMemoryVectorMemory(embeddingEngine)
        knowledgeIndex = HybridKnowledgeIndex(vectorMemory, knowledgeBase)
        ragEngine = DefaultRagEngine(knowledgeIndex)
        learningSystem = LearningSystemImpl()

        // Слой 3: Личность и эмоции
        personalityEngine = PersonalityEngineImpl()
        emotionEngine = EmotionEngineImpl()

        // Модуль 39 (Local LLM Runtime) — пока стаб; Model Router уже готов его подключить,
        // как только isLocalModelAvailable() начнёт возвращать true (см. Blueprint, Этап 6).
        infrastructureLayer = NotAvailableInfrastructureLayer()

        // Общий ResourceManager — переиспользуется и Model Router'ом (ниже), и Runtime Core
        // (передаётся в RuntimeManager при его создании), чтобы не опрашивать
        // ActivityManager/BatteryManager дважды независимо друг от друга.
        val resourceManager = ResourceManager(this)

        // Модуль 10 (Model Router) — выбирает движок под категорию задачи с учётом
        // ресурсов устройства, доступности, стоимости, скорости, приватности и
        // предпочтений пользователя (см. router/ModelRouter.kt).
        modelRouter = ModelRouterFactory.create(
            cloudApiLayer = apiLayer,
            hasApiKey = { secureStorage.getApiKey() != null },
            isLocalModelAvailable = { infrastructureLayer.isLocalModelAvailable() },
            resourceProvider = {
                val snap = resourceManager.snapshot()
                DeviceResources(
                    availableRamMb = snap.availableRamBytes / (1024 * 1024),
                    batteryPercent = snap.batteryPercent,
                    isCharging = snap.isCharging,
                    isOnline = isNetworkOnline(),
                )
            },
        )

        // Этап 2, пункты 5-8: Memory Consolidation -> Reflection Engine -> Self-Improvement
        // Engine -> Skill Engine. Вынесены сюда (а не рядом с RAG Engine выше), т.к. зависят
        // от modelRouter, который создаётся только что.
        memoryConsolidation = com.laylapro.consolidation.LlmMemoryConsolidation(memorySystem, knowledgeIndex, modelRouter)
        reflectionEngine = com.laylapro.reflection.LlmReflectionEngine(modelRouter)
        selfImprovementEngine = com.laylapro.selfimprovement.LearningSystemSelfImprovement(learningSystem)
        skillEngine = com.laylapro.skill.EmbeddingBackedSkillEngine(embeddingEngine)

        // Модуль 11 (Prompt Engine) — единственное место, где собирается системный промпт.
        promptEngine = PromptEngineImpl(personalityEngine)

        // Слой 1: Рассуждение и планирование — обе используют Model Router, а не
        // конкретный ApiLayer напрямую (см. ReasoningEngineImpl/PlanningEngineImpl).
        reasoningEngine = ReasoningEngineImpl(modelRouter)
        planningEngine = PlanningEngineImpl(modelRouter)

        // Слой 7: Interceptor опасных действий
        securityLayer = SecurityLayerImpl()

        // Слой 3: Диалоговые сессии
        conversationEngine = ConversationEngineImpl(memorySystem)

        // Слой 1: Оркестратор всей системы
        aiCore = AICoreImpl(
            reasoningEngine = reasoningEngine,
            planningEngine = planningEngine,
            memorySystem = memorySystem,
            personalityEngine = personalityEngine,
            emotionEngine = emotionEngine,
            conversationEngine = conversationEngine,
            promptEngine = promptEngine,
            modelRouter = modelRouter,
            learningSystem = learningSystem,
            toolExecutor = toolExecutor,
            ragEngine = ragEngine,
        )

        // Слой 7: телеметрия — запускаем сразу, публикует события в EventBus
        MonitoringLayer(this).start()

        // Том 98: AI Core Runtime Architecture — сердце системы. Переиспользует тот же
        // Dispatcher/ModuleRegistry/RecoveryManager, что и toolExecutor выше, — иначе
        // регистрация DeviceControl/AndroidIntegration осталась бы в "параллельной" системе.
        // resourceManager тоже переиспользуется (см. Model Router выше).
        runtimeManager = RuntimeManager(
            context = this,
            aiCore = aiCore,
            modelRouter = modelRouter,
            moduleRegistry = moduleRegistry,
            recoveryManager = recoveryManager,
            dispatcher = dispatcher,
            resourceManager = resourceManager,
            memoryConsolidation = memoryConsolidation,
            reflectionEngine = reflectionEngine,
            selfImprovementEngine = selfImprovementEngine,
            skillEngine = skillEngine,
        )
        runtimeManager.start(applicationScope)
    }

    /** Простая проверка "есть ли активная сеть" для Model Router (DeviceResources.isOnline). */
    private fun isNetworkOnline(): Boolean {
        val cm = getSystemService(android.net.ConnectivityManager::class.java) ?: return true
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Модуль 12 (Device Control Layer) — регистрация в Dispatcher/Module Registry. */
    private fun registerDeviceControlModule(dispatcher: Dispatcher, moduleRegistry: ModuleRegistry) {
        deviceControlLayer = DeviceControlLayerImpl(this)

        dispatcher.register("DeviceControl") { command ->
            try {
                when (command.action) {
                    "set_wifi_state" -> {
                        val enabled = command.params["enabled"] as? Boolean ?: true
                        deviceControlLayer.setWifiState(enabled)
                        CommandResult(success = true, output = "wifi_panel_opened(enabled=$enabled)")
                    }
                    "open_bluetooth_settings" -> {
                        deviceControlLayer.openBluetoothSettings()
                        CommandResult(success = true, output = "bluetooth_settings_opened")
                    }
                    "open_sound_settings" -> {
                        deviceControlLayer.openSoundSettings()
                        CommandResult(success = true, output = "sound_settings_opened")
                    }
                    else -> CommandResult(success = false, error = "Неизвестное действие '${command.action}' для DeviceControl")
                }
            } catch (e: Exception) {
                CommandResult(success = false, error = e.message ?: "Ошибка DeviceControl")
            }
        }

        moduleRegistry.register(
            ModuleDescriptor(
                moduleId = "DeviceControl",
                moduleName = "Device Control Layer",
                capabilities = listOf("wifi", "bluetooth", "sound"),
                state = ModuleState.RUNNING,
            )
        )
    }

    /** Модуль 13 (Android Integration Layer / Accessibility Service) — регистрация. */
    private fun registerAndroidIntegrationModule(dispatcher: Dispatcher, moduleRegistry: ModuleRegistry) {
        dispatcher.register("AndroidIntegration") { command: Command ->
            when (command.action) {
                "click_by_text" -> {
                    val text = command.params["text"] as? String
                    val service = LaylaAccessibilityService.instance
                    when {
                        text.isNullOrBlank() ->
                            CommandResult(success = false, error = "Параметр 'text' обязателен для click_by_text")
                        service == null ->
                            CommandResult(
                                success = false,
                                error = "Accessibility Service LaylaPro не включён пользователем в " +
                                    "Настройки -> Специальные возможности на устройстве",
                            )
                        else -> {
                            val clicked = service.performClickByText(text)
                            if (clicked) {
                                CommandResult(success = true, output = "clicked: $text")
                            } else {
                                CommandResult(success = false, error = "Элемент с текстом '$text' не найден или не кликабелен")
                            }
                        }
                    }
                }
                else -> CommandResult(success = false, error = "Неизвестное действие '${command.action}' для AndroidIntegration")
            }
        }

        moduleRegistry.register(
            ModuleDescriptor(
                moduleId = "AndroidIntegration",
                moduleName = "Android Integration Layer",
                capabilities = listOf("click", "scroll"),
                state = ModuleState.RUNNING,
            )
        )
    }
}
