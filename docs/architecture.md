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

The engine compiles on a JDK 21 toolchain to Java 17 bytecode (`options.release.set(17)`). Java 17 is the floor
because it is the oldest long-term-support JDK still common on build agents, and groovyscope runs where the build
runs; nothing in the engine needs a newer language level. The build's `checkJava17Bytecode` task refuses a
dependency compiled for a newer JDK, and CI runs the integration tests on the built JAR with Temurin 17, 21 and 25
and Corretto 21 (since Phase 3 of the improvement plan). Doclint checks the Javadoc of public types and members
(`-Xdoclint:all,-missing/public`), so a broken reference fails the build.

Packages under `org.groovyscope.engine`:

| Package | Stage | Responsibility |
|---|---|---|
| `lsp` | 0, 1, 10 | LSP4J server: lifecycle, incremental document sync, definition, type definition, implementation, references, document highlight, hover, document and workspace symbols, folding ranges, inlay hints, completion, signature help, prepare/rename, formatting and range formatting, code actions (quick fixes, fix all, organize imports), published and pulled diagnostics, call hierarchy, semantic tokens, plus the `groovyscope/*` requests the CLI and MCP server use, grouped in `StatusRequests`, `QualityRequests`, `ExplainGraphRequests` and `SearchRequests` with `RequestParams` reading their parameters; `RequestExecutors` runs them on the light and heavy lanes. The only package allowed to import LSP4J types. |
| `org.groovyscope.engine` | 0 | `Main`: the command line entry point of the engine JAR (stdio language server or daemon), which reads the configuration the Python front end hands over. |
| `version` | 0 | Engine version and the embedded CodeNarc version from a Gradle-expanded resource. |
| `model` | 1 | The protocol-free value records every package speaks: positions, ranges, locations, symbols, diagnostics, edits, confidence, call sites, impact reports and check results. |
| `log` | 9 | `EngineLog`: levelled logging to stderr and the optional log file, never to stdout, which is the LSP channel. |
| `config` | 1 | The slice of `.groovyscope.yaml` the engine needs, handed over as JSON by the Python front end. |
| `workspace` | 1 | Source discovery under the configured roots (whole-tree fallback), open-document overlay, classpath from configuration (never inferred). |
| `compile` | 1 | `CompilationUnit` to `CANONICALIZATION`, never class generation; Grape and `@ASTTest` off; a filtered parent class loader; global AST transforms gated by `workspace.trust`. See "Compile strategy". |
| `util` | 6 | `Uris` (a `file:` URI as a path), `Hashes` (the one SHA-256 helper) and `Closeables`, shared by every package. |
| `index`, `semantics` | 1 | Positioned node index per document, declaration lookup, and a resolver that assigns a confidence to every answer. |
| `services` | 1, 6 | `LanguageServices`: the protocol-free engine API over an immutable `Snapshot` (compile + index + resolver), a facade over `SnapshotManager` (refresh, single flight compile, class loader ownership, symbol persistence) and `NavigationQueries` (definitions, references, hover, symbols, rename, impact, call hierarchy). |
| `daemon` | 2 | Unix domain socket server, one front end per connection, shared `WorkspaceRegistry`, idle watchdog, `groovyscope/status` and `groovyscope/shutdownDaemon`. |
| `lint`, `typecheck`, `format` | 3 | CodeNarc 4 in process (rule sets cached per path, findings cached per document hash); Groovy's static type checker over the workspace with the Groovy 6 checkers added to every `@CompileStatic`/`@TypeChecked` annotation; the bundled `basic` formatter returning one whole-document edit. |
| `services.QualityServices` | 3, 6 | `check` and the merged compile, lint and practice diagnostics the LSP publishes; a facade over `LintService` (rule sets, the lint class loader and cache, profile per file), `TypeCheckService` (the single flight type check, the configuration script, the coverage note), `FixService` (fixes, verdicts, code actions, organise imports, format) and `ProfileService` (runtime profiles, contracts, emulation). |
| `concurrent` | 18 | `Cancellation` (cooperative cancel checkpoints), `Containment` (which failures a request survives), `Freshness` (stale while revalidating for light requests), `MemoryGuard` and `MemoryPressureException` (refuse whole-workspace work under memory pressure). |
| `callgraph` | 4 | `CallGraph` (every call as an edge from its containing method, script or class initialiser to each resolved target, built once per snapshot) and `ImpactAnalyzer` (direct and transitive callers, overrides in both directions, tests, name-only sites, risk score, suggested checks). |
| `practices`, `profiles` | 8 | GS practice checks with AST fixes and explanations; runtime profiles (RP codes, descriptors, contracts, emulation compile). See below. |
| `search` | 10 | `CodeSearch` (structural search over the compiled trees: catch clauses, throws, calls, constructor calls, `return null`, string literals, annotations, `def` declarations, empty blocks, fields and methods, each with filters), `CloneFinder` (duplicated code by normalised statement hashes), `SymbolReader` (the source of one declaration with the comments and annotations above it) and `WorkspaceOverview` (sizes per package, largest files and classes, untested classes). Java documents are never searched. |
| `compile.MissingDependencies` | 10 | Folds the unresolved-class errors of a file with no configured classpath into one `GS100` warning naming the missing packages. |
| `services.DocumentSupport` | 10 | Per-document editor features: implementations, highlights, folding ranges and inlay type hints. |
| `explain` | 11 | `CodeFacts`: what a class or method does as facts (members, calls and callers from the call graph, field reads and writes, throws and catches, external calls by category, branch count, public methods grouped by verb). `TestSeams`: for one method, each collaborator with its visibility and how a test replaces it, the line where the tests already stub it, and the test classes with their helpers. |
| `graph` | 11 | `RelationshipGraph`: classes as nodes; `extends`, `implements`, `has`, `uses` and aggregated `calls` edges with the weakest confidence; scoped to seeds and a hop depth, capped best-connected first; a method-level view of one class. `Effects`: whether each method only reads or changes state (its own fields, another object, a parameter, a file, a database, a remote service, a process, a system property), propagated up static and inferred calls; a constructor filling its own fields is a read, a call resolved only by name is `unknown`; every call edge and method node carries its effect. `DiagramLayout`: the layered layout. |
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
lane for hover, definition, outline and status, so one long type check never starves navigation. `references`,
`rename` and the diagnostics push, which walk the workspace or lint it, run on the heavy lane. A light request
about one document never owns a compile: when that document is unchanged since the published compile, it is
answered from that snapshot at once while the compile runs on the workspace's background thread (stale while
revalidating); only a request about a document that changed waits for the fresh compile. The trade is bounded:
an answer about an unchanged document that points into a file being edited (a definition, an incoming call) can
carry that file's pre-edit range until the compile lands, which is milliseconds to seconds; after a configuration
reload nothing is answered from the old snapshot; and under heap pressure, when compiles are refused, a light
request is answered from the last compile even about an edited document. Hover shows the notes
of findings already computed and never lints. A request that finds the heavy queue full is answered at once with a `RequestFailed` error that names the load,
which is back pressure the client can act on instead of a timeout. Each request runs with its cancel flag bound
to the thread (`Cancellation`), and the compiler (between rounds), the type checker (between attempts), the
linter (between files), the index and the call graph (between modules and batches) check it at their safe
points, so `$/cancelRequest` stops work within one unit. Nothing is interrupted or stopped from outside. LSP4J
leaves a running request open when its connection closes, so each connection keeps a view of the lanes that
records its requests and cancels them all when the connection ends. An idle daemon closes its socket first and
keeps serving the clients already connected for a short grace period; only then are the lanes shut down, and a
request after that is answered with error -32099 "daemon is exiting", which a client takes as a reason to
reconnect.

