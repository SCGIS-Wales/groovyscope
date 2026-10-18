---
name: groovy-duplicates
description: Find duplicated code in a folder with groovyscope and say which methods to merge, largest group first.
---
# Duplicated code

Use for "find duplicated code", "what is copy-pasted here". Scope: `$ARGUMENTS` (a folder) when given,
else the workspace.

## Steps

1. `groovyscope.find_duplicates(paths)`: `EXACT` groups are copies, `SIMILAR` groups differ only in
   identifiers and literals; every location is listed, largest first.
2. `groovyscope.read_symbol` on the members of the largest group before proposing the merge.

## Report

Per group: the locations, the size in lines, whether it is exact or similar, and the concrete merge (which
method stays, what parameters it gains, who calls it). Say when a duplicate is deliberate (test fixtures,
generated code) and should stay.
