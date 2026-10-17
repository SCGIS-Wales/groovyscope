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

- [ ] **Stage 9: hardening, Corretto, Kiro formats, diagnostics, MCP parity** — branch `feat/hardening`.
  From a reliability, resilience, security and code audit of 0.7.1 (engine and Python), plus the owner's
  requests:
  - Security: user-level workspace trust (`groovyscope trust`); a repository's `.groovyscope.yaml` can no
    longer enable AST transforms, pick the `java` binary, load rule sets or emulation jars by itself; private
    0700 socket folders, 0600 sockets, no unlinking a live daemon's socket; reads and writes confined to the
    workspace; https-only redirects; validated JDK release names; pinned SBOM tool built apart from the
    published files.
  - Resilience: start lock and fail-fast daemon start; restart after an engine upgrade; stale-configuration
    warning and `daemon restart`; MCP reconnect and a lock for parallel tool calls; atomic writes keeping
    line endings and UTF-16 offsets; tolerant file walks that skip excluded folders and oversize files;
    compiler crashes contained; accept loop that survives errors; per-connection document and thread cleanup;
    bounded and per-profile caches.
  - JDK: version and vendor from the `release` file, `.jdk` bundle paths, reported (not silent) fallbacks,
    configured minimum passed to `java_home`, shims and standard folders; Amazon Corretto via
    `jdk install --vendor corretto`; verified on Corretto 21 and 25.
  - Kiro: agent format detection (V2, universal, out of sync, V3, unknown) and format-preserving merges.
  - Diagnostics: `--debug`/`--verbose`/`--log-file`, engine log with request tracing, `groovyscope logs`,
    `doctor --bundle`.
  - MCP: `hover`, `type_definition`, `document_symbols`, `rename`; compact single-copy results, titles and
    annotations.
  Done when: CI green on the PR, local Kiro CLI checks recorded, release published.

- [ ] **Stage 10: dependable assistant for every Kiro action on Groovy code** — code on branch
  `feat/agent-capabilities`, PR #44 merged 2026-10-17; docs, steering and MCP instructions on branch
  `docs/stage-10-agent-capabilities`, PR #46; release PR #45 (0.9.0) open and waiting for #46.
  Plan: `/Users/ssddgreg/.claude/plans/humming-crunching-badger.md` (approved).
  Delivered in #44 (engine 352 specs, Python 246 tests at 96.5%, all green locally on Corretto 21):
  - `groovyscope classpath` (Gradle init script / Maven, trusted workspaces only) writes
    `workspace.classpathFile`; its entries join the engine classpath. `groovyscope init`, `profile install`.
  - GS100: without a classpath, unresolved imports become one "missing dependencies" warning per file.
  - `lint.rules` per-rule overrides, `lint.publishMaxPriority` (default 2), `duplicates.minLines/minStatements`.
  - LSP: implementation, documentHighlight, foldingRange, inlayHint, rangeFormatting, fixable `data`,
    `source.fixAll.groovyscope`, `source.organizeImports`.
  - MCP: `read_symbol`, `find_code`, `find_duplicates`, `workspace_overview`; paged `document_symbols` with
    line ranges; `check`/`review` `codes`/`max_priority`, `byCode`, `fixable`, `missingDependencies`.
  - Kiro: the groovy agent's `tools` is `["*"]` so every MCP server (GitLab etc.) is usable; `doctor` names
    hidden servers, suggests the universal format, stays quiet outside a project; diffs mark a missing newline.
  Delivered on the docs branch (2026-10-17): README (per-project quick start with `init`, `trust`,
  `classpath`; GS100; `init`, `classpath` and `profile install` in the command table; an LSP capability
  table; all 21 MCP tools with the new `check`/`review`/`document_symbols` arguments; the new configuration
  keys), `docs/kiro.md` (agent `tools: ["*"]`, hidden servers, 21 tools, reading-tool examples),
  `docs/install.md` (per-project section, profiles on a second machine), `docs/troubleshooting.md` (GS100,
  `classpath` refusals and build failures, hidden servers), `docs/security.md` (`classpath` runs the build
  tool), `docs/architecture.md` (search package, `MissingDependencies`, `DocumentSupport`, LSP parity, new
  Python modules), `docs/practices.md` and `docs/rules.md` (GS100, `lint.rules`); the steering file and its
  package mirror and `INSTRUCTIONS` in `mcp_server.py` now say overview first, then outline, then
  `read_symbol`, `find_code` instead of grep, `find_duplicates` for duplication.
  Verified on a CodeNarc 4.0.0 checkout (2026-10-17, Ubuntu container, JDK 21, engine built from `main`):
  `check` before any classpath: 1,049 files, 16,935 findings, 519 GS100 warnings, 0 raw unresolved-class
  errors (all folded). `classpath` refused the untrusted checkout with the expected message; after `trust` it
  failed once because CodeNarc's build pins a Java 11 toolchain (now a troubleshooting row), then wrote 23
  entries (every dependency the build declares; 8 unbuilt class folders left out) in 8 s. `check` after:
  16,416 findings, 0 GS100, 0 unresolved. `workspace_overview`: 40 packages, 111,786 lines, 20 untested
  classes. `find_duplicates` on `org/codenarc/report`: 2 groups, an 11-line exact copy of
  `buildPackageAttributeMap` in `JsonReportWriter` and `XmlReportWriter`, and a 17-line similar block in
  `HtmlReportWriter` and `SortableHtmlReportWriter`. `find_code catch type=Exception rethrows=false`: 1 match,
  `AbstractReportWriter.initializeDefaultResourceBundle`, where `catch (MissingResourceException)` has no
  variable name, so Groovy declares a variable of that name typed `Exception` (a real finding; the one Java
  `catch (Exception e)` is out of scope by design). `read_symbol XmlReportWriter.writeReport`: lines 33 to 50.
  Left to do, in order:
  1. Kiro CLI free commands with a throwaway `KIRO_HOME`: `/code status`, `/mcp` (21 tools), `/tools` under
     `--agent groovy` on `--agent-engine v2` and `v3` lists another server's tools (already verified once).
  2. Two budgeted prompts (~2 credits; 12.2 of 50 used this month): "find duplicated code in
     src/main/groovy/org/codenarc/report" (expects `find_duplicates`) and "list catch blocks that catch
     Exception and do not rethrow" (expects `find_code`).
  3. Merge PR #46 only when every check is `pass` (Ubuntu, macOS, CodeQL, title; check each by name);
     release-please then refreshes PR #45. Approve its runs
     (`gh api -X POST repos/SCGIS-Wales/groovyscope/actions/runs/<id>/approve`), same gate, merge, verify the
     0.9.0 publish and the tap.
  4. Ask the owner before enabling branch protection on `main` with those required checks.
  5. After 0.9.0: `groovyscope kiro repair --global` on each machine (adds `tools: ["*"]`), and
     `groovyscope profile install` to bring the private profile to the second machine.