A compile is single flight: the first request that finds the snapshot stale runs it, later requests join its
future and poll their own cancel flag while they wait. A cancelled waiter leaves without touching the compile the
others still need; the compile itself is abandoned, at its next checkpoint, only when every request interested
in it is gone, and the workspace then stays stale for the next request. The compile holds a lock that a waiter
polls with its cancel flag, never a monitor, and the type check is single flight per snapshot in the same way.
CLI and MCP requests compare a fingerprint of the files on disk (one walk, shared with the compile that follows)
to notice edits made outside the editor; `daemon.diskCheckMillis` (0, every request) lets a very large repository
trust one look for a while. With no client connected, a workspace
nobody has asked about for `daemon.workspaceIdleMinutes` is released (trees, index, loader and jars), and the
next connection that opens it compiles afresh. Handing a workspace out and releasing it are atomic per root, so
a connection never receives one that is being closed; a released workspace refuses further use and drains its
pending symbol save before its SQLite store (WAL mode, with a busy timeout) closes. `status` answers from counters the model already holds, never
from a walk of the tree, so the liveness probe stays cheap on a busy daemon.

Memory has two more guardrails. In the engine, a `MemoryGuard` watches the tenured pool: once the memory still in
use after a collection passes `daemon.heapPressurePercent` (85%) of the maximum, every workspace sheds the caches
it can rebuild (call graph, quality services, folded diagnostics, cached symbols), `status` reports
`memory.pressure`, and requests that would walk the whole workspace (`duplicates`, `overview`, `graph`, and
`check`, `format` or `dynamicCode` with no `uris`) are refused with the `daemon.heap` advice while single-document
requests keep working; under pressure a compile or type check is refused too, while a light request keeps the
published snapshot. Shedding takes no lock, so it never waits behind a request. Pressure clears when a later
collection leaves the pool under the threshold. A file that crashes the type checker is found by halving the
files (a few compiles) and reported on itself, while the rest are still checked.
`OutOfMemoryError` is still never caught. Before that point, admission control keeps a huge workspace from being
loaded at all: `workspace.maxFiles` (20,000) and `workspace.maxTotalBytes` (256 MB) bound what the compile reads,
and the files left out are reported once as a `GS101` warning on the first document, naming the first omitted
file and the limits to raise.

