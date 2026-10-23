---
name: groovy-design
description: Improve a class's design with groovyscope: class_design names its responsibilities, cohesion groups and refactoring candidates; extract_method, move_member and change_signature perform them as gated edits.
---
# Class design

Use for "this class is too big", "refactor this", "where should this method live", and before changing a class.
Target: `$ARGUMENTS` (a class, or a file).

## Steps

1. `groovyscope.class_design(symbol)` (or `path`): responsibilities by verb, cohesion (the groups of methods that
   share fields; `separable` groups are a class of their own), feature envy, shared parameter lists, type
   switches, mutable state, and `suggestions` with the tool call for each.
2. Pick one suggestion at a time, the one the user's request is about first.
3. Perform it with the tool it names, as a dry run: `extract_method` (it says first when existing code already
   does it), `move_member`, `change_signature` or `edit_symbol`. Read each file's `gate` and the `tests`.
4. Apply each file's plan with `write` (`str_replace`, the given `old_str` and `new_str`), then
   `groovyscope verify` for the tests the change reaches.
5. `groovyscope.class_design` again: the finding you addressed is gone.

## Report

What was moved, extracted or changed, why (the design fact behind it), and the suggestions left for later.
