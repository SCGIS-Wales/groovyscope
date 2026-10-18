---
name: groovy-quality
description: Run groovyscope's full quality check on the workspace or a folder and summarise the findings by group, worst first, against the practices checklist.
---
# Full quality check

Use for "full quality check", "run all", "how healthy is this code". Scope: `$ARGUMENTS` (a folder or
files) when given, else the workspace.

## Steps

1. `groovyscope.check(paths)`: counts per code under `byCode`, findings worst first; narrow with `codes`
   or `max_priority` when the list is long.
2. `groovyscope.review(paths)`: the same findings grouped by the practices checklist with a verdict.
3. For "run all", also `find_duplicates`, `find_code(kind="catch", rethrows=false)`, `find_dynamic_code`
   and `coverage_gaps`, then one combined summary.

## Report

Groups top down: static compilation, explicit types, numbers and money, Groovy truth, strings, null safety,
immutability, exceptions and logging, security, concurrency, testing, style. Counts per group first, then
the worst examples with file and line. Offer "fix the safe ones" (`groovyscope.fix`) at the end.

## The checklist behind the groups

`@CompileStatic` or a documented `@CompileDynamic`; explicit types on the public API and no `def` combined
with a type; `intdiv` for integer division and `BigDecimal` with explicit scale for money; no Elvis where 0
or empty is a valid value and no safe-navigation result in a primitive; no GString map keys or GStrings
without interpolation; no `File` or `Path` truthiness for "not null"; guard clauses at boundaries and empty
collections instead of null; records or `@Immutable` with defensive copies; narrow catches with causes kept
and parameterised logging; no `Eval` on input, no `@Grab`, parameterised SQL, list-form process execution,
default XML parser settings, bounded regexes; shared state guarded and virtual threads for I/O; Spock specs
with behaviour names, `where:` tables and `with(obj)`; CodeNarc clean, and on Groovy 6 the NullChecker and
SqlInjectionChecker clean.
