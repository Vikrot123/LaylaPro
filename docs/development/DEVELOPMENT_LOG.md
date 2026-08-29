# LaylaPro — Development Log

This journal separates **verified Git/CI facts** from **reconstructed project history** inferred from the initial repository structure, README, source comments and architecture markers.

The repository does not contain a detailed commit-by-commit history for every original module. Therefore early phases below are reconstruction, not invented Git events.

---

## A. Reconstructed original development phase

### A1 — Initial Android project and application shell

The repository was created as an Android application with:

- Kotlin/Android Gradle project;
- `LaylaApplication` manual composition root;
- `MainActivity` and Compose UI;
- Foreground Service;
- settings and secure storage.

### A2 — Generic AI assistant / AI OS architecture

The original codebase attempted a broad multi-layer architecture rather than a narrow companion product.

Implemented or scaffolded areas included:

- AICore;
- Reasoning;
- Planning;
- API layer;
- Model Router;
- Memory;
- Knowledge/RAG;
- Personality;
- Emotion;
- Learning;
- Reflection;
- Self-Improvement;
- Skill system;
- Runtime Manager;
- Dispatcher;
- Scheduler;
- Workflow;
- Health/Recovery;
- Device Control;
- Accessibility;
- Agent Framework;
- Goals/Missions/Consensus;
- monitoring and security.

Source comments refer to numbered modules, stages and "Том 98 Runtime Core Architecture", indicating that a large architectural specification had been translated into code in broad slices.

### A3 — Anthropic cloud integration

An `AnthropicApiClient` was wired as the default cloud model provider, with API-key storage through Android encrypted preferences/Keystore infrastructure.

Planning was connected to Anthropic-style tool use/function calling.

### A4 — Device/Accessibility integration

The project added:

- Wi-Fi/settings panel control;
- Bluetooth/sound settings navigation;
- Accessibility `click_by_text` capability;
- tool catalog entries allowing Planning to propose those actions.

### A5 — Memory/knowledge expansion

The codebase added:

- in-memory memory system;
- hashing embedding engine;
- vector memory;
- hybrid knowledge index;
- RAG retrieval;
- explicit PROCESS_TEXT "remember" integration;
- memory consolidation/reflection/self-improvement scaffolding.

### A6 — Runtime and multi-agent expansion

The codebase added:

- runtime state machine;
- Dispatcher;
- Module Registry;
- TaskQueue/Scheduler;
- Workflow Engine;
- PersistentTaskLog;
- WatchDog/Recovery;
- Agent Registry;
- ConversationalAgent and AutomationAgent;
- Goal/Mission systems;
- Consensus Engine;
- meta-analysis/strategy components.

This produced a wide architecture, but many boundaries were not yet hardened by tests.

---

## B. Verified recovery/stabilization work — 2026-08-29

Active recovery branch:

`stabilization/baseline-recovery`

### B1 — Baseline audit

A complete repository tree and important runtime/security/model files were inspected before feature expansion.

Historical CI was RED.

Primary compilation causes identified:

- malformed/unclosed KDoc comments in logging/runtime logging sources;
- invalid JSON value assignments in Anthropic request construction;
- cascaded unresolved references caused by parsing failures.

### B2 — Restore compilation

The parser/KDoc defects were repaired.

Anthropic JSON request construction was corrected for the project's current kotlinx.serialization API.

Result: CI build recovered to GREEN.

### B3 — Enable stabilization CI

Workflow was expanded so the stabilization branch and pull requests are checked rather than only the original branch pattern.

Verified run #2 succeeded after the initial compilation recovery.

### B4 — Anthropic Sonnet 5 compatibility

Audit against the current Anthropic API behavior found that Sonnet 5 rejects unsupported sampling parameters such as `temperature`/`top_p`/`top_k` in relevant requests.

The client was adjusted so the Sonnet 5 path does not send the incompatible sampling field.

Verified CI run #3: GREEN.

### B5 — Execution/security audit

Audit found that `SecurityLayer` existed but was not on the actual model-tool execution path.

`DispatcherToolExecutor` could directly dispatch a model-proposed Accessibility click.

A fail-closed confirmation boundary was introduced so sensitive Accessibility `click_by_text` steps cannot execute merely because the model proposed them.

This is a baseline safety gate, not the final Capability/Authority architecture.

### B6 — Cancellation propagation

Audit found general `Exception` catches in coroutine paths that could absorb `CancellationException`.

Cancellation propagation was restored in critical runtime paths including Dispatcher/Recovery/WatchDog handling.

### B7 — State machine correctness

Original behavior mutated state using `getAndSet()` before checking whether the transition was allowed.

