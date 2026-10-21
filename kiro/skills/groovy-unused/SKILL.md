---
name: groovy-unused
description: List and remove unused imports, variables, parameters, private methods and fields, and unreachable code, in files or on the lines a branch changed, with groovyscope.
---
# Unused code

Use for "what is unused", "clean up dead code", "remove unused imports and parameters", and before finishing any
change. Target: `$ARGUMENTS` (files or a folder; the branch's changed lines when the request is about a merge
request).

## Steps

1. `groovyscope.find_unused(paths)`, or `find_unused(changed_only=true)` for a branch: every unused import,
   variable, parameter, private method and field, and unreachable statement, counted by kind. Nothing else is
   listed, so a file with hundreds of style findings cannot hide them.
2. Imports marked `fixable`: `groovyscope.fix(paths, codes=["UnusedImport", "UnnecessaryGroovyImport",
   "DuplicateImport"], apply=true)` removes them safely.
3. Everything else by hand, one at a time:
   - a variable: delete it, but keep its right-hand side as a statement when the call has an effect the code
     relies on;
   - a parameter: `groovyscope.impact_of(method, change="signature")` lists every call; remove the argument at
     each, unless an interface or override requires the parameter (then keep it and say why);
   - a private method or field: delete it after checking nothing reaches it by name (reflection, a framework,
     serialisation);
   - unreachable statements: delete them, or move them before the jump when they were meant to run.
4. `groovyscope.find_unused` again on the same target: it must report 0, then `groovyscope.check` the files for
   anything the edits broke.

## Report

The counts before and after by kind, what was removed and where, and anything kept on purpose with the reason
(an interface parameter, a reflective use).
