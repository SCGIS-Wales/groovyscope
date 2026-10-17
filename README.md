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

Stage 1 of 7 (navigation). The engine compiles the workspace with the Groovy compiler and serves go to
definition, type definition, find references, hover, document and workspace symbols, completion, signature
help, rename with a dry-run prepare step, and compile diagnostics. Every answer carries a confidence
(`STATIC`, `INFERRED`, `DYNAMIC_UNKNOWN`); rename refuses rather than guesses when a call site can only be
matched by name. Lint, format, impact analysis and the MCP server follow stage by stage; see
[docs/roadmap.md](docs/roadmap.md).

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
| `groovyscope lsp --stdio` | Runs the engine's Language Server (what Kiro spawns) |
| `groovyscope mcp`, `groovyscope daemon …` | Planned (Stages 5 and 2); exit 3 today |

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
