---
name: groovy-merge
description: Resolve the conflicts of a merge, rebase or cherry-pick (a GitLab merge request brought up to date locally) hunk by hunk with groovyscope, then check nothing was lost.
---
# Resolve merge conflicts

Use when git stopped on conflicts: "resolve the conflicts", "finish this merge", "the merge request has
conflicts". Target: `$ARGUMENTS` (a file, or every conflicted file).

## Steps

1. `groovyscope.merge_conflicts()`: the operation in progress, each unmerged file with its hunk count, and
   changed files that still hold markers. Never grep the tree for markers: underlines in licence files and
   binaries match, and staged files are missed.
2. Per file, `groovyscope.merge_conflicts(path)`: each hunk as numbered lines (ours, base, theirs) with a
   `suggestion` where one side left the lines unchanged, and `keepingBoth` when keeping both sides repeats a
   line git moved out of the hunk. Read the hunks without a suggestion, and the code around them, before
   choosing; for a large side, `merge_conflicts(path, hunk=N)`.
3. `groovyscope.resolve_conflicts(path, resolutions=[{hunk, take}], suggested=true)` as a dry run: check each
   `join` and that `parses` is true, then the same call with `apply=true`. One call per file, never an edit of
   single marker lines: removing a marker on its own leaves the rest of its hunk behind. When neither side is
   right, give the hunk's `text`.
4. `groovyscope.merge_review()`: declarations the result lost, deletions it undid, changes it dropped. Fix
   each finding, or say why it is intended.
5. Ask before `git add` and the commit; run the affected tests first (`./gradlew test` or the project's own
   command). A test that merged cleanly can still assert the other side's behaviour.

## Report

Per file: the hunks and the choice for each, with the reason when it was not a suggestion; what
merge_review found and what was done about it; whether the result parses and what the tests said; what is
left for the user (staging, the commit, anything decided by hand).
