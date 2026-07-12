package com.laylapro.runtime

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Файловый персистентный журнал задач — по мотивам maildir-паттерна из TaskMgr
 * в проанализированной архитектуре Layla (см. отчёт "Layla Architecture Analysis",
 * §4 и §11): каждая задача — это JSON-файл, который лежит в `pending/`, пока не
 * обработана, и переезжает в `completed/` или `error/` по результату.
 *
 * Зачем это нужно ПОВЕРХ уже существующего [TaskQueue] (который живёт только в
 * памяти процесса): задачи с приоритетом [TaskPriority.BACKGROUND] должны переживать
 * смерть процесса (например, если система убила приложение, пока пользователь
 * ждал результат в фоне) — при следующем запуске [RuntimeManager] может вызвать
 * [listPending] и вернуть их в [TaskQueue], как будто ничего не произошло.
 *
 * Обычные интерактивные задачи (чат, USER_MESSAGE) через этот журнал НЕ проходят —
 * это было бы лишним диском на каждое сообщение; см. использование в [RuntimeManager].
 */
class PersistentTaskLog(context: Context) {

    private val root = File(context.filesDir, "task_log").apply { mkdirs() }
    private val pendingDir = File(root, "pending").apply { mkdirs() }
    private val completedDir = File(root, "completed").apply { mkdirs() }
    private val errorDir = File(root, "error").apply { mkdirs() }

    fun writePending(task: Task) {
        val json = taskToJson(task)
        fileFor(pendingDir, task.id).writeText(json.toString())
    }

    fun moveToCompleted(taskId: UUID, resultSummary: String) {
        moveTo(completedDir, taskId) { json -> json.put("resultSummary", resultSummary) }
    }

    fun moveToError(taskId: UUID, error: String) {
        moveTo(errorDir, taskId) { json -> json.put("error", error) }
    }

    /** Задачи, оставшиеся в pending/ на диске — например, после падения процесса. */
    fun listPending(): List<Task> =
        pendingDir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { runCatching { jsonToTask(JSONObject(it.readText())) }.getOrNull() }
            ?: emptyList()

    /** Диагностика/UI: сколько задач в каждом состоянии сейчас лежит на диске. */
    fun counts(): Triple<Int, Int, Int> = Triple(
        pendingDir.listFiles()?.size ?: 0,
        completedDir.listFiles()?.size ?: 0,
        errorDir.listFiles()?.size ?: 0,
    )

    /** Чистка старых завершённых/ошибочных записей, чтобы журнал не рос бесконечно. */
    fun pruneOlderThan(maxAgeMs: Long) {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        for (dir in listOf(completedDir, errorDir)) {
            dir.listFiles()?.forEach { f -> if (f.lastModified() < cutoff) f.delete() }
        }
    }

    private fun fileFor(dir: File, taskId: UUID): File = File(dir, "$taskId.json")

    private fun moveTo(targetDir: File, taskId: UUID, mutate: (JSONObject) -> Unit) {
        val pendingFile = fileFor(pendingDir, taskId)
        val json = if (pendingFile.exists()) JSONObject(pendingFile.readText()) else JSONObject().put("id", taskId.toString())
        mutate(json)
        fileFor(targetDir, taskId).writeText(json.toString())
        pendingFile.delete()
    }

    private fun taskToJson(task: Task): JSONObject = JSONObject().apply {
        put("id", task.id.toString())
        put("parentTaskId", task.parentTaskId?.toString())
        put("sessionId", task.sessionId)
        put("name", task.name)
        put("description", task.description)
        put("type", task.type.name)
        put("priority", task.priority.name)
        put("ownerModule", task.ownerModule)
        put("status", task.status.name)
        put("createdAt", task.createdAt)
        put("retryCount", task.retryCount)
        put("maxRetries", task.maxRetries)
        put("timeoutMs", task.timeoutMs)
        // Контекст — только JSON-совместимые значения; сложные объекты сериализуются
        // через toString() (этого достаточно для восстановления параметров команды).
        val contextJson = JSONObject()
        task.context.forEach { (k, v) -> contextJson.put(k, v?.toString()) }
        put("context", contextJson)
    }

    private fun jsonToTask(json: JSONObject): Task {
        val context = mutableMapOf<String, Any?>()
        json.optJSONObject("context")?.let { obj ->
            obj.keys().forEach { key -> context[key] = obj.opt(key) }
        }
        return Task(
            id = UUID.fromString(json.getString("id")),
            sessionId = json.optString("sessionId", "background"),
            name = json.optString("name", "restored_task"),
            description = json.optString("description", ""),
            type = runCatching { TaskType.valueOf(json.getString("type")) }.getOrDefault(TaskType.BACKGROUND_JOB),
            priority = runCatching { TaskPriority.valueOf(json.getString("priority")) }.getOrDefault(TaskPriority.BACKGROUND),
            ownerModule = json.optString("ownerModule", "unknown"),
            status = TaskStatus.QUEUED, // восстановленная задача всегда возвращается в очередь заново
            retryCount = json.optInt("retryCount", 0),
            maxRetries = json.optInt("maxRetries", 2),
            timeoutMs = json.optLong("timeoutMs", 30_000),
            context = context,
        )
    }
}
