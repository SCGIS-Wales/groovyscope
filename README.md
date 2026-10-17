# groovyscope

[![CI](https://github.com/SCGIS-Wales/groovyscope/actions/workflows/ci.yml/badge.svg)](https://github.com/SCGIS-Wales/groovyscope/actions/workflows/ci.yml)
[![PyPI](https://img.shields.io/pypi/v/groovyscope)](https://pypi.org/project/groovyscope/)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)

Groovy 5/6 code intelligence for AI coding agents, with [Kiro CLI](https://kiro.dev) as the first consumer.

One warm JVM engine (Apache Groovy 6 compiler, CodeNarc 4, Groovy's static type checker and a conservative
formatter) answers semantic questions about Groovy, Gradle and Jenkinsfile code. Two front ends share it:

- an **LSP server** that Kiro CLI spawns from `.kiro/settings/lsp.json` and uses through its built-in `code`
  tool (definition, references, rename, diagnostics, hover, symbols);
- an **MCP server** for the questions LSP cannot express: impact of a change, call graphs, lint, format and
  security findings, returned as compact JSON so the agent stops reaching for `grep`, `sed`, `head` and `tail`.

Python owns the installer, CLI, configuration and MCP bridge. Every semantic answer comes from the JVM.

## Quick start

You need a JDK 17 or newer (21 recommended; Temurin, Amazon Corretto or any other build) and Kiro CLI. Once per
machine:

```bash
uv tool install groovyscope       # or: pipx install groovyscope, or: brew install SCGIS-Wales/tap/groovyscope
groovyscope kiro init --global    # adds groovyscope to ~/.kiro without removing anything already there
groovyscope doctor                # ok lines are good; info lines are optional extras
```

Once per Groovy project (Kiro CLI reads language servers only from the project's own `.kiro/`):

```bash
cd your-groovy-project
groovyscope kiro init             # writes .kiro/settings/lsp.json only, since the global setup has the rest
kiro-cli --agent groovy
> /code status                    # expect: groovyscope (groovy) - initialized
```

`kiro init --global` merges into `~/.kiro/settings/mcp.json`, `~/.kiro/agents/groovy.json` and
`~/.kiro/steering/`: other MCP servers and agent settings stay as they are, every changed file is backed up to
`<file>.bak`, and the diff is printed. Add `--dry-run` to see the diff without writing. In a project,
`groovyscope kiro init --full` writes all the files there instead, for a setup the whole team can commit.
Details in [docs/kiro.md](docs/kiro.md).

No JDK? The Homebrew formula brings one. Otherwise `groovyscope jdk install` downloads a checksum-verified
Temurin JDK into groovyscope's cache; nothing is downloaded unless you ask. See [docs/install.md](docs/install.md).

## What it does

One warm JVM per workspace compiles the code with the Groovy 6 compiler and serves go to definition, find
references, hover, symbols, completion, signature help, rename with a dry run, call hierarchy, semantic tokens
and diagnostics. Every answer carries a confidence (`STATIC`, `INFERRED`, `DYNAMIC_UNKNOWN`); rename refuses
rather than guesses when a call site can only be matched by name. Findings come from four sources:

- the compiler and Groovy 6's type checkers (NullChecker, SqlInjection, Regex, FormatString, Combiner, Purity);
- CodeNarc 4 with a profile aligned with the [practices knowledge base](docs/practices.md);
- groovyscope's own practice checks, GS001 to GS012 (integer division, Elvis on 0, GString logging, `Eval`,
  `@Grab`, string `execute()`, XXE, Groovy 6-only syntax under a Groovy 5 target and more);
- optional [runtime profiles](docs/profiles.md) (RP codes) for plugin code that runs on a different Groovy and
  classpath than the local build: constructs not proven on the runtime, descriptor parameter mistakes, secret
  flows, contract drift against a recorded baseline, and an emulation compile with the runtime's own Groovy.

`groovyscope fix` and the `fix` MCP tool apply the safe fixes (adding `@CompileStatic` only when the class
then type-checks, `intdiv`, typed parameter reads and more) and reject any edit that would stop the file
parsing. `explain` says why a finding matters and how to fix it.

## Commands

| Command | What it does |
|---|---|
| `groovyscope doctor [--bundle report.zip]` | Checks Python, configuration and trust, JDK (vendor and version), the engine JAR (SHA-256), Kiro files, the Kiro agent's format and runtime profiles; `--bundle` writes a zip for bug reports |
| `groovyscope logs [-n N] [-f] [--kiro]` | The engine daemon's log for this workspace, or groovyscope's lines from Kiro CLI's logs |
| `groovyscope trust [--revoke] [--list]` | Trust this workspace, so its `.groovyscope.yaml` may set `workspace.trust`, `jdk.home` and rule sets ([security](docs/security.md)) |
| `groovyscope kiro init \| repair [--global] [--full] [--agent-format auto\|v2\|universal\|v3] [--dry-run]` | Installs or re-merges the Kiro files: MCP server, agent and steering in `~/.kiro` with `--global`; the LSP entry (everything with `--full`) in a project. The agent keeps its format (V2, universal or V3) |
| `groovyscope check [PATHS] [--include lint,compile,typecheck,practices] [--format text\|json\|short\|sarif]` | All findings; `short` matches `groovyc --error-format short`, `sarif` feeds code scanning; exits 1 at or above `lint.failOnPriority` |
| `groovyscope fix [PATHS] [--code X] [--apply]` | Safe fixes; reports them unless `--apply` |
| `groovyscope explain CODE` | Why a code (GS001, RP010, NullChecker, a CodeNarc rule) matters and how to fix it |
| `groovyscope format [PATHS] [--apply]` | Bundled formatter (whitespace hygiene, safe CodeNarc-driven fixes) |
| `groovyscope impact TARGET [--change signature\|rename\|delete\|behaviour]` | Direct and transitive callers, overrides, tests, name-only sites, risk and suggested checks |
| `groovyscope callgraph TARGET [--direction in\|out] [--depth N]` | Callers of, or calls made by, a declaration with per-edge confidence |
| `groovyscope profile list \| validate FILE \| status PATH` | Runtime profiles found for the workspace, schema validation, and what governs a file |
| `groovyscope baseline save \| check` | Records the contract surface of profile-governed files; `check` exits 1 when something recorded changed |
| `groovyscope config validate [--show]` | Validates `.groovyscope.yaml` + `GROOVYSCOPE_*` overrides against the schema |
| `groovyscope lsp --stdio` / `groovyscope mcp` | What Kiro runs: LSP and MCP over the warm per-workspace daemon |
| `groovyscope daemon start \| stop \| restart \| status` | Manages the daemon (idle exit after `daemon.idleTimeoutMinutes`; `restart` applies a changed configuration) |
| `groovyscope jdk install [--vendor temurin\|corretto]` | Opt-in, checksum-verified JDK download into groovyscope's cache |
| `groovyscope fetch` / `groovyscope version` | Engine JAR fetch for packages without one; versions |

Most commands take `--json`. Every command takes `--debug`, `-v`/`--verbose` and `--log-file PATH`
([debug logging](docs/troubleshooting.md#debug-logging)).

## MCP tools

`groovyscope mcp` registers one server, `groovyscope`, with these tools. Every answer is one compact JSON text
block (no pretty printing, no second structured copy unless `output.structured: true`): paths relative to the
workspace root, 1-based `line`/`col`, a confidence where one applies, at most `output.maxResults` items within
`output.maxBytes`, finding messages clipped to `output.maxMessageChars`, and a `next_cursor` to page on. No tool
returns file bodies. Each tool carries a title and MCP behaviour annotations (read-only or not, idempotent,
closed-world), and parallel calls are queued safely on the one engine session.

| Tool | Answers |
|---|---|
| `find_symbol(query, kind?, limit?, cursor?)` | Declarations whose name contains the query |
| `goto_definition(path, line, col)` | The declaration under a position, with its confidence |
| `type_definition(path, line, col)` | The declaration of the type of the expression under a position |
| `hover(path, line, col)` | What an editor shows on hover: signature, documentation, runtime-profile parameter details, why a finding on that line matters |
| `document_symbols(path, depth?)` | The outline of a file (classes, members), instead of reading it |
| `find_references(path, line, col, include_declaration?, limit?, cursor?)` | Every reference with a confidence, plus counts per confidence |
| `call_graph(symbol, direction?, depth?)` | Callers of (`in`) or calls made by (`out`) a declaration |
| `impact_of(symbol, change?)` | Direct and transitive callers, overrides, tests, dynamic sites, risk and suggested checks |
| `check(paths?, profile?, include?, limit?, cursor?)` | Compile, lint, type-check and practice findings, worst first |
| `review(paths?, top?)` | The same findings grouped by the review checklist, with the profiles governing the paths |
| `fix(paths?, codes?, apply?)` | Safe fixes; a dry run unless `apply` |
| `rename(path, line, col, new_name, apply?)` | Rename everywhere it is used, refused when a use is matched by name only; a dry run unless `apply` |
| `explain(code)` | What a code means, why it matters and the fix |
| `profile_status(path)` | Target Groovy, compiling service, construct status on the runtime, descriptor parameters, test seams and protected code of a governed file |
| `format(paths?, apply?)` | Files the formatter would change; `apply` writes them |
| `find_dynamic_code(paths?, limit?, cursor?)` | Untyped classes, `def` on public API, `methodMissing`, `metaClass` writes, `@Grab`, `Eval`, `execute()` |
| `workspace_status()` | Daemon state, engine version, root, file count and trust flag |

## Configuration

Nothing is required. To change a default, copy [config/defaults.yaml](config/defaults.yaml) to
`.groovyscope.yaml` in your workspace and keep only what you change. Any key can be overridden with an
environment variable, for example `GROOVYSCOPE_DAEMON__HEAP=2g`. Runtime profiles live in
`~/.groovyscope/profiles/` or `<workspace>/.groovyscope/profiles/` and stay out of the repository if you want
them private; see [docs/profiles.md](docs/profiles.md).

## Security posture

`@Grab` and `@ASTTest` are disabled in the engine JVM and cannot be enabled through configuration. Global AST
transforms from the workspace classpath load only with `workspace.trust: true`. The classpath comes from
configuration, never from silent inference. See [docs/security.md](docs/security.md).

## Repository layout

```
engine/   JVM engine (Java 21 sources, Gradle, Spock tests)       -> groovyscope-engine-<ver>-all.jar
python/   groovyscope package (CLI, config, Kiro writers, tests)   -> PyPI wheel bundling the JAR
config/   CodeNarc rulesets, practice catalogue, example runtime profile, formatter and groovyc configuration
          (mirrored inside the package)
kiro/     Templates written by `groovyscope kiro init` (mirrored inside the package)
docs/     Install, Kiro, practices, profiles, rules, architecture, security, troubleshooting, benchmark, roadmap
```

## Contributing

Conventional Commit PR titles, squash merges, and releases through release-please. See
[AGENTS.md](AGENTS.md) for the build and style rules, and [docs/architecture.md](docs/architecture.md) for the
design.

Licensed under the Apache License 2.0.
