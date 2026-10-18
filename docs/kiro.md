# Kiro CLI integration

Kiro CLI spawns LSP servers configured in `.kiro/settings/lsp.json` and talks to them over JSON-RPC on stdio.
The agent never sees raw LSP: it calls the built-in `code` tool, which Kiro translates into LSP requests.
MCP servers (`.kiro/settings/mcp.json`) are a separate channel for higher-order tools.

## Global and per project

```bash
groovyscope kiro init --global    # once per machine: ~/.kiro (or $KIRO_HOME)
groovyscope kiro init             # once per project: <project>/.kiro
```

Kiro CLI 2.32 reads MCP servers, agents and steering from both `~/.kiro` and the project's `.kiro/`, but
language servers only from the project's `.kiro/settings/lsp.json` (verified: with only a global
`lsp.json`, `/code status` reports the workspace as not initialized, and `/code init` writes Kiro's built-in
server list without Groovy). So the global step installs the MCP server, the `groovy` agent and the steering,
and the project step adds the LSP entry. When the global setup is present, `groovyscope kiro init` in a
project writes only `lsp.json`; `--full` writes everything into the project instead.

| File | Written by | Purpose |
|---|---|---|
| `.kiro/settings/lsp.json` | project | `languages.groovy` entry: command `groovyscope lsp --stdio`, extensions, Jenkinsfile patterns, timeouts |
| `settings/mcp.json` | global, or project with `--full` | `mcpServers.groovyscope` entry: command `groovyscope mcp`, a two-minute tool timeout |
| `agents/groovy.json` | global, or project with `--full` | A `groovy` agent with `tools: ["*"]` (the `code` tool, groovyscope and every other MCP server you have configured, GitLab or Jira included), pre-approvals for the read-only tools, the shell commands that run without asking, and the start hook that runs `groovyscope checkup --agent` |
| `steering/groovyscope.md` | global, or project with `--full` | Always-on policy: navigate through LSP/MCP, review and fix before finishing, the review checklist |
| `steering/groovyscope-profiles.md` | same, only when a runtime profile has `guidance` (in a project: only a profile whose markers or files occur in it) | That guidance, per profile |

**Nothing of yours is removed.** JSON files are merged: other MCP servers, other languages and the other keys
of an existing `groovy` agent are kept, and only groovyscope's own entries are added or updated. The steering
files belong to groovyscope and are replaced. Every changed file is backed up to `<name>.bak` and the diff is
printed first. `--dry-run` shows the diff and writes nothing; `--force` replaces files wholesale (still with
backups unless `--no-backup`). `groovyscope kiro repair [--global]` re-merges after a manual edit or
`/code init` removed an entry. The global agent also reads `~/.kiro/steering/**/*.md` by absolute path.

The agent's `tools` is `["*"]` on purpose. Kiro only exposes an MCP server to an agent whose `tools` names it
(`@server`) or allows everything, and an agent written by groovyscope 0.8.x or earlier listed only `code` and
`@groovyscope`, which hid the other servers in `mcp.json`. `groovyscope doctor` names any server the agent's
list still hides and `groovyscope kiro repair --global` fixes it. `allowedTools` is what runs without a
prompt (read, grep, glob, `code` and the groovyscope tools); other servers' tools are available but ask.

## What happens when the agent starts

The agent's `hooks.agentSpawn` runs `groovyscope checkup --agent` (within Kiro's hook time and output budgets) and Kiro
injects its output into the session's context. The command applies the safe repairs itself: Kiro files written
by an older groovyscope are re-merged (the steering file carries a `written by groovyscope <version>` stamp so
staleness is detectable), a missing project LSP entry is written, the benchmark corpus is unpacked. What it
cannot decide for you it lists as `todo` with the command: trusting the repository and exporting the
classpath, both of which run the build. The steering tells the agent to open with one status line, offer each
todo as a question, and show the quality menu ([docs/prompts.md](prompts.md)). The command never fails: a
check that crashes becomes a line in the report, and when the hook cannot run at all the agent carries on and
says so once. `groovyscope checkup` in a terminal prints the same report with the fix per line; `--fix` applies
the safe repairs; `--json` is for scripts.

## What happens after every write, and at the end of a turn

