package com.laylapro.runtime

import android.util.Log
import com.laylapro.logging.Logger
import java.util.concurrent.ConcurrentLinkedDeque

enum class LogLevel { TRACE, DEBUG, INFO, WARNING, ERROR, FATAL }

data class LogEntry(
    val level: LogLevel,
    val tag: String,
    val message: String,
    val timestamp: Long = System.currentTimeMillis(),
)

/**
 * Logging Manager (Том 98): "Фиксируются команды пользователя, решения Planning/
 * Reasoning, ошибки, исключения, использование памяти, системные события, время
 * выполнения операций." Уровни: TRACE..FATAL.
 *
 * Пишет и в Logcat (для разработки), и в кольцевой буфер в памяти (для
 * встроенного экрана диагностики / отправки отчёта об ошибке из приложения).
 * TODO: персистентная запись на диск (ротация файлов) для логов уровня WARNING+,
 * если потребуется анализ постфактум после падения процесса.
 *
 * ADR-019: реализует нейтральный интерфейс [Logger] (пакет `com.laylapro.logging`),
 * чтобы `agent/*` мог зависеть от узкого контракта логирования, а не от этого
 * конкретного класса из `runtime` — это разрывает цикл `runtime ⇄ agent`,
 * найденный при независимом аудите Этапов 1-2 (находка M1).
 */
class LoggingManager(private val ringBufferSize: Int = 500) : Logger {

    private val buffer = ConcurrentLinkedDeque<LogEntry>()

    fun log(level: LogLevel, tag: String, message: String) {
        val entry = LogEntry(level, tag, message)
        buffer.addLast(entry)
        while (buffer.size > ringBufferSize) buffer.pollFirst()

        when (level) {
            LogLevel.TRACE, LogLevel.DEBUG -> Log.d(tag, message)
            LogLevel.INFO -> Log.i(tag, message)
            LogLevel.WARNING -> Log.w(tag, message)
            LogLevel.ERROR, LogLevel.FATAL -> Log.e(tag, message)
        }
    }

    fun trace(tag: String, message: String) = log(LogLevel.TRACE, tag, message)
    override fun debug(tag: String, message: String) = log(LogLevel.DEBUG, tag, message)
    override fun info(tag: String, message: String) = log(LogLevel.INFO, tag, message)
    override fun warning(tag: String, message: String) = log(LogLevel.WARNING, tag, message)
    override fun error(tag: String, message: String) = log(LogLevel.ERROR, tag, message)
    fun fatal(tag: String, message: String) = log(LogLevel.FATAL, tag, message)

    fun recent(limit: Int = 100): List<LogEntry> = buffer.toList().takeLast(limit)

    fun recentByLevel(minLevel: LogLevel, limit: Int = 100): List<LogEntry> =
        buffer.filter { it.level.ordinal >= minLevel.ordinal }.takeLast(limit)
}