- [ ] **Stage 11: proactive agent, readable findings, MR reviewer, understanding and naming** — branch
  `feat/proactive-agent` (0.10.0), then `feat/understanding-naming` (0.11.0). Approved 2026-10-17 from the
  owner's test of 0.9.0 on Kiro CLI 2.29.1: the setup after every install is tedious, findings such as
  `catch (Exception ignored)` do not say whether they are defects, and the agent should act as the merge
  request reviewer and quality improver.
  0.10.0 (branch `feat/proactive-agent`, 2026-10-17; Python 284 tests at 96%, engine specs green):
  - A. Benchmark corpus: CodeNarc 4.0.0 `src/main` bundled in the wheel (Apache 2.0, NOTICE kept) and unpacked
    to `~/.groovyscope/benchmark/codenarc/` by `kiro init|repair --global` and by the agent start hook; no
    command to run (`groovyscope benchmark path [DIR]` only prints or moves it).
  - B. Zero post-install steps: `groovyscope checkup` (Kiro files older than the package via a version
    stamp, project LSP entry, trust, classpath and GS100 count, hidden servers, agent format, daemon) with
    exact fixes; the agent's `hooks.agentSpawn` runs `checkup --agent`, applies the safe repairs itself (Kiro
    files, corpus) and injects the rest; steering opens with a status line, offers `trust` and `classpath`,
    and shows the quality menu; the welcome message lists it. Never fails the agent start.
  - C. Catch findings that explain themselves: `handling` (swallowed, logged, returns-default, rethrows),
    the variable name shown apart, the matching code so `explain` applies; steering requires defect, gap or
    by design per group with the fix.
  - D. MR reviewer: `review_changes(base)` and `groovyscope review --changed`: changed files and hunks from
    git, review, impact of changed public declarations, risky sites inside the changed lines, grouped
    blocking, should fix, by design, with a verdict.
  - E. CI parity: `check --import-sarif` merges SpotBugs findings; `coverage_gaps` reads the JaCoCo XML;
    `docs/ci.md` with the GitLab jobs (SARIF as `sast`, `review --changed` as the MR gate).
  - F. `docs/prompts.md` cookbook (twelve prompts: tools called, how to read the answer, defect or not, the
    fix, cost) and glossary; `/context show` to confirm steering; README, kiro.md and steering updates.
  Delivered as planned, with these decisions: the corpus is a 740 KB deterministic zip of `src/main` only
  (`scripts/bundle_benchmark.py`), unpacked to `~/.groovyscope/benchmark/codenarc/`; the steering file is
  stamped `written by groovyscope <version>` so `checkup` detects files from an older release; the start hook
  is `hooks.agentSpawn` with a 20 s budget and never fails the session; `checkup --agent` runs no `kiro-cli`
  subprocess; SARIF files are read only from `reports.sarif`, `--import-sarif` and the build's default
  SpotBugs output; the 23rd tool is `coverage_gaps`. Left for the owner's machine: the hook output on a
  Kiro 2.29.1 start, one `review_changes` prompt on a real branch, one `coverage_gaps` prompt after
  `./gradlew test jacocoTestReport`.
  0.11.0:
  - G. `explain_code`: what a class, method or file does from its AST and call graph (responsibilities,
    inputs and outputs, calls and callers, mutated state, exceptions, external calls); nothing guessed from
    names.
  - H. `relationship_graph` (classes, inheritance, composition, calls, data flow, confidence per edge) and
    `groovyscope graph --format drawio|mermaid|json`: a layered layout sized to an A4 page, calm palette,
    no overlapping text or blocks.
  - I. Naming: GS020 to GS026 (booleans without is/has/can/should, getters that mutate, verbs and nouns
    swapped, generic names, abbreviations, type prefixes, names contradicting the type, inconsistent
    vocabulary) plus `check_naming` data for the agent's semantic judgement; `naming.vocabulary` in the
    configuration; renames only through `rename`: dry run first, static references only, recompile after
    the write and roll back on new errors.
  - J. Every new tool optimised for MCP and Kiro CLI (compact, paged, no file bodies), exceptions
    contained with a message that names the cause, and the LSP, MCP and CLI traffic through the daemon
    covered by tests.
  Done when: CI green on each PR, the owner's Kiro 2.29.1 checks recorded (hook output on start, one
  `review_changes`, one `explain_code`, one draw.io export opened, one naming pass), releases published.
  Progress 2026-10-17: both branches merged (#47, #49). The second delivered `groovyscope/explainCode` and
  `groovyscope/graph` in the engine (CodeFacts, RelationshipGraph), `explain_code`, `relationship_graph`
  (draw.io A4 layout checked for overlaps, Mermaid) and `check_naming` (GS020 to GS027, NamingFinder) in the
  MCP server with the `explain-code`, `graph` and `naming` commands; `rename` recompiles the changed files
  and rolls back on new errors. Verified on the CodeNarc corpus: `AbstractRule` explained, a 40-class graph
  on one landscape A4 page with no overlapping boxes, 29 naming findings in `rule/basic` after calibrating
  GS022 so that role nouns (`...Rule`, `...Visitor`) are not verb-first names. Because 0.10.0 was not tagged
  before #49 merged, release-please folds both into one release, **0.10.0** (PR #48); there is no 0.11.0.
  Continuation (for a new session, from `main`): the only open items are the release PR #48, whose
  workflow runs the owner approves in the Actions tab before merging it, the 0.10.0 publish to PyPI and the
  tap to verify afterwards, and the owner's Kiro 2.29.1 checks to record in the log below. Prompt:
  "continue Stage 11 from docs/roadmap.md: verify the 0.10.0 release and record my Kiro checks".

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
- 2026-10-17: Stage 10 code merged (PR #44); release-please opened PR #45 (0.9.0). Docs, steering and MCP
  instructions written on `docs/stage-10-agent-capabilities` (PR #46); `classpath`, GS100, `find_duplicates`,
  `find_code` and `read_symbol` verified on a CodeNarc 4.0.0 checkout (numbers in the stage entry). Kiro CLI
  checks and the two budgeted prompts still need the owner's machine.
- 2026-10-17: 0.9.0 released (PR #45 merged by the owner). Owner's test on Kiro CLI 2.29.1 with Corretto 25:
  `kiro repair --global` added `tools: ["*"]`, `/mcp` showed groovyscope with 21 tools next to six other
  servers, `/tools` listed every groovyscope tool as trusted, `classpath` wrote 213 entries in 19 s, and the
  catch prompt called `find_code` with the right arguments on the first try. Stage 11 approved from that
  feedback; started on `feat/proactive-agent`.
- 2026-10-17: Stage 11 code merged: PR #47 (checkup with auto-repair at agent start, bundled CodeNarc corpus,
  catch findings that explain themselves, `review_changes`, SARIF and JaCoCo imports, prompts cookbook) and
  PR #49 (`explain_code`, `relationship_graph` with draw.io and Mermaid output, `check_naming` GS020 to
  GS027, verified `rename`). 26 MCP tools. Release-please PR #48 carries both as 0.10.0 and waits for the
  owner to approve its workflow runs.
