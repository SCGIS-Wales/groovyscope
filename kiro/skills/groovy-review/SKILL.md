---
name: groovy-review
description: Review the current branch as a merge request reviewer with groovyscope: verdict, blocking and should-fix findings, impact, risky constructs, uncovered methods; drafts comments for GitLab.
---
# Review this branch as a merge request reviewer

Use for "review this branch", "review my merge request", "review my changes". Base branch: `$ARGUMENTS`
when given, else `main`; "only what is staged" means `staged: true`.

## Steps

1. `groovyscope.review_changes(base)`: changed files and lines from git, every finding on the changed lines
   grouped `blocking`, `shouldFix` and `nits`, pre-existing findings as counts, `impact` of changed public
   declarations, `risky` constructs added, `uncovered` methods (when a JaCoCo report exists), a `verdict`.
2. If `classpath.state` is `stale`, say so and offer `groovyscope.refresh_classpath` before judging
   unresolved classes (it runs the build; trusted workspaces only).
3. `groovyscope.explain(code)` for any finding whose meaning you are not sure of.

## Report, in this order

- The verdict in one sentence: `request changes`, `approve with comments` or `approve`.
- Blocking items with file and line: errors, and anything from the security and runtime-profile groups.
- Should-fix items (warnings on the changed lines); offer `groovyscope.fix` for the mechanical ones.
- What is by design, and why.
- What the change breaks elsewhere (`impact`: callers, transitive callers, tests, risk per declaration).
- Uncovered methods among the changed files, with an offer to write Spock specs.
- Pre-existing findings in the touched files as counts only. No style remarks when there are blocking items.

## Posting on GitLab

The answer carries `comments` (one per finding: `path`, `line`, `kind`, `body` written to the author) and a
`summaryComment`. With a GitLab MCP server in the session, offer to post them; after a yes, create one inline
discussion per comment at that path and line, then the summary as a note. Post the bodies unchanged, never
the `elsewhere` counts, never a finding twice, and never approve or merge on the user's behalf. Without a
GitLab server, show the comments as a table (file, line, text) ready to paste.
