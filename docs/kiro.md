# Kiro CLI integration

Kiro CLI spawns LSP servers configured in `.kiro/settings/lsp.json` and talks to them over JSON-RPC on stdio.
The agent never sees raw LSP: it calls the built-in `code` tool, which Kiro translates into LSP requests.
MCP servers (`.kiro/settings/mcp.json`) are a separate channel for higher-order tools.

## What `groovyscope kiro init` writes

| File | Purpose |
|---|---|
| `.kiro/settings/lsp.json` | `languages.groovy` entry: command `groovyscope lsp --stdio`, extensions, Jenkinsfile patterns, timeouts |
| `.kiro/settings/mcp.json` | `mcpServers.groovyscope` entry (the server itself lands in Stage 5) |
| `.kiro/agents/groovy.json` | A `groovy` agent with the `code` tool, the MCP server and permission rules |
| `.kiro/steering/groovyscope.md` | Always-on policy: navigate through LSP/MCP, never grep for symbols; check and format through groovyscope |

JSON files are merged: other languages and servers are preserved, groovyscope's keys win. Markdown is replaced.
Any overwritten file is backed up to `<name>.bak` and the diff is printed. `--dry-run` shows the diff only;
`--force` replaces files wholesale.

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

## Known rough edges

- `/code init` can overwrite custom entries in `lsp.json` (kirodotdev/Kiro#10684). Commit the file and run
  `groovyscope kiro repair` if the groovy entry disappears.
- Kiro CLI releases weekly. The permission `effect` values and the `@server` tool selector in
  `agents/groovy.json` should be checked against your installed version (`kiro-cli --version`).
- The Kiro IDE does not read `lsp.json`; it relies on editor extensions. Steering and `mcp.json` are shared.
- Whether Kiro forwards `workspace/configuration` is not verified; the engine reads `.groovyscope.yaml`
  directly, so it does not depend on it.