The agent's `hooks.postToolUse` entry (matcher `write`) runs `groovyscope hook` after each file write. The command
reads Kiro's hook JSON from stdin, keeps only Groovy, Gradle and Jenkinsfile files inside the workspace, asks the
daemon to check them (`hooks.afterWriteInclude`, default compile, lint and practices; the type check is left out
because it compiles the whole workspace) and prints a `groovyscope checked <file>: N error(s), M warning(s)` block
with up to `hooks.afterWriteMaxFindings` findings, errors first. Kiro adds the block to the context, so the agent
sees a broken edit in the same turn instead of at the end of the task. A clean file prints nothing. The engine
request is bounded by `hooks.timeoutSeconds`; a hook never starts the daemon (the LSP session does, and a cold JVM
would hold the agent for its start-up), so when it is over budget or the daemon is down the hook prints one
line and the session carries on.

The `hooks.stop` entry runs the same command at the end of a turn. It does nothing unless `hooks.stopGate: true`
in `.groovyscope.yaml`; then the Groovy files changed in the working tree (git status) are compiled and, while
any has compile errors, the hook answers `{"decision": "block", "reason": ...}` so the agent fixes them before
finishing. Whether Kiro V3 honours a blocking `Stop` answer is documented both ways; the owner's check decides.

Settings: `hooks.afterWrite` (default true), `hooks.afterWriteInclude`, `hooks.afterWriteMaxFindings` (12),
`hooks.stopGate` (false), `hooks.timeoutSeconds` (45).

## Skills and prompts

`groovyscope kiro init` writes twelve skills under `.kiro/skills/groovy-*/SKILL.md` (`~/.kiro/skills/` with
`--global`), one per recipe of the [prompts cookbook](prompts.md): `groovy-review`, `groovy-fix`,
`groovy-quality`, `groovy-duplicates`, `groovy-catch-audit`, `groovy-dynamic`, `groovy-coverage`,
`groovy-impact`, `groovy-rename`, `groovy-explain`, `groovy-naming`, `groovy-profile`. Kiro loads only the name
and description at start and the body when the skill runs, so the recipes cost nothing until used; each is a
slash command (`/groovy-impact OrderService.place`). The MCP server serves the same twelve as prompts
(`prompts/list`, one optional `arguments` string), which Kiro shows under `/` as well, so a client without
Kiro's skills directory still has them. The steering file now keeps only the session rules and points at the
skills, which is what cut its size by half. If a custom agent does not list the skills, add
`skill://.kiro/skills/**/SKILL.md` to its `resources` (Kiro's documented form; the owner's Kiro 3 check
confirms it).

## The reviewer agent

`groovyscope kiro init` also writes `agents/groovy-reviewer.json`: a read-only agent whose prompt runs
`review_changes`, then `impact_of` for each changed public declaration, and reports a verdict with blocking,
should-fix and nit findings. It has no `write` or `shell` tool and no hooks, so it suits the `subagent` tool and
unattended runs (see [docs/ci.md](ci.md), "Merge request review with Kiro").

## Is the steering loaded?

Inside Kiro, `/context show` lists the files in the agent's context; `steering/groovyscope.md` must appear,
from `~/.kiro` or the project's `.kiro`. The surest sign in a conversation is the first answer: an agent with
the steering calls `workspace_overview` or `find_code` and never `grep`, and it opens with the checkup line.
If it does not, run `groovyscope checkup` and `groovyscope kiro repair --global`, then restart Kiro CLI.

## Agent file formats (V2, universal, V3)

Kiro CLI 2.x has two agent engines and four agent file states, as `kiro-cli agent upgrade --dry-run` reports
them:

| Format | Keys | Loaded by |
|---|---|---|
| V2 | `tools`, `allowedTools`, `toolsSettings` | the V2 engine (the 2.x default) |
| universal | the V2 keys plus a V3 `permissions` block and a `kiroUpgrade` stamp | both engines |
| universal, out of sync | a universal file whose V2 keys changed after the stamp | both, with stale V3 rules |
| V3 | a `permissions` block without the stamp | the V3 engine only (`kiro-cli --v3`) |

groovyscope detects the format of an existing `groovy.json` (asking `kiro-cli agent upgrade --dry-run` when Kiro
CLI is installed) and keeps it: V2 and universal files get groovyscope's V2 entries, and an out-of-sync universal
file is re-stamped by Kiro CLI itself; a V3 file gets groovyscope's permission rules (MCP tools, file reads, the
allowed shell commands) next to yours. A new agent is written universal when the installed Kiro CLI can produce
it, so it works in both engines; otherwise V2. `--agent-format v2|universal|v3` converts explicitly. A file
stamped by a newer Kiro (an unknown `kiroUpgrade` revision) or managed outside the project is left untouched
and reported. `groovyscope doctor` shows the Kiro CLI version and the agent's format.