In the front end, `selfheal` keeps a small state file next to the daemon log (the heap of the last start, the
start times of the last ten minutes, the log size at the last start). Before each start it reads what the
previous JVM wrote after that point: an `OutOfMemoryError` there grows the heap by `daemon.heapGrowthFactor`
(1.5) up to `daemon.maxHeap` (`auto`: a quarter of physical memory, at most 4g), and the grown heap is kept
across later starts; a start inside the window waits a jittered exponential backoff (1 s, 2 s, 4 s, capped at
30 s); after `daemon.maxRestartsPerTenMinutes` (3) starts the front end stops starting the daemon and every
request fails with the advice instead of thrashing. A stop or start someone asked for (`daemon stop`, `restart`,
`start`, or the swap to a newer engine after an upgrade) empties the window: only exits nobody asked for count. `daemon status`, `doctor` and `checkup` show the grown heap
and the restart count. The daemon log rotates at `daemon.logMaxBytes` (5 MB, `daemon.logsKept` 3 copies), and
with `daemon.heapDumpOnOutOfMemory` the JVM writes a heap dump under the cache before exiting. `doctor` reports
what has piled up under the cache (rotated logs beyond the kept count, CDS archives untouched for a fortnight,
heap dumps older than a week) and `checkup --fix` removes it.

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
can resolve does, and only the genuinely broken files are partial. The first round's errors are reported, and
so are the errors that this completion pass finds: Groovy stopped the first round at the first failing file, so
an unknown type in another file's method body surfaces only here. Later rounds' errors are not reported, since
they mostly name the classes their own drops removed (an unknown name declared only in a file dropped for a
syntax error is filtered the same way).

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
with a constructor, a call to a method whose overloads of that arity declare one return type, a call to a
closure property whose every return constructs one type, a chained call, a type matched by simple name when
the compiler could not resolve it, an implicit `this` call inside a closure answered by its owner or its
`@DelegatesTo` type, a workspace extension or `@Category` method, an overload picked by argument types).
`DYNAMIC_UNKNOWN`: matched by name only, or several targets remain (overloads the argument types cannot tell
apart, a closure whose owner and delegate both declare the method). Rename refuses when any reference is
`DYNAMIC_UNKNOWN`; impact analysis reports those sites for manual review.

