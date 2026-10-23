---
name: groovy-edit
description: Change Groovy code through a planned, gated edit: plan it with groovyscope, read the diff and the advice, apply it with Kiro's write so the user sees the diff, then read the after-write block.
---
# A planned edit

Use for "change this method", "add a member", "add an import", "remove this method", and any edit of a Groovy,
Gradle or Jenkinsfile file. Target: `$ARGUMENTS` (a symbol and what to change).

## Steps

1. `groovyscope.read_symbol(path, symbol)` to see the declaration as it is.
2. `groovyscope.edit_symbol(path, symbol, operation, text)` as a dry run (`replace`, `replace_body`,
   `insert_before`, `insert_after`, `delete`, `add_member`, `add_import`). Read its `gate` (a `block` decision and
   its `reasons` mean the text is wrong: fix the text and plan again), its `advice` (a method like one that exists,
   a helper that only delegates), and its `tests` (what reaches the change).
3. Apply the plan with `write`, command `str_replace`, the `path`, `old_str` and `new_str` exactly as given: the
   user sees Kiro's diff and answers its prompt. The hook says `planned write applied`, or that the file drifted:
   then read the declaration again and plan again.
4. Read the `groovyscope checked ...` block after the write and fix what it reports.

## Report

What changed and where, the gate's verdict, and the tests that reach the change (run them with
`groovyscope verify`).
