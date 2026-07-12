package com.laylapro.logging

/**
 * ADR-019: нейтральный интерфейс логирования, не принадлежащий ни `runtime`,
 * ни `agent`. До этого класса `agent/*` напрямую импортировал
 * `com.laylapro.runtime.LoggingManager`, а `runtime.RuntimeManager` конструировал
 * объекты `com.laylapro.agent.*` — двунаправленный пакетный цикл `runtime ⇄ agent`
 * (см. независимый аудит Этапов 1-2, находка M1).
 *
 * `runtime.LoggingManager` теперь реализует этот интерфейс (со всей своей
 * дополнительной функциональностью — кольцевой буфер, Logcat, уровни TRACE/FATAL),
 * а `agent/*` зависит только от этого узкого контракта, а не от конкретного класса
 * в `runtime`. Это разрывает цикл: `agent -> logging`, `runtime -> logging`,
 * `runtime -> agent` — направление зависимостей снова однонаправленное.
 */
interface Logger {
    fun debug(tag: String, message: String)
    fun info(tag: String, message: String)
    fun warning(tag: String, message: String)
    fun error(tag: String, message: String)
}
