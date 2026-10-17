# Kiro CLI integration

Kiro CLI spawns LSP servers configured in `.kiro/settings/lsp.json` and talks to them over JSON-RPC on stdio.
The agent never sees raw LSP: it calls the built-in `code` tool, which Kiro translates into LSP requests.
MCP servers (`.kiro/settings/mcp.json`) are a separate channel for higher-order tools.

## What `groovyscope kiro init` writes

| File | Purpose |
|---|---|
| `.kiro/settings/lsp.json` | `languages.groovy` entry: command `groovyscope lsp --stdio`, extensions, Jenkinsfile patterns, timeouts |
| `.kiro/settings/mcp.json` | `mcpServers.groovyscope` entry: command `groovyscope mcp`, a two-minute tool timeout |
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

## What the MCP tools add

The `code` tool stops at what LSP can express. The `groovyscope` MCP server (one process per Kiro session,
sharing the workspace daemon with the LSP side) adds `find_symbol`, `goto_definition`, `find_references`,
`call_graph`, `impact_of`, `check`, `format`, `find_dynamic_code` and `workspace_status`; the README lists
their arguments. Typical turns in the `groovy` agent:

- "What breaks if I change the signature of `Greeter.greet`?" → `impact_of("app.Greeter.greet", change="signature")`
- "Lint the files I touched" → `check(paths=[...])`, then fix priority 1 and 2 findings
- "Where does this project rely on dynamic Groovy?" → `find_dynamic_code()`

Kiro's `/mcp` command lists the server and its tools once the agent has loaded it. To exercise the server
outside Kiro, run the MCP Inspector against it from the workspace root:

```bash
npx @modelcontextprotocol/inspector groovyscope mcp
```

`workspace_status` is the cheapest first call: it starts the daemon if needed and reports the engine version.

## Known rough edges

- `/code init` can overwrite custom entries in `lsp.json` (kirodotdev/Kiro#10684). Commit the file and run
  `groovyscope kiro repair` if the groovy entry disappears.
- Kiro CLI releases weekly. The permission `effect` values and the `@server` tool selector in
  `agents/groovy.json` should be checked against your installed version (`kiro-cli --version`).
- The Kiro IDE does not read `lsp.json`; it relies on editor extensions. Steering and `mcp.json` are shared.
- Kiro counts an MCP server that fails to start as "1 MCP failure" at the prompt; `groovyscope doctor` and
  `groovyscope daemon status` show why (usually no JDK or no engine JAR).
- Whether Kiro forwards `workspace/configuration` is not verified; the engine reads `.groovyscope.yaml`
  directly, so it does not depend on it.
