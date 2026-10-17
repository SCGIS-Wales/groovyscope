# Credit benchmark: Kiro CLI with and without groovyscope

The point of groovyscope is that an agent stops paying for `grep`, `sed`, `head` and `tail` round trips and
for wrong guesses about Groovy's dynamic dispatch. This page fixes a protocol that measures that in Kiro
credits, so the claim can be checked on a real repository and repeated after every release.

## Status

The protocol below is complete and reproducible. **No run has been recorded yet**: a full run costs roughly
ten to twenty Kiro credits (five tasks, two conditions, at least one repetition each), which is a large share
of a free account's monthly allowance. The results table at the end stays empty until someone with the
credits to spare runs it; the figures from that run, with the Kiro and groovyscope versions, replace it.

## Subject repository

Any Groovy codebase of a few hundred files with tests, for example a Gradle multi-project with Spock specs
or a Jenkins shared library (`vars/` plus `src/`). The repository must be at the same commit for every task
and condition; use a fresh `git worktree` per run and discard it afterwards. Record the commit hash.

## Conditions

| Condition | Setup |
|---|---|
| **without** | No `.kiro/settings/lsp.json` entry for Groovy, no `groovyscope` MCP server, the default Kiro agent, no steering file. Kiro has only its file tools and the shell. |
| **with** | `groovyscope kiro init` run in the worktree, `kiro-cli --agent groovy`, `/code status` showing `groovyscope (groovy) initialized` and `/mcp` showing the groovyscope tools (nine in 0.6.x) before the first prompt. |

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

| Task | Credits without | Credits with | Time without | Time with | Correct without | Correct with |
|---|---|---|---|---|---|---|
| 1 Find callers | | | | | | |
| 2 Impact of a change | | | | | | |
| 3 Safe rename | | | | | | |
| 4 Lint and fix | | | | | | |
| 5 Dynamic code review | | | | | | |

Versions: Kiro CLI `_`, groovyscope `_`, model `_`, repository `_` at `_`, date `_`.
