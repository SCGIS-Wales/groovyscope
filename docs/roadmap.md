# Roadmap and progress tracker

This file is the source of truth for where the build stands. Update it at the end of every stage and at any
natural stopping point. On resume: read this file, then `git log --oneline -20` and `gh pr list --state all`.
Never redo a stage whose box is ticked and whose PR is merged; an unmerged PR means continue on its branch.

Research spec: [research/2026-10-17-groovy-code-intelligence-for-kiro.md](research/2026-10-17-groovy-code-intelligence-for-kiro.md).

Decisions (2026-10-17): repository moves from `api-py/groovyscope` to `SCGIS-Wales/groovyscope` to match the
PyPI trusted publisher (workflow `workflow.yml`); engine in Java 21; Groovy only for Spock tests; JDK 21 locally.

## Stages

- [x] **Stage 0: foundation** — branch `feat/groovyscope-foundation`, PR: SCGIS-Wales/groovyscope#1 (merged 2026-10-17).
  Monorepo scaffold, Java 21 engine skeleton serving the LSP lifecycle (LSP4J 1.0.0), Python package with
  `doctor`, `version`, `config validate`, `kiro init|repair`, `lsp --stdio`, placeholders for `mcp` and
  `daemon`, CI on Ubuntu and macOS, CodeQL, semantic PR titles, release-please, publish workflow with
  trusted publishing and provenance attestations, docs.
  Done when: CI green, release PR for 0.1.0 merged, wheel on PyPI (needs the repository transfer first).
  - [x] Repository transferred to `SCGIS-Wales/groovyscope` and remote re-pointed (2026-10-17)
  - [x] `v0.1.0` published to PyPI through `workflow.yml` (2026-10-17, trusted publishing + attestations)
- [x] **Stage 1: engine compile + index + LSP navigation** — branch `feat/engine-navigation`, PR #10 merged 2026-10-17.
  Delivered on the branch: `config`, `workspace`, `compile` (three-pass strategy, filtered parent class loader,
  trust gate), `index`, `semantics` (confidence per answer), `services`, full LSP wiring (definition,
  typeDefinition, references, hover, documentSymbol, workspaceSymbol, completion, signatureHelp,
  prepareRename/rename, diagnostics, incremental sync), seven fixture projects, security specs, 132 Spock
  specs at 90% line coverage, Python hands the effective config to the engine. Remaining for this stage:
  none; incremental per-file invalidation moves to Stage 2 with the daemon.
  `workspace/`, `compile/` (CompilationUnit to CANONICALIZATION, incremental, Grape/ASTTest off, trust gate),
  `index/`, `semantics/` (STC metadata, UNKNOWN never guessed), real LSP capabilities (definition,
  typeDefinition, references, hover, documentSymbol, workspaceSymbol, completion, signatureHelp,
  prepareRename/rename, compile diagnostics). Fixture projects. Security tests for `@Grab`, `@ASTTest`
  and untrusted global AST transforms. Done when a references query on a fixture returns correct locations
  through Kiro's `code` tool.
- [x] **Stage 2: daemon + Python relay** — branch `feat/daemon-relay`, PR #11 merged 2026-10-17.
  Delivered on the branch: `DaemonServer` (Unix domain socket, one LSP front end per connection, shared
  `WorkspaceRegistry`, idle watchdog, `groovyscope/status`, `groovyscope/shutdownDaemon`), `SymbolStore`
  (SQLite symbol cache keyed by content hash, answers symbol searches before the first compile), engine
  `daemon` command and `--cache-dir`; Python `daemon.py`, `rpc.py`, `relay.py`, `lsp --stdio` relays through
  the daemon with direct-exec fallback, real `daemon start|stop|status`, CDS archive flags, doctor daemon
  line; specs and tests including a real daemon lifecycle integration test. Per-file incremental
  invalidation deferred (full recompile inside the warm JVM is fast enough for now).
