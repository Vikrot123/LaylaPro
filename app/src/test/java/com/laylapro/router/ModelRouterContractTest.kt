package com.laylapro.router

import com.laylapro.api.ApiLayer
import com.laylapro.api.ToolCallResponse
import com.laylapro.api.ToolSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ModelRouterContractTest {
    private object FakeApi : ApiLayer {
        override suspend fun complete(systemPrompt: String, userMessage: String, maxTokens: Int, temperature: Float) = "ok"
        override suspend fun completeWithTools(
            systemPrompt: String,
            userMessage: String,
            tools: List<ToolSpec>,
            maxTokens: Int,
            temperature: Float,
        ) = ToolCallResponse("ok")
        override fun completeStreaming(systemPrompt: String, userMessage: String, maxTokens: Int, temperature: Float): Flow<String> = flowOf("ok")
    }

    private val resources = DeviceResources(
        availableRamMb = 4096,
        batteryPercent = 80,
        isCharging = true,
        isOnline = true,
    )

    @Test
    fun privacy_constraint_is_not_bypassed_by_fallback() {
        val cloud = EngineBinding(
            profile = ModelProfile(
                id = "cloud", engineId = "cloud", displayName = "cloud",
                supportedCategories = setOf(TaskCategory.CHAT), requiresNetwork = true,
                costTier = CostTier.CHEAP_CLOUD, privacyTier = PrivacyTier.TRUSTED_CLOUD,
                speedTier = SpeedTier.FAST, maxContextTokens = 10000,
            ),
            apiLayer = FakeApi,
            isAvailable = { true },
        )
        val router = ModelRouter(listOf(cloud), resourceProvider = { resources })

        assertThrows(NoSuitableModelException::class.java) {
            router.route(TaskCategory.CHAT, RoutingConstraints(maxPrivacyTier = PrivacyTier.ON_DEVICE))
        }
    }

    @Test
    fun tool_use_requirement_is_not_relaxed() {
        val incapable = EngineBinding(
            profile = ModelProfile(
                id = "plain", engineId = "plain", displayName = "plain",
                supportedCategories = setOf(TaskCategory.PLANNING), requiresNetwork = false,
                requiresToolUse = false, costTier = CostTier.FREE_LOCAL,
                privacyTier = PrivacyTier.ON_DEVICE, speedTier = SpeedTier.FAST,
                maxContextTokens = 10000,
            ),
            apiLayer = FakeApi,
            isAvailable = { true },
        )
        val router = ModelRouter(listOf(incapable), resourceProvider = { resources })

        assertThrows(NoSuitableModelException::class.java) {
            router.route(TaskCategory.PLANNING, RoutingConstraints(requiresToolUse = true))
        }
    }

    @Test
    fun prefer_offline_may_relax_without_relaxing_hard_constraints() {
        val cloud = EngineBinding(
            profile = ModelProfile(
                id = "cloud", engineId = "cloud", displayName = "cloud",
                supportedCategories = setOf(TaskCategory.CHAT), requiresNetwork = true,
                costTier = CostTier.CHEAP_CLOUD, privacyTier = PrivacyTier.TRUSTED_CLOUD,
                speedTier = SpeedTier.FAST, maxContextTokens = 10000,
            ),
            apiLayer = FakeApi,
            isAvailable = { true },
        )
        val router = ModelRouter(listOf(cloud), resourceProvider = { resources })

        val result = router.route(
            TaskCategory.CHAT,
            RoutingConstraints(preferOffline = true, maxPrivacyTier = PrivacyTier.TRUSTED_CLOUD),
        )
        assertEquals("cloud", result.profile.engineId)
    }
}
