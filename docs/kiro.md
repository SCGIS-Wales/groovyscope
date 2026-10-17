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
| `agents/groovy.json` | global, or project with `--full` | A `groovy` agent with the `code` tool, the MCP server and tool approvals |
| `steering/groovyscope.md` | global, or project with `--full` | Always-on policy: navigate through LSP/MCP, review and fix before finishing, the review checklist |
| `steering/groovyscope-profiles.md` | same, only when a runtime profile has `guidance` (in a project: only a profile whose markers or files occur in it) | That guidance, per profile |

**Nothing of yours is removed.** JSON files are merged: other MCP servers, other languages and the other keys
of an existing `groovy` agent are kept, and only groovyscope's own entries are added or updated. The steering
files belong to groovyscope and are replaced. Every changed file is backed up to `<name>.bak` and the diff is
printed first. `--dry-run` shows the diff and writes nothing; `--force` replaces files wholesale (still with
backups unless `--no-backup`). `groovyscope kiro repair [--global]` re-merges after a manual edit or
`/code init` removed an entry. The global agent also reads `~/.kiro/steering/**/*.md` by absolute path.

## Verify

```text
kiro-cli --agent groovy
> /code status          # expect: groovyscope (groovy) - initialized
> /code logs -l ERROR   # empty is good
```

If a custom agent lacks the `code` tool, Kiro reports "Code tool is not enabled for this agent"; add `"code"`
or `"@builtin"` to the agent's `tools`.

## What the `code` tool can ask today

Go to definition, type definition, find references, hover, document symbols, workspace symbol search,
completion, signature help, rename (with a prepare step that refuses dynamic targets), call hierarchy,
semantic tokens, formatting, quick fixes and diagnostics (pushed and pulled, compile plus lint). The
engine reads the effective `.groovyscope.yaml` from the `GROOVYSCOPE_CONFIG_JSON` variable that
`groovyscope lsp` sets, so `workspace.sourceRoots`, `workspace.classpath`, `workspace.exclude` and
`workspace.trust` apply.

## What the MCP tools add

The `code` tool stops at what LSP can express. The `groovyscope` MCP server (one process per Kiro session,
sharing the workspace daemon with the LSP side) adds thirteen tools; the README lists their arguments.
Typical turns in the `groovy` agent:

- "What breaks if I change the signature of `Greeter.greet`?" → `impact_of("app.Greeter.greet", change="signature")`
- "Review the files I touched and fix what is safe" → `review(paths=[...])`, then `fix(paths=[...], apply=true)`
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
  `allowedTools`, `toolsSettings`): read, search, `code` and the groovyscope MCP tools run without asking,
  `write` and `shell` ask, except `./gradlew`, `groovyscope`, `git status` and `git diff`. Releases up to
  0.6.x also wrote a `permissions` block, which makes Kiro CLI 2.32 treat the agent as V3-only and fall back
  to `kiro_default` ("agent groovy not found"); `groovyscope kiro repair` removes it. `kiro-cli agent list`
  must show `groovy` without "(v3 format)", and `kiro-cli agent upgrade .kiro/agents` converts the file to
  Kiro's universal format if you want both engines to load it.
- The Kiro IDE does not read `lsp.json`; it relies on editor extensions. Steering and `mcp.json` are shared.
- Kiro counts an MCP server that fails to start as "1 MCP failure" at the prompt; `groovyscope doctor` and
  `groovyscope daemon status` show why (usually no JDK or no engine JAR).
- Whether Kiro forwards `workspace/configuration` is not verified; the engine reads `.groovyscope.yaml`
  directly, so it does not depend on it.
