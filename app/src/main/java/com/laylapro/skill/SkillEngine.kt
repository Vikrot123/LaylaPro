package com.laylapro.skill

import com.laylapro.agent.GoalManager
import com.laylapro.core.CoreEvent
import com.laylapro.core.EventBus
import com.laylapro.embedding.EmbeddingEngine
import com.laylapro.embedding.cosineSimilarity
import com.laylapro.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Skill Engine — Этап 2, пункт 8.
 *
 * Отличие от [com.laylapro.learning.LearningSystem] (Этап 1, "патчи промпта из
 * ИСПРАВЛЕНИЙ пользователя") и от [com.laylapro.agent.meta.LearningManager]
 * (Этап 1, "численный бонус к выбору АГЕНТА"): Skill Engine хранит ПЕРСИСТЕНТНЫЕ,
 * именованные УСПЕШНЫЕ решения конкретных целей — то, что уже сработало, чтобы в
 * будущем похожая цель могла быть узнана по семантической близости и обработана
 * увереннее, а не заново "с нуля".
 *
 * Намеренно переиспользует уже построенный [EmbeddingEngine] (Этап 2, пункт 1) для
 * сопоставления новой цели с сохранёнными навыками — вместо того чтобы городить
 * второй, параллельный механизм похожести текста.
 */
data class Skill(
    val id: String = UUID.randomUUID().toString(),
    val goalDescription: String,
    val stepsSummary: List<String>,
    var usageCount: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
)

interface SkillEngine {
    suspend fun recordSuccess(goalDescription: String, stepsSummary: List<String>)
    suspend fun findSkillFor(goalDescription: String): Skill?
    fun allSkills(): List<Skill>
}

class EmbeddingBackedSkillEngine(
    private val embeddingEngine: EmbeddingEngine,
    private val matchThreshold: Float = 0.6f,
) : SkillEngine {

    private data class StoredSkill(val skill: Skill, val vector: FloatArray)

    private val skills = ConcurrentHashMap<String, StoredSkill>()

    override suspend fun recordSuccess(goalDescription: String, stepsSummary: List<String>) {
        // Если очень похожий навык уже есть — просто увеличиваем счётчик использования,
        // а не плодим почти дублирующиеся записи (иначе allSkills() росла бы неограниченно
        // для часто повторяющихся похожих целей вроде "включи Wi-Fi").
        val existing = findSkillFor(goalDescription)
        if (existing != null) {
            existing.usageCount++
            return
        }

        val vector = embeddingEngine.embed(goalDescription)
        val skill = Skill(goalDescription = goalDescription, stepsSummary = stepsSummary)
        skills[skill.id] = StoredSkill(skill, vector)
    }

    override suspend fun findSkillFor(goalDescription: String): Skill? {
        if (skills.isEmpty()) return null
        val queryVector = embeddingEngine.embed(goalDescription)
        return skills.values
            .map { it to cosineSimilarity(queryVector, it.vector) }
            .filter { it.second >= matchThreshold }
            .maxByOrNull { it.second }
            ?.first?.skill
    }

    override fun allSkills(): List<Skill> = skills.values.map { it.skill }
}

/**
 * Реактивная обёртка: успешные цели записываются в Skill Engine автоматически по
 * [CoreEvent.GoalCompleted] (тот же паттерн подписки, что и в [com.laylapro.agent.meta.PerformanceAnalyzer]).
 *
 * ЧЕСТНАЯ ГРАНИЦА: сейчас [findSkillFor] нигде не вызывается синхронно из
 * [com.laylapro.planning.PlanningEngine] — навыки накапливаются, но ещё не влияют на
 * планирование новых целей. Это осознанно отложенная точка расширения (см. Blueprint
 * Этапа 1, принцип "не создавать параллельных механизмов там, где уже есть..." —
 * подключение Skill Engine к Planning Engine потребует отдельного продуманного решения
 * о том, как именно найденный навык должен влиять на TaskGraph, а не поспешной интеграции).
 */
class SkillRecordingService(
    scope: CoroutineScope,
    private val skillEngine: SkillEngine,
    private val goalManager: GoalManager,
    private val loggingManager: Logger,
) {
    init {
        EventBus.events.filterIsInstance<CoreEvent.GoalCompleted>()
            .onEach { event ->
                val goal = runCatching { goalManager.get(UUID.fromString(event.goalId)) }.getOrNull() ?: return@onEach
                runCatching { skillEngine.recordSuccess(goal.description, goal.stepsTaken.toList()) }
                    .onFailure { loggingManager.warning("SkillRecordingService", "Не удалось записать навык: ${it.message}") }
            }
            .launchIn(scope)
    }
}
