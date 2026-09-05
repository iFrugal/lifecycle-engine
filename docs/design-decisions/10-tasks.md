# Decision 10: Tasks (D8)

## Status: DECIDED

## Evidence
13 task declarations, 5 conditional, 7 carrying deferred cross-entity signals.

## Decision: optional module over the event mechanism
- **Core** compiles `task:` to an emitted NOTIFICATION with action `lifecycle.task.create` carrying
  `name`, `assignTo`, the creating entity, the `onComplete` signal template `{action, target}` and a projected
  payload. The reserved `lifecycle.` prefix is the only vocabulary the core owns (H5 test asserts it).
- **lifecycle-tasks** consumes it and stores the task as OPEN. `TaskService.complete(taskId, actor, payload)`
  checks the actor holds an assignee role, marks COMPLETED, and publishes the `onComplete` signal with the
  completing actor and payload merged in. Target defaults to the creating entity and may be another one:
  the brief's deferred cross-entity signal, through the same path as every other event.
- Claiming, cancelling, listing live in the module. Cancelling emits nothing. Completing a task whose target
  has moved on yields an audited `NO_MATCH`, which is correct.
- **Conditional tasks** = two edges with disjoint `when` maps, one declaring the task and one not. Two rules,
  because there are two rules.

## Rejected
- First-class in core: adds a store, a query API and a lifecycle to the thing that must stay small.
- Entirely the caller's problem: loses the reviewable `task:` declaration the rule owners need to see.
