package com.laylapro.router

import com.laylapro.api.ApiLayer
import com.laylapro.core.CoreEvent
import com.laylapro.core.EventBus
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Модуль 10 (Model Router) — Этап 1 архитектуры LaylaPro AI OS.
 *
 * Спроектирован по Open/Closed Principle: чтобы добавить новый движок (например,
 * локальную GGUF-модель из Этапа 6, или второй облачный провайдер), НЕ нужно менять
 * код [ModelRouter] — достаточно вызвать [ModelRouter.registerEngine] с новым
 * [EngineBinding] (плагин-паттерн). Чтобы изменить саму логику выбора — не нужно
 * трогать [ModelRouter], достаточно передать другую реализацию [RoutingStrategy]
 * через [ModelRouter.setStrategy] (Strategy pattern — открыт для расширения алгоритма
 * ранжирования, закрыт для модификации самого класса).
 *
 * Критерии выбора модели (по требованию): тип задачи, доступность ресурсов устройства
 * (RAM/батарея/сеть — см. [DeviceResources]), доступность самой модели прямо сейчас
 * ([EngineBinding.isAvailable]), стоимость ([CostTier]), скорость ([SpeedTier]),
 * конфиденциальность ([PrivacyTier]) и явные предпочтения пользователя
 * ([UserRoutingPreferences] + [RoutingConstraints.preferredEngineId]).
 */
enum class TaskCategory { CHAT, REASONING, PLANNING, TRANSLATION, SUMMARIZATION, EMBEDDING, VISION, IMAGE_GENERATION, CODE }

/** Чем меньше ordinal — тем дешевле/предпочтительнее при прочих равных. */
enum class CostTier { FREE_LOCAL, CHEAP_CLOUD, EXPENSIVE_CLOUD }

/** Чем меньше ordinal — тем приватнее (данные дальше от третьих лиц). */
enum class PrivacyTier { ON_DEVICE, TRUSTED_CLOUD, THIRD_PARTY_CLOUD }

/** Чем меньше ordinal — тем быстрее обычно отвечает движок этого класса. */
enum class SpeedTier { INSTANT, FAST, MODERATE, SLOW }

/**
 * Снимок состояния устройства, влияющий на выбор модели. Определён локально в пакете
 * `router` (а не переиспользует `runtime.ResourceSnapshot` напрямую), чтобы Model Router
 * оставался самодостаточным модулем со слабой связанностью — интеграция с Resource
 * Manager происходит через маппинг на границе (см. wiring в LaylaApplication), а не
 * через прямую зависимость router -> runtime.
 */
data class DeviceResources(
    val availableRamMb: Long,
    val batteryPercent: Int,
    val isCharging: Boolean,
    val isOnline: Boolean,
)

data class ModelProfile(
    val id: String,
    val engineId: String,
    val displayName: String,
    val supportedCategories: Set<TaskCategory>,
    val requiresNetwork: Boolean,
    val requiresToolUse: Boolean = false,
    val costTier: CostTier,
    val privacyTier: PrivacyTier,
    val speedTier: SpeedTier,
    val maxContextTokens: Int,
    /** Минимум свободной RAM для локальных движков; 0 для облачных (не имеет значения). */
    val minRamMb: Long = 0,
)

data class EngineBinding(
    val profile: ModelProfile,
    val apiLayer: ApiLayer?,
    /** true, если движок реально готов к использованию прямо сейчас (ключ есть, модель загружена и т.п.). */
    val isAvailable: () -> Boolean,
)

/**
 * Глобальные, редко меняющиеся предпочтения пользователя (в перспективе — экран настроек).
 * В отличие от [RoutingConstraints] (разовые требования конкретного вызова), это —
 * настройки по умолчанию, которые вызывающий код может не указывать явно каждый раз.
 */
data class UserRoutingPreferences(
    var preferOffline: Boolean = false,
    var maxCostTier: CostTier = CostTier.EXPENSIVE_CLOUD,
    var maxPrivacyTier: PrivacyTier = PrivacyTier.THIRD_PARTY_CLOUD,
    var prioritizeSpeed: Boolean = false,
    var preferredEngineId: String? = null,
)

data class RoutingConstraints(
    val preferOffline: Boolean? = null,
    val maxCostTier: CostTier? = null,
    val requiresToolUse: Boolean = false,
    val maxPrivacyTier: PrivacyTier? = null,
    val prioritizeSpeed: Boolean? = null,
    val preferredEngineId: String? = null,
    val minContextTokens: Int = 0,
)

data class RoutingDecision(val profile: ModelProfile, val apiLayer: ApiLayer, val score: Float)

