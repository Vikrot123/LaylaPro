# LaylaPro — Target Architecture

This document defines the intended architecture after the project is unfrozen. It is a target direction, not a statement that every subsystem already exists.

## 1. Architectural objective

LaylaPro becomes a relationship-centered system with assistant/automation capabilities underneath it.

Target high-level chain:

`Interaction -> Context -> User Model -> Companion Self -> Relationship State -> Meaning/Intent -> Reasoning -> Planning -> Decision -> Capability/Authority -> Execution -> Result -> Reflection -> Memory/Knowledge -> Relationship/Learning Updates`

The central architectural change is the addition of persistent **Companion Self** and **Relationship State** as first-class layers.

## 2. Non-negotiable boundaries

The future design must preserve these distinctions:

`Companion != Assistant`

`Self != Relationship`

`User Model != Truth`

`Emotion != Relationship Authority`

`Memory != Truth`

`Reflection != Learning Application`

`Decision != Authority`

`Authority != Execution`

`Relationship State != Capability`

No emotional or relational state may directly authorize device actions.

## 3. Proposed subsystem map

### 3.1 Interaction Layer

Responsibilities:

- text input/output;
- voice input/output;
- future multimodal input;
- presentation/avatar layer;
- explicit external-share/process-text boundaries;
- conversation-session ingress.

Must not own durable identity or relationship truth.

### 3.2 Companion Self / Identity Core

New first-class subsystem.

Responsibilities:

- stable Layla identity;
- baseline personality configuration;
- values/behavioral principles;
- stable preferences and style;
- identity version/generation;
- controlled identity evolution;
- explicit distinction between immutable core traits and learnable/adaptive traits.

A single LLM response must never mutate Self directly.

Likely model:

`SelfProfile + SelfGeneration + controlled update proposal -> validated new SelfGeneration`

### 3.3 User Model

Responsibilities:

- known user preferences;
- user-provided profile facts;
- inferred preferences with provenance/uncertainty;
- communication preferences;
- important people/events chosen for memory;
- boundaries.

Inferred user state must never silently become unquestionable truth.

### 3.4 Relationship Core

New central subsystem.

Responsibilities:

- relationship identity;
- relationship generation/version;
- familiarity and trust state;
- closeness/intimacy dimensions;
- shared commitments;
- unresolved relationship topics;
- rituals/traditions;
- relationship milestones;
- explicit boundaries/consent preferences;
- relationship change history.

Do not implement one opaque `loveScore`. Use explicit typed dimensions and events.

### 3.5 Emotional State Engine

Replacement/evolution of the current simple Emotion Engine.

Separate:

- observed user affect;
- current simulated companion affect;
- conversational mood;
- relationship affect;
- uncertainty/confidence of inference.

Emotion is context for response generation, not permission to act.

### 3.6 Memory Architecture

The current Memory/Knowledge/RAG foundation should evolve into explicit stores:

- Short-Term Conversation Memory;
- Working Context;
- Episodic Memory;
- Semantic User Memory;
- Relationship Memory;
- Commitment/Promise Memory;
- Shared-Routine Memory;
- Skill/Preference Memory;
- Knowledge Base.

Every durable entry should eventually have:

- stable ID;
- generation/version where mutable;
- source/provenance;
- creation timestamp;
- sensitivity classification;
- confidence if inferred;
- correction/deletion path;
- retention/lifecycle policy.

Raw memory payloads must not leak into logs.

### 3.7 Context Assembly

A dedicated Context subsystem should build the bounded context for each interaction from:

- current conversation;
- companion Self;
- relationship state;
- relevant user facts;
- relevant episodic memories;
- current emotional state;
- relevant commitments/routines;
- retrieved knowledge.

Context assembly must be deterministic enough to audit and must respect privacy and token budgets.

### 3.8 Meaning / Intent

Before action planning, classify whether the interaction is primarily:

- companionship/conversation;
- emotional support;
- memory recall;
- shared planning;
- information request;
- device/task request;
- relationship negotiation/boundary update;
- safety-sensitive request.

This prevents every conversation from being treated as an automation goal.

### 3.9 Reasoning

Reasoning produces structured analysis/artifacts, not authority.

Future contracts should distinguish:

- evidence/context inputs;
- reasoning artifact;
- uncertainty;
- conclusion/proposal.

### 3.10 Planning

Planning creates a proposal/plan.

`Plan != Decision != Authority != Execution`

Companion plans may include conversational/relationship actions as well as tool actions, but tool actions require explicit downstream governance.

### 3.11 Decision

A general decision layer should record selected outcomes among alternatives.

Relationship closeness must not convert a decision into permission.

### 3.12 Capability / Authority

This should become a strict central boundary for all real-world effects.

