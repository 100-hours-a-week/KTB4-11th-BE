# SQS implementation ledger

Plan: ../docs/sqs-order-execution-plan.md (task workspace original document).
Base: 38e0688. Existing feature branch preserved; implementation in detached worktree.

- Tasks 1-4 implemented: confirmed message codec, shared market pricing, Redis reservations, locked admission/consumption, persistent idempotency/result query, publisher/listener, reconciliation and rollout docs.
- Ruling: keep legacy synchronous route when ORDER_QUEUE_ENABLED=false; explicit enable switches to 202. Prevent accidental production consumption before AI contract/IAM verification.
- Ruling: Redis reservation mutations share the existing DB account lock. Java exact integer arithmetic + atomic hash field insert replace a multi-key Lua counter; no cache totals to reconcile.
- Human confirmed AI envelope: root eventId, payload.account_id/user_id, no actor; absent source means AI with account policy validation. Missing original timestamp uses SQS SentTimestamp; preserve original requestedAt on DLQ replay.
- Independent review: cross-account eventId collision reproduced RED; release only exact losing reservation under its account lock, preserve winner, return conflict. Final suite GREEN.
- Schema rollout: new entity requires SQL before deploying even if queue flag is false. No existing table schema changes.
- Full suite first found test-context data leakage (fixed with DirtiesContext), then Mockito restubbing failure (fixed using doReturn).
- 2026-10-23 final verification: gradlew test spotbugsMain BUILD SUCCESSFUL. 458 tests / 440 passed / 18 opt-in live tests skipped / zero failures or errors. git diff --check clean.
- Real AWS queue/MySQL/production Redis IAM not touched. Local Redis test is opt-in; Docker engine not ready and WSL Ubuntu has no Redis binary.