## Lint, type-check and format (Stage 3)

- **Lint.** CodeNarc 4 runs in process on one document at a time (`StringSourceAnalyzer`), so unsaved
  editor text is linted too. Rule sets come from `lint.ruleset` / `lint.jenkinsRuleset` /
  `lint.gradleRuleset` or the bundled profiles, with `lint.rules` applied on top (a rule switched off or given
  another priority); Jenkinsfiles, `vars/` scripts and a shared library's `src/` get the Jenkins profile and
  `*.gradle` build scripts the Gradle profile automatically. Findings are cached by document content hash and
  profile, published as diagnostics (`lint.publish`, down to `lint.publishMaxPriority`; `check` and `review`
  are never filtered), served through pull diagnostics, and exposed as quick fixes where the fix is purely
  textual (unnecessary semicolon, trailing whitespace, extra blank line). Every diagnostic a fix can repair
  carries `data.fixable`, and the `source.fixAll.groovyscope` and `source.organizeImports` code actions apply
  the safe fixes of a whole file.
- **Type-check.** `check --include typecheck` compiles the workspace to `INSTRUCTION_SELECTION` (still no
  bytecode) in a separate unit with a customizer that appends the configured Groovy 6 checkers
  (`NullChecker`, `SqlInjectionChecker`, `PurityChecker` + `ModifiesChecker`) to the `extensions` of every
  existing `@CompileStatic` or `@TypeChecked` annotation. The customizer runs at canonicalization, so an import
  alias (`import groovy.transform.CompileStatic as Static`) and an `@AnnotationCollector` that bundles either
  annotation count too. Dynamic code is never forced into static typing, except by the project's own compiler
  configuration script (`typecheck.configScript`, by default `config/groovyc/compile-static.groovy`), which runs
  as `groovyc --configscript` would, and only in a trusted workspace. A whole-workspace `check` ends with one
  information note, `typecheck-coverage`, naming how many classes stayed dynamic and whether a configuration
  script was left unapplied.
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

`practices/` holds the catalogue (`practices.json`, which Gradle's `processResources` copies from
`python/src/groovyscope/_templates/config/practices/`) and `PracticeFinder`, the entry point. It builds one
`PracticeContext` per document (tree, text, configuration, and whether the document is a test source, Jenkins
code, a Gradle script or a Spock specification) and runs every group on it: `CoreScan` (GS001 to GS012, one walk
in source order that hands each node to `NumberChecks`, `TruthChecks`, `LoggingChecks`, `SecurityChecks`,
`VersionChecks` and `SpockWithCheck`, using the resolver for static types), `TestDoubleFinder` (GS013 to GS016 in
test sources), `NamingFinder` (GS020 to GS027), `SecretFinder` (GS019), `PitfallFinder`, `SqlFinder`,
`JenkinsFinder`, `SpockFinder` and `GradleFinder`. Next to them sit
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
discovers and validates profiles (`profiles.py`, `profile-schema.json`) and hands them to the engine in the
configuration file it starts the engine with (`--config`, written private under the cache).

The daemon is shared by editors and by CLI and MCP calls. Editors report edits; CLI and MCP calls do not,
so every `groovyscope/*` request first compares a fingerprint of the compile files (path, size,
modification time) with the one taken at the last compile and recompiles when it differs.

## Call graph and impact (Stage 4)

