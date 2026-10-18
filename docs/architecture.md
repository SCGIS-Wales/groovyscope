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
| `lsp` | 0, 1, 10 | LSP4J server: lifecycle, incremental document sync, definition, type definition, implementation, references, document highlight, hover, document and workspace symbols, folding ranges, inlay hints, completion, signature help, prepare/rename, formatting and range formatting, code actions (quick fixes, fix all, organize imports), published and pulled diagnostics, call hierarchy, semantic tokens, plus the `groovyscope/*` requests the CLI and MCP server use. The only package allowed to import LSP4J types. |
| `version` | 0 | Engine version and the embedded CodeNarc version from a Gradle-expanded resource. |
| `config` | 1 | The slice of `.groovyscope.yaml` the engine needs, handed over as JSON by the Python front end. |
| `workspace` | 1 | Source discovery under the configured roots (whole-tree fallback), open-document overlay, classpath from configuration (never inferred). |
| `compile` | 1 | `CompilationUnit` to `CANONICALIZATION`, never class generation; Grape and `@ASTTest` off; a filtered parent class loader; global AST transforms gated by `workspace.trust`. See "Compile strategy". |
| `index`, `semantics` | 1 | Positioned node index per document, declaration lookup, and a resolver that assigns a confidence to every answer. |
| `services` | 1 | `LanguageServices`: the protocol-free engine API over an immutable `Snapshot` (compile + index + resolver). |
| `daemon` | 2 | Unix domain socket server, one front end per connection, shared `WorkspaceRegistry`, idle watchdog, `groovyscope/status` and `groovyscope/shutdownDaemon`. |
| `lint`, `typecheck`, `format` | 3 | CodeNarc 4 in process (rule sets cached per path, findings cached per document hash); Groovy's static type checker over the workspace with the Groovy 6 checkers added to every `@CompileStatic`/`@TypeChecked` annotation; the bundled `basic` formatter returning one whole-document edit. |
| `services.QualityServices` | 3 | `check`, `format`, quick fixes and the merged compile + lint diagnostics the LSP publishes. |
| `callgraph` | 4 | `CallGraph` (every call as an edge from its containing method, script or class initialiser to each resolved target, built once per snapshot) and `ImpactAnalyzer` (direct and transitive callers, overrides in both directions, tests, name-only sites, risk score, suggested checks). |
| `practices`, `profiles` | 8 | GS practice checks with AST fixes and explanations; runtime profiles (RP codes, descriptors, contracts, emulation compile). See below. |
| `search` | 10 | `CodeSearch` (structural search over the compiled trees: catch clauses, throws, calls, constructor calls, `return null`, string literals, annotations, `def` declarations, empty blocks, fields and methods, each with filters), `CloneFinder` (duplicated code by normalised statement hashes), `SymbolReader` (the source of one declaration with the comments and annotations above it) and `WorkspaceOverview` (sizes per package, largest files and classes, untested classes). Java documents are never searched. |
| `compile.MissingDependencies` | 10 | Folds the unresolved-class errors of a file with no configured classpath into one `GS100` warning naming the missing packages. |
| `services.DocumentSupport` | 10 | Per-document editor features: implementations, highlights, folding ranges and inlay type hints. |
| `explain` | 11 | `CodeFacts`: what a class or method does as facts (members, calls and callers from the call graph, field reads and writes, throws and catches, external calls by category, branch count, public methods grouped by verb). |
| `graph` | 11 | `RelationshipGraph`: classes as nodes; `extends`, `implements`, `has`, `uses` and aggregated `calls` edges with the weakest confidence; scoped to seeds and a hop depth, capped best-connected first; a method-level view of one class. |
| `practices.NamingFinder` | 11 | GS020 to GS027 from the declared types and the body (a getter that assigns a field, a plural on a single string), with the team's abbreviations and vocabulary from `naming`. |

Keeping the engine API free of LSP4J types outside `lsp` lets the front end be swapped for, or contribute to,
Apache Groovy's own `groovy-lsp` (GROOVY-12411). That server was merged into Groovy's master branch on
11 October 2026 as `subprojects/groovy-lsp` with a `groovylsp` launcher in the distribution; it is not in a
released Groovy yet. When it ships, the plan is to evaluate it as the LSP front end for Kiro's `code` tool
while the MCP tools (impact, call graph, lint, format, dynamic-code review) stay on this engine, because
they need the index, call graph and CodeNarc integration that a plain language server does not offer.

