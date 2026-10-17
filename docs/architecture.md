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
| `daemon` | 2 | Unix domain socket server, one front end per connection, shared `WorkspaceRegistry`, idle watchdog, `groovyscope/status` and `groovyscope/shutdownDaemon`. |
| `lint`, `typecheck`, `format` | 3 | CodeNarc in process, Groovy 6 checkers as diagnostics, GrEclipse formatting returning edits. |
| `callgraph` | 4 | Call graph and impact analysis with per-edge confidence. |

Keeping the engine API free of LSP4J types outside `lsp` lets the front end be swapped for, or contribute to,
Apache Groovy's own `groovy-lsp` (GROOVY-12411) if it ships.

## Daemon (Stage 2)

`groovyscope lsp --stdio` no longer runs a JVM per editor session. It probes the workspace's Unix domain
socket, starts `groovyscope-engine daemon` detached when nothing answers, and then relays its own stdio to
the socket byte for byte, so Kiro still sees a plain stdio language server. Every connection gets its own
LSP front end; workspaces are shared through a `WorkspaceRegistry`, so two Kiro sessions on one project
reuse one compile and one index. The daemon exits after `daemon.idleTimeoutMinutes` without connections or
when `groovyscope daemon stop` sends `groovyscope/shutdownDaemon`. If the daemon cannot be started, the CLI
says so on stderr and runs the engine directly, exactly as in Stage 1.

Locations (overridable with `GROOVYSCOPE_SOCKET_DIR` and `GROOVYSCOPE_CACHE_DIR`):

| What | macOS | Linux |
|---|---|---|
| Socket | `~/Library/Caches/groovyscope/run/<key>.sock` | `$XDG_RUNTIME_DIR/groovyscope/<key>.sock` |
| Daemon log | `~/Library/Caches/groovyscope/daemon/<key>.log` | `$XDG_CACHE_HOME/groovyscope/daemon/<key>.log` |
| Symbol cache | `~/Library/Caches/groovyscope/<key>/symbols.db` | `$XDG_CACHE_HOME/groovyscope/<key>/symbols.db` |
| CDS archive | `~/Library/Caches/groovyscope/cds/engine-<jar sha>.jsa` | `$XDG_CACHE_HOME/groovyscope/cds/engine-<jar sha>.jsa` |

`<key>` is the first sixteen hex digits of the SHA-256 of the absolute workspace root, computed the same
way in Python and in the engine.

The symbol cache (SQLite) stores every declaration keyed by the content hash of its document. On a cold
start, workspace and document symbol requests are answered from the cache for unchanged files while the
first compile runs in the background; the cache is rewritten after every compile. The Class Data Sharing
archive is created by the JVM on first run (`-XX:+AutoCreateSharedArchive`, JDK 19+) and reused afterwards.

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
- The whole workspace is recompiled after an edit (debounced) inside the warm daemon. Per-file invalidation
  with a dependency graph remains future work; on projects of a few hundred files the full recompile is
  well under a second.

## Python package (`python/`)

| Module | Responsibility |
|---|---|
| `cli.py` | click commands; nothing but wiring and exit codes |
| `config.py` | defaults ← `.groovyscope.yaml` ← `GROOVYSCOPE_*`; JSON-schema validation |
| `java.py` | JDK discovery with injected `runner`/`which` for testability |
| `jar.py` | bundled JAR lookup and SHA-256 verification, `GROOVYSCOPE_JAR` override |
| `launch.py` | the JVM command line with non-configurable safety flags and the optional CDS archive |
| `daemon.py` | socket, log and cache locations; probe, start (detached), stop, status |
| `rpc.py` | a small JSON-RPC client over the socket (Content-Length framing) |
| `relay.py` | asyncio byte relay between this process's stdio and the socket |
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
