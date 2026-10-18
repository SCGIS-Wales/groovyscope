---
name: groovy-impact
description: Say what breaks if a class, method or closure parameter changes: callers, transitive callers, tests, dynamic sites and risk, from groovyscope's call graph.
---
# Impact before a change

Use for "what breaks if I change X", "who calls X", "can I change this signature". Target: `$ARGUMENTS`
(`Class.method` or a symbol), and the kind of change: signature, rename, delete or behaviour.

## Steps

1. `groovyscope.impact_of(symbol, change)`: direct and transitive callers (static facts), overrides, tests,
   `dynamicUnknowns` (call sites matched by name only), a `riskLevel`.
2. `groovyscope.call_graph(symbol, direction="in", depth=2)` when the shape of the callers matters.

## Report

Affected files, transitive callers and tests, not only the direct callers; the dynamic sites that need a
human look; what to check by hand; and the risk in one line with its reason.
