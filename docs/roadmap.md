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
- [x] **Stage 5: MCP server** — branch `feat/mcp-server`, PR #18 merged 2026-10-17.
  `mcp_server.py` on the official `mcp` SDK (`MCPServer`, stdio) over a persistent `rpc.py` session to the
  daemon: find_symbol, goto_definition, find_references, call_graph, impact_of, check, format,
  find_dynamic_code, workspace_status. Compact paginated JSON through `render.py`. Engine gains
  `groovyscope/symbols`, `/definition`, `/references` and `/dynamicCode` plus `DynamicCodeFinder`.
- [x] **Stage 6: packaging extras** — branch `feat/packaging-extras`, PR #21 merged 2026-10-17.
  `groovyscope fetch [--from]` (checksum-verified release JAR into the cache, used when nothing is bundled),
  `groovyscope jdk install` and `jdk.autoProvision` (Temurin via the Adoptium API, verified, found by
  discovery), `scripts/homebrew_formula.py`, rendered and tested by a daily workflow in
  `SCGIS-Wales/homebrew-tap` that commits each new release (no cross-repository token). Decisions: the GrEclipse formatter stays
  out of the JAR (Spotless resolves Eclipse bundles from p2 at run time, which breaks "no runtime
  downloads"; vendoring them would double the wheel), so `format.engine: basic` remains the shipped
  formatter; Maven Central publishing of the engine JAR is skipped (no Central Portal account; the JAR is
  attached to every GitHub Release with provenance).
- [x] **Stage 7: docs polish, benchmark, upstream watch** — branch `feat/docs-benchmark` (2026-10-17).
  Docs brought up to date with everything shipped (README status, rules, architecture, AGENTS, install);
  `docs/benchmark.md` fixes the credit benchmark protocol (five tasks, two conditions, what to record,
  answer key first). First run recorded on 2026-10-17 on CodeNarc 4.0.0 (10.73 credits, one pass); see
  `docs/benchmark.md`. Upstream watch recorded below.

- [x] **Stage 8: practices knowledge base and runtime profiles** — branch `feat/groovy-practices`, PR #32 merged 2026-10-17, released as 0.7.0.
  Practice catalogue and `docs/practices.md`; GS001-GS012 with AST fixes; CodeNarc profile aligned with the
  knowledge base; strict NullChecker plus Regex, FormatString and Combiner checkers gated on Groovy 6;
  `fix`, `explain`, `review` (CLI and MCP); `check --format short|sarif`; type checking that survives broken
  files; diagnostics published only when they change; joint Groovy/Java compiles. Runtime profiles
  (`docs/profiles.md`): RP codes, descriptor and Terraform checks, contract baseline, parameter navigation,
  isolated emulation compile, `profile` and `baseline` commands, `profile_status` MCP tool. `kiro init
  --global` merges into `~/.kiro`. The daemon re-reads files changed on disk before every CLI or MCP request.
  Done when: CI green on the PR, local Kiro CLI checks recorded, release published.

## Upstream watch: Apache Groovy's own language server (GROOVY-12411)

Check monthly (`gh api repos/apache/groovy/pulls/2936` and the Groovy release notes) and log the result here.

- 2026-10-17: apache/groovy#2936 was **merged on 2026-10-11** into `master` as `subprojects/groovy-lsp`
  (87 source files, a `groovylsp` launcher in the distribution's `bin/`). Maven Central has no
  `org.apache.groovy:groovy-lsp` artifact and the latest tag is `GROOVY_6_0_0`, so it is unreleased. When a
  Groovy release ships it: evaluate it as the LSP front end for Kiro's `code` tool (a new stage), keep the
  MCP tools on this engine (index, call graph, CodeNarc, dynamic-code review are not LSP features), and
  compare navigation answers on the fixture projects before switching.

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
- 2026-10-17: v0.5.0 released after PR #17 fixed a lost-update race in `LanguageServices.refresh()` that
  failed the release PR's macOS run. Stage 5 implemented locally: engine build green, 144 Python tests at
  98% coverage including an end-to-end `groovyscope mcp` test against the real daemon; Kiro CLI 2.32.0
  reports `groovyscope (groovy) initialized`, `/mcp` shows `groovyscope ● running 9 tools`, no error logs.
- 2026-10-17: Stage 5 merged (PR #18). Stage 6 started on `feat/packaging-extras`.
- 2026-10-17: Stage 6 implemented locally: 163 Python tests at 97% coverage; `groovyscope fetch` pulled the
  v0.5.0 JAR from the GitHub Release and verified it against SHA256SUMS.txt; `groovyscope jdk install`
  downloaded Temurin 21.0.12.1 from Adoptium, verified it, and `doctor` reported it as provisioned; the
  formula for 0.5.0 was merged into SCGIS-Wales/homebrew-tap (PR #1 there) and
  `brew install SCGIS-Wales/tap/groovyscope` plus `brew test` pass on macOS. Automatic tap updates need the
  `HOMEBREW_TAP_TOKEN` repository secret.
- 2026-10-17: PyPI had silently stopped receiving releases after 0.2.0: `release-please.yml` called
  `workflow.yml` as a reusable workflow, so the Sigstore certificate named `release-please.yml` and PyPI's
  trusted publisher rejected the attested upload. PR #20 makes release-please dispatch `workflow.yml`
  instead; 0.3.0, 0.4.0 and 0.5.0 were published by manual dispatch the same day.
- 2026-10-17: Stage 6 merged (PR #21). Stage 7 on `feat/docs-benchmark`: docs polish, benchmark protocol,
  upstream watch entry (groovy-lsp merged upstream, unreleased).
- 2026-10-17: Stage 7 merged (PR #22). v0.6.0 released: the release-please run dispatched `workflow.yml`
  and every job passed (build, attestations, PyPI, release assets, Homebrew tap job, which skipped with a
  notice because `HOMEBREW_TAP_TOKEN` is not set). Roadmap complete except the benchmark run.
- 2026-10-17: Dependabot PRs #2-#7 merged (CycloneDX 3 report path fixed in the publish workflow). The Homebrew
  tap now updates itself from PyPI (SCGIS-Wales/homebrew-tap#3). Benchmark preparation on CodeNarc found and
  fixed: Java sources ignored and compile-retry cascades (#25), Kiro 2.x ignoring the groovy agent (#26),
  `execute()` false positives (#27), the MCP session receiving every diagnostic (#28). Releases 0.6.1 and
  0.6.2. First credit benchmark run recorded.
- 2026-10-17: Stage 8 verified locally with Kiro CLI 2.32.0. Free commands against a throwaway `KIRO_HOME`
  that already had another MCP server: `kiro init --global` plus project `kiro init` gave
  `groovyscope (groovy) initialized (548ms)`, `/mcp` listed both servers with groovyscope running 13 tools, and
  `/tools` showed them allowed. Kiro reads language servers only from the project's `.kiro/`, hence the
  per-project step. Three budgeted prompts cost 1.47 credits (10.73 before): "why is line 71 flagged"
  called `check` and `explain` and gave the GS005 fix (0.21); "review XmlReportUtil and apply safe fixes"
  called `review`, `fix` and `profile_status`, added `@CompileStatic` and left the file compiling (1.07); a
  runtime-profile question called `profile_status` and answered that `Set.of` is unproven on the target
  Groovy (0.19). Follow-ups fixed the same day: the type fix now handles `final x = ...`, duplicate edits
  from two codes on one spot apply once, and `profile_status` lists each construct's status.
- 2026-10-17: Stage 8 merged (PR #32, CI green on Ubuntu and macOS). v0.7.0 released through the dispatched
  `workflow.yml` (build, attestations, release assets, PyPI all green).
