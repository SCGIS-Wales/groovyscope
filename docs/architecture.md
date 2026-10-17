# Architecture

## Shape

```
Kiro CLI ──(stdio, LSP)──▶ groovyscope lsp --stdio ──▶ engine JAR (LSP4J front end)
Kiro CLI ──(stdio, MCP)──▶ groovyscope mcp (Stage 5) ──(JSON-RPC over Unix socket, Stage 2)──▶ engine daemon
```

One JVM engine per workspace. Two thin clients. Python owns the edges (installer, CLI, configuration, MCP
bridge, output shaping); the JVM owns every semantic answer because the Groovy AST, static type checker and
CodeNarc exist only there.

## Engine (`engine/`, Java 21, bytecode 17)

Packages under `org.groovyscope.engine`:

| Package | Stage | Responsibility |
|---|---|---|
| `lsp` | 0 | LSP4J server: lifecycle today; definition, references, hover, symbols, rename, diagnostics later. The only package allowed to import LSP4J types. |
| `version` | 0 | Engine version from a Gradle-expanded resource. |
| `workspace` | 1 | Roots, source sets, classpath from configuration (never inferred). |
| `compile` | 1 | `CompilationUnit` to `CANONICALIZATION`, never class generation; per-file invalidation; Grape and `@ASTTest` off; global AST transforms gated by `workspace.trust`. |
| `index`, `semantics` | 1 | Symbol and reference indexes; resolution through STC metadata; unresolved dynamic calls are `UNKNOWN`, never guessed. |
| `daemon` | 2 | Unix domain socket server, idle timeout, SQLite persistence, custom `groovyscope/*` methods for the MCP bridge. |
| `lint`, `typecheck`, `format` | 3 | CodeNarc in process, Groovy 6 checkers as diagnostics, GrEclipse formatting returning edits. |
| `callgraph` | 4 | Call graph and impact analysis with per-edge confidence. |

Keeping the engine API free of LSP4J types outside `lsp` lets the front end be swapped for, or contribute to,
Apache Groovy's own `groovy-lsp` (GROOVY-12411) if it ships.

## Python package (`python/`)

| Module | Responsibility |
|---|---|
| `cli.py` | click commands; nothing but wiring and exit codes |
| `config.py` | defaults ← `.groovyscope.yaml` ← `GROOVYSCOPE_*`; JSON-schema validation |
| `java.py` | JDK discovery with injected `runner`/`which` for testability |
| `jar.py` | bundled JAR lookup and SHA-256 verification, `GROOVYSCOPE_JAR` override |
| `launch.py` | the JVM command line with non-configurable safety flags |
| `kiro.py` | idempotent merge/backup/diff writers for the Kiro files |
| `doctor.py` | pure report building for `doctor` |
| `render.py` | relative paths, pagination with continuation cursors, byte caps for agent output |

Style: functional, no user-defined classes (TypedDicts only), `mypy --strict`.

## Packaging

The release builds the shaded JAR, `scripts/bundle_jar.py` copies it into `python/src/groovyscope/_jar/`
with a manifest (version, file name, SHA-256), and `uv build` produces a `py3-none-any` wheel. One artefact
serves Linux and macOS; the JDK is the only external prerequisite.

## Token economy

MCP tools return compact JSON: paths relative to the root, `line:col`, a symbol kind, at most
`output.maxResults` items within `output.maxBytes`, plus a continuation cursor. They never return file bodies.
