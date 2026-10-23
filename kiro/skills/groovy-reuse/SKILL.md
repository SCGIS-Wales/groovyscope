---
name: groovy-reuse
description: Before writing a helper or any method of six lines or more, find existing code that already does it with groovyscope suggest_reuse and explain_code helpers, and call it instead of writing a copy.
---
# Reuse before writing

Use before writing a helper, a parser, a retry loop, a formatter, a conversion, or any method of six lines or more.
Target: `$ARGUMENTS` (what you are about to write, as text or a description).

## Steps

1. `groovyscope.suggest_reuse(text)` with the code you are about to write (a draft is enough), or
   `suggest_reuse(symbol, path)` for an existing method: matches by shape (`exact`, `similar`), by name and
   parameter types (`signature`), and by the calls they make (`calls`), each with a score and a reason.
2. `groovyscope.explain_code(symbol)` on the class you are editing: its `helpers` lists the utilities in reach.
3. Call what exists when it does the job. When it nearly does, change it (with `edit_symbol` or
   `change_signature`) rather than writing a second version; when two copies exist, extract one with
   `extract_method` and call it from both.
4. Write new code only when nothing matches; the edit gate holds a write that repeats existing code back (GS080).

## Report

What was reused and where it lives, or why nothing fitted.
