# Decision 08: Ordering (D5)

## Status: DECIDED

## Decision
The engine **requires no ordering**. Two events for one entity arriving swapped produce either a refusal
(edge does not match the state yet) or a conflict (version moved). Both are audited; neither corrupts history.

What disorder costs is a false refusal, so transports are configured for per-entity ordering where they can be:
- Kafka adapter keys every record by `(tenant, type, id)`; ordering holds within a partition.
- In-memory transport is a single FIFO queue.
- Job queues / SQS standard: no ordering guarantee; documented as such.

A refusal caused by disorder is indistinguishable from any other refusal by design: the audit states what state
the entity was in, which is the truth at the time. No reordering buffer, no sequence numbers on events.