## Daemon (Stage 2)

`groovyscope lsp --stdio` no longer runs a JVM per editor session. It probes the workspace's Unix domain
socket, starts `groovyscope-engine daemon` detached when nothing answers, and then relays its own stdio to
the socket byte for byte, so Kiro still sees a plain stdio language server. Every connection gets its own
LSP front end; workspaces are shared through a `WorkspaceRegistry`, so two Kiro sessions on one project
reuse one compile and one index. The daemon exits after `daemon.idleTimeoutMinutes` without connections or
when `groovyscope daemon stop` sends `groovyscope/shutdownDaemon`. If the daemon cannot be started, the CLI
says so on stderr and runs the engine directly, exactly as in Stage 1.

The JVM runs with `-Xmx<daemon.heap>`, the serial collector and `-XX:+ExitOnOutOfMemoryError`: a daemon that
runs out of heap exits at once instead of serving requests from a full heap with wedged threads, the relay and
the MCP server start a fresh one on the next request, and the log records the cause. `groovyscope daemon
status`, `doctor` and `checkup` read `OutOfMemoryError` from the log tail and name the `daemon.heap` fix;
`groovyscope/status` reports `memory.usedMb` and `memory.maxMb`, and the same commands warn once the heap in
use passes 85% of the maximum, before the daemon dies.

Requests from every connection run on two bounded lanes shared by the process (`RequestExecutors`), not on the
common pool: a heavy lane of `daemon.workerThreads` threads (the processor count by default) for anything that
compiles, lints, type-checks or formats, with at most `daemon.requestQueue` requests waiting, and a small light
lane for hover, definition, outline, references and status, so one long type check never starves navigation.
A request that finds the heavy queue full is answered at once with a `RequestFailed` error that names the load,
which is back pressure the client can act on instead of a timeout. Each request runs with its cancel flag bound
to the thread (`Cancellation`), and the compiler (between rounds), the type checker (between attempts), the
linter (between files), the index and the call graph (between modules and batches) check it at their safe
points, so `$/cancelRequest` stops work within one unit. Nothing is interrupted or stopped from outside.

A compile is single flight: the first request that finds the snapshot stale runs it, later requests join its
future and poll their own cancel flag while they wait. A cancelled waiter leaves without touching the compile the
others still need; the compile itself is abandoned, at its next checkpoint, only when every request interested
in it is gone, and the workspace then stays stale for the next request. With no client connected, a workspace
nobody has asked about for `daemon.workspaceIdleMinutes` is released (trees, index, loader and jars), and the
next connection that opens it compiles afresh. `status` answers from counters the model already holds, never
from a walk of the tree, so the liveness probe stays cheap on a busy daemon.

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

Groovy stops a compilation unit at the end of the phase in which any file reports an error, so one missing
import would keep every file from reaching `CANONICALIZATION`. The engine therefore compiles in passes:

1. all sources to `CANONICALIZATION`;
2. on failure, the sources that reported errors are dropped and the rest are compiled again, until a round
   succeeds (each round drops at least one file, so this ends; later rounds are small and fast);
3. each dropped file is parsed on its own to `CONVERSION` and run through `VariableScopeVisitor`, so it still
   yields symbols, local-variable navigation and name-based matches. Its diagnostics are kept.

