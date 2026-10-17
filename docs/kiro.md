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

## Verify

```text
kiro-cli --agent groovy
> /code status          # expect: groovyscope (groovy) - initialized
> /code logs -l ERROR   # empty is good
> /mcp                  # expect: groovyscope running 26 tools, next to your other servers
> /context show         # expect: steering/groovyscope.md among the context files
> /tools                # the other servers' tools are listed too (available, prompting)
```

If a custom agent lacks the `code` tool, Kiro reports "Code tool is not enabled for this agent"; add `"code"`
or `"@builtin"` to the agent's `tools`.

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
sharing the workspace daemon with the LSP side) adds twenty-six tools; the README lists their arguments and
[docs/prompts.md](prompts.md) the prompts. Typical turns in the `groovy` agent:

- "Review this branch as a merge request reviewer" → `review_changes(base="main")`: verdict, blocking and
  should-fix findings on the changed lines, impact of the changed declarations, risky constructs added,
  uncovered methods, with the build's SpotBugs findings merged in
- "Which methods have no test coverage?" → `coverage_gaps()` on the JaCoCo report
- "Explain what `ResourceExecutionPlugin` does" → `explain_code(symbol=...)`: members, calls, callers, field
  writes, exceptions and the external world it touches, as facts the agent turns into prose
- "Draw the class diagram around `OrderService` for draw.io" → `relationship_graph(symbols=[...], depth=2,
  drawio_path="docs/order-service.drawio")`, an A4 page to open in diagrams.net
- "Are things named well in this package?" → `check_naming(paths=[...])`, then `rename` for each agreed change

- "What is in this repository?" → `workspace_overview()`, then `document_symbols(path)` for a file's outline
  and `read_symbol(path, "Class.method")` for the one declaration that matters, instead of reading files
- "List the catch blocks that catch Exception and do not rethrow" →
  `find_code("catch", type="Exception", rethrows=false)`
- "Find duplicated code under `src/main/groovy/report`" → `find_duplicates(paths=["src/main/groovy/report"])`
- "What breaks if I change the signature of `Greeter.greet`?" → `impact_of("app.Greeter.greet", change="signature")`
- "Review the files I touched and fix what is safe" → `review(paths=[...])`, then `fix(paths=[...], apply=true)`;
  `check(codes=["CatchException"])` or `check(max_priority=1)` narrows a long list, and a
  `missingDependencies` summary means `groovyscope classpath` has not been run
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

`workspace_status` is the cheapest first call: it starts the daemon if needed and reports the engine version.

## Known rough edges

- `/code init` can overwrite custom entries in `lsp.json` (kirodotdev/Kiro#10684). Commit the file and run
  `groovyscope kiro repair` if the groovy entry disappears.
- With `--agent groovy`, Kiro CLI takes tool approvals from the agent's `allowedTools`, not from
  `--trust-tools`. For unattended runs (`kiro-cli chat --no-interactive`), add `"write"` to `allowedTools` in
  a copy of the agent; the shipped agent keeps `write` and `shell` behind a prompt.
- Kiro CLI releases weekly. `agents/groovy.json` uses the agent format Kiro CLI 2.x loads (`tools`,
  `allowedTools`, `toolsSettings`): every tool and MCP server is available (`tools: ["*"]`); read, search,
  `code` and the groovyscope MCP tools run without asking, `write`, `shell` and other servers' tools ask,
  except `./gradlew`, `groovyscope`, `git status` and `git diff`. Releases up to
  0.6.x also wrote a `permissions` block, which makes Kiro CLI 2.32 treat the agent as V3-only and fall back
  to `kiro_default` ("agent groovy not found"); `groovyscope kiro repair` removes it. `kiro-cli agent list`
  must show `groovy` without "(v3 format)", and `kiro-cli agent upgrade .kiro/agents` converts the file to
  Kiro's universal format if you want both engines to load it.
- The Kiro IDE does not read `lsp.json`; it relies on editor extensions. Steering and `mcp.json` are shared.
- Kiro counts an MCP server that fails to start as "1 MCP failure" at the prompt; `groovyscope doctor` and
  `groovyscope daemon status` show why (usually no JDK or no engine JAR).
- Whether Kiro forwards `workspace/configuration` is not verified; the engine reads `.groovyscope.yaml`
  directly, so it does not depend on it.