Result: invalid transitions were logged but still became real state.

The state machine was changed to fail closed: validate the transition before an atomic state update.

### B8 — Model Router hard constraints

Audit found that fallback routing could bypass hard constraints including privacy/cost/tool-use/network/RAM and could reach an engine with no usable `ApiLayer`.

Routing was hardened:

- hard constraints remain hard;
- only soft preference may be relaxed intentionally;
- no null/placeholder `ApiLayer` candidate can be selected.

### B9 — External PROCESS_TEXT boundary

Original chat PROCESS_TEXT integration could forward externally supplied text and automatically call `sendMessage()`.

Because planning/tool execution exists, an external app's selected text must not become an automatically executed AI instruction.

Behavior was changed so external text only pre-fills the chat. User action is required to send it.

Remember/process-text ingestion was also constrained and validated.

### B10 — Permission model correction

`MEDIA_PROJECTION` was incorrectly represented as permanently granted.

It is an ad-hoc user-mediated capture grant/token and was corrected so the permission abstraction does not claim permanent authority.

### B11 — Workflow validation

Workflow contracts were tightened around:

- duplicate node IDs;
- missing dependencies;
- self-dependencies;
- invalid timeout/retry values;
- cycle/unreachable graph behavior.

### B12 — Unit-contract baseline

The project CI was expanded from APK build only to:

`unit tests + APK build`

Tests were added for the newly hardened boundaries, including:

- StateMachine fail-closed transitions;
- Model Router constraints;
- execution confirmation boundary;
- cancellation behavior;
- Workflow validation.

Verified code checkpoint:

`6d76efeaa65a6192c1708c54b753054196f96f1b`

Verified CI run #7 (`33253993919`): `success`.

---

## C. Known findings not yet implemented at freeze

### C1 — Agent -> AICore bypass

`BaseLoopAgent` owns/calls `AICore.processInput()` directly.

This violates the runtime claim that Dispatcher is the sole controlled path and bypasses its timeout/recovery/health semantics.

Planned fix when resumed:

- introduce a narrow Runtime-owned Agent input gateway;
- construct agents with that gateway rather than `AICore`;
- add contracts preventing direct bypass.

This fix is **not part of the frozen code checkpoint**.

### C2 — Persistent background task ownership

Audit found an ownership ambiguity:

- persisted background tasks are restored/enqueued;
- `submitBackgroundTask()` can poll a shared scheduler queue;
- the polled `Task` and the command parameters being executed need not be proven to be the same durable task;
- recovery can therefore risk completing/erroring the wrong persistent record or executing mismatched work.

Planned fix when resumed:

- exact persisted Task owns exact module/action/params;
- a dedicated serialized persisted-task execution path or equivalent explicit ownership;
- restart/idempotence tests.

This fix is **not part of the frozen code checkpoint**.

### C3 — Lifecycle/concurrency audit remains open

Still requires systematic tests for:

- Runtime start/stop/restart;
- long-lived coroutine ownership;
- Monitoring lifecycle;
- EventBus subscribers;
- Learning/Memory/Goal mutable state;
- multi-session concurrency.

---

## D. Product redirection — 2026-08-29

The strategic purpose of LaylaPro was changed.

Old broad direction:

**generic AI OS / assistant with agents and device automation**.

New primary direction:

**long-term personal virtual AI partner / companion for adult users**.

Assistant, reasoning, tools, device automation and agents become supporting capabilities rather than the primary identity of the product.

The following future architectural domains were defined:

- Companion Self / Identity;
- User Model;
- Relationship Core;
- Emotional State;
- Relationship/Episodic/Semantic Memory;
- Companion Context;
- Meaning/Intent;
- Reasoning;
- Planning;
- Decision;
- Initiative;
- Capability/Authority;
- Execution;
- Reflection;
- Controlled Learning/Adaptation;
- Voice and rich presence;
- Privacy/local-first storage.

Canonical boundaries added to the roadmap:

`Companion != Assistant`

`Memory != Truth`

`Emotion != Authority`

`Relationship State != Authority`

`Plan != Decision != Authority != Execution`

---

## E. Freeze checkpoint — 2026-08-29

Decision: pause LaylaPro after documenting the state and new architecture direction.

Reason: active development priority moves back to the separate LiliyaCore project. LaylaPro should not accumulate speculative partial changes while attention is elsewhere.

Freeze semantics:

- preserve the stabilization branch;
- preserve exact verified code checkpoint in this journal;
- preserve known unfinished P0 work;
- preserve the new Virtual AI Partner architecture direction;
- no further LaylaPro changes until explicitly unfrozen.