Dropping a file breaks the files that use its classes, which then break theirs. In a deep hierarchy (a base
class with hundreds of subclasses, as in CodeNarc's rules) that cascade would drop most of the workspace. When
the rounds drop any file the first *resolved* round did not report, the engine keeps that round's trees instead:
it saw every class at once. The resolved round is the first one that got past parsing: a round that fails on a
syntax error drops only the broken file and says nothing about the rest, so it cannot anchor the guard, and
the round after it does. A syntax error in a base class therefore costs three rounds (syntax, resolution, one
cascade step), not one per subclass. The retries also have a wall-clock budget (`RetryBudget`: three times the
first round, five seconds at least); past it, the resolved round is kept as it stands. The type checker's retry
loop uses the same budget. Groovy checks for errors after every step of a phase, so it stopped that
round after resolving class headers; the engine runs the rest of semantic analysis on those trees itself (a
fresh `ResolveVisitor` for headers, bodies and variable scopes, then `StaticImportVisitor`). Every name that
can resolve does, and only the genuinely broken files are partial. Only the first round's errors are
reported, since later rounds mostly report the classes their own drops removed.

Classpaths are often incomplete (JUnit 4 without Hamcrest, say). A classpath type whose members mention a
missing class is treated as having no readable members, so the affected call stays unresolved instead of
failing the request.

The classpath is `workspace.classpath` plus the entries of `workspace.classpathFile`, which `groovyscope
classpath` writes by running the project's Gradle build with a bundled init script (a `groovyscopeClasspath`
task per project printing its resolved compile and test classpath and class folders) or Maven's
`dependency:build-classpath`; the command is refused in an untrusted workspace because build scripts execute.
The file records a fingerprint of every build file, so the front end can tell a stale export from a fresh one
with stat calls alone (`classpath.status`). The engine receives the classpath inside its configuration, and a
changed configuration (a re-exported classpath, an edited `.groovyscope.yaml`) reaches a running daemon
through `groovyscope/reloadConfig`: the CLI sends it when the daemon's `configHash` differs from its own, the
MCP server before any request once the configuration or classpath file changed on disk. The workspace model
takes the new configuration in place, the services drop their caches and the next query recompiles; no
connection is dropped. The disk fingerprint also covers the classpath entries, so a rebuilt module jar or
classes folder marks the snapshot stale like an edited source.
With no classpath at all, every import of a library class and every use of it is a compile error that hides
the one real cause, so `MissingDependencies` folds those errors into one `GS100` warning per file (severity
from `practices.severity`, `off` drops them) before diagnostics are published or returned.

Joint Groovy/Java projects: with `workspace.javaSources` (on by default), `.java` files under the source
roots (`src/main/java` and `src/test/java` are among the defaults) join the compile, read by Groovy's parser,
which accepts most Java. Groovy code then resolves the project's Java classes, and navigation, references
and impact analysis cover them. Java files are never reported on, linted, formatted or scanned for dynamic
code: javac and the IDE own Java. A Java construct Groovy rejects only makes that file partial.

The class loader of the round whose trees are kept stays open with them, because Groovy decompiles classpath
types lazily when their members are first visited; the loaders of discarded rounds are closed at once. The kept
loader is owned by the `CompilationResult` (a `LoaderHandle`), and `LanguageServices` closes it when the next
snapshot is published, so each generation releases its jars instead of waiting for the garbage collector to
notice them. A file that stops parsing does not carry its previous tree into the new snapshot either (that
would pin the whole previous generation, trees and loader, for as long as the error lasts): its last good
text is parsed again, alone, into the current generation, so the outline and navigation degrade gracefully at
the cost of one small parse. The call graph is dropped on every recompile for the same reason.

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
  profiles, with `lint.rules` applied on top (a rule switched off or given another priority); Jenkinsfiles
  and `vars/` scripts get the Jenkins profile automatically. Findings are cached by document content hash and
  profile, published as diagnostics (`lint.publish`, down to `lint.publishMaxPriority`; `check` and `review`
  are never filtered), served through pull diagnostics, and exposed as quick fixes where the fix is purely
  textual (unnecessary semicolon, trailing whitespace, extra blank line). Every diagnostic a fix can repair
  carries `data.fixable`, and the `source.fixAll.groovyscope` and `source.organizeImports` code actions apply
  the safe fixes of a whole file.
- **Type-check.** `check --include typecheck` compiles the workspace to `INSTRUCTION_SELECTION` (still no
  bytecode) in a separate unit with a customizer that appends the configured Groovy 6 checkers
  (`NullChecker`, `SqlInjectionChecker`, `PurityChecker` + `ModifiesChecker`) to the `extensions` of every
  existing `@CompileStatic` or `@TypeChecked` annotation. Dynamic code is never forced into static typing.
- **Format.** `format.engine: basic` is bundled: leading tabs to spaces, trailing whitespace removed, blank
  runs capped, exactly one final newline, and semicolons removed only on lines CodeNarc flagged. It never
  re-flows code. `greclipse` is accepted by the schema but refused by the engine: the Groovy Eclipse
  formatter needs the Eclipse bundles that Spotless fetches at runtime, which is at odds with "no runtime
  downloads", and vendoring them would roughly double the wheel. Decision (Stage 6): not bundled; teams
  that want it run Spotless in their build with `config/format/greclipse.properties`.
- **CLI.** `groovyscope check` and `groovyscope format` talk to the daemon through the custom
  `groovyscope/check` and `groovyscope/format` requests (passing the workspace root, since those connections
  skip `initialize`) and return compact JSON on request.

## Practices and runtime profiles (Stage 8)

