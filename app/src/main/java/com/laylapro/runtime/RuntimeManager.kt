package com.laylapro.runtime

import android.content.Context
import com.laylapro.core.AICore
import com.laylapro.core.CoreResponse
import com.laylapro.core.InputPayload
import com.laylapro.core.UserInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Runtime Manager (Том 98 «Runtime Core Architecture») — "единственная точка входа
 * после запуска приложения". Собирает воедино все компоненты Runtime Core:
 * Module Registry, Dispatcher, Event Bus (глобальный), Runtime State Machine,
 * Task Manager/Scheduler, Workflow Engine, Context/Session/Resource/Permission/
 * Health/Logging Manager, Recovery Manager, WatchDog.
 *
 * Ни UI, ни один модуль не должны обращаться к AICore или друг к другу напрямую —
 * только через [dispatcher] (Command/CommandResult) или через публичные методы
 * этого класса.
 */
class RuntimeManager(
    context: Context,
    private val aiCore: AICore,
    /** Нужен Consensus Engine (см. агентный блок ниже) для повторного рассуждения при расхождении агентов. */
    private val modelRouter: com.laylapro.router.ModelRouter,
    val moduleRegistry: ModuleRegistry = ModuleRegistry(),
    val recoveryManager: RecoveryManager = RecoveryManager(),
    val dispatcher: Dispatcher = Dispatcher(recoveryManager, moduleRegistry),
    /** Позволяет переиспользовать тот же ResourceManager, что уже используется
     * в [com.laylapro.router.ModelRouter] (общий снимок ресурсов устройства вместо
     * двух независимых опросов ActivityManager/BatteryManager). */
    val resourceManager: ResourceManager = ResourceManager(context),
    /** Этап 2, пункты 5-8 — опциональны (nullable) по тому же принципу, что и
     * `AICoreImpl.ragEngine`: отсутствие любого из них не ломает Runtime Core,
     * просто соответствующая реактивная обвязка не создаётся (Liskov-совместимая деградация). */
    private val memoryConsolidation: com.laylapro.consolidation.MemoryConsolidation? = null,
    private val reflectionEngine: com.laylapro.reflection.ReflectionEngine? = null,
    private val selfImprovementEngine: com.laylapro.selfimprovement.SelfImprovementEngine? = null,
    private val skillEngine: com.laylapro.skill.SkillEngine? = null,
) {
    // --- Ядро Runtime Core ---
    val stateMachine = StateMachine()
    val watchDog = WatchDog(recoveryManager, moduleRegistry, onModuleUnresponsive = { module ->
        loggingManager.error("WatchDog", "Модуль '$module' не отвечает — перевод в RECOVERY")
        stateMachine.transition(RuntimeState.RECOVERY)
    })

    // --- Task Manager / Scheduler / Workflow ---
    val taskQueue = TaskQueue()
    val scheduler = Scheduler(taskQueue)
    val workflowEngine = WorkflowEngine(dispatcher)
    val sharedContext = SharedContext()

    // --- Остальные менеджеры Тома 98 ---
    val contextManager = ContextManager()
    val sessionManager = SessionManager()
    val permissionManager = PermissionManager(context)
    val healthManager = HealthManager()
    val loggingManager = LoggingManager()

    /**
     * Файловый журнал фоновых задач (см. PersistentTaskLog.kt) — по мотивам
     * maildir-паттерна из TaskMgr в архитектуре Layla. Только для приоритета
     * [TaskPriority.BACKGROUND]; обычный чат идёт через [taskQueue] в памяти.
     */
    val persistentTaskLog = PersistentTaskLog(context)

    // --- Модуль 3/5 (Agent Framework / Goal Manager / Mission Manager) — многоагентная
    // система поверх AICore, масштабируемая на десятки/сотни агентов без изменения кода. ---

    // Долгоживущий scope для компонентов, которые слушают Event Bus постоянно (не только
    // во время конкретного pursueGoal-вызова) — meta-компоненты и World Model должны
    // начать слушать события сразу при создании, а не только после start().
    private val metaScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)

    val goalManager = com.laylapro.agent.GoalManager()
    val missionManager = com.laylapro.agent.MissionManager(goalManager)
    val agentRegistry = com.laylapro.agent.AgentRegistry()
    val capabilityManager = com.laylapro.agent.CapabilityManager(agentRegistry)
    val goalDecomposer: com.laylapro.agent.GoalDecomposer = com.laylapro.agent.KeywordGoalDecomposer
    val agentCapacityManager = com.laylapro.agent.AgentCapacityManager()
    val consensusEngine: com.laylapro.agent.ConsensusEngine = com.laylapro.agent.DefaultConsensusEngine(modelRouter)

    // Пять частей бывшего единого "MetaAgent" (см. пакет agent.meta): Performance/Failure
    // Analyzer только измеряют (реактивно, через Event Bus), Learning Manager сглаживает,
    // Strategy Optimizer — единственный, кто действует, Meta Supervisor их оркестрирует.
    val performanceAnalyzer = com.laylapro.agent.meta.PerformanceAnalyzer(metaScope)
    val failureAnalyzer = com.laylapro.agent.meta.FailureAnalyzer(metaScope)
    private val learningManager = com.laylapro.agent.meta.LearningManager(metaScope)
    val strategyOptimizer = com.laylapro.agent.meta.StrategyOptimizer(
        performanceAnalyzer, learningManager, agentCapacityManager, loggingManager,
    )
    val metaSupervisor = com.laylapro.agent.meta.MetaSupervisor(
        metaScope, agentRegistry, performanceAnalyzer, failureAnalyzer, strategyOptimizer,
    )

    // World Model — единый источник состояния, узнаёт обо всём ТОЛЬКО через Event Bus,
    // без единой прямой ссылки на GoalManager/MissionManager/AgentCoordinator (см. WorldModel.kt).
    val worldModel = com.laylapro.agent.WorldModel(metaScope)

    val agentCoordinator = com.laylapro.agent.AgentCoordinator(
        agentRegistry = agentRegistry,
        goalManager = goalManager,
        missionManager = missionManager,
        loggingManager = loggingManager,
        capabilityManager = capabilityManager,
        goalDecomposer = goalDecomposer,
        capacityManager = agentCapacityManager,
        consensusEngine = consensusEngine,
        strategyOptimizer = strategyOptimizer,
    )

    // --- Этап 2, пункты 5/6/8: реактивные обёртки над Event Bus (тот же паттерн,
    // что и meta-компоненты выше) — каждая создаётся только если соответствующий
    // "движок" реально передан (см. KDoc конструктора про nullable-деградацию). ---
    private val memoryConsolidationService = memoryConsolidation?.let {
        com.laylapro.consolidation.MemoryConsolidationService(metaScope, it, loggingManager)
    }

    private val reflectionService = if (reflectionEngine != null && selfImprovementEngine != null) {
        com.laylapro.reflection.ReflectionService(metaScope, reflectionEngine, selfImprovementEngine, goalManager, loggingManager)
    } else null

    private val skillRecordingService = skillEngine?.let {
        com.laylapro.skill.SkillRecordingService(metaScope, it, goalManager, loggingManager)
    }

    // Гарантирует, что обработка сообщений идёт по одному (MVP: без реального
    // параллелизма задач), сохраняя корректность конечного автомата состояний.
    private val processingLock = Mutex()

    companion object {
        private const val MODULE_AI_CORE = "AICore"
    }

    init {
        registerCoreModules()

        // AI Core регистрируется в Dispatcher под именем "AICore" — единственный
        // разрешённый способ до него достучаться (см. requirement "запрещается
        // вызывать методы других модулей напрямую").
        dispatcher.register(MODULE_AI_CORE) { command ->
            val sessionId = command.params["sessionId"] as? String ?: "default"
            val text = command.params["text"] as? String ?: ""
            val started = System.currentTimeMillis()
            val response = aiCore.processInput(UserInput(sessionId = sessionId, payload = InputPayload.Text(text)))
            healthManager.reportSimple(MODULE_AI_CORE, health = 1.0f, latencyMs = System.currentTimeMillis() - started)
            CommandResult(success = !response.isError, output = response, error = if (response.isError) response.text else null)
        }

        watchDog.register(MODULE_AI_CORE) {
            // Лёгкая проверка "пульса" без реального сетевого запроса.
            aiCore.eventBus.replayCache
            true
        }

        // Заглушки здоровья для остальных модулей ТЗ, которые пока не реализованы
        // как отдельные объекты с собственным жизненным циклом (Memory/Planning/
        // Reasoning и т.д. — это synchronous-объекты внутри AICoreImpl). Как только
        // они станут независимыми сервисами — здесь регистрируется реальный check.
        for (module in WatchDog.CORE_MONITORED_MODULES) {
            if (module != MODULE_AI_CORE) {
                watchDog.register(module) { true }
            }
        }

        stateMachine.transition(RuntimeState.INITIALIZING)

        // Модуль 3 (Agent Framework): регистрируем стандартный набор специализированных
        // агентов. Новый агент подключается ОДНОЙ строкой здесь (или динамически позже
        // через agentRegistry.register(...)) — без изменения AgentCoordinator (OCP).
        agentRegistry.register(com.laylapro.agent.ConversationalAgent(aiCore, goalManager, loggingManager))
        agentRegistry.register(com.laylapro.agent.AutomationAgent(aiCore, goalManager, loggingManager))

        stateMachine.transition(RuntimeState.READY)
        loggingManager.info("RuntimeManager", "Runtime Core инициализирован, состояние READY")
    }

    private fun registerCoreModules() {
        val descriptors = listOf(
            ModuleDescriptor(MODULE_AI_CORE, "AI Core", priority = TaskPriority.CRITICAL, capabilities = listOf("chat", "reasoning", "planning")),
            ModuleDescriptor("Memory", "Memory System", dependencies = listOf(MODULE_AI_CORE)),
            ModuleDescriptor("Planning", "Planning Engine", dependencies = listOf(MODULE_AI_CORE)),
            ModuleDescriptor("Reasoning", "Reasoning Engine", dependencies = listOf(MODULE_AI_CORE)),
            ModuleDescriptor("Voice", "Voice Engine", capabilities = listOf("stt", "tts")),
            ModuleDescriptor("Vision", "Vision Engine", capabilities = listOf("screen_analysis")),
            ModuleDescriptor("DeviceControl", "Device Control Layer", capabilities = listOf("wifi", "bluetooth", "sound")),
            ModuleDescriptor("AndroidIntegration", "Android Integration Layer", capabilities = listOf("click", "scroll")),
            ModuleDescriptor("ApiLayer", "API Layer", priority = TaskPriority.HIGH),
            ModuleDescriptor("Plugins", "Plugin System"),
            ModuleDescriptor("Synchronization", "Synchronization Layer", priority = TaskPriority.LOW),
            ModuleDescriptor("Monitoring", "Monitoring Layer"),
            ModuleDescriptor("Security", "Security Layer", priority = TaskPriority.HIGH),
        )
        descriptors.forEach {
            it.state = ModuleState.RUNNING
            moduleRegistry.register(it)
        }
    }

    fun start(scope: CoroutineScope = CoroutineScope(Dispatchers.Default)) {
        watchDog.start(scope)
        sessionManager.create()
        recoverPendingBackgroundTasks()
    }

    /**
     * После падения процесса/перезапуска устройства фоновые задачи, которые остались
     * в `pending/` на диске, возвращаются в очередь — они не должны бесследно исчезать
     * только потому, что приложение было убито системой (см. PersistentTaskLog.kt).
     */
    private fun recoverPendingBackgroundTasks() {
        val recovered = persistentTaskLog.listPending()
        if (recovered.isEmpty()) return
        loggingManager.warning("RuntimeManager", "Восстановлено ${recovered.size} фоновых задач(и) с диска после перезапуска")
        recovered.forEach { scheduler.submit(it) }
    }

    /**
     * Вход для задач с приоритетом BACKGROUND — в отличие от [submitUserMessage],
     * не блокирует UI и переживает смерть процесса (см. [persistentTaskLog]).
     * `targetModule`/`action` — тот же контракт, что и в [Dispatcher.dispatch].
     */
    suspend fun submitBackgroundTask(
        name: String,
        targetModule: String,
        action: String,
        params: Map<String, Any?>,
        sessionId: String = "background",
    ): CommandResult {
        val task = Task(
            sessionId = sessionId,
            name = name,
            description = "$targetModule.$action",
            type = TaskType.BACKGROUND_JOB,
            priority = TaskPriority.BACKGROUND,
            ownerModule = targetModule,
            context = (params + mapOf("action" to action)).toMutableMap(),
        )
        persistentTaskLog.writePending(task)
        scheduler.submit(task)

        val picked = scheduler.nextTask()
            ?: return CommandResult(success = false, error = "Очередь фоновых задач занята, задача осталась в pending/")

        picked.status = TaskStatus.RUNNING
        picked.startedAt = System.currentTimeMillis()

        val result = dispatcher.dispatch(Command(targetModule = targetModule, action = action, params = params))
        scheduler.markFinished()
        picked.finishedAt = System.currentTimeMillis()

        if (result.success) {
            picked.status = TaskStatus.SUCCESS
            persistentTaskLog.moveToCompleted(task.id, result.output?.toString() ?: "ok")
        } else {
            picked.status = TaskStatus.FAILED
            persistentTaskLog.moveToError(task.id, result.error ?: "unknown error")
        }

        return result
    }

    fun stop() {
        stateMachine.transition(RuntimeState.SHUTDOWN)
        watchDog.stop()
        // MJ1 (независимый аудит реализации): metaScope держит 8 долгоживущих подписчиков
        // Event Bus (PerformanceAnalyzer, FailureAnalyzer, LearningManager, MetaSupervisor,
        // WorldModel, MemoryConsolidationService, ReflectionService, SkillRecordingService).
        // Без явной отмены они продолжали бы слушать события бесконечно после "остановки" рантайма.
        metaScope.cancel()
    }

    /**
     * Основной вход для текстовых/голосовых сообщений пользователя.
     * Реализует типовой цикл из Тома 98:
     * READY -> LISTENING -> UNDERSTANDING -> REASONING -> PLANNING -> EXECUTING -> RESPONDING -> READY
     */
    /**
     * Вход для МНОГОШАГОВЫХ целей (Agent Framework, модуль 3) — в отличие от
     * [submitUserMessage] (один цикл LISTENING->...->READY на одно сообщение),
     * здесь [agentCoordinator] может прогнать несколько таких циклов подряд через
     * выбранного специализированного агента, пока цель не будет явно завершена или
     * не закончится лимит шагов. Использовать для "сделай X (и потом Y, и потом Z)"
     * сценариев, а не для обычной реплики в чате.
     */
    suspend fun pursueGoal(
        sessionId: String,
        description: String,
        maxSteps: Int = 6,
        priority: com.laylapro.agent.GoalPriority = com.laylapro.agent.GoalPriority.NORMAL,
        missionId: java.util.UUID? = null,
        requiresConsensus: Boolean = false,
    ) = agentCoordinator.pursueGoal(sessionId, description, maxSteps, priority, missionId, requiresConsensus)

    suspend fun submitUserMessage(sessionId: String, text: String): CoreResponse = processingLock.withLock {
        sessionManager.switchTo(sessionId)
        contextManager.setLastUserAction("user_message: $text")
        contextManager.appendDialogHistory("USER: $text")
        loggingManager.info("RuntimeManager", "Новая задача от пользователя (session=$sessionId)")

        val task = Task(
            sessionId = sessionId,
            name = "user_message",
            description = text.take(120),
            type = TaskType.USER_MESSAGE,
            priority = TaskPriority.HIGH,
            ownerModule = MODULE_AI_CORE,
            context = mutableMapOf("sessionId" to sessionId, "text" to text),
        )
        scheduler.submit(task)
        sharedContext.setRemainingSteps(task.id, listOf("understanding", "reasoning", "planning", "response"))
        contextManager.setActiveTaskIds(listOf(task.id.toString()))

        stateMachine.transition(RuntimeState.LISTENING)

        val picked = scheduler.nextTask()
        if (picked == null) {
            stateMachine.transition(RuntimeState.WAITING)
            return@withLock CoreResponse(sessionId, "Система занята, попробуйте ещё раз через секунду.", isError = true)
        }

        picked.status = TaskStatus.RUNNING
        picked.startedAt = System.currentTimeMillis()

        stateMachine.transition(RuntimeState.UNDERSTANDING)
        picked.currentStep = "understanding"
        stateMachine.transition(RuntimeState.REASONING)
        picked.currentStep = "reasoning"
        sharedContext.markStepCompleted(task.id, "understanding")
        stateMachine.transition(RuntimeState.PLANNING)
        picked.currentStep = "planning"
        sharedContext.markStepCompleted(task.id, "reasoning")
        stateMachine.transition(RuntimeState.EXECUTING)
        picked.currentStep = "executing"
        sharedContext.markStepCompleted(task.id, "planning")

        // Единственный разрешённый способ дойти до AI Core — через Dispatcher.
        val result = dispatcher.dispatch(
            Command(
                targetModule = MODULE_AI_CORE,
                action = "processInput",
                params = picked.context,
                // C1 (независимый аудит реализации): AICoreImpl.processInput() делает МИНИМУМ
                // три последовательных вызова модели (Reasoning -> Planning -> финальный ответ),
                // плюс возможные вложенные dispatch() на реальные действия устройства — дефолтных
                // 15 секунд Command.timeoutMs хватает на ОДИН вызов, но не на весь конвейер.
                // 60 секунд — запас под три вызова по 15-18 сек каждый в худшем случае.
                timeoutMs = 60_000,
            )
        )

        scheduler.markFinished()
        picked.finishedAt = System.currentTimeMillis()

        val response = result.output as? CoreResponse
            ?: CoreResponse(sessionId, result.error ?: "Неизвестная ошибка AI Core", isError = true)

        if (result.success) {
            picked.status = TaskStatus.SUCCESS
            sharedContext.markStepCompleted(task.id, "response")
            contextManager.appendDialogHistory("LAYLA: ${response.text}")
            loggingManager.info("RuntimeManager", "Задача ${task.id} выполнена успешно")
        } else {
            picked.status = TaskStatus.FAILED
            picked.retryCount += 1
            sharedContext.recordError(task.id, result.error ?: "unknown error")
            loggingManager.error("RuntimeManager", "Задача ${task.id} провалилась: ${result.error}")
        }

        // Проверка ресурсов — Resource Manager может решить выгрузить кэш/модели
        // после тяжёлой операции, прежде чем систему переведут обратно в READY.
        resourceManager.checkAndAct()

        stateMachine.transition(RuntimeState.RESPONDING)
        sharedContext.clear(task.id)
        contextManager.setActiveTaskIds(emptyList())
        stateMachine.transition(RuntimeState.READY)

        response
    }
}
