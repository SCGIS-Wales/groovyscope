---
name: groovy-profile
description: Answer runtime profile questions for a file governed by one: which Groovy version and service it really runs on, whether a construct is proven there, and what must not change.
---
# Runtime profile questions

Use for "can I use X in this plugin", "what runs this file", and before editing any file a runtime profile
governs. File: `$ARGUMENTS`.

## Steps

1. `groovyscope.profile_status(path)`: the Groovy version and service the file really runs on, the descriptor
   parameters, the test seams, the protected methods, and the status of each construct (proven, unproven,
   banned).
2. `groovyscope.explain(code)` for an RP code.

## Rules

Use only constructs the profile marks as proven; RP001 and RP002 findings are blocking. Keep parameter names
and types, output keys, exception texts and test seam visibility unchanged unless the task is to change that
contract. Never edit a protected method unless asked.