Examples requiring controlled authority:

- Accessibility clicks;
- sending messages;
- file operations;
- account operations;
- money/payment actions;
- app installation/uninstallation;
- sensitive settings;
- microphone/camera/screen capture;
- long-running background behavior;
- external publication/sharing.

Expected properties:

- fail closed;
- exact capability + scope;
- explicit user-controlled policy;
- expiry where appropriate;
- auditability;
- no relationship-state bypass.

### 3.13 Execution

Execution performs already-authorized effects and returns observable results.

`ExecutionRequest -> Authority check -> exact executor -> Result`

No model or companion subsystem may directly call Accessibility/device APIs.

### 3.14 Reflection

Reflection analyzes completed interactions and relationship outcomes.

It may propose:

- memory candidates;
- preference updates;
- relationship-state candidates;
- personality adaptation candidates;
- skill improvements.

It does not apply them directly.

### 3.15 Controlled Learning / Adaptation

All durable self-improvement needs candidate -> decision/policy -> controlled application semantics.

Separate update channels:

- User Model update;
- Relationship update;
- Self adaptation;
- Memory consolidation;
- behavioral/skill learning.

Do not use one shared arbitrary prompt override as the final architecture.

### 3.16 Initiative Engine

New subsystem for bounded partner initiative.

Inputs:

- relationship state;
- important events/reminders;
- unfinished conversation topics;
- user-defined quiet hours and preferences;
- recent interaction frequency;
- consent/initiative policy.

Outputs only an **initiative proposal**.

`Initiative Proposal != Authority to notify/contact/act`

A policy layer decides whether/when it is appropriate to surface the initiative.

### 3.17 Companion Runtime

The current RuntimeManager should eventually become a composition/orchestration layer with explicit lifecycle ownership.

Required properties:

- one controlled entry path;
- explicit scopes/jobs ownership;
- start/stop/restart contracts;
- cancellation propagation;
- health/diagnostic observability;
- no direct module bypasses;
- exact task ownership;
- composition isolation where appropriate.

The current Agent -> AICore direct call is explicitly a known violation to remove first when development resumes.

### 3.18 Model Router

Model routing remains useful for cloud/local/hybrid operation.

Hard constraints must remain fail closed:

- privacy tier;
- cost limits;
- required capabilities/tool use;
- network availability;
- local RAM requirements;
- user preference for local-only operation.

Fallback may soften preference, never hard safety/privacy constraints.

### 3.19 Privacy / Security

Future privacy architecture should include:

- local-first sensitive memory;
- encryption at rest;
- Keystore-backed key hierarchy;
- separate API-secret and relationship-data keys;
- redacted logging;
- secure backup/export design;
- user-controlled deletion;
- explicit cloud-disclosure boundaries.

## 4. Mapping from current code to future architecture

Current `PersonalityEngine` -> seed for Companion Self, likely substantial rewrite.

Current `EmotionEngine` -> seed for Emotional State Engine, substantial rewrite.

Current `MemorySystem` -> split into explicit memory layers/stores.

Current `KnowledgeIndex`/RAG -> retained as retrieval infrastructure, separated from relationship truth.

Current `LearningSystem` prompt override -> temporary implementation; future controlled adaptation pipeline replaces it.

Current Agent Framework -> may remain for specialized task orchestration, but is not the companion identity itself.

Current `AICoreImpl` -> likely decomposed; currently owns too many responsibilities for the final companion architecture.

Current `RuntimeManager` -> retained conceptually as lifecycle/composition owner, but requires correctness cleanup and decomposition.

Current `SecurityLayer` -> seed only; future Capability/Authority architecture should be more explicit and execution-adjacent.

Current Accessibility/DeviceControl -> executors only, never decision/authority owners.

## 5. Suggested future package direction

Names are provisional, but responsibilities should become visible in package boundaries:

- `companion.self`
- `companion.relationship`
- `companion.emotion`
- `companion.initiative`
- `user.model`
- `memory.episodic`
- `memory.relationship`
- `memory.semantic`
- `context`
- `reasoning`
- `planning`
- `decision`
- `capability`
- `authority`
- `execution`
- `reflection`
- `learning`
- `runtime`
- `observability`
- `privacy`

Do not perform this package migration mechanically. Introduce boundaries incrementally with contract tests.

## 6. Architecture development method

When resumed:

1. repair current runtime ownership defects;
2. freeze a stable legacy baseline;
3. introduce contracts before companion complexity;
4. move one responsibility at a time out of the monolithic application/runtime graph;
5. require exact CI GREEN per slice;
6. update development journals at stable checkpoints.

Avoid a full rewrite unless evidence shows incremental extraction is less safe than replacement.
