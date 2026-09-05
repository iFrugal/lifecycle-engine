# Decision 03: Guards (D1)

## Status: DECIDED

## Evidence
28 of 73 reference guards use payload; all equality, some nested one level. 73 of 73 use actor role.

## Decision
Three guard kinds on a transition, all must hold:

1. **`roles`** - any-of. Absent or empty = any actor, including one with no roles.
2. **`when`** - map of dotted payload path → condition. Conditions: a literal (equality, numbers compared by value),
   a list (any-of), or `{ exists: true|false }`. Evaluation is a fold over *all* entries with no early return (H2).
   Absent payload path fails a literal/list condition and satisfies `exists: false`.
3. **`guard`** - the escape hatch. Names a `GuardPredicate` registered at startup. The compiler refuses to load a
   rule set naming an unregistered guard. Receives an immutable `GuardContext(current StateRecord, event)`.

No expression language. Comparisons/arithmetic, if ever needed, enter as a new validated guard kind, not as
strings inside `when`.

## Precedence and ambiguity (static)
For candidates matching the same state and action, the compiler orders by `from` specificity:
exact name > `PREFIX.*` (deeper prefix wins) > `*`. Within one specificity two edges may coexist only if their
`when` maps are **provably disjoint**: a common path with non-intersecting literal sets. Roles and `guard` do
not count as disambiguators. Anything else fails to load as AMBIGUOUS.

At runtime, if more than one edge still matches after guards, the outcome is `Refused(AMBIGUOUS)` and audited.
It should be unreachable; the test suite treats reaching it as a compiler bug.

## Refusal reasons produced by guard evaluation
`NO_MATCH` (no edge for state+action) → `ROLE_DENIED` (edges exist, actor lacks roles) → `GUARD_FAILED`
(`when` or `guard` not met) → `AMBIGUOUS`. The order is the evaluation order, so the audit reason is the
first gate that stopped the event.
