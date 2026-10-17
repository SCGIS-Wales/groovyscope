# Architecture

## Shape

```
Kiro CLI ──(stdio, LSP)──▶ groovyscope lsp --stdio ──▶ engine JAR (LSP4J front end)
Kiro CLI ──(stdio, MCP)──▶ groovyscope mcp ──(JSON-RPC over Unix socket)──▶ engine daemon
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
| `lint`, `typecheck`, `format` | 3 | CodeNarc 4 in process (rule sets cached per path, findings cached per document hash); Groovy's static type checker over the workspace with the Groovy 6 checkers added to every `@CompileStatic`/`@TypeChecked` annotation; the bundled `basic` formatter returning one whole-document edit. |
| `services.QualityServices` | 3 | `check`, `format`, quick fixes and the merged compile + lint diagnostics the LSP publishes. |
| `callgraph` | 4 | `CallGraph` (every call as an edge from its containing method, script or class initialiser to each resolved target, built once per snapshot) and `ImpactAnalyzer` (direct and transitive callers, overrides in both directions, tests, name-only sites, risk score, suggested checks). |

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

## Lint, type-check and format (Stage 3)

- **Lint.** CodeNarc 4 runs in process on one document at a time (`StringSourceAnalyzer`), so unsaved
  editor text is linted too. Rule sets come from `lint.ruleset` / `lint.jenkinsRuleset` or the bundled
  profiles; Jenkinsfiles and `vars/` scripts get the Jenkins profile automatically. Findings are cached by
  document content hash and profile, published as diagnostics (`lint.publish`), served through pull
  diagnostics, and exposed as quick fixes where the fix is purely textual (unnecessary semicolon, trailing
  whitespace, extra blank line).
- **Type-check.** `check --include typecheck` compiles the workspace to `INSTRUCTION_SELECTION` (still no
  bytecode) in a separate unit with a customizer that appends the configured Groovy 6 checkers
  (`NullChecker`, `SqlInjectionChecker`, `PurityChecker` + `ModifiesChecker`) to the `extensions` of every
  existing `@CompileStatic` or `@TypeChecked` annotation. Dynamic code is never forced into static typing.
- **Format.** `format.engine: basic` is bundled: leading tabs to spaces, trailing whitespace removed, blank
  runs capped, exactly one final newline, and semicolons removed only on lines CodeNarc flagged. It never
  re-flows code. `greclipse` is accepted by the schema but refused by the engine: the Groovy Eclipse
  formatter needs the Eclipse bundles that Spotless fetches at runtime, which is at odds with "no runtime
  downloads"; vendoring them is a Stage 6 packaging question.
- **CLI.** `groovyscope check` and `groovyscope format` talk to the daemon through the custom
  `groovyscope/check` and `groovyscope/format` requests (passing the workspace root, since those connections
  skip `initialize`) and return compact JSON on request.

## Call graph and impact (Stage 4)

The call graph is derived from the index: every method-name, static-call and constructor-call entry is
resolved and becomes an edge from its container to each candidate target with the resolver's confidence.
Impact analysis walks that graph. Direct callers are the static and inferred edges into the declaration;
transitive callers follow static and inferred edges only, up to five levels; `DYNAMIC_UNKNOWN` edges are
listed separately as sites to review and never counted as resolved. Overrides are same-name, same-arity
methods in subclasses and superclasses. Tests are callers living under `src/test` or in classes named
`*Spec` / `*Test`. The risk score is a transparent heuristic (8 per direct caller, 2 per transitive, 10 per
override, 15 per dynamic site, 10 for public, 10 for rename or delete) capped at 100 and mapped to low,
medium or high. For classes, fields and properties, "callers" are the references that resolve to them.

Over LSP the same data backs `callHierarchy/incomingCalls` and `outgoingCalls`, and `semanticTokens/full`
is served from the index (declarations plus the usages that resolve to them). The agent-facing requests are
`groovyscope/impact` and `groovyscope/callGraph`, used by `groovyscope impact` and `groovyscope callgraph`,
which accept a qualified name (`pkg.Class.method`, `Class#method`, `Class`) or `path:line:col`.

A performance spec generates a synthetic workspace (300 classes in CI, `-Dgroovyscope.perf.files=2000` for
the full run) and prints cold compile, warm references and single-file re-check timings; its budgets are
deliberately loose so shared runners do not fail the build.

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
| `jar.py` | JAR lookup (`GROOVYSCOPE_JAR`, bundled, fetched) and SHA-256 verification |
| `fetch.py` | `groovyscope fetch`: checksum-verified install of a release JAR into the cache |
| `provision.py` | opt-in Temurin JDK provisioning through the Adoptium API (`jdk install`, `jdk.autoProvision`) |
| `launch.py` | the JVM command line with non-configurable safety flags and the optional CDS archive |
| `daemon.py` | socket, log and cache locations; probe, start (detached), stop, status |
| `rpc.py` | a small JSON-RPC client over the socket (Content-Length framing); one-shot requests and persistent sessions |
| `mcp_server.py` | the MCP server: nine tool functions over one engine session, compact output through `render.py` |
| `relay.py` | asyncio byte relay between this process's stdio and the socket |
| `kiro.py` | idempotent merge/backup/diff writers for the Kiro files |
| `doctor.py` | pure report building for `doctor` |
| `render.py` | relative paths, pagination with continuation cursors, byte caps for agent output |

Style: functional, no user-defined classes (TypedDicts only), `mypy --strict`.

## Packaging

The release builds the shaded JAR, `scripts/bundle_jar.py` copies it into `python/src/groovyscope/_jar/`
with a manifest (version, file name, SHA-256), and `uv build` produces a `py3-none-any` wheel. One artefact
serves Linux and macOS; the JDK is the only external prerequisite.

## MCP server

`groovyscope mcp` runs the official `mcp` SDK's `MCPServer` on stdio. At start it opens one persistent
JSON-RPC session to the workspace daemon (starting it when needed), sends `initialize`/`initialized`, and
binds every tool to that session; the session closes when the MCP client disconnects. Tools are plain
functions built by `build_tools(root, call, output, environ)`, which keeps them testable against a fake
engine. Each tool maps to one engine request: `groovyscope/symbols`, `/definition`, `/references`,
`/callGraph`, `/impact`, `/check`, `/format`, `/dynamicCode` and `/status`. Every request carries `root`, so
the daemon serves several Kiro sessions on the same workspace from one compiled snapshot.

`find_dynamic_code` is the one tool with no LSP counterpart: `DynamicCodeFinder` walks the index for the
constructs that defeat static analysis (classes with neither `@CompileStatic` nor `@CompileDynamic`, `def`
on public methods, `methodMissing`/`propertyMissing`/`invokeMethod` hooks, `metaClass` writes, `@Grab` on
classes, methods or imports, `Eval`/`GroovyShell`/template engines, and `execute()`), so an agent can review
them before trusting navigation or impact answers around them.

## Token economy

MCP tools return compact JSON: paths relative to the root, `line:col`, a symbol kind, at most
`output.maxResults` items within `output.maxBytes`, plus a continuation cursor. They never return file bodies.
