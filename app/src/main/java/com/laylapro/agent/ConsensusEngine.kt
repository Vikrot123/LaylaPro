package com.laylapro.agent

import com.laylapro.core.CoreEvent
import com.laylapro.core.EventBus
import com.laylapro.router.ModelRouter
import com.laylapro.router.TaskCategory
import kotlinx.coroutines.CancellationException

/**
 * Consensus Engine — сравнивает и согласовывает результаты нескольких агентов вместо
 * наивного "склеить тексты". Различает два принципиально разных сценария объединения:
 *
 * 1. [MergeMode.INDEPENDENT_SUBGOALS] — подцели после [GoalDecomposer] отвечают на
 *    РАЗНЫЕ части составной цели (например, "включи Wi-Fi" и "найди билеты на самолёт").
 *    Здесь конфликта по определению нет — просто объединяем с атрибуцией, без LLM.
 * 2. [MergeMode.REDUNDANT_CONSENSUS] — несколько агентов независимо решали ОДНУ И ТУ ЖЕ
 *    цель (см. [Goal.requiresConsensus]), и их ответы нужно СРАВНИТЬ: если совпадают —
 *    просто взять один; если расходятся — инициировать повторное рассуждение (ещё один
 *    вызов модели, которому явно показаны оба варианта и предложено разрешить
 *    противоречие), а не молча выбрать случайный.
 */
enum class MergeMode { INDEPENDENT_SUBGOALS, REDUNDANT_CONSENSUS }

interface ConsensusEngine {
    suspend fun reconcile(goal: Goal, outcomes: List<GoalOutcome>, mode: MergeMode): GoalOutcome
}

class DefaultConsensusEngine(private val modelRouter: ModelRouter) : ConsensusEngine {

    override suspend fun reconcile(goal: Goal, outcomes: List<GoalOutcome>, mode: MergeMode): GoalOutcome {
        if (outcomes.isEmpty()) {
            return GoalOutcome(goal.id.toString(), "none", "Нет результатов для согласования", GoalStatus.FAILED, 0)
        }
        if (outcomes.size == 1) return outcomes.single()

        return when (mode) {
            MergeMode.INDEPENDENT_SUBGOALS -> concatenate(goal, outcomes)
            MergeMode.REDUNDANT_CONSENSUS -> reconcileConsensus(goal, outcomes)
        }
    }

    /** Разные части одной составной цели — не конфликт, просто объединяем с атрибуцией по агенту. */
    private fun concatenate(goal: Goal, outcomes: List<GoalOutcome>): GoalOutcome {
        val allCompleted = outcomes.all { it.status == GoalStatus.COMPLETED }
        val anyCompleted = outcomes.any { it.status == GoalStatus.COMPLETED }
        val combinedText = outcomes.joinToString("\n\n") { "[${it.handledBy}] ${it.finalText}" }

        return GoalOutcome(
            goalId = goal.id.toString(),
            handledBy = outcomes.joinToString(",") { it.handledBy },
            finalText = combinedText,
            status = if (allCompleted || anyCompleted) GoalStatus.COMPLETED else GoalStatus.FAILED,
            stepsTaken = outcomes.sumOf { it.stepsTaken },
        )
    }

    private suspend fun reconcileConsensus(goal: Goal, outcomes: List<GoalOutcome>): GoalOutcome {
        val statusesDiffer = outcomes.map { it.status }.distinct().size > 1
        val textsDiffer = !statusesDiffer && wordOverlapSimilarity(outcomes) < CONSENSUS_SIMILARITY_THRESHOLD

        if (!statusesDiffer && !textsDiffer) {
            // Все агенты по сути согласны — берём наиболее содержательный (больше шагов = больше проверок сделал).
            return outcomes.maxByOrNull { it.stepsTaken }!!
        }

        EventBus.tryPublish(
            CoreEvent.ConsensusConflictDetected(goal.id.toString(), outcomes.joinToString(",") { it.handledBy })
        )

        val comparisonPrompt = buildString {
            appendLine("Несколько независимых агентов решали одну и ту же задачу: \"${goal.description}\".")
            appendLine("Их ответы разошлись. Сравни варианты, определи, есть ли между ними противоречие,")
            appendLine("и дай ОДИН согласованный финальный ответ пользователю.")
            outcomes.forEachIndexed { i, o -> appendLine("Вариант ${i + 1} (агент ${o.handledBy}, статус ${o.status}): ${o.finalText}") }
        }

        val reconciled = try {
            val routing = modelRouter.route(TaskCategory.REASONING)
            routing.apiLayer.complete(
                systemPrompt = "Ты — Consensus Engine: сверяешь ответы нескольких AI-агентов и разрешаешь противоречия.",
                userMessage = comparisonPrompt,
                maxTokens = 600,
            )
        } catch (e: CancellationException) {
            throw e // Патч 1: не проглатывать отмену корутины вместе с обычными ошибками
        } catch (e: Exception) {
            // Деградация: если повторное рассуждение недоступно (нет сети/движка) — берём
            // ответ агента с максимальным числом подтверждённых шагов, как наименее рискованный выбор.
            outcomes.maxByOrNull { it.stepsTaken }!!.finalText
        }

        EventBus.tryPublish(CoreEvent.ConsensusReconciled(goal.id.toString(), reconciled.take(200)))

        return GoalOutcome(
            goalId = goal.id.toString(),
            handledBy = "consensus(${outcomes.joinToString(",") { it.handledBy }})",
            finalText = reconciled,
            status = GoalStatus.COMPLETED,
            stepsTaken = outcomes.sumOf { it.stepsTaken },
        )
    }

    /** Простая оценка похожести без ML — доля общих слов между первым и остальными ответами. */
    private fun wordOverlapSimilarity(outcomes: List<GoalOutcome>): Float {
        val base = outcomes.first().finalText.lowercase().split(Regex("\\W+")).filter { it.length > 3 }.toSet()
        if (base.isEmpty()) return 1f
        val similarities = outcomes.drop(1).map { outcome ->
            val other = outcome.finalText.lowercase().split(Regex("\\W+")).filter { it.length > 3 }.toSet()
            if (other.isEmpty()) 0f else (base.intersect(other).size.toFloat() / base.union(other).size)
        }
        return similarities.minOrNull() ?: 1f
    }

    companion object {
        private const val CONSENSUS_SIMILARITY_THRESHOLD = 0.3f
    }
}