`practices/` holds the catalogue (`practices.json`, mirrored from `config/practices/`), `PracticeFinder`
(GS001-GS012, a visitor over the snapshot's trees that uses the resolver for static types),
`PracticeFixes` (AST-aware edits, applied with earlier edits winning on overlap) and `Explanations` (the
`groovyscope/explain` request, hover notes and `codeDescription` links). Findings are cached per snapshot
version. `QualityServices.fix` applies every safe fix, re-parses the result and rejects a document that no
longer parses; for a class without a typing choice it adds `@CompileStatic` only when the class then passes
the static type checker, otherwise `@CompileDynamic`.

`profiles/` implements [runtime profiles](profiles.md). `Profiles.governing` picks the profile for a
document (activation plus marker). `ProfileFinder` reports RP codes from the tree; `ProfileArtefacts` checks
descriptors and Terraform files; `Contracts` extracts the contract surface and compares it with
`.groovyscope/contract-baseline.json`; `ProfileNavigation` resolves accessor arguments to descriptor and
Terraform declarations for definition, references and hover; `RemoteCompiler` runs the emulation compile
reflectively against the profile's jars in a class loader parented to the platform loader, so the engine's
own Groovy never leaks in. A profile's target version re-runs GS010 as RP002, its checker policy caps or
drops Groovy 6 checker findings, and its protected regions filter every fix and code action. Python
discovers and validates profiles (`profiles.py`, `profile-schema.json`) and hands them to the engine in
`GROOVYSCOPE_CONFIG_JSON`.

The daemon is shared by editors and by CLI and MCP calls. Editors report edits; CLI and MCP calls do not,
so every `groovyscope/*` request first compares a fingerprint of the compile files (path, size,
modification time) with the one taken at the last compile and recompiles when it differs.

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

## Known limits

- GDK extension methods on JDK types (for example `String.size()`) are not resolved.
- Overloads with the same number of parameters are not told apart by argument type (that needs the static
  type checker, which runs only at a later phase); a call matches each such overload.
- Default-argument overloads and methods added by AST transformations are not visible before class
  generation, which the engine never runs.
- The whole workspace is recompiled after an edit inside the warm daemon, lazily: a push after the
  `diagnostics.debounceMillis` quiet window, or the next request that needs the snapshot. Measured on the
  CodeNarc corpus (529 Groovy files, no classpath): 3.6 s for the recompile, 4.2 s for the separate type-check
  compile, 1.2 s for a whole-workspace `check` with nothing changed. What made edits slow before 0.13 was not
  the compile but the delivery: every push recomputed practice and lint findings for every file, and a pull
  for one document computed every document. Now a push covers the documents the connection has open
  (`diagnostics.push: open`), a pull computes its one document, and the type-check compile runs only when a
  requested file mentions `@CompileStatic` or `@TypeChecked` (the checkers apply nowhere else). Per-file
  invalidation with a dependency graph remains future work; the numbers above say it is not the bottleneck.

## Python package (`python/`)

| Module | Responsibility |
|---|---|
| `cli.py` | click commands; nothing but wiring and exit codes |
| `config.py` | defaults ← `.groovyscope.yaml` ← `GROOVYSCOPE_*`; JSON-schema validation |
| `workspace_init.py` | `groovyscope init`: a starting `.groovyscope.yaml` from the layout and build files, read as text |
| `classpath.py` | `groovyscope classpath`: the Gradle init script or Maven command, parsing, `workspace.classpathFile`, the build fingerprint and the classpath state |
| `trust.py` | the per-user trust record that gates `workspace.trust`, `jdk.home`, rule sets and `classpath` |
| `java.py` | JDK discovery with injected `runner`/`which` for testability |
| `jar.py` | JAR lookup (`GROOVYSCOPE_JAR`, bundled, fetched) and SHA-256 verification |
| `fetch.py` | `groovyscope fetch`: checksum-verified install of a release JAR into the cache |
| `provision.py` | opt-in Temurin JDK provisioning through the Adoptium API (`jdk install`, `jdk.autoProvision`) |
| `launch.py` | the JVM command line with non-configurable safety flags and the optional CDS archive |
| `daemon.py` | socket, log and cache locations; probe, start (detached), stop, status |
| `rpc.py` | a small JSON-RPC client over the socket (Content-Length framing); one-shot requests and persistent sessions |
| `mcp_server.py` | the MCP server: twenty-seven tool functions over one engine session, compact output through `render.py`; reloads the engine configuration in place when it changes |
| `mcp_search.py`, `mcp_server_support.py` | `read_symbol`, `find_code`, `find_duplicates` and `workspace_overview`; the shared helpers (paging, paths, clipping) |
| `relay.py` | asyncio byte relay between this process's stdio and the socket |
| `kiro.py` | idempotent merge/backup/diff writers for the Kiro files |
| `kiro_agent.py` | agent format detection (V2, universal, out of sync, V3) and format-preserving merges; the servers an agent's `tools` hides |
| `checkup.py` | `groovyscope checkup`: the post-install report with the fix per item and the safe repairs the agent start hook applies |
| `benchmark.py` | the bundled CodeNarc corpus (`_benchmark/`), unpacked into the user's home with a version stamp |
| `changes.py` | changed files and line ranges from git, the pure review rules (split, bucket, touched declarations, verdict) and the merge request comment drafts |
| `reports.py` | SARIF logs as findings and JaCoCo XML as coverage gaps, read as data |
| `graph.py` | the relationship graph as a picture: layered layout (cycle break, longest-path layers, barycentre ordering), A4 fit with a readable minimum font, draw.io XML and Mermaid emitters, an overlap check |
| `mcp_understanding.py` | `explain_code`, `relationship_graph` and `check_naming` over `groovyscope/explainCode`, `/graph` and `/check` |
| `doctor.py` | pure report building for `doctor` |
| `render.py` | relative paths, pagination with continuation cursors, byte caps for agent output |

Style: functional, no user-defined classes (TypedDicts only), `mypy --strict`.

## Packaging

The release builds the shaded JAR, `scripts/bundle_jar.py` copies it into `python/src/groovyscope/_jar/`
with a manifest (version, file name, SHA-256), and `uv build` produces a `py3-none-any` wheel and an sdist
that both carry the JAR. One artefact serves Linux and macOS; the JDK is the only external prerequisite.

Releases are cut by release-please on `main`; merging the release PR tags `vX.Y.Z` and dispatches
`workflow.yml` (build, provenance attestations, PyPI through trusted publishing, release assets with SBOMs
and checksums). The Homebrew tap updates itself: a daily workflow in SCGIS-Wales/homebrew-tap renders the
formula for the newest PyPI release, installs and tests it on macOS, and commits it. The publish workflow is
dispatched rather than called as a reusable workflow because PyPI verifies that the signing certificate
names the registered workflow file. The tap formula (`scripts/homebrew_formula.py`) installs the sdist into
a virtualenv with uv and depends on `openjdk@21`; `groovyscope fetch` and `groovyscope jdk install` cover
installs that bundle no JAR or have no JDK, both checksum-verified and never implicit.

## MCP server

`groovyscope mcp` runs the official `mcp` SDK's `MCPServer` on stdio. At start it opens one persistent
JSON-RPC session to the workspace daemon (starting it when needed) and binds every tool to that session; the
session closes when the MCP client disconnects. It sends no LSP `initialize`: every request names its root,
and an initialized session would make the engine push diagnostics for every file to a client that ignores
them. Tools are plain
functions built by `build_tools(root, call, output, environ)`, which keeps them testable against a fake
engine. Each tool maps to one engine request: `groovyscope/symbols`, `/definition`, `/references`,
`/callGraph`, `/impact`, `/check` (also behind `review`), `/fix`, `/explain`, `/profile`, `/format`,
`/dynamicCode`, `/status`, `/readSymbol`, `/findCode`, `/duplicates` and `/overview`. Every request carries
`root`, so the daemon serves several Kiro sessions on the same workspace from one compiled snapshot.

Results are returned as one compact JSON text block per call. The SDK would otherwise pretty-print each result
and also attach it as `structuredContent`, sending every answer twice and about a third larger;
`output.structured: true` restores the structured copy for clients that read it. Tools carry titles and
`ToolAnnotations` (read-only, idempotent, closed-world). The SDK runs tools on worker threads and agents call
tools in parallel, so the one engine connection is a channel: a reader thread matches replies to requests by id
and each call waits on its own future, with no lock held for the duration of a request. Three guardrails sit in
front of it (`mcp.*` in `.groovyscope.yaml`): a budget of `mcp.requestTimeoutSeconds` (100 s, under Kiro's
120 s tool timeout) after which the call sends `$/cancelRequest` to the engine and answers "still running";
`mcp.maxInFlight` (4) calls on the engine at a time, the rest waiting within the same budget; and single flight,
where a call identical to one still running joins it instead of doubling the work. The channel is reopened
(with a daemon restart if needed) when the engine went away; a slow engine is never mistaken for a dead one.
`hover`, `type_definition`, `document_symbols` and `rename` map to `groovyscope/hover`, `/typeDefinition`,
`/documentSymbols` and `/rename`, which reuse the LSP implementations.

The reading tools exist so an agent never needs a file body: `workspace_overview` for orientation,
`document_symbols` for a file's outline with line ranges (paged, filtered by kind or name), `read_symbol` for
the source of one declaration with line numbers, `find_code` for a structural search that grep cannot do
(a catch that swallows `Exception`, calls on a receiver type, `return null`, methods over a length), and
`find_duplicates` for repeated blocks with identifiers and literals ignored. `check` and `review` return
counts per code, `check` filters by `codes` and `max_priority`, and both summarise GS100 under
`missingDependencies` rather than listing it per file.

`review_changes` is composed in Python from the other tools: `changes.changed_files` runs git (`merge-base`,
`diff --unified=0`) for the changed files and their new-side line ranges; `check` on those files (with the
build's SARIF findings merged in by `reports.merge_sarif`) is split into findings on changed lines and
pre-existing ones, bucketed blocking (errors, security and runtime groups), should fix (warnings) and nits;
`documentSymbols` finds the declarations overlapping the changed ranges and `impact_of` scores them;
`find_code` for catch, `def` and `return null` is filtered to the changed lines; `coverage_gaps` names the
uncovered methods inside the change; `changes.verdict` writes the sentence. The CLI's `review --changed`
builds the same tools over the daemon socket and prints them.

`rename` with `apply` is verified: the changed documents are compiled before and after the write (`check`
with `compile` only), and when the count of compile errors rises the previous texts are written back and the
answer says why. `relationship_graph` writes a draw.io file only inside the workspace and only with the
`.drawio` extension; the layout is checked for overlapping or off-page boxes before the file is reported.

`find_dynamic_code` has no LSP counterpart either: `DynamicCodeFinder` walks the index for the
constructs that defeat static analysis (classes with neither `@CompileStatic` nor `@CompileDynamic`, `def`
on public methods, `methodMissing`/`propertyMissing`/`invokeMethod` hooks, `metaClass` writes, `@Grab` on
classes, methods or imports, `Eval`/`GroovyShell`/template engines, and `execute()` on a string, GString,
list, array or untyped receiver, the GDK's process launcher), so an agent can review
them before trusting navigation or impact answers around them.

## Token economy

MCP tools return compact JSON: paths relative to the root, `line:col`, a symbol kind, at most
`output.maxResults` items within `output.maxBytes`, plus a continuation cursor. They never return file bodies.

## CodeNarc version: resolved at build time, embedded, reported

The engine does not pin CodeNarc. `engine/settings.gradle.kts` asks Maven Central for
`org/codenarc/CodeNarc/maven-metadata.xml`, keeps the plain numeric releases (`4.0.0`, never `3.7.0-groovy-4.0`,
`-rc` or `-SNAPSHOT`), takes the highest and overrides the `codenarc` entry of the version catalogue with it. The
value in `gradle/libs.versions.toml` is the floor (the oldest release the engine was verified against, which
Dependabot still raises) and the offline fallback: a lower or missing answer never wins over it. The lookup is a
Gradle `ValueSource`, so a cached configuration is invalidated as soon as a new release appears. The build prints
one line, `CodeNarc <version>: <why>`, and expands the version into `engine-version.properties` and the JAR
manifest (`CodeNarc-Version`); `EngineVersion.codenarc()` reads it, `groovyscope/status` returns it, and the
bundle manifest written by `scripts/bundle_jar.py` carries it, so `groovyscope version` and `doctor` name the
CodeNarc the installed engine runs. The CycloneDX SBOM attached to each release records the same version.

| Need | How |
|---|---|
| Pin a release for one build | `./gradlew build -PcodenarcVersion=4.0.0` or `GROOVYSCOPE_CODENARC_VERSION=4.0.0` |
| No network | `--offline` or `-PcodenarcResolve=false`: the floor is used and the line says so |
| Corporate mirror | `-PcodenarcMetadataUrl=https://mirror/.../org/codenarc/CodeNarc/maven-metadata.xml` |

The benchmark corpus is a different thing: it is CodeNarc 4.0.0's `src/main` as a fixed subject, and it stays
fixed on purpose so credit benchmark runs remain comparable.
