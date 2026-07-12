package com.laylapro.agent

/**
 * Capability Manager — отдельный от общего [SpecializedAgent.estimateFit] механизм
 * выбора: вместо "насколько агент подходит для ВСЕЙ цели целиком" отвечает на вопрос
 * "какие агенты вообще умеют конкретную способность и насколько хорошо" — то есть
 * работает на уровне [AgentCapability], а не готовой формулировки цели.
 *
 * Используется [AgentCoordinator], когда цель (или подцель после декомпозиции) несёт
 * явное [Goal.requiredCapability] — тогда сначала сужаем пул кандидатов через
 * [candidatesFor], и только внутри этого пула ранжируем по обычному estimateFit.
 * Это и есть масштабирование на десятки/сотни агентов: без Capability Manager
 * координатору пришлось бы каждый раз спрашивать estimateFit у ВСЕХ агентов
 * (O(N) дорогих вызовов на цель), а с ним — сначала дешёвая фильтрация по
 * заранее известным capabilities (O(N) TRIVIAL проверок членства в Set), и только
 * потом (на маленьком подмножестве) полноценная оценка.
 */
data class CapabilityScore(val agent: SpecializedAgent, val capability: AgentCapability, val strength: Float)

class CapabilityManager(private val agentRegistry: AgentRegistry) {

    fun candidatesFor(capability: AgentCapability): List<CapabilityScore> =
        agentRegistry.byCapability(capability)
            .map { CapabilityScore(it, capability, it.capabilityStrength(capability)) }
            .filter { it.strength > 0f }
            .sortedByDescending { it.strength }

    /** Есть ли вообще хоть один агент, способный на данную capability (для быстрой проверки перед декомпозицией). */
    fun hasAnyAgentFor(capability: AgentCapability): Boolean = agentRegistry.byCapability(capability).isNotEmpty()

    /** Полная карта покрытия — какие способности вообще есть в системе и сколько агентов на каждую (для диагностики/UI). */
    fun coverageReport(): Map<AgentCapability, Int> =
        AgentCapability.entries.associateWith { agentRegistry.byCapability(it).size }
}
