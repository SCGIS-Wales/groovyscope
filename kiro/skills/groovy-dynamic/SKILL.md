---
name: groovy-dynamic
description: Find where the project relies on dynamic Groovy with groovyscope and say which of it is worth making static.
---
# Dynamic Groovy

Use for "where is this project dynamic", "what defeats static analysis", "make this static".

## Steps

1. `groovyscope.find_dynamic_code(paths)`: classes with neither `@CompileStatic` nor `@CompileDynamic`,
   `def` on public methods, `methodMissing`, `metaClass` writes, `Eval`, `@Grab`, string `execute()`.
2. `groovyscope.read_symbol` on a site before judging whether it can become static.

## Report

Group by construct, counts first. For each group: why it defeats static analysis (navigation and impact
answers around it are `DYNAMIC_UNKNOWN`, matched by name only), which sites are worth converting and which
are by design (builders, DSLs, scripts). Offer `groovyscope.fix` where adding `@CompileStatic` type-checks.
