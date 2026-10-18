---
name: groovy-rename
description: Rename a Groovy class, method, property or variable everywhere it is used, through groovyscope's verified rename: dry run first, refused on name-only matches, rolled back on new errors.
---
# Safe rename

Use for "rename X to Y everywhere". Target and new name: `$ARGUMENTS`.

## Steps

1. Locate the declaration (`groovyscope.find_symbol`, then `goto_definition`) to get the path, line and
   column.
2. `groovyscope.rename(path, line, col, new_name)` as a dry run: the files and edits it would make, or a
   refusal when any use is matched by name only.
3. After the user confirms, the same call with `apply: true`. The changed files are recompiled and the
   rename is rolled back if it leaves new errors.

## Rules

Never rename by text search and replace, and never accept a rename done that way. When the rename is
refused, list the name-only sites and let the user decide per site.
