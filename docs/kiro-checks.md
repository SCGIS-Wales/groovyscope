# Kiro CLI checks: what to run on a client machine and what to report

One pass through this page checks every stage that waits on the owner's Kiro check (stages 9 to 16 and 20) and
the memory work of stages 17 to 19. Sections A, K and L cost no credits. B to J are prompts; together they come
to about 6 to 9 credits on the default model. Section M is the paid benchmark and is optional. Fill in the
report table at the end and paste it back.

Every prompt names the tools it should call. Kiro prints each tool call: a prompt that ends in `grep`, `cat`
or `find` instead means the steering did not load (see [kiro.md](kiro.md#is-the-steering-loaded)); note it in
the report.

## 0. Prepare (terminal)

```sh
uv tool upgrade groovyscope        # or: pipx upgrade groovyscope / brew upgrade groovyscope
groovyscope version                # groovyscope and engine 0.18.0 or later, plus the embedded CodeNarc version
groovyscope doctor                 # every line ok or info
groovyscope kiro repair --global
kiro-cli --version

# A throwaway copy of the bundled CodeNarc 4.0.0 sources, as a git repository
rm -rf /tmp/gs-check && cp -R ~/.groovyscope/benchmark/codenarc /tmp/gs-check && cd /tmp/gs-check
printf 'pipeline {\n  agent any\n  stages { stage("build") { steps { echo "hi" } } }\n}\n' > Jenkinsfile
printf 'plugins { id "groovy" }\n' > build.gradle
git init -q && git add -A && git commit -qm base
groovyscope kiro init
groovyscope trust
```

Reset the copy between sections with `git checkout -- . && git clean -fdq -e .kiro`.

## A. Session start and wiring (no credits)

```text
kiro-cli chat --agent groovy
> /code status       # expect: groovyscope (groovy) - initialized
> /mcp               # expect: groovyscope running 28 tools
> /tools             # note the token cost shown for the groovyscope tools
> /context show      # note the size of steering/groovyscope.md
> /prompts           # expect the twelve groovy-* prompts (or type / and look for them)
```

Report: the first line the agent printed (the start hook's checkup line, which proves `hooks.agentSpawn` ran),
the `/tools` figure for groovyscope and the `/context show` figure for the steering file.

## B. The `code` tool on Groovy, Gradle and Jenkinsfile (Kiro#6322)

```text
> Use the code tool to find the references of execute in src/main/groovy/org/codenarc/CodeNarcRunner.groovy.
> Use the code tool to show the hover for writeReport in src/main/groovy/org/codenarc/report/XmlReportWriter.groovy.
> Use the code tool to list the diagnostics of src/main/groovy/org/codenarc/report/XmlReportWriter.groovy.
> Use the code tool to show the document symbols of build.gradle and of Jenkinsfile.
```

Expect answers from the `code` tool. Report any "unsupported file extension" and for which file type.

## C. Navigation and understanding

| Prompt | Should call |
|---|---|
| Give me an overview of this workspace. | `workspace_overview` |
| What is CodeNarcRunner.execute, what does it return, and how many places use it? | `symbol_context` (new in 0.17.0) |
| Explain what XmlReportWriter does. | `explain_code` |
| Find duplicated code in src/main/groovy/org/codenarc/report. | `find_duplicates`, then `read_symbol` |
| List the catch blocks that catch Exception and do not rethrow. | `find_code` |
| What breaks if I change the signature of AbstractReportWriter.writeReport? | `impact_of` |

Expect for the duplicates prompt: an exact copy of `buildPackageAttributeMap` in `JsonReportWriter` and
`XmlReportWriter`. For the catch prompt: `AbstractReportWriter.initializeDefaultResourceBundle`.

## D. Fixes, formatting and rename with confirmations (stages 13 and 20)

```text
> Run a full quality check on src/main/groovy/org/codenarc/report.
> Apply the safe fixes in src/main/groovy/org/codenarc/report/JsonReportWriter.groovy.
```

For the second prompt expect two questions. Kiro's own approval comes first: the tool may run (V3 permission
rule, or the classic trust prompt). The groovyscope question comes second: "Apply safe fixes to 1 file(s)?",
with the file and its changed lines. Answer **no**: the answer says `the user said no; nothing was written`,
and `git status` in another terminal stays clean. Ask again and answer **yes**: `git diff --stat` shows the
file.

```text
> Format src/main/groovy/org/codenarc/report/XmlReportWriter.groovy and apply it.
> Rename the method buildPackageAttributeMap in JsonReportWriter to packageAttributes: dry run first, then apply.
```

Expect the same question before each write, and for the rename a dry run listing the files, then
`verified: the changed files compile with no new errors`. If no groovyscope question appears at all, Kiro does
not offer elicitation; report that, because the tools then fall back to Kiro's approval alone.

## E. Hooks (stage 15)

```text
> Add the method `int broken() { return "x" + }` to src/main/groovy/org/codenarc/report/JsonReportWriter.groovy.
```

Expect a block in the same turn, right after the write: `groovyscope checked
src/main/groovy/org/codenarc/report/JsonReportWriter.groovy: 1 error(s) ...`. The agent should then fix it
without being asked.

Stop gate: add `hooks:` and `  stopGate: true` to `.groovyscope.yaml`, restart the chat, then:

```text
> Add a syntax error to src/main/groovy/org/codenarc/report/XmlReportWriter.groovy and end your turn without fixing it.
```

Expect the turn to be refused with `groovyscope: 1 compile error(s) in the files you changed ...`. Remove
`stopGate` afterwards.

## F. Skills (stage 15)

```text
> /groovy-impact CodeNarcRunner.execute
```

Expect the recipe to run (`impact_of`, then a summary of callers, tests and risk). Report whether `/prompts`
or `/` listed the skills without a `skill://` resource in the agent file.

## G. Merge request review (stages 11 and 12)

```sh
git checkout -qb change
# edit any method in src/main/groovy/org/codenarc/report/JsonReportWriter.groovy, e.g. add a catch (Exception e) {}
git commit -qam change
```

```text
> Review this branch against main as a merge request reviewer.
```

Expect `review_changes`: a verdict, blocking and should-fix findings on the changed lines, the impact of the
changed declarations and draft comments. On a real repository with a GitLab MCP server you can also ask
"Post these comments on merge request !N" to check the posting step.

## H. Classpath round trip (stages 12 and 20; needs a real Gradle or Maven project)

The CodeNarc copy has no real build, so use one of your own projects:

```sh
cd <your project> && groovyscope kiro init && groovyscope trust && groovyscope classpath
```

Then add a dependency to `build.gradle` and in the chat:

```text
> Is the classpath still fresh?
> Refresh the classpath.
```

Expect `stale` from the first prompt. For the second, Kiro's approval and then the groovyscope question "Run
the gradle build of <project> to export its classpath?". Say yes; expect `fresh` afterwards. While a refresh
runs, `groovyscope classpath` in a second terminal should answer `a classpath export is already running for
this workspace`.

## I. Diagram and the practices resource (stages 11 and 20)

```text
> Draw the class relationships of src/main/groovy/org/codenarc/report into docs/report.drawio.
> Read the groovyscope practices resource for GS001.
```

Expect the question "Write the class diagram to docs/report.drawio?" before the file is written; open it in
diagrams.net and check that no boxes or labels overlap. For the second prompt (or type `#` and pick
groovyscope practices): GS001 with why, the bad and good form and the fix.

## J. Parallel requests and memory (stages 17 to 19)

```text
> In parallel, run a full quality check of the whole workspace and show the outline of src/main/groovy/org/codenarc/CodeNarcRunner.groovy.
```

Expect both answers, the outline promptly even while the check runs, and no "did not answer" message.

Out-of-memory drill, on the throwaway copy only, in the terminal:

```sh
export GROOVYSCOPE_DAEMON__HEAP=64m GROOVYSCOPE_DAEMON__MAXHEAP=256m
groovyscope daemon restart
groovyscope check --format short > /dev/null; groovyscope daemon status     # run this pair up to four times
groovyscope doctor
unset GROOVYSCOPE_DAEMON__HEAP GROOVYSCOPE_DAEMON__MAXHEAP && groovyscope daemon stop
```

Expect one of these, and report which. Either `check` fails with `groovyscope engine is low on heap` (the
pressure guard). Or the daemon exits with `OutOfMemoryError`, and `daemon status` then notes `the last start
used a 96m heap (grown after an out-of-memory exit)`, growing at most to 256m. By the fourth quick restart
expect `the daemon was started 3 times in the last ten minutes ... not starting it again`. Afterwards, delete
`<key>.state.json` next to the log path `daemon status` prints, so the next start uses the configured heap.

## K. Tool surface and `disabledTools` (stage 16, no credits)

Add `"disabledTools": ["coverage_gaps"]` to the groovyscope entry in `~/.kiro/settings/mcp.json` and restart the
chat. Expect `/mcp` to show 27 tools and `/tools` a lower figure. Run `groovyscope kiro repair --global`; the
`disabledTools` line must still be there. Remove it afterwards.

## L. Kiro 3 (V3 engine, stage 13)

```text
kiro-cli chat --agent groovy --agent-engine v3
> Show the outline of src/main/groovy/org/codenarc/CodeNarcRunner.groovy.
> Apply the safe fixes in src/main/groovy/org/codenarc/report/JsonReportWriter.groovy.
```

Expect the start hook's checkup line again (under V3 it may need `SessionStart`; report whether it appeared).
The outline should run without an approval prompt (read-only rule) and the fix should ask (mutating rule).

## M. Benchmark (stage 14, optional, about 5 credits)

Tasks 3 and 4 of [benchmark.md](benchmark.md) on the corpus, with and without groovyscope. Done when each is
under 2 minutes with groovyscope.

## Report

Copy, fill in and paste back:

| Check | Result (pass, fail, n/a) | Numbers or notes |
|---|---|---|
| 0 versions and doctor | | groovyscope, engine, CodeNarc, Kiro CLI versions |
| A start hook line, `/code status`, `/mcp` 28 tools | | |
| A `/tools` and `/context show` | | groovyscope token cost; steering size |
| A skills listed | | |
| B code tool on .groovy / .gradle / Jenkinsfile | | |
| C six navigation prompts called the right tools | | any grep or cat? |
| D fix: Kiro approval, then groovyscope question; no wrote nothing; yes wrote | | |
| D format and rename asked; rename verified | | |
| E after-write block in the same turn | | |
| E stop gate refused the turn | | |
| F `/groovy-impact` ran; skills without `skill://` | | |
| G `review_changes` verdict and draft comments | | |
| H stale, refresh with question, fresh; lock message | | |
| I draw.io question; diagram clean; GS001 resource | | |
| J parallel prompt answered both | | |
| J memory drill outcome | | pressure refusal or grown heap; crash-loop message |
| K `disabledTools` honoured and kept by repair | | `/tools` before and after |
| L V3: hook line; read-only ran; fix asked | | |
| M benchmark tasks 3 and 4 (optional) | | minutes and credits |