## Kiro CLI 3.0

Kiro CLI 3.0 (end of October 2026) makes the V3 engine the default and retires the Classic engine that read
V2 agent files. What that means for groovyscope:

- A V2 `groovy.json` is skipped by the V3 engine. `groovyscope doctor` and `checkup` warn when Kiro CLI 3.x
  is installed and the agent is still V2; the fix is `groovyscope kiro repair --agent-format universal` (Kiro
  CLI converts the file) or `--agent-format v3`.
- Approvals come from `permissions.rules`. groovyscope writes an `allow` rule for the read-only MCP tools and
  an `ask` rule for the mutating ones, derived from the same tool catalogue the MCP server uses.
- Hooks: V3 names the start hook `SessionStart`; the 2.x name `agentSpawn` in the universal file is reported
  to keep working. The check below confirms it.
- Steering: V3 honours `inclusion: fileMatch`, so a later release can load the Groovy steering only when
  Groovy files are in play.

Checklist to run once on Kiro CLI 2.31 and again on 3.0, with the results pasted into `docs/roadmap.md`:

```text
kiro-cli --version
kiro-cli --agent groovy
> /code status                    # groovyscope (groovy) - initialized
> find references of <a Groovy method>      # through the code tool: did it open the .groovy file, or report
                                           # "unsupported file extension" (kirodotdev/Kiro#6322)?
> what does the checkup say?      # proves hooks.agentSpawn (SessionStart on V3) ran
> /tools                          # token cost of the 28 groovyscope schemas
> /context show                   # size of steering/groovyscope.md
```

## Verify

The full list of checks, with Kiro CLI prompts and a report table: [kiro-checks.md](kiro-checks.md).

```text
kiro-cli --agent groovy
> /code status          # expect: groovyscope (groovy) - initialized
> /code logs -l ERROR   # empty is good
> /mcp                  # expect: groovyscope running 28 tools, next to your other servers
> /context show         # expect: steering/groovyscope.md among the context files
> /tools                # the other servers' tools are listed too (available, prompting)
```

If a custom agent lacks the `code` tool, Kiro reports "Code tool is not enabled for this agent"; add `"code"`
or `"@builtin"` to the agent's `tools`.

## Tool surface and context cost

