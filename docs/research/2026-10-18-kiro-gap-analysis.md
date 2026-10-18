# Kiro CLI capabilities against groovyscope: gap analysis and plan (2026-10-18)

Status: proposal, awaiting the owner's approval. Nothing here is scheduled until it is copied into
[roadmap.md](../roadmap.md) as a stage.

Sources: Kiro CLI documentation and changelog as indexed on 2026-10-18 (latest release 2.31.1 of 14 October;
CLI 3.0 makes the V3 engine the default at the end of October), Kiro GitHub issues, and a read of this
repository at 0.11.0 (`main` after #55 and #56). kiro.dev itself could not be fetched from the analysis
sandbox, so Kiro facts come from search extracts, issues and a documentation mirror; items marked
**unconfirmed** need a check on a machine with Kiro installed.

## 1. What Kiro CLI offers today

| Area | Kiro CLI (2.31, V3) | Notes |
|---|---|---|
| `code` tool | Tree-sitter layer for 18 languages (Groovy is not one) plus an optional LSP layer | LSP sub-operations seen in issues: `goto_definition`, `find_references`, `get_hover`, `rename_symbol`, `get_diagnostics`, `get_completions`, formatting. Kiro also calls `textDocument/documentSymbol` without checking capabilities (issue #5736). No evidence it uses call hierarchy, implementation, type definition, semantic tokens, folding, inlay hints, code actions or signature help |
| `lsp.json` | Per workspace only; `command`, `args`, `file_extensions`, `file_patterns`, `project_patterns`, `exclude_patterns`, `multi_workspace`, `initialization_options`, `request_timeout_secs` (default 60) | Issue #6322 (open): the tree-sitter extension gate can reject non built-in extensions before the LSP layer runs. Whether `.groovy` and `.gradle` pass the gate on 2.31 is **unconfirmed** |
| MCP | stdio and streamable HTTP; tools, server prompts (slash commands) and resources (`#` menu, user initiated); elicitation; `autoApprove`, `disabledTools`, `timeout`, `requestTimeout`; hot reload; on demand tool loading since 2.31 | Tool annotations (`readOnlyHint`, `destructiveHint`), `tools/list_changed`, progress and logging: no mention anywhere, assume not honoured. Result size handling undocumented. Tool names over 64 characters rejected |
| Hooks | `SessionStart` (was `agentSpawn`), `UserPromptSubmit`, `PreToolUse`, `PostToolUse`, `Stop`, `SessionEnd`; matchers by tool id, category (`read`, `write`, `shell`), `@server/tool`, regex; stdin JSON with `tool_name`, `tool_input`, `tool_response`; exit 0 adds stdout to context, exit 2 blocks with stderr shown to the model | Whether `Stop` can block (`{"decision":"block"}`) is documented both ways; timeout unit (seconds vs `timeout_ms`) conflicts between sources. Both **unconfirmed** |
| Agents | `.kiro/agents` JSON or Markdown; V3 `permissions.rules` (`capability: mcp`, `match`, `effect`), `tools` tags, `resources` (`file://`, `skill://`, `knowledgeBase`), `hooks`, `includeMcpJson`, `includePowers`, `model` | 2.x `allowedTools` and `toolsSettings` are retired in V3; the universal file keeps both |
| Steering | `inclusion: always | fileMatch | manual | auto` with `fileMatchPattern`; custom agents load steering only through `resources` | V3 honours all four modes; earlier engines only `always` |
| Skills | `.kiro/skills/<name>/SKILL.md`; metadata loaded at start, body on demand; each skill is a slash command | Progressive disclosure: cheap until used |
| Powers | Bundles of steering, MCP servers and hooks (`plugin.json`), global only, `/powers` since 2.25; MCP servers inside a power activate on keywords and are not in `mcp.json` | Kiro's own docs warn that five MCP servers can cost 50k tokens before the first prompt |
| Knowledge | `/knowledge add` with BM25 (fast) or semantic (best) index, default on in V3 | Semantic search over docs, not code structure |
| Subagents and workflows | `subagent` tool, parallel, isolated context, shares MCP and permissions; workflows since 2.26 | Non interactive subagents fail fast on approvals unless trusted |
| Headless | `kiro-cli chat --no-interactive --trust-tools ... --require-mcp-startup --output-format stream-json` | What a CI job would call |
| Context | Context files capped at 75% of the window; auto compaction near 80%; `/tools` shows token cost per tool; `/context show` | The cost of 27 tool schemas plus always on steering is paid on every session |

## 2. What groovyscope exposes today (0.11.0)

- **LSP:** incremental sync; definition, type definition, implementation, references, hover, document and
  workspace symbols, completion, signature help, prepare and rename, formatting, range formatting, code
  actions (quick fix, fix all, organise imports), push and pull diagnostics, document highlight, folding,
  inlay hints, call hierarchy, semantic tokens (full). Single root. 26 custom `groovyscope/*` requests for
  the CLI and MCP server. Not implemented: workspace diagnostics, code lens, selection range, linked editing,
  type hierarchy, execute command, multi root, file watcher registration, configuration change.
- **MCP:** 27 tools on stdio, compact JSON, paging with `limit` and `cursor` under a 16 kB cap, one shared
  engine session, 600 s timeout, live configuration reload. No prompts, resources, logging, progress or
  `tools/list_changed`.
- **Kiro files:** `lsp.json` (extensions, Jenkinsfile patterns, `request_timeout_secs: 120`), `mcp.json`,
  one agent template (`allowedTools` for read, grep, glob, code and `@groovyscope`, V3 rules added by
  `kiro-cli agent upgrade`), one steering file (`inclusion: always`, 126 lines), one hook (`agentSpawn`
  running `groovyscope checkup --agent`, output capped at 3800 characters).
- **Measured:** benchmark on CodeNarc (1,064 files, Kiro 2.32.0, groovyscope 0.6.1): 4.21 credits with
  against 4.82 without; navigation and review more complete with groovyscope; edits 12 to 15 minutes with
  against under 2 without, because every edit recompiles the whole workspace and republishes diagnostics.

## 3. Gaps

Ordered by how much they are likely to matter to an agent working on Groovy code.

### G1. Edit latency on large workspaces (optimisation, the biggest gap)

Each write by the agent changes the disk fingerprint; the next request, and the push diagnostics
scheduler, recompile every file. On the benchmark corpus that is 12 to 15 minutes per task against under
two without groovyscope. Per file invalidation was deferred in Stage 2 "because the full recompile is fast
enough": it is not on a thousand file workspace. Three measures, in order of payoff for cost:

1. **Quiet window and laziness.** Coalesce bursts of writes (agents write several files in a row) with a
   short quiet window and recompile only when a request needs the result or the window closes. Push
   diagnostics off for Kiro sessions (`lint.publish: false` in the agent profile), because Kiro's `code`
   tool pulls diagnostics on demand and the MCP `check` tool is explicit.
2. **Incremental recompile.** Recompile the changed files plus their dependants (imports and the call
   graph already give the dependency set); keep the rest of the snapshot. Diagnostics republished only for
   the files whose results changed (already the case for the publisher).
3. **Type check scope.** When one file cannot resolve its imports, the type check must still run for the
   others (the benchmark's task 4 lost the whole type check to two test files). Stage 10's `GS100` folding
   and Stage 12's early cascade stop may already cover this; verify on the corpus.

### G2. Kiro 3.0 readiness (verification, due before end of October)

CLI 3.0 makes V3 the default and retires Classic. Items to confirm on a machine with Kiro installed:

- The `code` tool answers definition, references, hover and diagnostics on `.groovy`, `.gradle` and
  `Jenkinsfile` through the LSP layer, given issue #6322 (extension gate). If it does not, the steering
  must stop telling the agent to prefer `code`, and the MCP tools carry navigation (they already can).
- The universal agent file's `hooks.agentSpawn` is honoured by V3 under the new `SessionStart` name, or the
  V3 section needs the new name.
- The V3 `permissions.rules` written by `groovyscope kiro repair` allow every `@groovyscope/*` tool. Kiro
  does not honour `readOnlyHint`, so the mutating tools (`fix`, `rename`, `format` with `apply`,
  `refresh_classpath`, `relationship_graph` with `drawio_path`) currently run without a prompt. Split the
  rules: read only tools allowed, mutating tools ask.
- `/tools` token cost of the 27 schemas and `/context show` cost of the steering file (numbers, not guesses).
- `docs/kiro.md` says both "27 tools" and "twenty-six tools"; fix the text.

### G3. Kiro features groovyscope does not use

| Kiro feature | Opportunity | Value |
|---|---|---|
| `PostToolUse` hook with matcher `write` | Run `groovyscope check` on the file just written and return new compile errors and findings to the model in the same turn, so the agent sees a broken edit immediately instead of at the end | High: self healing edits, fewer wasted turns |
| `Stop` hook | Refuse to end the turn while files the agent changed do not compile (opt in, `{"decision":"block"}`), with the diagnostics as the reason | High if blocking works on V3 (unconfirmed) |
| Skills (`SKILL.md`, progressive disclosure) | Move the twelve cookbook prompts of `docs/prompts.md` and the longer steering guidance into skills (`/groovy-review`, `/groovy-rename`, `/groovy-catch-audit`, ...). Metadata only at start, body on demand | High: cuts always on context, gives users slash commands |
| Steering `inclusion: fileMatch` | Load the Groovy steering only when Groovy, Gradle or Jenkinsfile files are in play | Medium: zero cost in non Groovy sessions |
| MCP prompts | The same recipes as server prompts, which Kiro shows as slash commands even without the skills files | Medium, cheap once the skills exist |
| Powers (`plugin.json`) | `groovyscope kiro init --power` packages steering, `mcp.json` and hooks as a power; the MCP server activates on Groovy keywords and costs nothing in other sessions | Medium: the recommended install shape for Kiro 3 |
| Subagent definition | A `groovy-reviewer` agent for merge request review that runs non interactive with read only tools trusted, usable from the `subagent` tool and from CI | Medium |
| Headless recipe | `docs/ci.md` section: `kiro-cli chat --no-interactive --require-mcp-startup --output-format stream-json` driving `review_changes` on a merge request, output parsed from JSON lines | Medium, documentation only |
| `knowledgeBase` resource | Index `docs/practices.md`, `docs/rules.md` and the runtime profile guidance with the semantic index so "why is this a bad practice" questions do not load whole files | Low |
| MCP resources, elicitation, sampling | User initiated or not honoured; nothing to gain now | None |

### G4. LSP features Kiro does not call

Call hierarchy, implementation, type definition, semantic tokens, folding, inlay hints, code actions and
signature help are served over LSP but Kiro's `code` tool never asks for them; the MCP tools already carry
the ones an agent needs (`call_graph`, `impact_of`, `type_definition`, `fix`). Editor only features that
are missing (code lens, selection range, linked editing, multi root, workspace diagnostics) matter only
when a second LSP consumer appears (Zed through ACP, an IDE, or Apache Groovy's own `groovy-lsp` as a front
end). No action now.

### G5. Tool surface and context cost

Twenty seven schemas and a 126 line steering file are loaded into every session; Kiro's own guidance warns
about exactly this. Without the measurement from G2 the size of the problem is a guess. Candidate measures
once measured: shorter tool descriptions (the long explanations move to skills), one combined
`symbol_context` answer (definition, hover and a reference count in one call, halving the round trips of
the common "what is X and who uses it" question; the benchmark's task 1 cost twice the calls with
groovyscope), and `disabledTools` for the tools a team never uses.

## 4. Is the solution optimised?

Partly. Answers are accurate and compact, navigation and review beat text search, and the daemon starts
in under a second. It is not optimised for the edit loop (G1) or for context cost (G5), and it uses one of
Kiro's six hook events and none of its skills, powers or prompts (G3). Nothing found is a wrong design;
the gaps are the next layer on a sound base.

## 5. Proposed plan

Each stage is one branch and one pull request, with the roadmap's usual "done when".

| Stage | Scope | Owner's part | Estimated effort |
|---|---|---|---|
| **13. Kiro 3.0 readiness** | The G2 checks; template fixes that follow (V3 hook name, split permission rules, docs count); `checkup` warns when Kiro 3 runs with a Classic only agent file | Run the checks on Kiro 2.31 and on 3.0 when it ships, paste the results | 1 session plus the owner's checks |
| **14. Edit loop performance** | G1: quiet window and lazy recompile, push diagnostics off for agent sessions, incremental recompile of changed files and dependants, type check scope verified; a timing spec on the generated workspace; benchmark tasks 3 and 4 repeated | Approve the benchmark run (about 5 credits) | 2 to 3 sessions |
| **15. Kiro native integration** | G3 high and medium rows: `PostToolUse` check on written files, opt in `Stop` gate, skills generated from the cookbook, steering trimmed and `fileMatch`, MCP prompts, `kiro init --power`, the `groovy-reviewer` subagent, the headless CI recipe | Kiro checks of the hooks and one `/groovy-review` run | 2 sessions plus the owner's checks |
| **16. Tool surface and context** | G5 after the G2 numbers: description trims, `symbol_context`, `disabledTools` guidance; re measure `/tools` and `/context` | Paste the before and after numbers | 1 session |

Recommended order: 13 first (cheap, and 3.0 lands in two weeks), then 14 (the largest measured
cost), then 15, then 16. Stages 13 and 15 need your Kiro checks, as every stage so far has.

Not proposed: editor only LSP features (G4), MCP resources, sampling or elicitation, a second transport.
