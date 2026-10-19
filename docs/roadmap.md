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

- [ ] **Stage 12: live configuration, classpath state and reliability** — branch `feat/live-config`,
  2026-10-17, from the owner's concern that the classpath the engine resolves against goes stale while the
  agent changes code, and that nobody could tell. Done in this stage:
  - A. The engine takes a new configuration in place (`groovyscope/reloadConfig`): the workspace model swaps
    it, the services drop their caches, the next query recompiles, connections stay open; `status` reports the
    live `configHash`. The CLI sends it whenever the daemon's hash differs, the MCP server before any request
    once `.groovyscope.yaml` or the classpath file changed on disk. `groovyscope classpath` reloads instead of
    stopping the daemon. The disk fingerprint covers the classpath entries, so a rebuilt jar marks the
    snapshot stale.
  - B. Classpath state: the export records a fingerprint of every build file (root and nested); `checkup`,
    `check`, `review_changes` and `workspace_status` report `fresh`, `stale`, `missing`, `configured` or
    `none` with the fix; `refresh_classpath` (27th MCP tool) runs the export for the agent in a trusted
    workspace. Steering and prompts say what `stale` means before unresolved classes are judged.
  - C. Reliability fixes from two independent reviews: a request timeout is reported instead of re-sent; a
    framing error reconnects; `rename` restores every file when a write or the verification fails; draw.io
    output only to `.drawio` files, atomically; SARIF logs indexed once and malformed ones never crash a tool;
    a universal Kiro agent file is never downgraded without kiro-cli; unique temporary names for atomic
    writes; a daemon that never answered is killed; the LSP relay ends instead of starting a second engine
    when the daemon dies mid-session. Engine: Groovy compiler bugs (`GroovyBugError`) are contained like
    other crashes and logged on the diagnostics thread; the resolver cache is thread safe; the type check runs
    once per snapshot; cascading retries stop early; per-file parse loaders are closed; open Java documents
    stay in the compile and oversized open documents are skipped; the idle exit waits for a connection
    accepted meanwhile; a reload in stdio mode reaches the registry.
  - D. Merge request review for GitLab: `review_changes` drafts one inline comment per finding (path, line,
    body written to the author: the observation, why it matters, a concrete suggestion) and a summary note;
    the steering tells the agent to post them through a GitLab MCP server after a yes, never to approve or
    merge, and to show a table when no such server is present.
  Done when: CI green, merged, released; the owner's Kiro check of one `stale` classpath round trip and one
  review posted on a merge request recorded here.
  Progress 2026-10-18: PR #51 merged with CI green; release-please PR #52 merged by the owner and the
  dispatched `workflow.yml` run 16 passed every job (build artefacts, attest provenance, PyPI trusted
  publishing, release assets). **0.11.0** verified from a cloud session: the GitHub Release carries the wheel,
  the sdist, the engine JAR, both SBOMs and `SHA256SUMS.txt`, whose digests match the files PyPI serves; the
  wheel installed from PyPI into a fresh venv reports `groovyscope 0.11.0` and `engine 0.11.0`, and `doctor`
  finds the bundled JAR with its checksum verified. The `stale` round trip was exercised from that wheel on a
  copy of the `gradle-multi` fixture (trusted, Gradle 8.14.3, JDK 21): `checkup` warned `no classpath yet`,
  `groovyscope classpath` wrote 2 entries with the build fingerprint and `checkup` reported `fresh`; a new
  dependency in `build.gradle` turned `checkup` and the MCP `check` answer to `stale` with the fix named;
  `groovyscope classpath` (and, in a second pass, the `refresh_classpath` tool, 1.3 s) reported that the
  running daemon reloaded the new classpath, `workspace_status` reported `fresh` with 8 entries, and the
  engine process kept its pid while `configHash` changed twice (the re-exported classpath, then an edited
  `.groovyscope.yaml`; an invalid key was refused before reaching the daemon). The MCP session listed 27
  tools. Still open: the owner's Kiro checks (the `stale` round trip inside Kiro CLI and one review posted on
  a merge request), to be recorded in the log below when supplied.

