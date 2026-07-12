package com.laylapro.agent

/**
 * Модуль 3 (Agent Framework) — Этап 1 архитектуры LaylaPro AI OS.
 *
 * Спроектировано как МНОГОАГЕНТНАЯ система, а не один универсальный агент:
 * [AgentCoordinator] держит открытый реестр [SpecializedAgent] (см. [AgentRegistry]) и
 * для каждой цели спрашивает у КАЖДОГО зарегистрированного агента [SpecializedAgent.estimateFit] —
 * число 0..1, насколько этот агент подходит для данной цели. Побеждает агент с максимальным
 * fit-score. Чтобы добавить нового специализированного агента (например, ResearchAgent для
 * задач, требующих глубокого поиска по памяти/RAG, или CodeAgent для программирования) —
 * достаточно реализовать интерфейс [SpecializedAgent] и зарегистрировать его в
 * [AgentRegistry] — код [AgentCoordinator] при этом не меняется (Open/Closed Principle).
 */
enum class AgentCapability {
    GENERAL_CONVERSATION,
    DEVICE_AUTOMATION,
    RESEARCH_AND_MEMORY,
    CODE_GENERATION,
    CREATIVE_CONTENT,
}

data class GoalOutcome(
    val goalId: String,
    val handledBy: String,
    val finalText: String,
    val status: GoalStatus,
    val stepsTaken: Int,
)

/**
 * Общий контракт любого агента в системе — от простого однопроходного до сложного
 * многошагового. [AgentCoordinator] работает только через этот интерфейс, поэтому
 * реализация конкретного агента полностью инкапсулирована и может быть заменена/
 * дополнена без изменений в остальной системе.
 */
interface SpecializedAgent {
    val id: String
    val capabilities: Set<AgentCapability>

    /**
     * Насколько СИЛЬНО агент владеет конкретной способностью (0..1), а не просто
     * "есть/нет" как в [capabilities]. По умолчанию — 1.0 для заявленных способностей
     * и 0.0 для остальных; агент может переопределить для более тонкой картины
     * (например, "я умею READ_MEMORY, но не так хорошо, как настоящий RAG-агент").
     * Используется [CapabilityManager] для ранжирования кандидатов на конкретную
     * способность, когда это важнее общего [estimateFit] по всей цели целиком.
     */
    fun capabilityStrength(capability: AgentCapability): Float = if (capability in capabilities) 1.0f else 0f

    /**
     * Насколько этот агент подходит для данной цели, от 0.0 (совсем не подходит)
     * до 1.0 (идеальное совпадение). Дешёвая эвристическая оценка — НЕ должна сама
     * вызывать LLM (это было бы слишком дорого делать для каждого агента на каждую
     * цель); полноценное рассуждение происходит только внутри победившего агента.
     */
    fun estimateFit(goal: Goal): Float

    /** Непосредственное исполнение цели — вызывается только агентом-победителем. */
    suspend fun pursue(goal: Goal): GoalOutcome
}