class NoSuitableModelException(message: String) : Exception(message)

/**
 * Strategy pattern: подменяемый алгоритм ранжирования доступных движков.
 * Новую стратегию можно подключить через [ModelRouter.setStrategy], не трогая
 * остальной код (OCP).
 */
fun interface RoutingStrategy {
    fun score(binding: EngineBinding, effective: EffectivePreferences, resources: DeviceResources): Float
}

/** Слияние [RoutingConstraints] конкретного вызова с [UserRoutingPreferences] по умолчанию. */
data class EffectivePreferences(
    val preferOffline: Boolean,
    val maxCostTier: CostTier,
    val maxPrivacyTier: PrivacyTier,
    val prioritizeSpeed: Boolean,
    val preferredEngineId: String?,
    val requiresToolUse: Boolean,
    val minContextTokens: Int,
)

/**
 * Формула по умолчанию: взвешенная сумма нормализованных факторов.
 * Вес скорости увеличивается, если prioritizeSpeed; явное предпочтение пользователя
 * (preferredEngineId) даёт большой бонус, но не является жёстким фильтром — если
 * предпочитаемый движок недоступен для категории задачи, побеждает следующий по счёту.
 */
object DefaultRoutingStrategy : RoutingStrategy {
    override fun score(binding: EngineBinding, effective: EffectivePreferences, resources: DeviceResources): Float {
        val profile = binding.profile

        val costScore = 1f - (profile.costTier.ordinal.toFloat() / CostTier.entries.size)
        val privacyScore = 1f - (profile.privacyTier.ordinal.toFloat() / PrivacyTier.entries.size)
        val speedWeight = if (effective.prioritizeSpeed) 2.5f else 1f
        val speedScore = (1f - (profile.speedTier.ordinal.toFloat() / SpeedTier.entries.size)) * speedWeight
        val contextScore = (profile.maxContextTokens.coerceAtMost(200_000).toFloat() / 200_000f) * 0.5f
        val preferredBonus = if (effective.preferredEngineId == profile.engineId) 5f else 0f
        // Если устройство offline, а движок требует сеть — это фильтруется ДО скоринга
        // (см. ModelRouter.route), поэтому здесь только "мягкие" факторы.
        val lowBatteryPenalty = if (!resources.isCharging && resources.batteryPercent < 15 && profile.speedTier == SpeedTier.SLOW) -1f else 0f

        return costScore + privacyScore + speedScore + contextScore + preferredBonus + lowBatteryPenalty
    }
}

class ModelRouter(
    initialEngines: List<EngineBinding>,
    private val resourceProvider: () -> DeviceResources,
    var userPreferences: UserRoutingPreferences = UserRoutingPreferences(),
    private var strategy: RoutingStrategy = DefaultRoutingStrategy,
) {
    private val engines = CopyOnWriteArrayList(initialEngines)

    /** Плагин-регистрация нового движка БЕЗ изменения кода ModelRouter (OCP). */
    fun registerEngine(binding: EngineBinding) {
        engines.removeIf { it.profile.engineId == binding.profile.engineId }
        engines.add(binding)
    }

    fun unregisterEngine(engineId: String) {
        engines.removeIf { it.profile.engineId == engineId }
    }

    /** Подмена алгоритма ранжирования без изменения кода ModelRouter (Strategy pattern, OCP). */
    fun setStrategy(newStrategy: RoutingStrategy) {
        strategy = newStrategy
    }

    fun availableProfiles(category: TaskCategory? = null): List<ModelProfile> =
        engines.filter { it.isAvailable() && (category == null || category in it.profile.supportedCategories) }
            .map { it.profile }

    fun route(category: TaskCategory, constraints: RoutingConstraints = RoutingConstraints()): RoutingDecision {
        val resources = resourceProvider()
        val effective = mergeWithUserPreferences(constraints)

        val candidates = engines.filter { binding ->
            val profile = binding.profile
            binding.isAvailable() &&
                category in profile.supportedCategories &&
                profile.costTier.ordinal <= effective.maxCostTier.ordinal &&
                profile.privacyTier.ordinal <= effective.maxPrivacyTier.ordinal &&
                profile.maxContextTokens >= effective.minContextTokens &&
                (!effective.requiresToolUse || profile.requiresToolUse) &&
                (!effective.preferOffline || !profile.requiresNetwork) &&
                (!profile.requiresNetwork || resources.isOnline) &&
                (profile.minRamMb == 0L || resources.availableRamMb >= profile.minRamMb)
        }

        val ranked = candidates
            .map { it to strategy.score(it, effective, resources) }
            .sortedByDescending { it.second }

        // Мягкая деградация: если строгий набор ограничений (например, preferOffline)
        // не дал ни одного кандидата — пробуем ещё раз без preferOffline/toolUse-фильтра,
        // прежде чем окончательно отказывать (Runtime Core не должен "молчать" там, где
        // есть хоть какой-то работающий движок для этой категории задачи).
        val chosen = ranked.firstOrNull()
            ?: engines.filter { it.isAvailable() && category in it.profile.supportedCategories }
                .map { it to strategy.score(it, effective.copy(preferOffline = false, requiresToolUse = false), resources) }
                .sortedByDescending { it.second }
                .firstOrNull()
            ?: throw NoSuitableModelException(
                "Нет доступного движка для категории $category (проверьте API-ключ в Настройках " +
                    "или доступность локальной модели)"
            )

        val (binding, score) = chosen
        EventBus.tryPublish(CoreEvent.ModelRouted(category.name, binding.profile.id, binding.profile.engineId, score))

        return RoutingDecision(profile = binding.profile, apiLayer = binding.apiLayer!!, score = score)
    }

    private fun mergeWithUserPreferences(constraints: RoutingConstraints): EffectivePreferences = EffectivePreferences(
        preferOffline = constraints.preferOffline ?: userPreferences.preferOffline,
        maxCostTier = constraints.maxCostTier ?: userPreferences.maxCostTier,
        maxPrivacyTier = constraints.maxPrivacyTier ?: userPreferences.maxPrivacyTier,
        prioritizeSpeed = constraints.prioritizeSpeed ?: userPreferences.prioritizeSpeed,
        preferredEngineId = constraints.preferredEngineId ?: userPreferences.preferredEngineId,
        requiresToolUse = constraints.requiresToolUse,
        minContextTokens = constraints.minContextTokens,
    )
}

