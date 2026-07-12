package com.laylapro.runtime

import java.util.UUID

enum class TaskType { USER_MESSAGE, VOICE_COMMAND, DEVICE_ACTION, BACKGROUND_JOB, PLUGIN_CALL, WORKFLOW }

/** Чем меньше ordinal, тем выше приоритет (CRITICAL выполняется первым, BACKGROUND — последним). */
enum class TaskPriority { CRITICAL, HIGH, NORMAL, LOW, BACKGROUND }

enum class TaskStatus { CREATED, QUEUED, RUNNING, WAITING, PAUSED, SUCCESS, FAILED, CANCELLED, TIMEOUT }

/**
 * Единица работы Task Manager (Том 98, "Task Manager"). Поля соответствуют
 * структуре Task из ТЗ один в один (плюс служебные `cancellable`/`canRunInParallel`,
 * унаследованные от MVP-версии Scheduler'а).
 */
data class Task(
    val id: UUID = UUID.randomUUID(),
    val parentTaskId: UUID? = null,
    val sessionId: String,
    val name: String,
    val description: String = "",
    val type: TaskType,
    val priority: TaskPriority = TaskPriority.NORMAL,
    var currentStep: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    var startedAt: Long? = null,
    var finishedAt: Long? = null,
    val ownerModule: String,
    var status: TaskStatus = TaskStatus.CREATED,
    var retryCount: Int = 0,
    val context: MutableMap<String, Any?> = mutableMapOf(),
    val metadata: MutableMap<String, Any?> = mutableMapOf(),
    val canRunInParallel: Boolean = false,
    val cancellable: Boolean = true,
    val timeoutMs: Long = 30_000,
    val maxRetries: Int = 2,
)
