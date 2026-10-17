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
| `lsp` | 0, 1 | LSP4J server: lifecycle, incremental document sync, definition, type definition, references, hover, document and workspace symbols, completion, signature help, prepare/rename, published diagnostics. The only package allowed to import LSP4J types. |
| `version` | 0 | Engine version from a Gradle-expanded resource. |
| `config` | 1 | The slice of `.groovyscope.yaml` the engine needs, handed over as JSON by the Python front end. |
| `workspace` | 1 | Source discovery under the configured roots (whole-tree fallback), open-document overlay, classpath from configuration (never inferred). |
| `compile` | 1 | `CompilationUnit` to `CANONICALIZATION`, never class generation; Grape and `@ASTTest` off; a filtered parent class loader; global AST transforms gated by `workspace.trust`. See "Compile strategy". |
| `index`, `semantics` | 1 | Positioned node index per document, declaration lookup, and a resolver that assigns a confidence to every answer. |
| `services` | 1 | `LanguageServices`: the protocol-free engine API over an immutable `Snapshot` (compile + index + resolver). |
| `daemon` | 2 | Unix domain socket server, idle timeout, SQLite persistence, custom `groovyscope/*` methods for the MCP bridge. |
| `lint`, `typecheck`, `format` | 3 | CodeNarc in process, Groovy 6 checkers as diagnostics, GrEclipse formatting returning edits. |
| `callgraph` | 4 | Call graph and impact analysis with per-edge confidence. |

Keeping the engine API free of LSP4J types outside `lsp` lets the front end be swapped for, or contribute to,
Apache Groovy's own `groovy-lsp` (GROOVY-12411) if it ships.

## Compile strategy

Groovy abandons semantic analysis for the whole compilation unit as soon as one file reports an error, which
would leave every file unresolved whenever a single import is missing. The engine therefore compiles in up to
three passes:

1. all sources to `CANONICALIZATION`;
2. on failure, the sources that reported errors are dropped and the rest are compiled again (repeated a few
   times, since dropping a file can orphan its dependants);
3. each dropped file is parsed on its own to `CONVERSION` and run through `VariableScopeVisitor`, so it still
   yields symbols, local-variable navigation and name-based matches. Its diagnostics are kept.

Workspace code is loaded through a parent class loader that exposes only the JDK and the Groovy runtime the
engine ships, never the engine's own dependencies, and global AST transformations are discovered only from the
Groovy runtime itself or, when `workspace.trust` is on, from the workspace classpath.

## Confidence

Every reference and definition carries one of three confidences. `STATIC`: the compiler or a declared type
pins the target. `INFERRED`: a single candidate matched on an inferred receiver type (a `def` initialised
with a constructor, a chained call, a type matched by simple name when the compiler could not resolve it).
`DYNAMIC_UNKNOWN`: matched by name only. Rename refuses when any reference is `DYNAMIC_UNKNOWN`; impact
analysis (Stage 4) will report those sites for manual review.

## Known limits of Stage 1

- GDK extension methods on JDK types (for example `String.size()`) are not resolved.
- Default-argument overloads and methods added by AST transformations are not visible before class
  generation, which the engine never runs.
- The whole workspace is recompiled after an edit (debounced). Incremental, per-file invalidation arrives
  with the daemon in Stage 2.

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
