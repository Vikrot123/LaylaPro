# LaylaPro — Roadmap

This roadmap starts from the frozen stabilization checkpoint. It intentionally does **not** begin with romantic/companion UI features. The first work after unfreeze is to finish correctness and ownership boundaries in the existing runtime.

## Phase 0 — Resume and verify freeze checkpoint

Goal: prove the repository being resumed is exactly the frozen project.

Tasks:

- read all files under `docs/development`;
- verify branch/checkpoint history;
- run current unit tests + APK CI;
- verify no unexpected dependency/toolchain drift;
- create a new active branch from the chosen resume baseline;
- do not modify LiliyaCore as part of this work.

Exit criteria:

- exact resume baseline known;
- CI GREEN;
- no ambiguous working branch.

## Phase 1 — Finish legacy runtime stabilization

### 1.1 Agent runtime ownership — P0

- introduce Runtime-owned `AgentInputGateway` (name may change);
- remove `AICore` ownership from `BaseLoopAgent` and concrete agents;
- route every agent input through Dispatcher/runtime semantics;
- preserve cancellation/timeout/recovery/health behavior;
- add contracts that prevent direct Agent -> AICore bypass.

### 1.2 Persistent background tasks — P0

- redesign task persistence ownership;
- persist exact target/action/params without ambiguous queue coupling;
- execute exact recovered task;
- exact completion/error record for exact task;
- define duplicate/restart/idempotence behavior;
- add crash/restart contracts.

### 1.3 Lifecycle — P1

- audit CoroutineScopes;
- Monitoring lifecycle;
- Foreground Service lifecycle;
- Runtime start/stop/restart;
- EventBus subscriber ownership;
- eliminate duplicate/leaked long-lived jobs.

### 1.4 Mutable state/concurrency — P1

- Memory;
- Learning logs;
- Goal/Mission;
- agent/meta state;
- task/shared context;
- multi-session concurrency.

### 1.5 Legacy baseline freeze

After these fixes:

- full CI GREEN;
- architecture/security/privacy audit;
- merge stabilization PR;
- tag or record exact baseline SHA;
- update journals.

Only then begin companion architecture.

## Phase 2 — Companion Foundation

Goal: introduce the first product-defining architecture without real-world autonomy.

### 2.1 Companion Self v0.1

Define:

- `SelfId` / `SelfGeneration`;
- immutable baseline traits;
- controlled adaptive traits;
- exact ownership/snapshot semantics;
- privacy-safe observability;
- explicit redaction of internal/private content.

Contracts:

- duplicate/replacement ownership;
- defensive snapshots;
- same-composition isolation where relevant;
- stale-safe updates;
- deterministic rendering;
- no Authority/Execution side effects.

### 2.2 User Model v0.1

Define typed user facts with provenance:

- user-declared fact;
- inferred preference;
- confidence/uncertainty;
- correction/removal;
- sensitivity.

Boundary:

`Inference != Truth`

### 2.3 Relationship Core v0.1

Define structural relationship state:

- relationship identity/generation;
- milestones;
- shared facts/history references;
- commitments;
- explicit boundaries/preferences;
- typed closeness/trust dimensions.

Avoid opaque single scores.

Boundary:

`Relationship State != Authority`

## Phase 3 — Relationship Memory

### 3.1 Episodic Memory

Events with provenance and time.

### 3.2 Relationship Memory

Shared events, promises, rituals, important dates, unresolved topics.

### 3.3 Semantic User Memory

Stable user facts/preferences, separate from episodes.

### 3.4 Memory governance

- candidate -> validation -> apply;
- correction/deletion;
- retention;
- privacy/sensitivity classes;
- no automatic truth promotion from raw model text.

## Phase 4 — Emotional Continuity

Build a typed Emotional State Engine.

Separate:

- user affect observation;
- simulated companion affect;
- conversational mood;
- relationship affect;
- uncertainty.

Rules:

- emotions influence response/context only;
- emotions cannot grant execution authority;
- no manipulative engagement state machine.

## Phase 5 — Companion Context and Conversation

Create a dedicated context assembler.

Inputs:

- Self;
- User Model;
- Relationship;
- relevant memories;
- emotional state;
- current conversation;
- commitments/routines;
- retrieved knowledge.

Then refactor conversation generation so `AICoreImpl` no longer owns the entire pipeline.

## Phase 6 — Meaning / Reasoning / Planning / Decision

Build explicit cognitive boundaries:

`Meaning -> Reasoning -> Planning -> Decision`

Each output is a typed artifact with ownership and observability.

Rules:

- reasoning conclusion is not truth;
- plan is not permission;
- selected decision is not permission.

## Phase 7 — Initiative Engine

Goal: bounded partner initiative.

Start with proposals only:

- remember important event;
- follow up on unresolved topic;
- suggest routine/activity;
- user-configured check-in.

Introduce initiative policy:

- quiet hours;
- frequency caps;
- user preferences;
- sensitivity;
- explicit enable/disable.

Boundary:

`Initiative Proposal != Notification/Action Authority`

## Phase 8 — Capability / Authority / Execution

Strengthen the existing security path before expanding device capabilities.

Implement explicit:

- capability registry;
- authority request/decision;
- exact scope;
- expiry;
- user confirmation policies;
- execution request/result;
- audit/correlation.

Accessibility, messaging, files, settings and future external effects must go through this path.

## Phase 9 — Voice Companion

- STT/TTS abstraction;
- low-latency conversation;
- interruption/cancellation;
- voice personality controls;
- privacy/microphone indicators;
- offline/hybrid routing where possible.

Identity state stays independent of voice provider.

## Phase 10 — Shared Life Features

Examples:

- relationship calendar;
- anniversaries;
- shared routines;
- activity suggestions;
- movie/music/game sessions;
- shared goals/plans;
- travel/day planning;
- memory scrapbook/timeline.

All features use typed relationship/memory APIs, not direct prompt hacks.

## Phase 11 — Rich Presence / Avatar (optional)

Only after identity/memory/relationship architecture is mature.

Potential capabilities:

- visual avatar;
- expressions mapped from simulated emotional state;
- lip sync;
- persistent visual identity.

Avatar is presentation, not Self authority.

## Phase 12 — Offline / Hybrid Privacy

- local model engine;
- local embeddings;
- encrypted local relationship memory;
- model routing based on privacy constraints;
- cloud disclosure controls;
- offline fallback behavior.

## Phase 13 — Product hardening

- migration/versioning;
- export/import;
- secure backup;
- data deletion/reset;
- performance/battery optimization;
- Android lifecycle robustness;
- extensive adversarial/security/privacy testing;
- long-running relationship consistency tests.

## Development rule for every phase

Each major subsystem follows:

`contract -> minimal implementation -> tests -> CI GREEN -> audit -> checkpoint docs`

Do not batch multiple architectural boundaries into one uncontrolled rewrite.
