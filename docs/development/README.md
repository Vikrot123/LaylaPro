# LaylaPro — Development Journal Index

LaylaPro is currently **temporarily frozen**.

Use these documents as the durable project context when development resumes.

## Reading order

1. [`CURRENT_STATE.md`](CURRENT_STATE.md) — exact current stage, verified checkpoint, completed stabilization work and immediate unfinished P0 work.
2. [`PRODUCT_VISION.md`](PRODUCT_VISION.md) — new product purpose: long-term personal Virtual AI Partner / companion.
3. [`ARCHITECTURE.md`](ARCHITECTURE.md) — target architecture and mandatory subsystem boundaries.
4. [`ROADMAP.md`](ROADMAP.md) — ordered engineering plan from runtime stabilization through Companion Foundation and later product layers.
5. [`DEVELOPMENT_LOG.md`](DEVELOPMENT_LOG.md) — reconstructed original history plus verified 2026-08-29 stabilization work.
6. [`NUANCES.md`](NUANCES.md) — correctness, security, privacy and architecture traps that must not be forgotten.
7. [`FREEZE_CHECKPOINT.md`](FREEZE_CHECKPOINT.md) — canonical frozen checkpoint and resume procedure.

## Canonical status

The last verified code-only stabilization checkpoint before these documentation commits is:

`6d76efeaa65a6192c1708c54b753054196f96f1b`

Its CI run #7 (`33253993919`) completed successfully with unit tests plus APK build.

Documentation written after that commit does **not** imply that the known Agent gateway or persistent-background ownership redesign is already implemented.

## New identity of the project

`LaylaPro = Virtual AI Partner / long-term companion`

Assistant and automation capabilities remain supporting infrastructure.

Canonical architecture boundaries:

- `Companion != Assistant`
- `Memory != Truth`
- `Emotion != Authority`
- `Relationship State != Authority`
- `Plan != Decision != Authority != Execution`

## Freeze rule

No further LaylaPro code or architecture work should occur until the project is explicitly unfrozen.

The active engineering focus after this checkpoint returns to the separate `LiliyaCore` repository.