The call graph is derived from the index: every method-name, static-call and constructor-call entry is
resolved and becomes an edge from its container to each candidate target with the resolver's confidence.
Impact analysis walks that graph. Direct callers are the static and inferred edges into the declaration;
transitive callers follow static and inferred edges only, up to five levels, and list the `DYNAMIC_UNKNOWN`
edges they meet without following them; direct `DYNAMIC_UNKNOWN` edges are listed separately as sites to
review and never counted as resolved. Overrides are same-name methods with the same erased parameter types in
subclasses and superclasses (a generic parameter matches any type). Tests are callers living under `src/test`
or in classes named `*Spec` / `*Test`; a test reaching the symbol only through a `DYNAMIC_UNKNOWN` edge is a
possible test, listed with that confidence, and it suppresses the "no test" advice. The risk score is a transparent heuristic (8 per direct caller, 2 per transitive, 10 per
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
| `cli/` | the click commands, one module per area registered on one `main` group (`python -m groovyscope.cli` runs it); nothing but wiring and exit codes |
| `cli/common.py` | what every command shares: logging, the configuration handed to the engine, starting and reaching the daemon, engine calls, printed rows and the test seams |
| `cli/workspace.py` | `version`, `doctor`, `checkup`, `config`, `trust`, `init`, the JDK, the classpath export and runtime profiles |
| `cli/kiro.py` | `kiro init`, `attach` and `repair`, and the bundled benchmark corpus |
| `cli/daemon.py` | `lsp`, `mcp`, the daemon's lifecycle and its logs |
| `cli/quality.py` | `check`, `hook`, `review`, `verify`, test results, coverage, `format`, `fix`, `explain`, naming and the contract baseline |
| `cli/graph.py` | the relationship graph, `explain-code`, impact and the call graph |
| `cli/checks.py` | `checks prepare` and `checks report`, the Kiro checks kit |
| `cli/merge.py` | `merge status`, `show`, `resolve` and `review`: the merge conflict tools from a terminal |
| `config.py` | defaults ← `.groovyscope.yaml` ← `GROOVYSCOPE_*`; JSON-schema validation |
| `workspace_init.py` | `groovyscope init`: a starting `.groovyscope.yaml` from the layout and build files, read as text |
| `classpath.py` | `groovyscope classpath`: the Gradle init script or Maven command, parsing, `workspace.classpathFile`, the build fingerprint and the classpath state |
| `trust.py` | the per-user trust record that gates `workspace.trust`, `jdk.home`, rule sets and `classpath` |
| `java.py` | JDK discovery with injected `runner`/`which` for testability |
| `jar.py` | JAR lookup (`GROOVYSCOPE_JAR`, bundled, fetched) and SHA-256 verification |
| `fetch.py` | `groovyscope fetch`: checksum-verified install of a release JAR into the cache |
| `gitignore.py` | keeps the workspace's `.groovyscope/` folder out of git: one entry in the repository's `.gitignore`, never duplicated (an equivalent line or a rule git already applies counts), and never written while a merge or rebase is in progress |
| `conflicts.py` | merge conflict hunks: parsing (with the stray markers a hand edit leaves), suggestions when one side left a hunk unchanged, the choices, the joins of both sides that repeat the lines git moved out of a hunk, and the git state (operation in progress, unmerged files, the base from the index) |
| `mcp_merge.py` | `merge_conflicts`, `resolve_conflicts` (one write per file, parsed by the engine's `groovyscope/parse` first) and `merge_review` (declarations lost, deletions undone and changes dropped, against ours, theirs and the merge base) |
| `provision.py` | opt-in Temurin JDK provisioning through the Adoptium API (`jdk install`, `jdk.autoProvision`) |
| `launch.py` | the JVM command line with non-configurable safety flags and the optional CDS archive |
| `daemon.py` | socket, log and cache locations; probe, start (detached), stop, status |
| `rpc.py` | a small JSON-RPC client over the socket (Content-Length framing) and the demultiplexed channel the MCP server keeps open |
| `mcp_server.py` | the MCP server: one module-level function per tool, bound to the session with `functools.partial`, over one engine session; compact output through `render.py`; reloads the engine configuration in place when it changes; the CLI reuses the same functions |
| `mcp_search.py` | `read_symbol`, `find_code`, `find_duplicates` and `workspace_overview` |
| `paths.py` | workspace paths and file URIs, the guard that refuses a `path` or `paths` outside the workspace before any tool runs, and the helpers the tool modules share (clipping, the call and paging signatures) |
| `tools_catalog.py` | the MCP tool catalogue: which tools exist, what each is for and which change the workspace; Kiro's approval rules, the read only reviewer agent and each tool's MCP annotations (read only, destructive, idempotent, open world) are generated from it |
| `interaction.py` | MCP elicitation (a confirmation before a write) and progress notes from inside a running tool |
| `skills.py` | the Kiro skills shipped as templates, and the same recipes served as MCP prompts; the `groovy-fix` checklist is generated from the practices catalogue and the posting stance is shared by the fix and review skills |
| `edits.py` | LSP text edits applied to documents and written back inside the workspace only, atomically, keeping line endings; `write_if_unchanged` |
| `atomic.py` | whole-file writes through a unique temporary file and a rename, with an optional mode and fsync |
| `process.py` | child processes read as UTF-8 text, with bytes that are not UTF-8 kept as surrogate escapes |
| `hooks.py` | `groovyscope hook`: the after-write check and the opt-in stop gate Kiro runs |
| `verify.py` | the tests a change reaches and the one Gradle command that compiles and runs them |
| `test_results.py` | JUnit XML results from any folder or CI archive |
| `practices.py` | the practice catalogue on the Python side: grouping for `review`, SARIF and short output |
| `diagnostics.py` | debug logging, `groovyscope logs` and the `doctor --bundle` archive for bug reports |
| `selfheal.py` | the restart budget with backoff and the heap that grows once after an out-of-memory exit, up to `daemon.maxHeap` |
| `sweep.py` | what grows under the cache folder and what `doctor` reports and `checkup --fix` removes |
| `checks.py` | the Kiro checks kit behind `checks prepare` and `checks report` |
| `relay.py` | asyncio byte relay between this process's stdio and the socket |
| `kiro.py` | idempotent merge/backup/diff writers for the Kiro files |
| `kiro_agent.py` | agent format detection (V2, universal, out of sync, V3) and format-preserving merges; the servers an agent's `tools` hides |
| `kiro_steer.py` | groovyscope in any Kiro agent: the parts an agent lacks (server, steering, hooks) for `doctor`, and merging only those into it in its own format (`kiro attach`) |
| `checkup.py` | `groovyscope checkup`: the post-install report with the fix per item; `--fix` applies the safe repairs, and `--agent` (the start hook) only reports and adds the `.gitignore` entry |
| `benchmark.py` | the bundled CodeNarc corpus (`_benchmark/`), unpacked into the user's home with a version stamp |
| `changes.py` | changed files and line ranges from git, the pure review rules (split, bucket, touched declarations, verdict) and the merge request comment drafts |
| `reports.py` | SARIF logs as findings and JaCoCo XML as coverage gaps, read as data |
| `diagram.py` | draw.io layout through the engine's `groovyscope/layout` (Eclipse Layout Kernel, layered: crossings minimised by layer sweeps, network simplex placement, orthogonal routing with one track per edge; subclasses share one port under their base); shared helpers used by `diagram.sharedHelperCallers` or more are drawn once and named inside their users' boxes after an arrow; the free layering and width-bounded ones (Coffman-Graham) are tried and the smallest readable paper wins, fewer crossings breaking ties; crossings and overlaps are reported |
| `graph.py` | the fallback layout (when the engine has no layout request) and the draw.io and Mermaid writers: layered layout (cycle break, longest-path layers, barycentre ordering), parents centred over their children, orthogonal routes through the gaps between rows and boxes (one shared port and lane per base class, fixed ports and waypoints in the draw.io file), the smallest paper that keeps the text readable (A4, then A3, then A2; wide rows wrap onto several lines only when even A2 cannot hold them, and past that the A2 page grows), one shared port and lane per caller as well, draw.io XML and Mermaid emitters (calls with a filled arrow from caller to callee, blue when they only read, orange when they change state, grey when matched by name; dashes say how sure the call is; a method's border and the shared-helper names take the same colours), a check for overlapping boxes and lines through boxes |
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

A tool can also talk to the person behind the client while it runs (`interaction`). The SDK runs each tool on an
anyio worker thread and passes it the request context, which the response wrapper binds to that thread (the
`ctx` parameter never appears in a tool's schema). Before `fix`, `format` or `rename` write with `apply`, before
`refresh_classpath` runs the build and before `relationship_graph` writes a draw.io file, the tool asks through
MCP elicitation when the client declared that capability: a one-line question with the files and the lines each
edit changes, answered with a single yes or no. A no or a cancel writes nothing and the answer says so
(`confirmation`, `note`); a client without elicitation, or `mcp.confirmWrites: never`, keeps the dry-run
convention and the client's own tool approval. While a call waits for the engine or the build, a progress note
goes out every `mcp.progressSeconds` (5 s) for clients that asked for progress. Long-running MCP tasks are not
used: the SDK has the types but no server support, and Kiro does not negotiate them; the request budget,
cancellation and progress cover the long build instead.

The classpath export behind `refresh_classpath` and `groovyscope classpath` runs the build tool in its own process
group and stops the whole group (TERM, then KILL) when it is over its timeout, so an abandoned build never keeps
running; a lock next to the classpath file refuses a second export of the same workspace while one runs; and the
MCP tool reads the workspace's trust when it is called, so a `groovyscope trust --revoke` takes effect at once.

The practices catalogue is served as MCP resources: `groovyscope://practices` (the review checklist groups and one
row per GS and RP code) and `groovyscope://practices/{code}` (why, the bad and good form, the fix, the guide
section). Resources cost no context until the user or the agent reads one.

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
`.drawio` extension; the layout is checked for overlapping or off-page boxes and for lines through boxes before
the file is reported, and the answer gives the file's absolute path as `writtenTo`.

`find_dynamic_code` has no LSP counterpart either: `DynamicCodeFinder` walks the index for the
constructs that defeat static analysis (classes with neither `@CompileStatic` nor `@CompileDynamic`, `def`
on public methods, `methodMissing`/`propertyMissing`/`invokeMethod` hooks, `metaClass` writes, `@Grab` on
classes, methods or imports, `Eval`/`GroovyShell`/template engines, and `execute()` on a string, GString,
list, array or untyped receiver, the GDK's process launcher), so an agent can review
them before trusting navigation or impact answers around them.

## Token economy

MCP tools return compact JSON: paths relative to the root, `line:col`, a symbol kind, at most
`output.maxResults` items within `output.maxBytes`, plus a continuation cursor. They never return file bodies.

## CodeNarc version: pinned, canaried, embedded, reported

The engine builds against the CodeNarc release pinned in `gradle/libs.versions.toml`, the only place that names
it; Dependabot proposes each new release as a pull request, so every build of one commit embeds the same rules.
The weekly `codenarc-canary.yml` builds and tests with the latest stable release instead
(`-PcodenarcResolve=true`, read from Maven Central's `maven-metadata.xml`, plain numeric releases only, never
`-groovy-4.0`, `-rc` or `-SNAPSHOT` builds), so a new release is tried before its bump is merged. The build prints
one line, `CodeNarc <version>: <why>`, and expands the version into `engine-version.properties` and the JAR
manifest (`CodeNarc-Version`); `EngineVersion.codenarc()` reads it, `groovyscope/status` returns it, and the
bundle manifest written by `scripts/bundle_jar.py` carries it, so `groovyscope version` and `doctor` name the
CodeNarc the installed engine runs. The CycloneDX SBOM attached to each release records the same version.

| Need | How |
|---|---|
| Another release for one build | `./gradlew build -PcodenarcVersion=<version>` or `GROOVYSCOPE_CODENARC_VERSION=<version>` |
| The latest stable release | `-PcodenarcResolve=true` (what the canary does) |
| Corporate mirror for that lookup | `-PcodenarcMetadataUrl=https://mirror/.../org/codenarc/CodeNarc/maven-metadata.xml` |

The benchmark corpus is a different thing: it is CodeNarc 4.0.0's `src/main` as a fixed subject, and it stays
fixed on purpose so credit benchmark runs remain comparable.
