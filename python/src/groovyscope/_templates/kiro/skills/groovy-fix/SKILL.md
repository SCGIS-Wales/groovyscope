---
name: groovy-fix
description: Review the changed files against the base branch, apply groovyscope's safe mechanical fixes, then show what is left for a person.
---
# Review my changes and fix what is safe

Use for "fix what is safe", "clean up my changes". Base branch: `$ARGUMENTS` when given, else `main`.

## Steps

1. `groovyscope.review_changes(base)` for the changed files and their findings.
2. `groovyscope.fix(paths=<changed files>, apply=true)`. Safe fixes never change behaviour: `intdiv`, typed
   parameter reads, `@CompileStatic` where the class then type-checks, unnecessary semicolons, Spock `with`.
3. `groovyscope.review(paths=<changed files>)` again and fix every remaining error and warning by hand,
   explaining each with `groovyscope.explain(code)` where needed. Never hand-align code: formatting goes
   through `groovyscope.format` only.

## Report

What was fixed automatically (file, line, code), what you fixed by hand, and what is left with the reason
it needs a decision. A `missingDependencies` summary (GS100) means no classpath is configured: say so and
suggest `groovyscope classpath`; do not edit code to silence unresolved library imports.
