# Kiro CLI checks: what to run on a client machine and what to report

One pass through this page checks every stage that waits on the owner's Kiro check (stages 9 to 16, 20 and 21)
and the memory work of stages 17 to 19. Sections A, K and L cost no credits; B to J are prompts and together
come to about 6 to 9 credits on the default model. Section M is the paid benchmark and is optional. At the end,
`groovyscope checks report` prints the block to paste back.

Every prompt names the tools it should call. Kiro prints each tool call: a prompt that ends in `grep`, `cat` or
`find` instead means the steering did not load (see [kiro.md](kiro.md#is-the-steering-loaded)); note it in the
report.

Use Kiro's terminal UI, the default (`kiro-cli chat` without `--classic`): the classic chat is deprecated with
Kiro CLI 3.0, invokes prompts as `@groovy-impact` instead of `/groovy-impact`, and asked no groovyscope question
before a write in the first check.

History: the first check (0.18.0, Kiro CLI 2.29.1, classic) passed the wiring, the `code` tool on `.groovy`
files, the navigation prompts, rename and parallel requests, and found what Stage 21 fixed (see the roadmap).

## 0. Prepare (terminal, one command per line)

```sh
uv tool upgrade groovyscope
groovyscope version
groovyscope kiro repair --global
groovyscope checks prepare
```

`groovyscope version` should say 0.19.0 or later (`pipx upgrade` or `brew upgrade groovyscope` if you installed
it that way). `checks prepare` builds the workspace in `<tmp>/gs-check` (on Linux `/tmp/gs-check`): the CodeNarc
sources, a `build.gradle`, a `Jenkinsfile`, git history with `main` and a checked-out `change` branch that adds
one reviewable change, the project's Kiro LSP entry and trust. It does not change your shell's directory; it
prints the two commands to run next:

```sh
cd /tmp/gs-check
kiro-cli chat --agent groovy
```

To start again from scratch: `groovyscope checks prepare --force` (it only ever replaces a workspace it made).

## A. Session start and wiring (no credits)

```text
/code status
/mcp
/tools
/context show
/prompts
```

Expect `groovyscope (groovy) - initialized`, groovyscope loaded with 28 tools, the twelve `groovy-*` prompts,
and a welcome text with no tool count in it. Note the first line the agent printed (the start hook's checkup
line), the `/tools` total for groovyscope (2.5k tokens in the first check) and the `/context show` figure for
`steering/groovyscope.md`.

## B. The `code` tool on Groovy, Gradle and Jenkinsfile (Kiro#6322)

```text
Use the code tool to show the document symbols of build.gradle and of Jenkinsfile.
Use the code tool to show the document symbols of src/main/groovy/org/codenarc/CodeNarcRunner.groovy, then find the references of execute.
```

Expect symbols for both build files. For `CodeNarcRunner`, `execute` should now be listed on line 82 (its
name), not 81 (its `@SuppressWarnings`), so the references call lands on the method the first time and finds
three callers. Report any "unsupported file extension" and for which file.

## C. Navigation (one prompt per line, or all at once)

```text
What is CodeNarcRunner.execute, what does it return, and how many places use it?
List the catch blocks that catch Exception and do not rethrow.
```

The first should call `symbol_context`: the use in `CodeNarc.groovy` is now inferred, not dynamic (the
`CodeNarcTask` ones stay dynamic: their receiver is an untyped parameter). The second should call `find_code`
and call `AbstractReportWriter.initializeDefaultResourceBundle` a defect, with `catch (MissingResourceException e)`
as the fix: its header names no type, so it catches every Exception.

## D. Fixes, formatting and rename (stages 13, 20 and 21)

```text
Apply the safe fixes in src/main/groovy/org/codenarc/report/JsonReportWriter.groovy.
```

Kiro's own approval comes first. Then either a groovyscope question ("Apply safe fixes to 1 file(s)?" with the
file and its changed lines) or, when Kiro offers no questions, an answer that says `"confirmation": "not offered"`.
Report which. If the question appears: answer **no** first (`git status` in another terminal stays clean), then
ask again and answer **yes**.

The answer should list `fixed` (CompileStatic 1, NoDef and VariableTypeRequired 1 each) with about 4 changed
lines, not 126, and `left` (the `def` locals whose type is unknown without a classpath) with a note to fix them
by hand. `git diff` should show `import org.codenarc.rule.Rule` next to `Rule rule = violation.rule`.

```text
Rename the method buildPackageAttributeMap in JsonReportWriter to packageAttributes: dry run first, then apply.
```

Expect a dry run, then `verified: the changed files compile with no new errors`. Reset with
`git checkout -- src` afterwards.

## E. Hooks (stage 15)

```text
This is a deliberate test of the after-write hook. Append exactly this method inside the class in src/main/groovy/org/codenarc/report/XmlReportWriter.groovy and do not correct it: int broken() { return "x" + }
```

Expect a `groovyscope checked src/main/groovy/org/codenarc/report/XmlReportWriter.groovy: 1 error(s)` block in
the same turn, right after the write. If the agent still refuses to write broken code, run the hook by hand in a
second terminal in the workspace:

```sh
groovyscope daemon restart
printf '  int broken() { return "x" + }\n' >> src/main/groovy/org/codenarc/report/XmlReportWriter.groovy
echo '{"hook_event_name":"postToolUse","tool_name":"write","tool_input":{"path":"src/main/groovy/org/codenarc/report/XmlReportWriter.groovy"}}' | groovyscope hook
git checkout -- src
```

Stop gate: run `printf 'hooks:\n  stopGate: true\n' >> .groovyscope.yaml`, restart the chat, then:

```text
This is a deliberate test of the stop gate. Add a syntax error to src/main/groovy/org/codenarc/report/XmlReportWriter.groovy and end your turn without fixing it.
```

Expect the turn to be refused with `groovyscope: 1 compile error(s) in the files you changed`. Afterwards
`git checkout -- src .groovyscope.yaml`. The `1 of 1 hooks finished` line after each turn should now show well
under a second (it was 1.1 s).

## F. Skills (stage 15)

```text
/groovy-impact CodeNarcRunner.execute
```

In the classic chat the same prompt is `@groovy-impact CodeNarcRunner.execute`. Expect `impact_of`, then
callers, tests and risk.

## G. Merge request review (stages 11 and 12)

```text
Review this branch against main as a merge request reviewer.
```

The workspace is on the `change` branch, which adds `describe()` to `JsonReportWriter` with a catch that
swallows every exception. Expect `review_changes`: a verdict, that catch as a finding on the changed lines, the
impact of the changed declarations and draft comments.

## H. Classpath round trip (stages 12 and 20; needs a real Gradle or Maven project)

The CodeNarc copy has no real build, so use one of your own projects:

```sh
cd <your project>
groovyscope kiro init
groovyscope trust
groovyscope classpath
```

Then add a dependency to `build.gradle` and in the chat:

```text
Is the classpath still fresh?
Refresh the classpath.
```

Expect `stale`, then Kiro's approval and (when Kiro offers questions) "Run the gradle build of <project> to
export its classpath?"; `fresh` afterwards. While a refresh runs, `groovyscope classpath` in a second terminal
should answer `a classpath export is already running for this workspace`.

## I. Diagram and the practices resource (stages 11, 20 and 21)

```text
Draw the class relationships of src/main/groovy/org/codenarc/report into docs/report.drawio.
Read the groovyscope practices resource for GS001.
```

The folder alone should be enough now (no list of class names). The answer gives `writtenTo`, the absolute path
of the file. Open it in diagrams.net: base classes centred over their subclasses, and no line running over a box.
For the second prompt note whether Kiro read the resource (`groovyscope://practices/GS001`) or called `explain`.

## J. Parallel requests and memory (stages 17 to 19)

```text
In parallel, run a full quality check of the whole workspace and show the outline of src/main/groovy/org/codenarc/CodeNarcRunner.groovy.
```

Expect both answers, the outline promptly while the check runs. The full check should still report exactly 2
errors (GS006 in `GroovyDslRuleSet` and `RuleSetUtil`).

Out of memory drill, on the throwaway workspace only, in the terminal:

```sh
export GROOVYSCOPE_DAEMON__HEAP=64m GROOVYSCOPE_DAEMON__MAXHEAP=256m
groovyscope daemon restart
groovyscope check --format short > /dev/null; groovyscope daemon status
groovyscope doctor
unset GROOVYSCOPE_DAEMON__HEAP GROOVYSCOPE_DAEMON__MAXHEAP
groovyscope daemon stop
```

Run the `check` line up to four times. Expect either `groovyscope engine is low on heap` (the pressure guard),
or an `OutOfMemoryError` exit after which `daemon status` notes a grown heap (96m, at most 256m) and, by the fourth
quick restart, `the daemon was started 3 times in the last ten minutes ... not starting it again`. Afterwards
delete `<key>.state.json` next to the log path `daemon status` prints.

## K. Tool surface and `disabledTools` (stage 16, no credits)

Add `"disabledTools": ["coverage_gaps"]` to the groovyscope entry in `~/.kiro/settings/mcp.json` and restart the
chat. Expect 27 tools and a lower `/tools` figure. Run `groovyscope kiro repair --global`: it prints a short
summary per file now (`--diff` for the details), and the `disabledTools` line must still be there. Remove it
afterwards.

## L. Kiro 3 (V3 engine, stage 13)

```sh
groovyscope kiro repair --global --agent-format universal
kiro-cli chat --agent groovy --agent-engine v3
```

```text
Show the outline of src/main/groovy/org/codenarc/CodeNarcRunner.groovy.
Apply the safe fixes in src/main/groovy/org/codenarc/report/JsonReportWriter.groovy.
```

Expect the start hook's checkup line, the outline without an approval prompt (read-only rule) and the fix to
ask (mutating rule).

## M. Benchmark (stage 14, optional, about 5 credits)

Tasks 3 and 4 of [benchmark.md](benchmark.md) on the corpus, with and without groovyscope. Done when each is
under 2 minutes with groovyscope.

## Report

In the workspace, after the checks:

```sh
groovyscope checks report
```

It prints the versions, every `doctor` line (with `mcp client`: which client called the tools and whether it can
be asked before a write), the hook start-up time and the result table. Fill in the table and paste the whole
block back.
