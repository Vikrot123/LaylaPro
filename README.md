# LaylaPro — Android-приложение (MVP-скелет, 20 модулей + Runtime Core)

Полный скелет проекта Android Studio по техническому заданию LaylaPro: 7 слоёв,
20 модулей ТЗ + **Runtime Core Architecture (Том 98)** — центральная система
управления жизненным циклом, задачами и восстановлением после сбоев.

## Как получить готовый .apk-файл

Важно: собрать бинарный `.apk` можно только там, где есть Android SDK + Gradle
(и, для скачивания зависимостей, интернет) — это или ваш компьютер с Android Studio,
или облачный сервис вроде GitHub Actions. Ниже — три рабочих способа, от самого простого.

### Способ 1 (проще всего, ничего не устанавливать): GitHub Actions

В проект уже добавлен файл `.github/workflows/build-apk.yml`, который сам собирает
APK в облаке GitHub при каждом push.

1. Создайте пустой репозиторий на [github.com](https://github.com/new).
2. Загрузите туда содержимое этой папки (`git init`, `git add .`, `git commit`,
   `git push`, либо просто перетащите файлы через веб-интерфейс GitHub).
3. Откройте вкладку **Actions** в репозитории — сборка "Build LaylaPro APK"
   запустится автоматически (или нажмите "Run workflow" вручную).
4. Через 2–5 минут откройте завершившийся запуск и скачайте архив
   **LaylaPro-debug-apk** внизу страницы — внутри `app-debug.apk`.
5. Перекиньте `app-debug.apk` на телефон (почта себе, Google Drive, USB-кабель)
   и установите — Android спросит разрешение "Установка из неизвестных источников"
   при первом таком файле, это нормально для debug-сборок не из Google Play.

### Способ 2: Android Studio на своём компьютере

1. Установите [Android Studio](https://developer.android.com/studio).
2. Откройте эту папку через *File → Open*, дождитесь Gradle Sync (нужен интернет).
3. Меню *Build → Build App Bundle(s) / APK(s) → Build APK(s)*.
4. Готовый файл появится в `app/build/outputs/apk/debug/app-debug.apk` —
   Android Studio покажет всплывающую ссылку "locate" сразу после сборки.

### Способ 3: командная строка (если у вас уже есть Android SDK)

```bash
# Установите переменную ANDROID_HOME на путь к вашему Android SDK, затем:
gradle wrapper --gradle-version 8.7   # один раз генерирует gradlew/gradle-wrapper.jar
./gradlew assembleDebug
# Готовый файл: app/build/outputs/apk/debug/app-debug.apk
```

Во всех трёх случаях получается **debug-APK** — он не подписан для публикации
в Google Play, но полностью устанавливается и работает на обычном телефоне.
Для публикации в Play Store потребуется дополнительно подписать `release`-сборку
собственным ключом (`Build → Generate Signed App Bundle / APK` в Android Studio).

---

Рабочая сквозная цепочка MVP — **чат с Claude через Anthropic API**, проходящий
через полный Runtime Core конвейер (очередь задач → диспетчер → AI Core).

## Function Calling (Device Control / Android Integration)

Planning Engine теперь использует настоящий **Anthropic tool use** (`ApiLayer.completeWithTools`,
см. `api/AnthropicApiClient.kt`) вместо всегда-текстового ответа:

1. `PlanningEngineImpl.buildPlan()` отправляет модели список инструментов из
   `planning/ToolCatalog.kt` (`device_control_set_wifi`, `device_control_open_bluetooth_settings`,
   `device_control_open_sound_settings`, `android_integration_click_by_text`).
2. Если модель решает вызвать инструмент(ы) — они превращаются в `TaskStep`-ы графа задач.
3. `AICoreImpl` выполняет эти шаги через `ToolExecutor` (мост к Runtime Core),
   который в реальности — `DispatcherToolExecutor`, дёргающий `Dispatcher.dispatch()`.
4. `Dispatcher` находит зарегистрированный в `LaylaApplication.kt` `ModuleHandler` для
   `"DeviceControl"` или `"AndroidIntegration"` и реально выполняет действие
   (`DeviceControlLayerImpl` / `LaylaAccessibilityService.performClickByText`).
5. Результаты возвращаются в `AICoreImpl`, попадают в системный промпт финального
   ответа — и Claude отвечает пользователю с учётом того, что реально произошло.

Пример: "Включи Wi-Fi" → Planning Engine вызывает `device_control_set_wifi(enabled=true)` →
Dispatcher → `DeviceControlLayerImpl.setWifiState(true)` (открывает системную панель на
Android 10+) → LaylaPro отвечает "Открыла панель Wi-Fi, включите тумблер".

Чтобы добавить новый инструмент: один `Entry` в `ToolCatalog.kt` + `ModuleHandler` в
`LaylaApplication.kt` (или в любом другом месте композиции, где он больше подходит).

## Runtime Core (пакет `runtime/`)

Реализовано по Тому 98 «Runtime Core Architecture»:

- **RuntimeManager** — единственная точка входа после запуска приложения;
  собирает все компоненты ниже и предоставляет `submitUserMessage()`.
- **Module Registry** — реестр модулей (ID, версия, зависимости, приоритет,
  состояние, health, capabilities); без регистрации модуль не участвует в системе.
- **Dispatcher** — единственный путь вызова модулей (`Command`/`CommandResult`),
  с таймаутами и автоматической регистрацией сбоев в Recovery Manager.
- **Event Bus** (`core/CoreEvent.kt`) — асинхронная шина с категориями событий
  (SystemEvent, TaskEvent, MemoryEvent, ConversationEvent, VoiceEvent, VisionEvent,
  DeviceEvent, PluginEvent, NetworkEvent, SecurityEvent, HealthEvent).
- **Runtime State Machine** — 16 состояний (BOOT…SHUTDOWN) с таблицей допустимых
  переходов; переключать состояние может только RuntimeManager.
- **Task Manager / Scheduler** — очередь задач с приоритетами (CRITICAL…BACKGROUND),
  полной структурой Task (TaskID/ParentTaskID/SessionID/…/RetryCount/Context/Metadata).
- **Workflow Engine** — исполнение DAG-графов действий с зависимостями между узлами,
  таймаутами, retry-политикой и rollback при сбое.
- **Context Manager** — единый глобальный контекст (активное приложение, состояние
  экрана, история диалога, эмоциональное состояние и т.д.), доступный только через него.
- **Session Manager** — создание/закрытие/переключение/восстановление сессий.
- **Recovery Manager** — перезапуск модулей после сбоя, `retryTask()` с backoff,
  пометка деградировавших модулей без остановки всей системы.
- **WatchDog** — опрос модулей каждые 1000 мс (AI Core, Memory, Planning, Reasoning,
  Voice, Vision, AndroidIntegration, ApiLayer, Plugins, Synchronization, Monitoring,
  Security); при зависании — перевод в RECOVERY.
- **Resource Manager** — мониторинг RAM/Battery/Storage, публикация `ResourcePressure`
  и точки расширения для выгрузки моделей/очистки кэша/переключения на облако.
- **Permission Manager** — централизованная проверка разрешений Android
  (Accessibility, Overlay, Notifications, Microphone, Camera, Storage, Location,
  Bluetooth, MediaProjection).
- **Health Manager** — приём heartbeat от модулей (health/latency/memory/cpu/status).
- **Logging Manager** — уровни TRACE…FATAL, кольцевой буфер в памяти + Logcat.

**UI (`ChatViewModel`) обращается только к `RuntimeManager.submitUserMessage()`** —
не к `AICore` напрямую, в точном соответствии с требованием "никто не должен
вызывать другой модуль напрямую".

## Что уже работает

- **AI Core** (`core/AICoreImpl.kt`) — оркестратор: Reasoning → Planning → Memory →
  Personality → API Layer → Conversation → Memory. Вызывается ТОЛЬКО через
  `RuntimeManager` → `Dispatcher` → `Command(targetModule = "AICore", ...)`.
- **API Layer** (`api/AnthropicApiClient.kt`) — реальные HTTP-запросы к
  `https://api.anthropic.com/v1/messages` (модель по умолчанию `claude-sonnet-5`),
  включая потоковую генерацию через SSE.
- **Reasoning / Planning Engine** — классификация интента и построение плана через тот же API.
- **Memory System** — ранжирование Recency+Importance (пока в оперативной памяти процесса).
- **Personality / Emotion / Conversation Engine** — системный промпт, эвристика эмоций, сессии.
- **Security Layer** — хранит API-ключ в `EncryptedSharedPreferences` (Android Keystore);
  `SecurityLayerImpl` умеет вызывать `BiometricPrompt` для опасных действий.
- **Device Control Layer**, **Android Integration Layer (Accessibility Service)**,
  **Monitoring Layer**, **Final Integration Layer (Foreground Service)** — рабочий код
  по примерам из ТЗ.
- **Voice Engine** — штатные `SpeechRecognizer`/`TextToSpeech` (заготовка под
  дальнейшую замену на whisper.cpp).
- Экран чата на **Jetpack Compose** + экран настроек для ввода API-ключа.

## Что оставлено стабом (см. TODO в коде каждого файла)

- **Vision Engine** — нужен MediaProjection + локальная VLM (ONNX Runtime).
- **Knowledge Base** — сейчас `InMemoryKnowledgeBase`; в ТЗ — SQLite/ObjectBox с эмбеддингами.
- **Plugin System** — реестр в памяти; динамическая загрузка `.dex`/`.apk` не реализована.
- **Synchronization Layer** — нет backend'а (в ТЗ — PostgreSQL/Supabase + E2EE).
- **Infrastructure Layer** — нет локального инференса (в ТЗ — llama.cpp/ExecuTorch).
- **WatchDog для Memory/Planning/Reasoning/Voice/…** — сейчас health-check этих модулей
  всегда возвращает `true` (заглушка), т.к. они пока не самостоятельные сервисы со
  своим жизненным циклом, а синхронные объекты внутри `AICoreImpl`. TODO: как только
  какой-то из них станет отдельным Service/процессом — заменить на реальный ping.

## Как открыть и запустить

1. Установите **Android Studio** (Koala или новее) с Android SDK 34 и JDK 17.
2. Откройте папку `LaylaPro/` через *File → Open*.
3. Android Studio предложит сгенерировать `gradlew`/Gradle Wrapper — согласитесь
   (в архиве нет бинарного `gradle-wrapper.jar`, только `gradle-wrapper.properties`
   с версией Gradle 8.7 — Android Studio сама его создаст при синхронизации).
4. Дождитесь Gradle Sync (нужен интернет для скачивания зависимостей).
5. Запустите на устройстве/эмуляторе с **Android 10+ (API 29+)**.
6. В приложении откройте **Настройки** (иконка шестерёнки) и вставьте свой
   Anthropic API-ключ (получить на `console.anthropic.com → API Keys`).
7. Возвращайтесь в чат — сообщения идут через RuntimeManager → Dispatcher → AI Core → Claude.

## Дальнейшие шаги (по рекомендациям из ТЗ и Тома 98)

1. Подключить Accessibility Service к реальному сценарию через **Workflow Engine**:
   голосовая команда → `AndroidVoiceEngine.listen()` → `RuntimeManager.submitUserMessage()`
   → Planning Engine строит `Workflow` из узлов (`WorkflowNode(module = "AndroidIntegration",
   action = "open_app", ...)`) → `WorkflowEngine.execute()` дергает `LaylaAccessibilityService`
   через тот же `Dispatcher`.
2. Заменить `InMemoryMemorySystem`/`InMemoryKnowledgeBase` на Room/SQLite с
   персистентным хранением и реальными эмбеддингами.
3. Расширить `ToolCatalog` новыми инструментами (открыть приложение, прочитать
   уведомления, поставить будильник и т.д.) — каждый новый `Entry` + `ModuleHandler`
   автоматически становится доступен модели через Function Calling.
4. Превратить Memory/Planning/Reasoning/Voice/Vision из синхронных объектов в
   независимые модули с собственной регистрацией в `Dispatcher`+`WatchDog`+`HealthManager` —
   тогда WatchDog сможет реально их перезапускать по отдельности (см. `Restartable`
   в `RecoveryManager.kt`).
5. При необходимости офлайн-режима — интегрировать llama.cpp/ExecuTorch
   (`InfrastructureLayer`) и whisper.cpp (заменить `AndroidVoiceEngine`).

## Структура пакетов

```
com.laylapro
├── core          # 1. AI Core, Event Bus (CoreEvent)
├── reasoning     # 2. Reasoning Engine
├── planning      # 3. Planning Engine
├── memory        # 4. Memory System
├── knowledge     # 5. Knowledge Base
├── learning      # 6. Learning System
├── personality   # 7. Personality Engine
├── emotion       # 8. Emotion Engine
├── conversation  # 9. Conversation Engine
├── voice         # 10. Voice Engine
├── vision        # 11. Vision Engine
├── device        # 12. Device Control Layer
├── accessibility # 13. Android Integration Layer
├── plugin        # 14. Plugin System
├── api           # 15. API Layer (Anthropic)
├── sync          # 16. Synchronization Layer
├── security      # 17. Security Layer
├── infra         # 18. Infrastructure Layer
├── monitoring    # 19. Monitoring Layer
├── integration   # 20. Final Integration Layer
├── runtime       # Том 98: Runtime Core (Manager, Registry, Dispatcher, State
│                 #   Machine, Task/Scheduler, Workflow Engine, Context/Session/
│                 #   Resource/Permission/Health/Logging Manager, Recovery, WatchDog)
├── ui            # Compose UI (chat, settings, theme)
└── util          # SecureStorage и общие утилиты
```

