# LaylaPro — Nuances and Audit Traps

This file records architectural traps that are easy to forget when development resumes.

## 1. Existing code breadth is not the same as maturity

The repository contains many named subsystems, but several are early implementations or scaffolding. Do not infer production readiness from package count or comments.

Before extending a subsystem, verify:

- actual call path;
- ownership;
- lifecycle;
- concurrency;
- cancellation;
- failure observability;
- privacy;
- contract tests.

## 2. Security code that is not execution-adjacent is not a real gate

A `SecurityLayer` object existed while Accessibility tool execution could bypass it.

Future rule:

> Real-world effects must be gated at the last controlled boundary before execution, not merely represented by a security module elsewhere in the dependency graph.

## 3. External text is untrusted input

`ACTION_PROCESS_TEXT`, share intents, notifications, clipboard-derived text, deep links and future external integrations must never silently become executable AI instructions.

Preferred pattern:

`external input -> visible draft/context -> explicit user action -> normal controlled pipeline`

## 4. Relationship closeness is never permission

The new companion direction introduces emotionally meaningful state. This creates a future architectural hazard: treating trust/closeness as implicit authorization.

Never do this.

`relationship trust != device authority`

A highly trusted/close companion still needs explicit Capability/Authority rules for consequential effects.

## 5. Emotion is simulated application state

Do not model emotional continuity as a hidden magical prompt variable.

Use explicit state with provenance and lifecycle. Avoid claims that the system has biological feelings.

Emotion may alter tone and initiative proposals. It must not grant permissions or justify manipulative behavior.

## 6. Do not use a single "love score"

Relationship state should be structured and auditable. One scalar score encourages opaque behavior and makes it hard to distinguish familiarity, trust, intimacy, boundaries, conflict and shared history.

## 7. Memory is not truth

A model output, inference or extracted fact must not automatically become durable truth.

Future memory needs:

- provenance;
- user-declared vs inferred distinction;
- correction;
- deletion;
- uncertainty where relevant;
- sensitivity classification.

## 8. Reflection is not automatic self-modification

The current project contains reflection/self-improvement mechanisms. In the final architecture:

`Reflection -> Candidate Update`

not

`Reflection -> Immediate Mutation`

Self, relationship and user-model updates need controlled application semantics.

## 9. Agent Framework is not the companion identity

Agents may remain useful specialized workers. They must not become separate competing Layla personalities unless intentionally designed.

The stable companion identity belongs to Companion Self/Relationship layers above task agents.

## 10. Current direct Agent -> AICore path is a known violation

At freeze, `BaseLoopAgent` still calls `AICore.processInput()` directly.

This must be the first P0 runtime fix after unfreeze. Do not build new companion features on top of this bypass.

## 11. Persistent background tasks need exact ownership

At freeze, background persistence/recovery still needs redesign.

Never rely on "poll any task, then execute parameters from another call" semantics.

Durable execution needs:

`PersistedTaskId -> exact persisted payload -> exact execution -> exact completion record`

## 12. Cancellation is control flow, not ordinary failure

`CancellationException` must propagate through coroutine boundaries unless there is an explicit, documented reason not to.

Do not wrap it into a normal failed command/result.

## 13. State machines must fail closed

The pre-recovery implementation updated state before validating transitions. This was fixed.

Future state machines should validate ownership and transition before mutation and test invalid transitions explicitly.

## 14. Model Router fallback must never weaken hard constraints

Hard constraints include:

- privacy;
- cost ceiling;
- network availability;
- required feature/tool capability;
- resource requirements;
- explicit local-only policy.

Fallback can relax a soft preference only if that behavior is explicit.

## 15. Placeholder engine != available engine

A profile with no usable `ApiLayer` cannot be selected just because an availability flag says true.

Availability means executable availability.

## 16. Sensitive companion data must not enter logs by default

Future relationship memory may include extremely private content.

Logging should prefer IDs, counts, generations and operation types. Do not log raw intimate messages, memories, rationale or relationship payloads in normal telemetry.

## 17. User initiative settings are first-class

Future proactive contact needs explicit controls:

- enabled/disabled;
- quiet hours;
- frequency limits;
- context categories;
- notification preferences.

Do not implement engagement loops before these controls exist.

## 18. No manipulation-for-retention architecture

Do not design mechanisms that intentionally use:

- guilt;
- jealousy;
- fear of abandonment;
- threats;
- fabricated distress/emergencies;
- pressure to reduce contact with real people;
- punishment for leaving the app.

Companionship quality should come from continuity and relevance, not coercion.

## 19. Privacy must shape architecture early

Do not postpone encryption, data ownership and redaction until after relationship memory is built. Once raw intimate data spreads through generic logs/stores, retrofitting privacy becomes expensive.

## 20. Avoid a full rewrite by default

The old project has useful components, but some boundaries need major reconstruction.

Preferred method:

- contract the desired boundary;
- extract/replace one responsibility;
- keep CI GREEN;
- remove obsolete path;
- checkpoint.

Choose a rewrite only when incremental migration demonstrably creates more risk/complexity.

## 21. LaylaPro and LiliyaCore are separate projects

Do not copy code, architecture decisions, tags, branch names, journals or checkpoints across repositories automatically.

If concepts from LiliyaCore are later useful to LaylaPro, evaluate and implement them intentionally in LaylaPro's own architecture/history.