/**
 * Собирает стандартный набор [EngineBinding] для текущего состояния проекта: один
 * облачный Claude-профиль (доступен, если задан API-ключ и есть сеть) + заготовка
 * под будущий локальный движок. Добавление реального локального движка в Этапе 6 —
 * это ВЫЗОВ [ModelRouter.registerEngine] с новым профилем, а не правка этого файла
 * или файлов Reasoning/Planning/AICore (OCP в действии).
 */
object ModelRouterFactory {

    fun create(
        cloudApiLayer: ApiLayer,
        hasApiKey: () -> Boolean,
        isLocalModelAvailable: () -> Boolean,
        resourceProvider: () -> DeviceResources,
    ): ModelRouter {
        val cloudProfile = ModelProfile(
            id = "anthropic-claude-sonnet-5",
            engineId = "anthropic-cloud",
            displayName = "Claude Sonnet 5 (облако)",
            supportedCategories = setOf(
                TaskCategory.CHAT, TaskCategory.REASONING, TaskCategory.PLANNING,
                TaskCategory.CODE, TaskCategory.SUMMARIZATION, TaskCategory.TRANSLATION,
            ),
            requiresNetwork = true,
            requiresToolUse = true,
            costTier = CostTier.CHEAP_CLOUD,
            privacyTier = PrivacyTier.TRUSTED_CLOUD,
            speedTier = SpeedTier.FAST,
            maxContextTokens = 200_000,
        )

        // Заготовка под Этап 6 (Local LLM Runtime): пока isLocalModelAvailable() всегда
        // false, поэтому этот профиль просто не побеждает в route() — но контракт уже
        // на месте (privacyTier = ON_DEVICE, requiresNetwork = false, costTier = FREE_LOCAL).
        val localProfile = ModelProfile(
            id = "local-gguf-llamacpp",
            engineId = "llama-cpp-local",
            displayName = "Локальная модель (GGUF, пока не подключена)",
            supportedCategories = setOf(TaskCategory.CHAT, TaskCategory.REASONING),
            requiresNetwork = false,
            requiresToolUse = false,
            costTier = CostTier.FREE_LOCAL,
            privacyTier = PrivacyTier.ON_DEVICE,
            speedTier = SpeedTier.MODERATE,
            maxContextTokens = 4096,
            minRamMb = 2048,
        )

        return ModelRouter(
            initialEngines = listOf(
                EngineBinding(profile = cloudProfile, apiLayer = cloudApiLayer, isAvailable = hasApiKey),
                EngineBinding(profile = localProfile, apiLayer = null, isAvailable = isLocalModelAvailable),
            ),
            resourceProvider = resourceProvider,
        )
    }
}