- [ ] **Stage 13: Kiro 3.0 readiness** — branch `feat/kiro3-readiness`, 2026-10-18, from the gap analysis
  [research/2026-10-18-kiro-gap-analysis.md](research/2026-10-18-kiro-gap-analysis.md) (approved 2026-10-18).
  Kiro CLI 3.0 makes the V3 engine the default at the end of October 2026. In this stage: the V3 permission
  rules written for the agent allow only the read-only tools, so `fix`, `rename`, `format` with `apply`,
  `refresh_classpath` and `relationship_graph` with `drawio_path` ask (Kiro ignores `readOnlyHint`); `checkup`
  and `doctor` warn when the Kiro CLI is 3.x and the agent file is Classic only; `docs/kiro.md` gains a Kiro 3
  section with the verification checklist and loses the "twenty-six tools" slip; the steering says what to do
  when the `code` tool cannot open Groovy files. Owner's checks on Kiro 2.31 and on 3.0: the `code` tool
  answers definition, references, hover and diagnostics on `.groovy`, `.gradle` and `Jenkinsfile` (issue
  #6322, extension gate); the universal agent's `hooks.agentSpawn` runs under V3 (or needs `SessionStart`);
  `/tools` token cost of the 27 schemas and `/context show` cost of the steering file.
  Done when: CI green, merged, released; the owner's four checks recorded here with the numbers.
- [ ] **Stage 14: edit loop performance** — not started. From the benchmark: edits on the CodeNarc corpus took
  12 to 15 minutes with groovyscope against under 2 without, because each write recompiles 1,064 files and
  republishes diagnostics. Planned: a quiet window that coalesces bursts of writes and recompiles lazily
  (on the next request or when the window closes); push diagnostics off for agent sessions (Kiro pulls them);
  incremental recompile of the changed files plus their dependants from the import and call graph, the rest
  of the snapshot kept; the type check verified to survive one file with unresolved imports; a timing spec on
  the generated workspace. Done when: benchmark tasks 3 and 4 repeated on the corpus under 2 minutes each
  (about 5 credits, approved), CI green, merged, released.
  Progress 2026-10-18 on `feat/edit-loop-performance`: measured first. On the bundled CodeNarc corpus (529
  Groovy files, no classpath) through the daemon, a single-file `check` after an edit took 6.1 s (recompile
  3.6 s, separate type-check compile 4.2 s) and a whole-workspace `check` after an edit 4.3 s, so the
  recompile was never the minutes the benchmark saw. The generated 300-file workspace found the multiplier:
  a push after an edit cost 18.7 s when it covered every document (practice and lint passes for all of them)
  against 0.34 s for the one open document, and a pull for one document computed every document too.
  Delivered: `diagnostics.push` (`open`, the default: the documents the connection has open; `workspace`;
  `none`) and `diagnostics.debounceMillis`; a pull computes its one document; the type-check compile runs
  only when a requested file mentions `@CompileStatic` or `@TypeChecked` (the checker and the Groovy 6
  extensions apply nowhere else); debug timings for pushes and type checks; the performance spec measures the
  open-document push against the full one. After: single-file `check` after an edit 3.8 s on the corpus, the
  open-document push 0.33 s on 300 files. Not done, by measurement: incremental recompile of changed files
  plus dependants stays future work (3.6 s per full recompile on the corpus is not the bottleneck); the
  quiet window exists as a setting rather than a new scheduler, because the next request already compiles
  lazily. Left for the owner: benchmark tasks 3 and 4 repeated with Kiro (about 5 credits) to close the stage.
- [ ] **Stage 15: Kiro native integration** — not started. Planned: a `PostToolUse` hook on `write` that runs
  `groovyscope check` on the written file and returns new errors to the model in the same turn; an opt-in
  `Stop` hook that refuses to end the turn while changed files do not compile; skills generated from the
  prompts cookbook (`.kiro/skills/<name>/SKILL.md`, each a slash command, body loaded on demand); the
  steering trimmed and switched to `inclusion: fileMatch` for Groovy, Gradle and Jenkinsfile; the same recipes
  as MCP prompts; `groovyscope kiro init --power` packaging steering, `mcp.json` and hooks as a Kiro power;
  a `groovy-reviewer` subagent for merge request review; a headless CI recipe in `docs/ci.md`
  (`kiro-cli chat --no-interactive --require-mcp-startup --output-format stream-json`). Done when: CI green,
  merged, released; the owner's Kiro checks of both hooks and one skill run recorded here.
  Progress 2026-10-18, first branch `feat/kiro-native` (hooks, reviewer, CI recipe): `groovyscope hook` reads
  Kiro's hook JSON from stdin; on `PostToolUse` for a `write` it checks the Groovy files the write touched
  (`hooks.afterWriteInclude`, compile, lint and practices by default; the type check stays out because it
  compiles the whole workspace) and prints a `groovyscope checked <file>` block, errors first, nothing for a
  clean file; on `Stop`, with `hooks.stopGate: true`, it compiles the Groovy files changed in the working tree
  and answers `{"decision": "block", "reason": ...}` while any has compile errors. The engine request is
  bounded by `hooks.timeoutSeconds` and the command never fails the session. The agent template gains
  `hooks.postToolUse` (matcher `write`) and `hooks.stop`; `agents/groovy-reviewer.json` is a read-only
  reviewer agent for the `subagent` tool and unattended runs; `docs/ci.md` gets the headless merge request
  review job; the steering explains the after-write block. Smoke-tested on the corpus through the daemon: a
  broken write produced the block with the compile error, the gate blocked the turn with the error and the
  reason. Next branch: skills generated from the cookbook, the same recipes as MCP prompts, the steering
  trimmed (`inclusion: fileMatch` once the owner's Kiro 3 check says custom agents honour it), the
  `kiro init --power` packaging once the plugin schema is confirmed on a Kiro install.
  Second branch `feat/kiro-skills` (2026-10-18): twelve skills under `.kiro/skills/groovy-*/SKILL.md`, one
  per cookbook recipe (review, fix, quality, duplicates, catch-audit, dynamic, coverage, impact, rename,
  explain, naming, profile), each with the tool sequence, how to read the answer and what to report, written
  by `kiro init` in both scopes and refreshed by `repair`; the same twelve served as MCP prompts (`prompts/list`,
  one optional `arguments` string, `$ARGUMENTS` in the body); the steering cut from 137 to 55 lines, keeping
  the session rules, the after-write block, navigation and the every-change rules, and pointing at the
  skills for the recipes. Not done: `inclusion: fileMatch` (custom agents load steering through `resources`,
  which ignores the front matter, so the switch waits for the Kiro 3 check) and the power packaging (plugin
  schema unconfirmed). Left for the owner: one skill run (`/groovy-impact <symbol>`) and whether the custom
  agent lists the skills without a `skill://` resource entry.
- [ ] **Stage 16: tool surface and context cost** — branch `feat/tool-surface`, 2026-10-18, merged as PR #74,
  waiting for the owner's `/tools` and `/context show` numbers; started at the owner's "proceed with any open stages" ahead of the Kiro numbers, measured
  on the MCP SDK's own `tools/list` output instead. Planned: shorter
  tool descriptions (the explanations move to the skills), a combined `symbol_context` tool (definition,
  hover and a reference count in one call), `disabledTools` guidance for teams, the `/tools` and
  `/context show` numbers before and after. Done when: both numbers lower, CI green, merged, released.

- [x] **Stage 17: engine memory and out-of-memory resilience** — branch `feat/engine-memory`, 2026-10-18,
  merged as PR #65 (see the log); from
  the reliability review [research/2026-10-18-reliability-and-kiro-features.md](research/2026-10-18-reliability-and-kiro-features.md)
  (findings E1, E2, E4, E6) and the owner's priority: the JVM must not wedge or grow without bound. Planned:
  the kept compile's class loader owned by the result and closed when the next snapshot is published; a file
  that stops parsing keeps its last good text re-parsed into the current generation instead of a tree that
  pins the previous one; the call graph dropped on every recompile; a phase-aware cascade guard with a
  wall-clock budget so a syntax error in a base class costs one or two rounds, not 64, for the compile and the
  type check; one type check per `fix` call; `-XX:+ExitOnOutOfMemoryError` so an out-of-memory daemon exits and
  is respawned, with `daemon status`, `doctor` and `checkup` naming the cause and the `daemon.heap` fix from the
  log; `groovyscope/status` reporting heap use so `doctor` warns before the limit. Done when: specs for each
  change (a base class with a syntax error compiles in bounded rounds, the previous loader is closed, memory is
  reported), CI green, merged, released.
- [x] **Stage 18: asynchronous guardrails** — branch `feat/async-guardrails`, 2026-10-18, merged as PR #70,
  released in 0.16.0; from the approved plan
  ([research/2026-10-18-reliability-and-kiro-features.md](research/2026-10-18-reliability-and-kiro-features.md),
  section 5). Engine: cooperative cancellation end to end (G1), bounded two-lane request executors with a busy
  answer (G2), single-flight compile with cancellable waiters (G3), containment and eviction (G7). Front end:
  demultiplexed engine session with per-call futures (G8), timeout and cancel propagation with single flight
  (G9), an in-flight limit (G10), relay and probe fixes (G12). Done when: the specs and tests named in section
  5.3 pass, CI green, merged, released.
- [x] **Stage 19: out-of-memory self-healing** — branch `feat/oom-self-healing`, 2026-10-18, merged as PR #71,
  released in 0.16.0. Engine: memory pressure guard that sheds caches
  and refuses whole-workspace work (G4), workspace size budget (G5), opt-in heap dump (G6). Front end:
  crash-loop guard with backoff and heap escalation up to `daemon.maxHeap` recorded in a state file (G11),
  bounded logs and a cache sweep (G13), stop gate and write fixes (G14). Done when: the specs and tests named
  in section 5.3 pass, CI green, merged, released.
- [ ] **Stage 20: elicitation and progress** — branch `feat/elicitation-progress`, 2026-10-18, merged as PR #77,
  waiting for the owner's Kiro check; started at the owner's "proceed with any open stages" ahead of the Kiro 3 check. `rename`, `fix`,
  `format` with `apply`, `refresh_classpath` and draw.io writes ask the user through MCP elicitation when the
  client supports it; progress on `refresh_classpath` and the cold first request; `refresh_classpath` as a
  long-running task where negotiated; the practices catalogue as a knowledge base resource. Done when: CI
  green, merged, released; the owner's Kiro check of one elicitation prompt recorded here.
- [ ] **Stage 21: fixes from the owner's first Kiro check** — 2026-10-18, approved by the owner. Three PRs,
  merged as #79 (A), #80 (B) and the `feat/kiro-checks-kit` PR (C), released together; waiting for the owner's
  retest. A (engine): an unknown type in one file is reported while another file has an
  unresolved import (Groovy stopped the first round at the first failing file and the errors of the completion
  pass were dropped); the `def` fix writes the import of a type from another package and counts applied and left
  fixes per code; a folder seeds the relationship graph; a `def` local initialised by a method with a declared
  return type or by a factory closure is inferred, not dynamic; document symbols start on the line of the name.
  B (front end): real changed-line counts, a draw.io layout with lines that never cross boxes, a hook that starts
  in well under a second, no fixed tool count in the welcome text, prompt syntax per Kiro engine, the client's
  capabilities recorded, a plain verdict on implicit catches, absolute paths, a short `kiro repair` summary.
  C (test kit): `groovyscope checks prepare` (the workspace for every check, with a `change` branch to review,
  built without pasting shell) and `checks report` (versions, doctor lines, hook start-up time, the result
  table); `kiro-checks.md` rewritten for the terminal UI, with a reworded hook prompt and a terminal fallback.
  Done when: the three PRs merged, released, and the owner's retest of the failed checks recorded here.
- [x] **Process: merge and release automation** — PRs merge without a human once CI is green (auto-merge
  after the owner enables it in the repository settings; by API until then); `release-please.yml` takes the
  `RELEASE_PLEASE_TOKEN` secret so the release PR's checks run unattended and it marks itself for auto-merge;
  the session that cut a release verifies the publish (tag, workflow, PyPI, tap). Recorded in `AGENTS.md`.
  Still the owner's: add the secret and tick "Allow auto-merge".

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
- 2026-10-17: Stage 12 implemented on `feat/live-config` (engine specs green, Python 311 tests at 96%): live
  configuration reload, classpath state and fingerprint, `refresh_classpath`, the reliability fixes from the
  two reviews, merge request comment drafts for GitLab.
- 2026-10-18: 0.10.0 (PR #48, `workflow.yml` run 15 on 2026-10-17) and 0.11.0 (PR #52, run 16) published
  through the dispatched `workflow.yml`, every job green, both on PyPI. 0.11.0 verified from PyPI: install,
  `version`, `doctor`, checksums against the release assets, the `stale` classpath round trip and the live
  reload through the CLI and the MCP tools (details in the Stage 12 entry). The Homebrew formula in
  SCGIS-Wales/homebrew-tap still rendered 0.7.0 at 05:30 UTC, so the daily `groovyscope formula` workflow had
  not yet picked up 0.8.0 to 0.11.0; check it after its next run or dispatch it by hand. The Kiro checks for
  Stage 11 and Stage 12 still need the owner's machine.
- 2026-10-18: CodeNarc is no longer pinned (owner's request). `engine/settings.gradle.kts` resolves the latest
  stable release from Maven Central at build time through a Gradle `ValueSource` (floor and offline fallback:
  the version catalogue; pin with `-PcodenarcVersion`; mirror with `-PcodenarcMetadataUrl`), embeds it in
  `engine-version.properties` and the JAR manifest, `status` returns `codenarc`, the bundle manifest carries
  it and `groovyscope version` and `doctor` print it. Verified locally: 376 specs green, 318 Python tests at
  95% (one hypothesis flake in `test_deep_merge_identity`, green on re-run, unrelated), the built JAR reports
  `codenarc=4.0.0` (the latest stable release on 2026-10-18). The benchmark corpus stays at 4.0.0 by design.
- 2026-10-18: Kiro CLI gap analysis written and approved by the owner (`docs/research/2026-10-18-kiro-gap-analysis.md`):
  the edit loop latency, Kiro 3.0 readiness, unused Kiro features (hooks, skills, powers, prompts) and the
  context cost of 27 schemas plus always-on steering are the gaps; stages 13 to 16 added above. Stage 13
  started on `feat/kiro3-readiness`.
- 2026-10-18: Stage 14 implemented on `feat/edit-loop-performance` (381 specs, 322 Python tests at 95%): pushed
  diagnostics scoped to the open documents, per-document pull, the type-check compile skipped where it cannot
  report; measured 18.7 s to 0.33 s per push on the generated workspace and 6.1 s to 3.8 s per single-file
  check after an edit on the corpus. The benchmark re-run with Kiro is the owner's part.
- 2026-10-18: Stage 15 started on `feat/kiro-native`: `groovyscope hook` (after-write check, opt-in stop gate),
  the `groovy-reviewer` agent and the headless CI review recipe; 331 Python tests at 95%.
- 2026-10-18: Stage 15 second branch `feat/kiro-skills`: twelve skills from the cookbook, MCP prompts, the
  steering halved; 347 Python tests at 95%.
- 2026-10-18: reliability review written (`docs/research/2026-10-18-reliability-and-kiro-features.md`): two
  independent reviews of the engine and the Python package, the MCP features Kiro honours (elicitation above
  all) and the ones it does not. Stages 17 to 19 added; the owner put the JVM's memory behaviour first, so
  Stage 17 starts on `feat/engine-memory` ahead of the front-end work.
- 2026-10-18: Stage 17 implemented locally: `LoaderHandle` owned by the `CompilationResult` and closed by
  `LanguageServices` when the next snapshot is published; a file that stops parsing is re-parsed from its last
  good text into the current generation instead of pinning the previous one; the call graph dropped on every
  recompile; the cascade guard anchored on the first resolved round (a syntax error in a base class costs three
  rounds) with a `RetryBudget` for the compile and the type checker; one type check per `fix` call;
  `-XX:+ExitOnOutOfMemoryError` on the daemon's JVM; `groovyscope/status` reports `memory`; `daemon status`,
  `doctor` and `checkup` warn above 85% heap use and name the `daemon.heap` fix after an out-of-memory exit.
  Engine specs 385 green, 349 Python tests at 95% coverage.
- 2026-10-18: revised plan for asynchronous calls and out-of-memory guardrails written (research doc, section
  5); stages 18 to 20 re-cut around it, awaiting the owner's approval. PR #64 merged.
- 2026-10-18: v0.15.0 released (PR #66 merged by API after re-running its parked checks; publish workflow
  dispatched). Plan PR #67 merged; the owner approved the plan. Release automation PR opened; Stage 18 starts
  on `feat/async-guardrails`.
- 2026-10-18: Stage 18 implemented locally on `feat/async-guardrails` (PR #70; release automation PR #69 merged).
  Engine: `Cancellation` (thread-bound
  cancel flag with checkpoints in the compiler, type checker, linter, index and call graph), `RequestExecutors`
  (heavy and light lanes, bounded queues, a `RequestFailed` busy answer), single-flight compile with cancellable
  waiters in `LanguageServices`, per-module containment in `AstIndex`, idle workspace eviction, O(1) `status`
  with `load`, a bounded report set, `maxFileBytes` in `text()`, coalesced symbol saves; settings
  `daemon.workerThreads`, `daemon.requestQueue`, `daemon.workspaceIdleMinutes`. Front end: an RPC channel
  (reader thread, futures by id, `$/cancelRequest` on timeout), the MCP session caller without its global lock
  (`mcp.requestTimeoutSeconds`, `mcp.maxInFlight`, single flight), the relay connecting before stdin and one
  restart of a vanished daemon, probe states with a busy guard against a second JVM, hooks that never cold-start
  the engine. Engine specs 392 green, 358 Python tests at 95% coverage.
- 2026-10-18: Stage 19 implemented locally on `feat/oom-self-healing`. Engine: `MemoryGuard` on the tenured
  pool (`daemon.heapPressurePercent`) shedding every workspace's caches and refusing whole-workspace requests
  under pressure, `memory.pressure` in `status`; `workspace.maxFiles` and `workspace.maxTotalBytes` with one
  `GS101` warning for what is left out. Front end: `selfheal` (state file next to the daemon log, exit cause
  from the log, restart budget `daemon.maxRestartsPerTenMinutes` with jittered backoff, heap grown by
  `daemon.heapGrowthFactor` up to `daemon.maxHeap` after an out-of-memory exit and kept), log rotation
  (`daemon.logMaxBytes`, `daemon.logsKept`), opt-in heap dumps (`daemon.heapDumpOnOutOfMemory`), a cache sweep
  reported by `doctor` and applied by `checkup --fix`, `git status -z` in the stop gate, and writes that refuse
  a file changed since it was read. Engine specs 400 green, 367 Python tests at 95% coverage.
- 2026-10-18: v0.16.0 released with stages 18 and 19 (PRs #70 and #71 merged by API after green CI; the release
  PR #68 merged alone after its parked checks were re-run; publish workflow green, PyPI 0.16.0 live). Next: Stage
  20 (elicitation and progress) after the owner's Kiro 3 check, and the owner's outstanding Kiro checks for
  stages 13 to 16. The owner's two one-off steps for unattended releases (the `RELEASE_PLEASE_TOKEN` secret and
  "Allow auto-merge") are still open.
- 2026-10-18: Stage 16 implemented locally on `feat/tool-surface`. The tool descriptions are one line each in
  `tools_catalog.DESCRIPTIONS` (the docstrings stay as developer documentation); the advertised input schemas
  lose the generated titles, `anyOf [T, null]` pairs and `default: null`; the annotation title that repeated
  the tool title is gone; the server instructions shrank from 1,506 to 631 characters. `symbol_context` (28th
  tool) answers definition, hover and reference count in one call. Measured on `tools/list`: 27 schemas in
  21,768 bytes (about 5,440 tokens) before, 28 in 12,973 (about 3,240) after; `test_tool_surface` holds a
  14,000-byte budget. Also from the plan's section 5, not delivered in Stage 19: every answer is held to
  `output.maxBytes` (P6, the largest list is halved until it fits and `truncated` says what went), and
  `kiro repair` keeps a user's `timeout`, `requestTimeout`, `disabledTools`, `autoApprove`, `disabled` and log
  level on the groovyscope `mcp.json` entry (P8). `docs/kiro.md` has the numbers and the `disabledTools`
  guidance. Left for the owner: `/tools` and `/context show` in Kiro before and after.
- 2026-10-18: incident. The auto-merge step added to `release-please.yml` in PR #69 called
  `gh pr merge --auto`, which merges at once when the repository allows no auto-merge and nothing is required
  yet. Release PRs #73 (0.16.1, documentation only) and #75 (0.17.0, Stage 16) were merged by the bot seconds
  after they opened, before any check ran; being merged by the built-in token, neither started the tagging run,
  so v0.16.1 was tagged only by the next push and v0.17.0 not at all. The code in both had passed CI on its own
  PR and the publish workflow builds and tests again. Fixed on `fix/release-pr-merge`: only GitHub's auto-merge
  is requested (the GraphQL mutation, which fails rather than merges), and only with `RELEASE_PLEASE_TOKEN`;
  `AGENTS.md` says never to merge before checks run and how to tag a release PR merged by the bot.
  Merged as PR #76; the run its merge started tagged v0.17.0 and dispatched the publish.
- 2026-10-18: Stage 16 merged (PR #74, CI green; merged by API, auto-merge still off). Stage 20 implemented
  locally on `feat/elicitation-progress`: `interaction` binds the SDK's request context to the tool's worker
  thread (the response wrapper declares a `ctx` parameter the schema never shows); `fix`, `format` and `rename`
  with `apply`, `refresh_classpath` and draw.io writes ask through MCP elicitation when the client declared it
  (one yes or no, the files and changed lines in the question; a no or a cancel writes nothing; no capability or
  `mcp.confirmWrites: never` keeps the dry-run convention); waiting calls send a progress note every
  `mcp.progressSeconds`; the practices catalogue is served as `groovyscope://practices` and
  `groovyscope://practices/{code}`. From the plan's section 5 (P5, not delivered in Stage 19): the classpath
  export runs in its own process group and is stopped whole on timeout, a lock refuses a second export of the
  same workspace, and `refresh_classpath` reads trust when called. Long-running MCP tasks are not used: the SDK
  has the types without server support and Kiro does not negotiate them. 390 Python tests at 95% coverage.
- 2026-10-18: [kiro-checks.md](kiro-checks.md) gathers every check the owner still owes (stages 9 to 16 and 20)
  plus a memory drill for stages 17 to 19, as Kiro CLI prompts with what to expect and a report table.

- 2026-10-18: owner's first Kiro check on 0.18.0 (Kiro CLI 2.29.1, classic). Passed: wiring (28 tools, 2.5k
  tokens; steering 0.1% of context), the `code` tool on `.groovy`, the six navigation prompts, rename, the
  classpath refresh question, parallel requests. Failed or not run: see Stage 21. Found while reproducing: an
  unknown type in a method body was not reported in the CodeNarc corpus (any other file with an unresolved
  import hid it), and the `def` fix wrote `Rule` without its import.
- 2026-10-18: owner's retest on 0.19.0 (Kiro CLI 2.29.1, terminal UI): hook start-up 0.24 s, `/groovy-impact`
  ran, fix answers carried left counts, classpath export and refresh worked on the owner's own project. A method
  level draw.io of 252 methods was unusable: the page was shrunk to A4 while the text stopped at 8 px, so labels
  spilled over every box. At the owner's direction the page now grows from A4 to A3 to A2 instead of the text
  shrinking (wide rows wrap only when even A2 cannot hold them; past A2 the page is extended and says so), calls
  share one port and lane per caller, and the layout check is indexed by row (10 s to under 1 s for a thousand
  edges). Still open from that retest, for the owner to approve: hook findings reaching the agent through
  `PreToolUse`, an `unresolved-class` code with an add-import fix, and the test kit's default folder.
- 2026-10-18: the owner found the lines still a mess after the paper change. The hand-rolled router is replaced by
  the Eclipse Layout Kernel's layered layout in the engine (`groovyscope/layout`, EPL-2.0 like LSP4J; the engine
  JAR grows by about 8 MB): crossings minimised layer by layer, network simplex placement, orthogonal routing with
  one track per edge. Shared helpers used by five or more (`diagram.sharedHelperCallers`) are drawn once and
  named inside their users' boxes. On the CodeNarc corpus: report writers 0 crossings and 0 overlaps on A4; the
  54-class report package with 313 relations went from 37 crossings to 6, 0 overlaps; every diagram about 1.4 s.
- 2026-10-18: at the owner's request, diagram lines say what a call does and which way it goes. The engine marks
  every method and call `read`, `write` or `unknown` (`Effects`: field and property assignments, mutating calls on
  fields and parameters, setters, file writes, non-query SQL, non-fetch HTTP, processes, exits and system
  properties, propagated up static and inferred calls; a constructor filling its own fields is a read). Calls are
  drawn with a filled arrow from caller to callee: blue reads, orange writes, grey unknown; solid when typed,
  dashed when inferred, dotted when matched by name. On the CodeNarc corpus `CodeNarcRunner` shows 17 reads, 17
  writes (the plugin registration chain) and 18 unknown plugin calls; the HTML report writer only reads; a graph
  of all 900 classes still answers in 1.4 s. Found on the way: `daemon restart` counted as a crash, so three
  restarts in ten minutes refused a fourth, and the refusal's own advice (`daemon start`) was refused too; a stop
  or start someone asks for now empties the crash-loop window.
- 2026-10-19: the owner's Kiro session (a `platform-python` agent adding a Graph `uniqueName` step to a Groovy
  plugin) showed three gaps, fixed at the owner's yes. A review of a path outside the workspace (Kiro was started in
  a neighbouring repository) answered clean, having checked nothing: every MCP tool now refuses `path` and `paths`
  the workspace does not have and names its root. The pasted PATCH retry loop with reworded log lines was not
  found as a copy: the clone finder now treats any string expression as one `text` token. The agent grepped and
  read 48 KB where `read_symbol` answers in one call, because its own agent did not load groovyscope's steering:
  `doctor` names such agents and `groovyscope kiro steer` adds the steering. The steering also says groovyscope
  answers for the folder Kiro was started in. release-please now bumps the package's own version in `uv.lock`.
- 2026-10-19: the owner uses agents other than `groovy`, so groovyscope must work in any of them. `groovyscope kiro
  attach AGENT...` merges only groovyscope's parts (steering, the server when unreachable, read-only tools allowed,
  start, write and stop hooks) into a named agent, keeping its name, tools, shell rules, hooks and format; a V3
  agent gains only MCP permission rules. `doctor` names agents that call groovyscope with a part missing (`kiro
  agents`); `kiro steer` from 0.21.1 is kept as an alias. Found on the way: the start hook installed the global
  Kiro files and unpacked the benchmark corpus in a folder with no Groovy at all; it now prints one line and repairs
  nothing there. The `uv.lock` bump in release-please used the wrong filter (the TOML parser wraps values):
  reproduced with release-please's own updater and fixed (`@.name.value`).
- 2026-10-19: in the owner's Kiro session the agent called a `get_diagnostics` tool that does not exist. The
  steering listed "diagnostics" among the `code` tool's operations (Kiro's code tool has a `get_diagnostics`
  operation, not a tool), and the agent also compiled a file alone with `groovy -e`, where every library import
  fails. The steering now sends errors and findings to `groovyscope.check` or `review`, says there is no
  `get_diagnostics` tool, and warns against classpath-less compiles. Measured on the way: the write hook takes 4
  to 7 s on a 3,000-line file, most of it the workspace recompile behind `compile`; to be proposed.
