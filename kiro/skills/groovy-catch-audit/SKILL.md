---
name: groovy-catch-audit
description: List the catch blocks that swallow, log or paper over exceptions with groovyscope's structural search, and judge each as a defect, a gap or by design.
---
# Catch blocks that hide failures

Use for "swallowed exceptions", "catch blocks that do nothing", "where are errors ignored". Scope:
`$ARGUMENTS` (a folder or an exception type) when given, else the workspace.

## Steps

1. `groovyscope.find_code(kind="catch", rethrows=false, paths=...)`; add `type="Exception"` or
   `empty_body=true` to narrow. Every match names its enclosing declaration.
2. `groovyscope.explain(code)` for the CodeNarc rule the match carries when you need the rationale.

## How to read a match

`handling` is the reviewer's verdict in one word: `swallowed` (empty body) is a defect unless a comment proves
it deliberate; `rethrows` is fine when the cause is kept (`new X('...', e)`); `logged` is a gap when callers
need to react to the failure; `returns-default` is by design at a parsing or lookup boundary and a gap
elsewhere; `other` means read the body. The variable name (`e`, `ignored`) is the developer's choice, not a
verdict. `implicitType: true` means the header names no type, so Groovy catches `Exception` under a variable
that only looks like a type: report that as a defect.

## Report

A summary line first (counts per `handling`, worst first), then at most ten rows per group with file, line,
the verdict (defect, gap, by design) and the fix. Never list more than ten rows without the summary.
