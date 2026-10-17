# Kiro CLI integration

Kiro CLI spawns LSP servers configured in `.kiro/settings/lsp.json` and talks to them over JSON-RPC on stdio.
The agent never sees raw LSP: it calls the built-in `code` tool, which Kiro translates into LSP requests.
MCP servers (`.kiro/settings/mcp.json`) are a separate channel for higher-order tools.

## Global or per project

```bash
groovyscope kiro init --global    # once per machine: ~/.kiro (or $KIRO_HOME)
groovyscope kiro init             # or per project: <project>/.kiro
```

Both write the same four files; Kiro CLI reads the global ones in every workspace and lets a project's own
`.kiro/` add to or override them.

| File (under `~/.kiro/` or `<project>/.kiro/`) | Purpose |
|---|---|
| `settings/lsp.json` | `languages.groovy` entry: command `groovyscope lsp --stdio`, extensions, Jenkinsfile patterns, timeouts |
| `settings/mcp.json` | `mcpServers.groovyscope` entry: command `groovyscope mcp`, a two-minute tool timeout |
| `agents/groovy.json` | A `groovy` agent with the `code` tool, the MCP server and tool approvals |
| `steering/groovyscope.md` | Always-on policy: navigate through LSP/MCP, review and fix before finishing, the review checklist |
| `steering/groovyscope-profiles.md` | Only when a runtime profile with `guidance` is active: that guidance, per profile |

**Nothing of yours is removed.** JSON files are merged: other MCP servers, other languages and the other keys
of an existing `groovy` agent are kept, and only groovyscope's own entries are added or updated. The two
steering files belong to groovyscope and are replaced. Every changed file is backed up to `<name>.bak` and
the diff is printed first. `--dry-run` shows the diff and writes nothing; `--force` replaces files wholesale
(still with backups unless `--no-backup`). `groovyscope kiro repair [--global]` re-merges after a manual edit
or `/code init` removed an entry.

The global agent also reads `~/.kiro/steering/**/*.md` by absolute path, so the policy applies in projects
that have no `.kiro/` of their own.

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
