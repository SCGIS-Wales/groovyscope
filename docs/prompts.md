# Prompts cookbook: what to ask the groovy agent, and how to read the answers

The `groovy` agent (`kiro-cli --agent groovy`) is a merge request reviewer and quality improver for Groovy,
Gradle and Jenkinsfile code. This page lists the prompts that get the most out of it, which groovyscope tools
each one should call (watch the tool names Kiro prints; a prompt that ends in `grep` or `cat` means the
steering did not load, see [docs/kiro.md](kiro.md#is-the-steering-loaded)), how to read the answer, and
what each kind of finding means. Credit costs are from runs on Kiro CLI 2.29 with the default model; your
numbers will differ, but the ratios hold.

## At the start of a session

Nothing to type. The agent's start hook runs `groovyscope checkup --agent`, which keeps `.groovyscope/` out of
git and reports the rest: Kiro files left behind by an upgrade, a missing project LSP entry or benchmark corpus
(all fixed by `groovyscope checkup --fix`), and the two things that need your decision, trusting the repository
and exporting its classpath. The agent opens with one line about that and offers the menu below. Answer "yes"
to the repairs, and to `groovyscope trust` and `groovyscope classpath` once per repository you trust; library
classes then resolve and the GS100 warnings go away.

## The menu

| Prompt | Tools | Cost |
|---|---|---|
| Review this branch as a merge request reviewer | `review_changes` | 0.3 to 0.8 |
| Review my changes and fix what is safe | `review_changes`, `fix` | 0.5 to 1.2 |
| Run a full quality check on this project | `check`, `review` | 0.3 to 0.6 |
| Find duplicated code in `<folder>` | `find_duplicates`, `read_symbol` | 0.2 to 0.4 |
| List the catch blocks that swallow exceptions | `find_code catch` | 0.2 |
| Where does this project rely on dynamic Groovy | `find_dynamic_code` | 0.2 |
| Which classes and methods have no test coverage | `coverage_gaps` | 0.2 |
| What breaks if I change `<Class.method>` | `impact_of` | 0.2 to 0.4 |
| Rename `<Class.member>` to `<name>` everywhere | `rename` (dry run, then apply) | 0.3 |
| Why is line `<n>` of `<file>` flagged | `check`, `explain` | 0.2 |
| Explain what `<Class>` does | `explain_code`, then `read_symbol` where needed | 0.3 to 0.5 |
| Draw the class relationships around `<Class>` | `relationship_graph` with `drawio_path` | 0.2 to 0.4 |
| Are the names in `<folder>` right | `check_naming`, then `rename` | 0.4 to 0.8 |
| Can I use `<construct>` in this plugin | `profile_status` | 0.2 |
| Resolve the merge conflicts | `merge_conflicts`, `resolve_conflicts`, `merge_review` | 0.3 per file |
| What is unused in `<folder>`, and remove it | `find_unused`, then `fix` and hand edits | 0.2 to 0.5 |

"Run all" runs the first six and gives one combined summary. Every row is also a skill, `/groovy-<name>`
(review, fix, quality, duplicates, catch-audit, dynamic, coverage, impact, rename, explain, naming, profile,
merge, unused),
and an MCP prompt of the same name; the skill body is this page's recipe, loaded only when it runs.

## 1. Review this branch as a merge request reviewer

```text
Review this branch as a merge request reviewer. Base branch is main.
```

Calls `review_changes(base="main")`. Expect, in this order: a one-sentence verdict (`request changes`,
`approve with comments` or `approve`); **blocking** findings with file and line (errors, and anything from
the security and runtime-profile groups, on the changed lines only); **should fix** (warnings on the changed
lines); what is **by design**; what the change breaks elsewhere (`impact`: callers, tests, risk per changed
declaration); the risky constructs the change added (catch blocks, `def`, `return null`); methods in the
change that no test executes (when a JaCoCo report exists); and pre-existing findings in the touched files as
counts only. SpotBugs findings from the build's SARIF report appear next to groovyscope's own.

Add `against develop` or `only what is staged` to change the base or review the index.

### Post the review on the merge request

```text
Review this branch as a merge request reviewer and post the comments on merge request !42.
```

The answer carries `comments` (one per finding: file, line, a body written to the author with the reason and
a concrete suggestion, respectful and inviting) and a `summaryComment` (thanks, the verdict in plain words,
the counts). With a GitLab MCP server in the session the agent posts each comment as an inline discussion on
the diff and the summary as a note, after you say yes; it never approves or merges. Without one, it shows the
comments as a table to paste. To add GitLab's MCP server to Kiro, put its entry next to groovyscope in
`~/.kiro/settings/mcp.json` (the URL is your GitLab's, the tool names come from that server):

```json
{
  "mcpServers": {
    "gitlab": { "url": "https://gitlab.example.com/api/v4/mcp" }
  }
}
```

### When the classpath is stale

A `check` or `review_changes` answer with `classpath.state: stale` means a build file changed after the last
`groovyscope classpath`, so classes from a new dependency do not resolve yet. Say:

```text
Refresh the classpath, then review again.
```

Calls `refresh_classpath()` (it runs the build, so only in a trusted workspace) and the engine picks the new
classpath up on its next request. No restart of Kiro or of the daemon.

## 2. Review my changes and fix what is safe

```text
Review my changes against main and apply the safe fixes, then show me what is left.
```

Calls `review_changes`, then `fix(paths, apply=false)` on the changed files as a dry run and shows you what it
would change; after your yes it calls `fix(paths, apply=true)`, then `review` again. Without codes named, `fix`
applies only fixes that keep behaviour (the `preserving` entries of the practices catalogue, and the
semicolon and whitespace fixes); a fix that changes behaviour, such as `intdiv` (GS001) or Spock `with(obj)`
(GS011), is offered one by one and applied only once you agree to that code. The `groovy-fix` skill lists both
sets, generated from the catalogue. Anything else is listed for you.

## 3. Full quality check

```text
Run a full quality check on this project and summarise by group, worst first.
```

Calls `check` (counts per code under `byCode`) and `review` (grouped by the practices checklist). Read the
groups top down: static compilation, explicit types, numbers and money, Groovy truth, strings, null safety,
immutability, exceptions and logging, security, concurrency, testing, style. Ask "fix the safe ones" next.

## 4. Duplicated code

```text
Find duplicated code in src/main/groovy/org/codenarc/report and tell me which methods to merge.
```

Calls `find_duplicates(paths=[...])`, then `read_symbol` on the largest group. `EXACT` groups are copies;
`SIMILAR` groups differ only in identifiers and literals. On the benchmark corpus this finds the 11-line copy
of `buildPackageAttributeMap` in `JsonReportWriter` and `XmlReportWriter`.

## 5. Catch blocks

```text
List the catch blocks in src/main/groovy that catch Exception and do not rethrow.
```

Calls `find_code("catch", type="Exception", rethrows=false)`. Every match carries `handling`, the reviewer's
verdict in one word:

| `handling` | What the block does | Usually |
|---|---|---|
| `swallowed` | The body is empty: the failure disappears | A defect, unless a comment says why |
| `rethrows` | A `throw` passes the failure on | Fine when the cause is kept (`new X('...', e)`) |
| `logged` | A logger or print call, then execution continues | A gap when callers need to react |
| `returns-default` | A value stands in for the failed result | By design at a parsing boundary, a gap elsewhere |
| `other` | State changes, a flag is set, a callback runs | Read the body |

`variable` is the developer's own name (`e`, `ignored`); it says nothing about correctness. `implicitType:
true` means the header names no type (`catch (MissingResourceException)`), so Groovy declares a variable
called `MissingResourceException` of type `Exception`: report it as a defect. `code` is the CodeNarc rule
(`EmptyCatchBlock`, `CatchException`, `CatchThrowable`) to pass to `explain`.

## 6. Dynamic Groovy

```text
Where does this project rely on dynamic Groovy, and which of it is worth making static?
```

Calls `find_dynamic_code`. Classes with neither `@CompileStatic` nor `@CompileDynamic`, `def` on public
methods, `methodMissing`, `metaClass` writes, `Eval`, `@Grab` and string `execute()` all defeat static
analysis: navigation and impact answers around them are `DYNAMIC_UNKNOWN`, matched by name only.

## 7. Test coverage

```text
Which classes and methods have no test coverage? Start with the ones that matter most.
```

Calls `coverage_gaps` on the build's JaCoCo XML report (`./gradlew test jacocoTestReport` first; the path is
`reports.jacoco`). `uncovered` methods have no executed line; `partial` ones have some. Follow with "write a
Spock spec for `<Class.method>`"; the agent reads the method with `read_symbol` and its callers with
`call_graph` before writing.

## 8. Impact before a change

```text
I want to add a required parameter to OrderService.place. What breaks, which tests cover it, what should I check by hand?
```

Calls `impact_of("OrderService.place", change="signature")`. Direct and transitive callers are static
facts; `dynamicUnknowns` are call sites matched by name only and need a human look; `riskLevel` combines
callers, overrides, dynamic sites and visibility.

## 9. Safe rename

```text
Rename the property Report.lines to Report.rows everywhere it is used.
```

Calls `rename` as a dry run first, then with `apply: true` after you confirm. It refuses when any use is
matched by name only, so a rename never touches a same-named thing elsewhere. Never accept a rename done by
text replacement.

## 10. Why is this flagged

```text
Why is line 71 of src/main/groovy/app/XmlReportUtil.groovy flagged, and what is the fix?
```

Calls `check(paths=[file])` and `explain(code)`. The answer names the practice, why it matters and the fix,
from the catalogue in [docs/practices.md](practices.md).

## 11. Explain what a class does

```text
Explain what ResourceExecutionPlugin does, its main responsibilities and what it depends on.
```

Calls `explain_code(symbol="ResourceExecutionPlugin")`: every member with its type, each method's parameters
and return, the calls it makes and receives, the fields it writes, the exceptions it throws and catches, the
external world it touches (http, file, sql, process, environment, clock, random, logging), public methods
grouped by their leading verb (the responsibilities), and the classes it is used by and uses. The agent writes
the explanation from those facts and reads a method with `read_symbol` only where a fact needs the code. It
never guesses from names: a method called `validate` that writes three fields is described as a method that
writes three fields.

## 11a. Draw the class relationships

```text
Draw the classes around OrderService, two hops, as a draw.io diagram at docs/order-service.drawio.
```

Calls `relationship_graph(symbols=["OrderService"], depth=2, drawio_path="docs/order-service.drawio")`. The
file opens in diagrams.net (or the draw.io desktop and VS Code extensions): one page on A4, A3 or A2, the smallest that keeps the text readable, base classes and
callers above what they use, a calm palette with one colour per package, hollow arrows for `extends`, dashed
for `implements`, diamonds for `has`, dashed open arrows for `uses`, solid for `calls` with the call count,
grey dotted edges for calls matched by name only, dashed boxes for tests and classes outside the workspace,
and a legend. Ask for `level: method` to see one class's methods and what they call. When the graph is too
big for a readable page the answer says so: narrow the seeds or lower the depth. `groovyscope graph` does
the same from a terminal.

## 11b. Check the names

```text
Are the classes, methods and variables in src/main/groovy/app named well? Propose renames.
```

Calls `check_naming(paths=[...])`: the GS020 to GS027 findings (booleans not phrased as questions, getters that
change state, verbs and nouns swapped, names that say nothing, abbreviations, type prefixes, names that
contradict their type, mixed vocabulary for one idea) and, for every class, field and method, the facts a
reviewer uses to judge a name: the type, what the method writes and touches, how often it is called. The agent
then says, per name, whether it describes what the thing is and does, and proposes a better one. Every rename
you accept goes through `rename` (dry run, then apply; refused on name-only matches; the changed files are
recompiled and the rename is rolled back if it leaves new errors). Your own shorthand goes under
`naming.allowedAbbreviations`, your verb choices under `naming.vocabulary`.

## 12. Runtime profile questions

```text
Can I use Set.of in this plugin?
```

Calls `profile_status(path)`: the Groovy version and service the file really runs on, and whether the
construct is proven there. RP001 and RP002 findings are blocking.

## 13. Merge conflicts

```text
Resolve the merge conflicts.
```

Bringing a GitLab merge request up to date locally (`git fetch origin && git merge origin/main`) stops on
conflicts. `merge_conflicts` lists the unmerged files and any changed file that still holds markers (from git,
so licence underlines under `.terraform/` and binaries never match). `merge_conflicts PATH` numbers each hunk's
lines and suggests a choice where one side left the lines unchanged, both added imports, or both added code
where the base had none; the base comes from git's index when the markers lack it. `resolve_conflicts` then
resolves a whole file in one call, so Kiro asks for approval once, not once per marker: a dry run shows each
join, and the Groovy result is parsed (syntax only, unresolved imports do not matter) before `apply` writes it.
Keeping both sides repeats the line git moved out of the hunk, typically the `}` two added test methods share,
which is the edit that most often goes wrong by hand. `merge_review` closes the loop: tests or methods the
result lost, deletions it undid, changes it dropped. It cannot see a test that merged cleanly and now asserts
the other side's behaviour, so the recipe ends with the affected tests. While a file still holds markers, the
after-write hook says so in one line instead of checking it, and `groovyscope merge status | show | resolve |
review` gives the same answers in a terminal. Set `git config merge.conflictStyle zdiff3` once so every hunk
shows its base.

## 14. Unused code

```text
What is unused in src/main/groovy, and remove it.
```

`find_unused` lists every unused import, variable, parameter, private method and field, and unreachable
statement, and nothing else, so a file with hundreds of `LineLength` and `Indentation` findings cannot hide them.
The imports go with one `fix`; variables, parameters and private members are edited by hand (a parameter's
arguments go at every call, which `impact_of` lists). The same findings lead every review: `check`, `review` and
`review_changes` carry an `unused` block and, when there is any, an `attention` line that sorts first in the
answer, the review verdict and the merge request summary name them, and the after-write hook prints an `UNUSED:`
line. `groovyscope unused --changed` exits 1 on them, for a pipeline.

## Glossary

- **Blocking, should fix, by design.** The three buckets of a review. Blocking: an error on a changed line,
  or a security or runtime-profile finding. Should fix: a warning on a changed line. By design: a pattern that
  is right in this place (a parser returning null on bad input) and should be left alone.
- **Catch without rethrow.** A `catch` whose body never throws: the failure stops there. Whether that is a
  defect depends on `handling` (above).
- **Swallowed exception.** An empty catch block. The failure, including bugs such as a null pointer, vanishes.
- **`DYNAMIC_UNKNOWN`.** A reference or call site matched by name only, because Groovy could not resolve the
  receiver statically. Rename refuses on them; impact lists them for review.
- **GS100, missing dependencies.** No classpath is configured, so imports of library classes cannot resolve.
  One warning per file replaces the wall of unresolved-class errors. Fix: `groovyscope trust` then
  `groovyscope classpath` (runs the build), or list the jars under `workspace.classpath`.
- **Imported findings.** Findings from the build's own SARIF reports (SpotBugs) merged into `check` and
  `review`, marked with their tool as `source`.
- **Coverage gap.** A class or method with lines no test executed, from the JaCoCo report.
- **Classpath state.** `fresh`: the exported classpath matches the build files (a fingerprint of every build
  file is recorded at export). `stale`: a build file changed since, so new dependencies may be unresolved.
  `missing`: a build exists but nothing was exported. `configured`: jars listed by hand under
  `workspace.classpath`. `none`: no build tool, nothing to resolve.
- **RP codes.** Findings from a runtime profile: the code runs somewhere other than where it is built. See
  [docs/profiles.md](profiles.md).
- **Externals.** The categories `explain_code` reports when a method touches the world outside the JVM: `http`,
  `file`, `sql`, `process`, `environment`, `clock`, `random`, `concurrency`, `parsing`, `logging`, `exit`.
- **Verified rename.** After an applied `rename`, the changed files are recompiled; a rename that adds compile
  errors is rolled back and reported, so a same-named member elsewhere can never be renamed by accident.