- [x] **Stage 3: CodeNarc + formatter + diagnostics** — branch `feat/lint-format`, PR #13 merged 2026-10-17.
  Delivered on the branch: CodeNarc 4 in process with bundled defensive and Jenkins profiles (engine
  resources mirror `config/codenarc`), lint findings as published and pulled diagnostics with quick fixes,
  Groovy 6 checkers appended to `@CompileStatic`/`@TypeChecked` classes via a compilation customizer, the
  bundled `basic` formatter, `groovyscope/check` and `groovyscope/format` RPCs, `groovyscope check` and
  `groovyscope format` commands. Not delivered: the GrEclipse formatter (needs vendored Eclipse bundles;
  moved to Stage 6), the SARIF upload in CI (deferred until the engine lints itself), and the NullChecker
  strict-mode switch (no public configuration found in groovy-typecheckers 6.0.0; it runs in its default
  mode).
  CodeNarc 4 in process with the bundled rulesets (prune unknown rules), Groovy 6 type-checker extensions,
  GrEclipse formatting via spotless-lib returning TextEdits, pull diagnostics, codeAction, `check` and
  `format` CLI commands, SARIF upload in CI.
- [x] **Stage 4: call graph + impact analysis** — branch `feat/callgraph-impact`, PR #15 merged 2026-10-17.
  Delivered on the branch: `CallGraph` and `ImpactAnalyzer` (direct, transitive, overrides, tests, dynamic
  unknowns, risk, suggested checks), LSP call hierarchy and semantic tokens, `groovyscope/impact` and
  `groovyscope/callGraph` requests, `groovyscope impact` and `groovyscope callgraph` commands, a
  performance report spec over a generated workspace (timings printed, loose budgets).
  `callgraph/`, `ImpactAnalyzer` with STATIC | INFERRED | DYNAMIC_UNKNOWN confidence, callHierarchy and
  semanticTokens, `impact` CLI command, performance report on a 2,000-file fixture.
- [ ] **Stage 5: MCP server** — branch `feat/mcp-server` (in progress since 2026-10-17).
  FastMCP server over `rpc.py`: find_symbol, goto_definition, find_references, call_graph, impact_of, check,
  format, find_dynamic_code, workspace_status. Compact paginated JSON through `render.py`.
- [ ] **Stage 6: packaging extras** — branch `feat/packaging-extras`.
  Homebrew tap, `fetch --from` fallback, opt-in Temurin provisioning, optional Maven Central publish.
- [ ] **Stage 7: docs polish, benchmark, upstream watch** — branch `feat/docs-benchmark`.
  Complete docs, credit benchmark on a real repository, monthly check of apache/groovy PR #2936
  (GROOVY-12411); v1.0.0.

## Log

- 2026-10-17: Stage 0 implemented locally (engine build green, 99 Python tests green at 99% coverage, wheel
  built with the JAR bundled, `kiro init` verified in a temp workspace). Kiro CLI 2.32.0 `/code status` reports
  `groovyscope (groovy) initialized (261ms)` against the installed wheel. PR #1 merged with CI green on Ubuntu and macOS.
- 2026-10-17: release-please cannot open the 0.1.0 release PR until the repository setting "Allow GitHub
  Actions to create and approve pull requests" is enabled (Settings > Actions > General > Workflow permissions).
- 2026-10-17: repository transferred from api-py to SCGIS-Wales; the PyPI trusted publisher now matches. Six
  Dependabot PRs (#2-#7) bump pinned actions and the CycloneDX plugin across major versions; review before merging.
- 2026-10-17: v0.1.0 released. Release PR #8 merged, publish workflow green (build, attest, PyPI, release assets).
  Stage 0 complete. Stage 1 started on `feat/engine-navigation`.
- 2026-10-17: Stage 1 merged (PR #10). Stage 2 started on `feat/daemon-relay`.
- 2026-10-17: Stage 2 merged (PR #11). Stage 3 started on `feat/lint-format`.
- 2026-10-17: Stage 3 merged (PR #13). Stage 4 started on `feat/callgraph-impact`.
- 2026-10-17: Stage 4 merged (PR #15). Stage 5 started on `feat/mcp-server`.
