package com.laylapro.router

import com.laylapro.api.ApiLayer
import com.laylapro.core.CoreEvent
import com.laylapro.core.EventBus
import java.util.concurrent.CopyOnWriteArrayList

enum class TaskCategory { CHAT, REASONING, PLANNING, TRANSLATION, SUMMARIZATION, EMBEDDING, VISION, IMAGE_GENERATION, CODE }
enum class CostTier { FREE_LOCAL, CHEAP_CLOUD, EXPENSIVE_CLOUD }
enum class PrivacyTier { ON_DEVICE, TRUSTED_CLOUD, THIRD_PARTY_CLOUD }
enum class SpeedTier { INSTANT, FAST, MODERATE, SLOW }

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
    val minRamMb: Long = 0,
)

data class EngineBinding(
    val profile: ModelProfile,
    val apiLayer: ApiLayer?,
    val isAvailable: () -> Boolean,
)

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

fun interface RoutingStrategy {
    fun score(binding: EngineBinding, effective: EffectivePreferences, resources: DeviceResources): Float
}

data class EffectivePreferences(
    val preferOffline: Boolean,
    val maxCostTier: CostTier,
    val maxPrivacyTier: PrivacyTier,
    val prioritizeSpeed: Boolean,
    val preferredEngineId: String?,
    val requiresToolUse: Boolean,
    val minContextTokens: Int,
)

object DefaultRoutingStrategy : RoutingStrategy {
    override fun score(binding: EngineBinding, effective: EffectivePreferences, resources: DeviceResources): Float {
        val profile = binding.profile
        val costScore = 1f - profile.costTier.ordinal.toFloat() / CostTier.entries.size
        val privacyScore = 1f - profile.privacyTier.ordinal.toFloat() / PrivacyTier.entries.size
        val speedWeight = if (effective.prioritizeSpeed) 2.5f else 1f
        val speedScore = (1f - profile.speedTier.ordinal.toFloat() / SpeedTier.entries.size) * speedWeight
        val contextScore = profile.maxContextTokens.coerceAtMost(200_000).toFloat() / 200_000f * 0.5f
        val preferredBonus = if (effective.preferredEngineId == profile.engineId) 5f else 0f
        val lowBatteryPenalty = if (!resources.isCharging && resources.batteryPercent < 15 && profile.speedTier == SpeedTier.SLOW) -1f else 0f
        return costScore + privacyScore + speedScore + contextScore + preferredBonus + lowBatteryPenalty
    }
}

/**
 * Router with hard safety/capability constraints. A fallback may relax only the
 * soft preferOffline preference; cost, privacy, tool-use, context, network and RAM
 * constraints are never bypassed.
 */
class ModelRouter(
    initialEngines: List<EngineBinding>,
    private val resourceProvider: () -> DeviceResources,
    var userPreferences: UserRoutingPreferences = UserRoutingPreferences(),
    private var strategy: RoutingStrategy = DefaultRoutingStrategy,
) {
    private val engines = CopyOnWriteArrayList(initialEngines)

    fun registerEngine(binding: EngineBinding) {
        engines.removeIf { it.profile.engineId == binding.profile.engineId }
        engines.add(binding)
    }

    fun unregisterEngine(engineId: String) {
        engines.removeIf { it.profile.engineId == engineId }
    }

    fun setStrategy(newStrategy: RoutingStrategy) {
        strategy = newStrategy
    }

    fun availableProfiles(category: TaskCategory? = null): List<ModelProfile> =
        engines.filter { binding ->
            binding.apiLayer != null && binding.isAvailable() &&
                (category == null || category in binding.profile.supportedCategories)
        }.map { it.profile }

    fun route(category: TaskCategory, constraints: RoutingConstraints = RoutingConstraints()): RoutingDecision {
        val resources = resourceProvider()
        val effective = mergeWithUserPreferences(constraints)

        val strict = eligible(category, effective, resources, relaxPreferOffline = false)
        val candidates = if (strict.isNotEmpty()) {
            strict
        } else if (effective.preferOffline) {
            eligible(category, effective, resources, relaxPreferOffline = true)
        } else {
            emptyList()
        }

        val chosen = candidates
            .map { it to strategy.score(it, effective, resources) }
            .maxByOrNull { it.second }
            ?: throw NoSuitableModelException(
                "Нет доступного движка для категории $category, удовлетворяющего ограничениям стоимости, приватности, возможностей и ресурсов"
            )

        val binding = chosen.first
        val apiLayer = binding.apiLayer
            ?: throw NoSuitableModelException("Движок '${binding.profile.engineId}' не имеет готового ApiLayer")

        EventBus.tryPublish(CoreEvent.ModelRouted(category.name, binding.profile.id, binding.profile.engineId, chosen.second))
        return RoutingDecision(binding.profile, apiLayer, chosen.second)
    }

    private fun eligible(
        category: TaskCategory,
        effective: EffectivePreferences,
        resources: DeviceResources,
        relaxPreferOffline: Boolean,
    ): List<EngineBinding> = engines.filter { binding ->
        val profile = binding.profile
        binding.apiLayer != null &&
            binding.isAvailable() &&
            category in profile.supportedCategories &&
            profile.costTier.ordinal <= effective.maxCostTier.ordinal &&
            profile.privacyTier.ordinal <= effective.maxPrivacyTier.ordinal &&
            profile.maxContextTokens >= effective.minContextTokens &&
            (!effective.requiresToolUse || profile.requiresToolUse) &&
            (relaxPreferOffline || !effective.preferOffline || !profile.requiresNetwork) &&
            (!profile.requiresNetwork || resources.isOnline) &&
            (profile.minRamMb == 0L || resources.availableRamMb >= profile.minRamMb)
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
                EngineBinding(cloudProfile, cloudApiLayer, hasApiKey),
                // Placeholder intentionally has no ApiLayer and therefore can never be routed,
                // even if availability is accidentally reported true before wiring is complete.
                EngineBinding(localProfile, null, isLocalModelAvailable),
            ),
            resourceProvider = resourceProvider,
        )
    }
}
