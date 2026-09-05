# Decision 04: Rule Format

## Status: DECIDED (schema provisional until the reference rule set is received)

## Decision
One document per entity type. Base documents omit `tenant`; overlays set it (DD-05).

```yaml
entityType: order
initial: NEW
maxHops: 16                       # optional, default 32
states: [NEW, PAID, FULFILLING, COMPLETED, CANCELLED, REFUND_PENDING, REFUNDED]

transitions:
  - id: order.pay                 # stable, unique; the unit a reviewer reads and the key overlays merge on
    from: NEW                     # exact | PREFIX.* | "*"
    except: [ ... ]               # optional, with wildcards
    on: PAY
    roles: [customer, payments-service]
    when: { payment.status: AUTHORISED }
    guard: some-registered-name   # optional escape hatch
    to: PAID
    emit:
      - action: ReceiptRequested                  # no `to` → NOTIFICATION
        payload: { orderId: $entity.id, amount: $payload.payment.amount }
      - action: PREPARE                           # `to` → SIGNAL
        to: { type: shipment, id: $payload.shipmentId }
        payload: { orderId: $entity.id }
      - action: ESCALATE
        to: self
        after: 72h                                # timer = delayed signal; signals only
        dispatch: transport                       # optional override; requires `reason`
        reason: "why this deviates from the default"
    task:                                          # optional, DD-10
      name: approve-refund
      assignTo: [finance]
      onComplete: { action: REFUND_DECIDED }       # target defaults to this entity
```

### Projections, not expressions
Emitted payload values are literals or `$`-references: `$payload` (whole), `$payload.a.b`, `$entity.id`,
`$entity.type`, `$tenant`, `$actor.id`, `$event.id`, `$state.from`, `$state.to`. `$$` escapes a literal `$`.
Unknown references fail at load. Nothing else resolves, so an emitted event is assembled from the consumed
event alone (H10).

### State names
Compared by value only (H1). A dotted name (`ACTIVE.SUSPENDED`) is a **matching hierarchy only**: `ACTIVE.*`
matches `ACTIVE` and descendants. No entry/exit actions, no inherited transitions, no nested machines.

### What the compiler refuses
Duplicate ids; unknown state in `from`/`to`/`except`; missing `initial`; unregistered `guard`; ambiguous pair
(DD-03); `after` on a notification; signal to an entity type with no machine; `dispatch` override without
`reason`; malformed `$` reference; task without `name` or `onComplete.action`.

### Warnings
State no edge enters (and not initial); `PREFIX.*` matching no state; signal action no edge of the target
type consumes (a tenant overlay may add it).
