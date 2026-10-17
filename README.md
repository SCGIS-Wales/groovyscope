# groovyscope

[![CI](https://github.com/SCGIS-Wales/groovyscope/actions/workflows/ci.yml/badge.svg)](https://github.com/SCGIS-Wales/groovyscope/actions/workflows/ci.yml)
[![PyPI](https://img.shields.io/pypi/v/groovyscope)](https://pypi.org/project/groovyscope/)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)

Groovy 5/6 code intelligence for AI coding agents, with [Kiro CLI](https://kiro.dev) as the first consumer.

One warm JVM engine (Apache Groovy compiler, CodeNarc, Groovy Eclipse formatter) answers semantic questions
about Groovy, Gradle and Jenkinsfile code. Two front ends share it:

- an **LSP server** that Kiro CLI spawns from `.kiro/settings/lsp.json` and uses through its built-in `code`
  tool (definition, references, rename, diagnostics, hover, symbols);
- an **MCP server** for the questions LSP cannot express: impact of a change, call graphs, lint, format and
  security findings, returned as compact JSON so the agent stops reaching for `grep`, `sed`, `head` and `tail`.

Python owns the installer, CLI, configuration and MCP bridge. Every semantic answer comes from the JVM.

## Status

Stage 5 of 7 (MCP server). The engine compiles the workspace with the Groovy compiler and
serves go to definition, type definition, find references, hover, document and workspace symbols,
completion, signature help, rename with a dry-run prepare step, call hierarchy, semantic tokens and
diagnostics. Every answer carries a confidence (`STATIC`, `INFERRED`, `DYNAMIC_UNKNOWN`); rename refuses
rather than guesses when a call site can only be matched by name. One JVM per workspace stays warm across
sessions, with a symbol cache for cold starts. CodeNarc 4 runs in process with a defensive profile, Groovy
6's checkers run on statically typed classes, lint findings arrive as diagnostics with quick fixes, a
conservative formatter is bundled, and `impact` answers "what breaks if I change this?" with direct and
transitive callers, overrides, tests and the dynamic call sites to review. `groovyscope mcp` exposes all
of that to agents as nine compact MCP tools over the same warm daemon. Packaging extras and the credit
benchmark follow; see [docs/roadmap.md](docs/roadmap.md).

## Install

Prerequisite: a JDK 17 or newer (21 recommended). Then:

```bash
uv tool install groovyscope     # or: pipx install groovyscope
groovyscope doctor
```

Platform notes, including JDK setup on Ubuntu and macOS, are in [docs/install.md](docs/install.md).

## Use with Kiro CLI

```bash
cd ~/src/your-groovy-project
groovyscope kiro init           # writes .kiro/settings/lsp.json, mcp.json, agents/groovy.json, steering
kiro-cli --agent groovy
> /code status                  # expect: groovyscope (groovy) - initialized
```

`kiro init` merges into existing files, backs up anything it changes to `*.bak` and prints a diff. Details in
[docs/kiro.md](docs/kiro.md).

## Commands

| Command | What it does |
|---|---|
| `groovyscope doctor` | Checks Python, configuration, JDK, the bundled engine JAR (SHA-256) and Kiro files |
| `groovyscope version` | Package and engine versions |
| `groovyscope config validate [--show]` | Validates `.groovyscope.yaml` + `GROOVYSCOPE_*` overrides against the schema |
| `groovyscope kiro init \| repair` | Installs or re-merges the Kiro integration files |
| `groovyscope check [PATHS] [--profile default\|jenkins] [--include lint,compile,typecheck] [--json]` | CodeNarc lint, compile and Groovy type-check findings; exits 1 at or above `lint.failOnPriority` |
| `groovyscope format [PATHS] [--apply] [--json]` | Bundled formatter (whitespace hygiene, safe CodeNarc-driven fixes); exits 1 when files would change unless `--apply` |
| `groovyscope impact TARGET [--change signature\|rename\|delete\|behaviour] [--json]` | Direct and transitive callers, overrides, tests, name-only sites, risk and suggested checks for a declaration (`pkg.Class.method`, `Class#method`, or `path:line:col`) |
| `groovyscope callgraph TARGET [--direction in\|out] [--depth N] [--json]` | Callers of, or calls made by, a declaration with per-edge confidence |
| `groovyscope lsp --stdio` | Serves LSP on stdio through the warm daemon (what Kiro spawns); falls back to a direct JVM |
| `groovyscope daemon start \| stop \| status` | Manages the per-workspace daemon (idle exit after `daemon.idleTimeoutMinutes`) |
| `groovyscope mcp` | Serves the MCP tools below on stdio through the warm daemon (what `.kiro/settings/mcp.json` runs) |

## MCP tools

`groovyscope mcp` registers one server, `groovyscope`, with these tools. Every answer is compact JSON: paths
relative to the workspace root, 1-based `line`/`col`, a symbol kind, a confidence where one applies, at most
`output.maxResults` items within `output.maxBytes`, and a `next_cursor` to page on. No tool returns file bodies.

| Tool | Answers |
|---|---|
| `find_symbol(query, kind?, limit?, cursor?)` | Declarations whose name contains the query; `kind` narrows to class, method, field or variable |
| `goto_definition(path, line, col)` | The declaration under a position, with `STATIC`, `INFERRED` or `DYNAMIC_UNKNOWN` confidence |
| `find_references(path, line, col, include_declaration?, limit?, cursor?)` | Every reference with a confidence, plus counts per confidence |
| `call_graph(symbol, direction?, depth?)` | Callers of (`in`) or calls made by (`out`) a declaration |
| `impact_of(symbol, change?)` | Direct and transitive callers, overrides, tests, dynamic sites, risk and suggested checks |
| `check(paths?, profile?, include?, limit?, cursor?)` | Lint, compile and type-check findings, worst first |
| `format(paths?, apply?)` | Files the formatter would change and how many lines; `apply` writes them |
| `find_dynamic_code(paths?, limit?, cursor?)` | Untyped classes, `def` on public API, `methodMissing` and friends, `metaClass` writes, `@Grab`, `Eval`, `execute()` |
| `workspace_status()` | Daemon socket and state, engine version, root, file count and trust flag |

## Configuration

Copy [config/defaults.yaml](config/defaults.yaml) to `.groovyscope.yaml` in your workspace and change what you
need. Any key can be overridden with an environment variable, for example `GROOVYSCOPE_DAEMON__HEAP=2g`.

## Security posture

`@Grab` and `@ASTTest` are disabled in the engine JVM and cannot be enabled through configuration. Global AST
transforms from the workspace classpath load only with `workspace.trust: true`. The classpath comes from
configuration, never from silent inference. See [docs/security.md](docs/security.md).

## Repository layout

```
engine/   JVM engine (Java 21 sources, Gradle, Spock tests)       -> groovyscope-engine-<ver>-all.jar
python/   groovyscope package (CLI, config, Kiro writers, tests)   -> PyPI wheel bundling the JAR
config/   CodeNarc rulesets, formatter and groovyc configuration (mirrored inside the package)
kiro/     Templates written by `groovyscope kiro init` (mirrored inside the package)
docs/     Install, Kiro, rules, architecture, security, troubleshooting, roadmap, research
```

## Contributing

Conventional Commit PR titles, squash merges, and releases through release-please. See
[AGENTS.md](AGENTS.md) for the build and style rules, and [docs/architecture.md](docs/architecture.md) for the
design.

Licensed under the Apache License 2.0.
