# Credit benchmark: Kiro CLI with and without groovyscope

The point of groovyscope is that an agent stops paying for `grep`, `sed`, `head` and `tail` round trips and
for wrong guesses about Groovy's dynamic dispatch. This page fixes a protocol that measures that in Kiro
credits, so the claim can be checked on a real repository and repeated after every release.

## Status

First run recorded on 2026-10-17 (one pass, results below). The protocol asks for a second pass on another
day; that repetition has not been run yet.

## Subject repository

The bundled corpus: CodeNarc 4.0.0 `src/main`, unpacked by groovyscope into `~/.groovyscope/benchmark/codenarc/`
(`groovyscope benchmark path` prints where). Every machine gets the same bytes without reaching GitHub, so
runs are comparable across machines and releases; `--refresh` restores it between runs. Any other Groovy
codebase of a few hundred files works too, at the same commit for every task and condition (a fresh
`git worktree` per run; record the commit hash).

## Conditions

| Condition | Setup |
|---|---|
| **without** | No `.kiro/settings/lsp.json` entry for Groovy, no `groovyscope` MCP server, the default Kiro agent, no steering file. Kiro has only its file tools and the shell. |
| **with** | `groovyscope kiro init` run in the worktree, `kiro-cli --agent groovy`, `/code status` showing `groovyscope (groovy) initialized` and `/mcp` showing the groovyscope tools (26 in 0.10.0) before the first prompt. |

Both conditions use the same Kiro CLI version, the same model setting, the same prompts, and a new chat
session per task (so context from one task never helps the next).

## Tasks

Each task is one prompt, given verbatim. Replace the placeholders with declarations that exist in the
subject repository and have at least three callers.

1. **Find callers.** "List every place that calls `<Class.method>`, with file and line, and say which calls
   you are sure about and which you are not."
2. **Impact of a signature change.** "I want to add a required parameter to `<Class.method>`. What would
   break, which tests cover it, and what should I check by hand?"
3. **Safe rename.** "Rename the property `<Class.property>` to `<newName>` everywhere it is used. Do not
   touch anything that merely has the same name."
4. **Lint and fix.** "Run the project's lint rules on `<path/to/File.groovy>` and fix every priority 1 and 2
   finding without changing behaviour."
5. **Dynamic code review.** "Where does this project rely on dynamic Groovy features (`def` on public
   methods, `methodMissing`, metaclass changes, `@Grab`, `Eval`)? Give me a list to review."

## What to record per task and condition

- Credits consumed: the balance shown by Kiro before and after the task. In Kiro CLI 2.32 the local
  `/usage` command prints `Credits (<used> of <plan> covered in plan)` without spending anything; the
  account page at app.kiro.dev shows the same figure.
- Wall-clock time from prompt to final answer.
- Tool calls made, grouped as: file reads, shell searches (`grep`, `rg`, `find`, `sed`, `head`, `tail`),
  `code` tool calls, groovyscope MCP calls, edits.
- Correctness, scored against a key prepared before the run from `groovyscope impact`, `find_references`
  and `find_dynamic_code` output plus a manual check: callers found / callers that exist, false callers
  reported, tests named / tests that exist, and for task 3 whether every real use changed and nothing else.

## Procedure

1. Prepare the answer key for the five tasks on the chosen commit.
2. For each condition, for each task: new worktree, condition setup, new chat, note the credit balance, give
   the prompt, wait for the final answer, note the balance, save the transcript, score it.
3. Repeat the whole matrix at least once more on another day; report the mean and the spread.
4. Record the versions: `kiro-cli --version`, `groovyscope version`, the model Kiro used.

The steering file installed by `groovyscope kiro init` tells the agent to prefer the `code` tool and the
MCP tools over text search. If a run shows the agent ignoring them, record that as a finding rather than
adjusting the prompt: it is exactly what the benchmark should reveal.

## Results

Subject: CodeNarc 4.0.0 (commit `3334d94`, 529 main and 531 test Groovy files, 15 Java files), with its
dependency jars on `workspace.classpath`. Kiro CLI 2.32.0, model `auto`, groovyscope 0.6.1, one pass,
2026-10-17. Each run used a fresh copy of the repository and a new non-interactive session
(`kiro-cli chat --no-interactive`), with the same trusted tools in both conditions (read, write, grep, glob,
code; plus the groovyscope MCP tools in the "with" condition; never shell).

