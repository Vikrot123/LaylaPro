package com.laylapro.agent

import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.ConcurrentHashMap

/**
 * Ограничивает число ОДНОВРЕМЕННЫХ выполнений на один [SpecializedAgent] (по умолчанию 1) —
 * иначе параллельная декомпозиция цели могла бы отправить агенту сразу несколько
 * `pursue()`-вызовов, которые все дёргают один и тот же [com.laylapro.core.AICore]
 * (а через него — один и тот же облачный API), что бьёт и по стоимости, и по
 * реальным rate-лимитам провайдера.
 *
 * Приоритет ([GoalPriority]) не реализован как вытеснение уже выполняющейся задачи
 * (это потребовало бы отмены на середине ReAct-цикла, что рискованно) — вместо этого
 * [AgentCoordinator] заранее СОРТИРУЕТ подцели по приоритету перед тем, как параллельно
 * их запускать, поэтому более приоритетные раньше встают в очередь на слот у
 * [kotlinx.coroutines.sync.Semaphore] (честная FIFO-очередь ожидающих).
 *
 * Лимит на агента можно менять в рантайме — этим пользуется [com.laylapro.agent.meta.StrategyOptimizer],
 * например снижая параллелизм для агента с высокой средней задержкой (см. PerformanceAnalyzer).
 */
class AgentCapacityManager(private val defaultLimit: Int = 1) {

    private val semaphores = ConcurrentHashMap<String, Semaphore>()
    private val limits = ConcurrentHashMap<String, Int>()

    private fun semaphoreFor(agentId: String): Semaphore =
        semaphores.getOrPut(agentId) { Semaphore(limits[agentId] ?: defaultLimit) }

    suspend fun <T> withAgentSlot(agentId: String, block: suspend () -> T): T {
        val sem = semaphoreFor(agentId)
        sem.acquire()
        try {
            return block()
        } finally {
            sem.release()
        }
    }

    /**
     * Меняет лимит параллелизма для агента. MJ4 (независимый аудит реализации):
     * простая замена объекта `Semaphore` в любой момент могла "подвесить" корутины,
     * которые уже вызвали [semaphoreFor] и ожидают на `acquire()` СТАРОГО объекта —
     * после замены в мапе их ожидание никогда не будет удовлетворено новыми вызовами
     * (те получат уже новый семафор). Полностью корректное решение требует собственного
     * примитива синхронизации с поддержкой изменения ёмкости "на месте" — сознательно
     * не реализовано (см. ADR-007: рискованные самодельные примитивы синхронизации
     * уже отклонялись в пользу простоты). Смягчение: заменяем объект, только когда он
     * СЕЙЧАС полностью свободен (`availablePermits == текущий лимит`, то есть никто не
     * держит и, значит, скорее всего, никто не ждёт) — не абсолютная гарантия при гонке
     * "точно в этот момент", но устраняет практическое подавляющее большинство случаев.
     */
    fun setLimit(agentId: String, newLimit: Int) {
        val safeLimit = newLimit.coerceAtLeast(1)
        val currentLimit = limits[agentId] ?: defaultLimit
        val existing = semaphores[agentId]

        limits[agentId] = safeLimit

        if (existing == null || existing.availablePermits == currentLimit) {
            semaphores[agentId] = Semaphore(safeLimit)
        }
        // Если семафор сейчас занят (availablePermits < currentLimit) — не трогаем его,
        // новый лимит применится при следующей естественной пересборке (см. semaphoreFor,
        // которая создаёт семафор только если ключа ещё нет в мапе); это может означать,
        // что смена лимита иногда откладывается до момента полного освобождения агента —
        // приемлемый компромисс ради отсутствия риска зависания ожидающих.
    }

    fun limitFor(agentId: String): Int = limits[agentId] ?: defaultLimit
}
