package com.laylapro.agent

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Реестр специализированных агентов — плагин-паттерн: новые агенты подключаются
 * через [register] в рантайме (или при инициализации приложения), без изменения
 * [AgentCoordinator] или уже зарегистрированных агентов (OCP).
 */
class AgentRegistry {

    private val agents = CopyOnWriteArrayList<SpecializedAgent>()

    fun register(agent: SpecializedAgent) {
        agents.removeIf { it.id == agent.id }
        agents.add(agent)
    }

    fun unregister(agentId: String) {
        agents.removeIf { it.id == agentId }
    }

    fun all(): List<SpecializedAgent> = agents.toList()

    fun byCapability(capability: AgentCapability): List<SpecializedAgent> =
        agents.filter { capability in it.capabilities }

    fun byId(agentId: String): SpecializedAgent? = agents.find { it.id == agentId }
}