Every tool schema an agent can call is in its context on every session, so groovyscope keeps them small: one
line per tool saying what it answers and what its arguments mean, and input schemas without the titles,
`anyOf [T, null]` pairs and `default: null` entries the MCP SDK generates (arguments are still validated
against the tool's signature, so nothing changes for a caller). How to read an answer and which tool to call
next live in the `groovy-*` skills and prompts, loaded when a recipe runs, and in the answers themselves. A
test holds the 28 schemas to a byte budget. Measured with the MCP SDK's own `tools/list` output:

| | Tools | Schema bytes | About tokens | Server instructions |
|---|---|---|---|---|
| 0.16.1 | 27 | 21,768 | 5,440 | 1,506 characters |
| Stage 16 | 28 | 12,973 | 3,240 | 631 characters |

`symbol_context(path, line, col)` answers what an agent otherwise asks `goto_definition`, `hover` and
`find_references` for in three turns: the definition with its confidence, the hover text and the reference
count split by confidence.

A team that never uses some tools can take them off the agent's list entirely with `disabledTools` on the
server entry in `.kiro/settings/mcp.json` (or `~/.kiro/settings/mcp.json`); `groovyscope kiro repair` keeps
it, together with a raised `timeout`, `requestTimeout`, `autoApprove`, `disabled` and the `GROOVYSCOPE_LOG`
level:

```json
{
  "mcpServers": {
    "groovyscope": {
      "command": "groovyscope",
      "args": ["mcp"],
      "disabledTools": ["coverage_gaps", "relationship_graph", "profile_status"]
    }
  }
}
```

| Drop | When |
|---|---|
| `coverage_gaps` | The build produces no JaCoCo XML report |
| `profile_status` | No runtime profile is configured (no RP findings) |
| `relationship_graph` | Nobody needs class diagrams or draw.io files |
| `check_naming`, `explain_code` | The team does not use the naming pass or the explanation recipes |
| `fix`, `format`, `rename`, `refresh_classpath` | A read-only reviewer agent; the review tools stay |
| `goto_definition`, `hover`, `find_references` | Keep `symbol_context` and the `code` tool instead |

Then compare `/tools` before and after in Kiro; each dropped tool also leaves the skill that uses it without a
step, so drop the matching `groovy-*` skill folder too.

## Confirmations, progress and the practices resource

When Kiro supports MCP elicitation, the writing tools ask before they write: `fix`, `format` and `rename` with
`apply`, `refresh_classpath` (it runs the build) and `relationship_graph` with `drawio_path`. The question lists
the files and how many lines each edit changes; a no writes nothing and the tool's answer says so. This comes on
top of the V3 permission rules, which already ask before any of these tools runs: the rule asks whether the tool
may run, the elicitation shows what it is about to change. Set `mcp.confirmWrites: never` in `.groovyscope.yaml`
to rely on the permission rule alone. While a tool waits for the engine or the build, it sends a progress note
every `mcp.progressSeconds` to clients that asked for progress.

Whether the client can be asked is recorded on the first tool call: `workspace_status` shows it under `client`
(name, version, protocol, declared capabilities, `elicitation`) and `groovyscope doctor` prints it as the
`mcp client` line. A write through a client that declares no elicitation says `"confirmation": "not offered"`
in its answer, so it is clear that Kiro's own tool approval was the only check. Kiro CLI 2.29.1 in classic
mode asked no groovyscope question in the owner's first check; the terminal UI is the one to try.

Prompts are typed `/groovy-impact` in Kiro's terminal UI (the default) and `@groovy-impact` in the classic chat
(`--classic`); `/prompts` lists them in both.

The practices catalogue is under `#` as two resources: `groovyscope://practices`, the checklist and every GS and
RP code, and `groovyscope://practices/<code>`, one code's why, bad and good form and fix.

Owner's check for Stage 20: in Kiro, ask for a safe fix with apply on a file that has one and note whether the
elicitation question appears, and what a no does.

## What the `code` tool can ask today

Go to definition, type definition, implementation, find references, document highlight, hover, document
symbols, workspace symbol search, folding ranges, inlay type hints, completion, signature help, rename (with a
prepare step that refuses dynamic targets), call hierarchy, semantic tokens, formatting of a file or a range,
code actions (quick fixes, `source.fixAll.groovyscope`, `source.organizeImports`) and diagnostics (pushed and
pulled: compile, type checkers, lint down to `lint.publishMaxPriority`, GS and RP checks, each marked `fixable`
when `fix` can repair it). The README has the table. The engine reads the effective `.groovyscope.yaml` from
the `GROOVYSCOPE_CONFIG_JSON` variable that `groovyscope lsp` sets, so `workspace.sourceRoots`,
`workspace.classpath`, `workspace.classpathFile`, `workspace.exclude` and `workspace.trust` apply.

## What the MCP tools add

The `code` tool stops at what LSP can express. The `groovyscope` MCP server (one process per Kiro session,
sharing the workspace daemon with the LSP side) adds 28 tools; the README lists their arguments and
[docs/prompts.md](prompts.md) the prompts. Typical turns in the `groovy` agent:

- "Review this branch as a merge request reviewer" → `review_changes(base="main")`: verdict, blocking and
  should-fix findings on the changed lines, impact of the changed declarations, risky constructs added,
  uncovered methods, with the build's SpotBugs findings merged in
- "Which methods have no test coverage?" → `coverage_gaps()` on the JaCoCo report
- "Explain what `ResourceExecutionPlugin` does" → `explain_code(symbol=...)`: members, calls, callers, field
  writes, exceptions and the external world it touches, as facts the agent turns into prose
- "Draw the class diagram around `OrderService` for draw.io" → `relationship_graph(symbols=[...], depth=2,
  drawio_path="docs/order-service.drawio")`, on A4, A3 or A2 (the smallest that keeps the text readable) to open in diagrams.net
- "Are things named well in this package?" → `check_naming(paths=[...])`, then `rename` for each agreed change

- "What is in this repository?" → `workspace_overview()`, then `document_symbols(path)` for a file's outline
  and `read_symbol(path, "Class.method")` for the one declaration that matters, instead of reading files
- "List the catch blocks that catch Exception and do not rethrow" →
  `find_code("catch", type="Exception", rethrows=false)`
- "Find duplicated code under `src/main/groovy/report`" → `find_duplicates(paths=["src/main/groovy/report"])`
- "What breaks if I change the signature of `Greeter.greet`?" → `impact_of("app.Greeter.greet", change="signature")`
- "Review the files I touched and fix what is safe" → `review(paths=[...])`, then `fix(paths=[...], apply=true)`;
  `check(codes=["CatchException"])` or `check(max_priority=1)` narrows a long list, and a
  `missingDependencies` summary means `groovyscope classpath` has not been run, and `classpath.state: stale`
  means a build file changed since: `refresh_classpath()` exports it again (trusted workspaces)
- "Why is line 40 flagged?" → `explain("GS002")`
- "Can I use `Set.of` in this plugin?" → `profile_status(path)`, which names the runtime's Groovy version and
  the constructs it has not proven
- "Where does this project rely on dynamic Groovy?" → `find_dynamic_code()`

The engine re-reads files changed on disk before every MCP or CLI request, so a `review` right after the
agent's `write` sees the new text.

Kiro's `/mcp` command lists the server and its tools once the agent has loaded it. To exercise the server
outside Kiro, run the MCP Inspector against it from the workspace root:

```bash
npx @modelcontextprotocol/inspector groovyscope mcp
```

`workspace_status` is the cheapest first call: it starts the daemon if needed and reports the engine version
and whether the classpath still matches the build. Configuration changes reach a running engine on the next
request: the MCP server compares the effective configuration (`.groovyscope.yaml`, the classpath file) with
what the engine runs and sends a `groovyscope/reloadConfig` when they differ, so neither Kiro nor the daemon
needs a restart after `groovyscope classpath` or an edit to the configuration.

### Posting a review on GitLab

`review_changes` returns `comments` (one per finding, with `path`, `line` and a `body` written to the author)
and a `summaryComment`. With a GitLab MCP server in the same Kiro session the agent offers to post them as
inline discussions on the merge request and the summary as a note; see [docs/prompts.md](prompts.md#1-review-this-branch-as-a-merge-request-reviewer)
for the prompt and a server entry for `mcp.json`.

## Known rough edges

- `/code init` can overwrite custom entries in `lsp.json` (kirodotdev/Kiro#10684). Commit the file and run
  `groovyscope kiro repair` if the groovy entry disappears.
- With `--agent groovy`, Kiro CLI takes tool approvals from the agent's `allowedTools`, not from
  `--trust-tools`. For unattended runs (`kiro-cli chat --no-interactive`), add `"write"` to `allowedTools` in
  a copy of the agent; the shipped agent keeps `write` and `shell` behind a prompt.
- Kiro CLI releases weekly. `agents/groovy.json` uses the agent format Kiro CLI 2.x loads (`tools`,
  `allowedTools`, `toolsSettings`): every tool and MCP server is available (`tools: ["*"]`); read, search,
  `code` and the read-only groovyscope MCP tools run without asking; the tools that change the workspace
  (`fix`, `rename` and `format` with `apply`, `refresh_classpath`, `relationship_graph` writing a draw.io
  file), `write`, `shell` and other servers' tools ask, except `./gradlew`, `groovyscope`, `git status` and
  `git diff`. Kiro ignores MCP's `readOnlyHint`, so the split is written into `allowedTools` (one
  `@groovyscope/<tool>` per read-only tool) and into the V3 `permissions.rules`. Releases up to
  0.6.x also wrote a `permissions` block, which makes Kiro CLI 2.32 treat the agent as V3-only and fall back
  to `kiro_default` ("agent groovy not found"); `groovyscope kiro repair` removes it. `kiro-cli agent list`
  must show `groovy` without "(v3 format)", and `kiro-cli agent upgrade .kiro/agents` converts the file to
  Kiro's universal format if you want both engines to load it.
- The Kiro IDE does not read `lsp.json`; it relies on editor extensions. Steering and `mcp.json` are shared.
- Kiro counts an MCP server that fails to start as "1 MCP failure" at the prompt; `groovyscope doctor` and
  `groovyscope daemon status` show why (usually no JDK or no engine JAR).
- Whether Kiro forwards `workspace/configuration` is not verified; the engine reads `.groovyscope.yaml`
  directly, so it does not depend on it.
