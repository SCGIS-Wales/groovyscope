# Roadmap and progress tracker

This file is the source of truth for where the build stands. Update it at the end of every stage and at any
natural stopping point. On resume: read this file, then `git log --oneline -20` and `gh pr list --state all`.
Never redo a stage whose box is ticked and whose PR is merged; an unmerged PR means continue on its branch.

Research spec: [research/2026-10-17-groovy-code-intelligence-for-kiro.md](research/2026-10-17-groovy-code-intelligence-for-kiro.md).

Decisions (2026-10-17): repository moves from `api-py/groovyscope` to `SCGIS-Wales/groovyscope` to match the
PyPI trusted publisher (workflow `workflow.yml`); engine in Java 21; Groovy only for Spock tests; JDK 21 locally.

## Stages

- [x] **Stage 0: foundation** — branch `feat/groovyscope-foundation`, PR: pending.
  Monorepo scaffold, Java 21 engine skeleton serving the LSP lifecycle (LSP4J 1.0.0), Python package with
  `doctor`, `version`, `config validate`, `kiro init|repair`, `lsp --stdio`, placeholders for `mcp` and
  `daemon`, CI on Ubuntu and macOS, CodeQL, semantic PR titles, release-please, publish workflow with
  trusted publishing and provenance attestations, docs.
  Done when: CI green, release PR for 0.1.0 merged, wheel on PyPI (needs the repository transfer first).
  - [ ] Repository transferred to `SCGIS-Wales/groovyscope` and remote re-pointed
  - [ ] `v0.1.0` published to PyPI through `workflow.yml`
- [ ] **Stage 1: engine compile + index + LSP navigation** — branch `feat/engine-navigation`.
  `workspace/`, `compile/` (CompilationUnit to CANONICALIZATION, incremental, Grape/ASTTest off, trust gate),
  `index/`, `semantics/` (STC metadata, UNKNOWN never guessed), real LSP capabilities (definition,
  typeDefinition, references, hover, documentSymbol, workspaceSymbol, completion, signatureHelp,
  prepareRename/rename, compile diagnostics). Fixture projects. Security tests for `@Grab`, `@ASTTest`
  and untrusted global AST transforms. Done when a references query on a fixture returns correct locations
  through Kiro's `code` tool.
- [ ] **Stage 2: daemon + Python relay** — branch `feat/daemon-relay`.
  Unix domain socket daemon per workspace, idle timeout, SQLite index persistence, CDS archive;
  `daemon.py`, `relay.py`, `rpc.py`; `groovyscope daemon start|stop|status`. Done when two sessions share one
  JVM and the daemon exits on idle.
- [ ] **Stage 3: CodeNarc + formatter + diagnostics** — branch `feat/lint-format`.
  CodeNarc 4 in process with the bundled rulesets (prune unknown rules), Groovy 6 type-checker extensions,
  GrEclipse formatting via spotless-lib returning TextEdits, pull diagnostics, codeAction, `check` and
  `format` CLI commands, SARIF upload in CI.
- [ ] **Stage 4: call graph + impact analysis** — branch `feat/callgraph-impact`.
  `callgraph/`, `ImpactAnalyzer` with STATIC | INFERRED | DYNAMIC_UNKNOWN confidence, callHierarchy and
  semanticTokens, `impact` CLI command, performance report on a 2,000-file fixture.
- [ ] **Stage 5: MCP server** — branch `feat/mcp-server`.
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
  `groovyscope (groovy) initialized (261ms)` against the installed wheel. PR to be opened.