- 2026-10-19: from the owner's Kiro review of a branch, approved by the owner (items 1 to 3 of five). The agent
  spent a call on `base: "main"` in a `master` repository, four more calls sorting about 960 warnings by hand, and
  called several GS002 findings harmless itself. `review_changes` now defaults to the repository's default branch
  (`origin/HEAD`, else `main` or `master`) and names it when a given base is unknown; its answer ranks the
  changed-line findings in `triage` (risks, smells, noise) with the fixable count and the `groovyscope fix`
  command; GS002 skips a default that is the same empty value (`?: ''`, `?: 0`, `?: []`, `?: [:]`); and the
  bundled CodeNarc profile drops `VariableTypeRequired`, which reported every `def` a second time next to `NoDef`.
- 2026-10-19: items 4 and 5 of the owner's approved review improvements. The regression in the owner's session was
  one changed line in a Java test helper that four integration tests read, found only by a 12-minute CI run, and
  the agent chose compile tasks by hand. `workspace.sourceSets` (on) adds every `src/<set>/groovy` and
  `src/<set>/java` to the roots, so `src/it/java` is analysed; test code is decided in one place (`TestSources`:
  any source set but `main`, or a `Test`, `Tests`, `Spec`, `IT`, `ITCase`, `IntegrationTest` class name);
  `review_changes` reviews changed Java too and answers `tests`: the test methods the change reaches through the
  call graph and the Gradle command (compile tasks of the changed source sets, `--tests` per affected class of the
  tasks in `verify.runTasks`; other tasks, such as `integrationTest` bound to `sourceSets.it` in `build.gradle`,
  listed for CI). `groovyscope verify` runs that plan in one step.
- 2026-10-19: review accuracy, from the owner's second review round (the agent verified the review's plan against
  the code and found most of it overstated). GS002 skips an Elvis passed straight to a parser that rejects empty
  input (`readTree(resp ?: '{}')`) and says it is a defect only where the empty value is a real input; GS026 on a
  time-named `String` (`certificateExpiry`) is information, not a warning, and suggests `...Text` for timestamp
  text carried on purpose; the review's risky constructs count only catches that hide the failure (swallowed or
  returning a default), not those that log or rethrow; `groovyscope fix --changed` (MCP `changed_only`) applies
  safe fixes only on the lines the branch changed, the engine filtering by finding so a fix's import still comes
  with it, and the triage suggests that command.
