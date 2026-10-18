---
name: groovy-explain
description: Explain what a Groovy class, method or file does from groovyscope's facts (members, calls, callers, state written, exceptions, external world), optionally with a draw.io or Mermaid relationship diagram.
---
# Explain what code does

Use for "what does X do", "explain this class", "how does this work", "draw the classes around X".
Target: `$ARGUMENTS`.

## Steps

1. `groovyscope.explain_code(symbol)` (or `path` for a file): every member with its type, each method's
   parameters and return, the calls it makes and receives, the fields it writes, the exceptions it throws
   and catches, the external world it touches (http, file, sql, process, environment, clock, random,
   logging), public methods grouped by their leading verb, the classes it uses and is used by.
2. `groovyscope.read_symbol` only where a fact needs the code.
3. For structure questions, `groovyscope.relationship_graph(symbols, depth, drawio_path="docs/<name>.drawio")`
   and tell the user to open the file in diagrams.net; add `mermaid: true` when the answer itself should show
   the diagram; `level: method` for one class's methods and what they call.

## Report

Write the explanation from the facts, never from names: a method called `validate` that writes three fields
is described as a method that writes three fields. Responsibilities first (the verb groups), then
dependencies, then state and failure modes.