| Task | Credits without | Credits with | Time without | Time with | Correct without | Correct with |
|---|---|---|---|---|---|---|
| 1 Find callers (`PathUtil.getName`) | 0.22 | 0.43 | 12 s | 33 s | 8 of 9 sites; called the list complete | 9 of 9, each marked certain |
| 2 Impact of a signature change (`ModifiersUtil.parseModifiers`) | 0.45 | 0.42 | 27 s | 43 s | production caller, tests, and the indirect user `IllegalClassMemberRule` | production caller and tests; missed the indirect user |
| 3 Safe rename (`includeRuleDescriptions`) | 1.30 | 1.36 | 45 s | 713 s | all 10 code sites plus 2 docs pages, nothing else | all 10 code sites, nothing else |
| 4 Lint and fix (`XmlReportUtil.groovy`) | 2.13 | 1.22 | 97 s | 888 s | 1 of 3 findings fixed; turned the `cdata` closure into a block (behaviour change) | 2 of 3 fixed; class-level `@CompileStatic` breaks the builder closure |
| 5 Dynamic code review | 0.72 | 0.78 | 34 s | 37 s | hooks and metaclass writes; missed `GroovyShell` and four template engines | hooks, metaclass writes, `GroovyShell`, template engines; also listed four `execute()` calls on untyped receivers that are not process launches |
| **Total** | **4.82** | **4.21** | | | | |

Versions: Kiro CLI 2.32.0, groovyscope 0.6.1, model `auto`, repository CodeNarc at `3334d94`, date 2026-10-17.
In all, 10.73 credits were spent, including 1.70 on "with" runs that had to be repeated (below).

### What the run showed

- **Accuracy.** groovyscope answered navigation and review questions more completely (tasks 1 and 5) and
  never invented a call site. Text search missed a test call and every runtime-evaluation site. Task 2 is the
  exception: the agent with groovyscope stopped at direct callers although `impact_of` lists transitive ones.
- **Credits.** Roughly even: 4.21 with groovyscope against 4.82 without. Lookups cost slightly more with
  groovyscope (more tool calls), edits cost less.
- **Edits are slow on a large workspace.** Tasks 3 and 4 took 12 to 15 minutes with groovyscope against under
  two without. Each edit makes the daemon recompile 1,064 files and republish diagnostics for every file.
- **Neither condition fixed task 4 correctly.** The fix needs `@CompileDynamic` (or `@CompileStatic` with the
  builder method left dynamic). groovyscope's type check should have caught the broken `@CompileStatic`, but
  the two test files that import the missing `junit-jupiter-params` stop the whole type-check compile before
  static checking runs.
- **Rename safety worked as designed.** groovyscope's rename refused because two references are name-only
  matches, and the agent fell back to explicit edits of the reported sites.

### Harness notes

- With `--agent groovy`, Kiro CLI takes tool approvals from the agent's `allowedTools`, not from
  `--trust-tools`, so the first "with" runs of tasks 3 and 4 could not write files (0.94 and 0.70 credits
  spent). The repeat runs added `write` to the run copy's `allowedTools`, matching what the default agent gets
  from `--trust-tools`. Interactive sessions are unaffected: Kiro asks.
- A first "with" run of task 1 stalled with every process idle and was stopped (0.06 credits); the repeat
  completed normally.
- `/usage` was read before and after every run through a pseudo terminal; it costs no credits.

### Follow-ups taken into Stage 8

Per-edit latency (recompile and diagnostics scope), type checking that survives broken files, the
`execute()` precision on untyped receivers, and steering that tells the agent to follow `impact_of`'s
transitive callers.

## Phase 5 checks on the corpus (20 October 2026)

Not a credit run: `groovyscope check --include practices` over the bundled corpus (529 files, no classpath, so
only declared types are known) and over the `jenkins-lib` fixture, to count what the Phase 5 checks of
`docs/improvement-plan.md` report and how often they are wrong. It costs no credits and takes about 15 seconds.

| Code | First run | After refinement | Severity after |
|---|---|---|---|
| GS017 Closure parameter without `@DelegatesTo` or `@ClosureParams` | 7 | 7 | information |
| GS028 number used as a condition | 8 | 6 | 4 information (primitive), 2 warning (boxed) |
| GS029 multi-line closure on `it` | 37 | 8 | information |
| GS037 `return` inside `each` | 3 | 3 | 1 warning (a value returned), 2 information (a bare return) |
| GS038 mutable static state | 42 | 42 | warning |

The corpus has no tests, build scripts, SQL, secrets or Jenkins code, so GS018, GS019, GS030 to GS036 and GS040
to GS052 found nothing there; their fixtures under `engine/src/test/resources/projects/` cover them.
`jenkins-lib` is clean code: no practice findings (its one lint finding is CodeNarc's `ClassNotSerializable`).

**False positives, from a manual sample of 20.** The first run's sample had 6 findings that point at nothing to
change (30%): three single expressions wrapped over lines (GS029), a condition that already tests zero
(`limit || limit == 0`, GS028) and two bare `return`s used deliberately as `continue` (GS037). The checks were
refined: GS029 needs a body of several statements or a block, GS028 skips an operand compared beside it, and
GS037 keeps the warning for a returned value (always discarded) and reports a bare return as information. A
fresh sample of 20 after that (8 GS038, 4 GS029, 3 GS017, 3 GS028, 2 GS037) has 2 false positives (10%), both the
deliberate bare returns, now information. GS038 is accurate in every sampled case but reports any
`static final` list or map literal, which in this corpus are constants nobody mutates; it stays a warning, as
the plan asks, and teams can lower it with `practices.severity`.
