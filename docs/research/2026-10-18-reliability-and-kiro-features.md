# Reliability, resilience and the Kiro features still unused (2026-10-18)

Status: assessment for the owner, with a proposed plan at the end. Nothing here is scheduled until it is copied
into [roadmap.md](../roadmap.md) as a stage.

Method: two independent adversarial code reviews at 0.14.0 (one of the Python package, one of the engine),
each finding traced from a real caller or input to the failure; the two most serious engine findings were
re-read in the code afterwards. Plus the MCP protocol features the installed SDK (`mcp` 2.x) offers against
what groovyscope uses and what Kiro CLI honours (from the gap analysis of the same day).

## 1. Reliability findings, ranked

| # | Where | What goes wrong | Severity |
|---|---|---|---|
| E1 | `engine/.../compile/GroovyCompiler.java:131,164` | The cascade guard only looks at the first attempt and needs it to have reached semantic analysis. A syntax error, the normal state mid-edit, dies at parsing, so the guard never fires: each retry round drops the dependants of the broken class and recompiles nearly the whole workspace again, up to `MAX_RETRIES = 64`, all under the services lock, so every LSP, MCP and diagnostics request on that workspace stalls. The type-check compile has the same shape with 16 retries and no guard at all | High |
| E2 | `GroovyCompiler.java:279`, `services/LanguageServices.java:320-345`, `python/.../launch.py` | The kept compile's `GroovyClassLoader` (with up to 200 open jars) is never closed; "keep last good AST" carries a `ModuleNode` whose `CompileUnit` pins the entire previous generation (every AST, the loader, its jars) while any file has a syntax error; `failedSnapshot` and the lazily rebuilt call graph pin a third. `OutOfMemoryError` is caught nowhere and the JVM runs without `-XX:+ExitOnOutOfMemoryError`, so after an OOM every request retries the same compile and fails until the idle timeout | High |
| P1 | `python/.../mcp_server.py:968-1013`, `_templates/kiro/mcp.json` | One engine request holds the single session lock for up to 600 s while Kiro gives up at 120 s; every other tool call, even `explain`, queues behind it and fails too; a client cancel does not release the worker thread. One whole-workspace `check` on a large repository freezes every tool for minutes | High |
| P2 | `python/.../relay.py:406-419`, `cli.py:652-660` | The LSP relay attaches stdin to the event loop before connecting to the daemon; if the connect fails (daemon just exited idle, `daemon stop` elsewhere) the fallback `execv` loses the bytes already read, Kiro's `initialize` among them, and the editor session hangs for its request timeout | High |
| E3 | `index/AstIndex.java:76`, `GroovyCompiler.java:244-247` | No per-module containment in indexing: one `LinkageError` or deep-AST `StackOverflowError` fails the whole refresh and leaves a stale index for every file; a compiler crash marks every file broken and parses them one by one | Medium |
| E4 | `services/QualityServices.java:674-677` | `fix` runs a whole-workspace type-check compile per `CompileStatic` finding with two options: N findings, N compiles, each with retries; reachable from the after-write hook | Medium |
| E5 | every `computeAsync` handler, `GroovyscopeLanguageServer.java:208-218` | All requests share `ForkJoinPool.commonPool()` (one thread on a two-core box) plus the per-workspace lock: a 60 s type check from one connection stalls hover for every other; `status` walks the file tree on the reader thread | Medium |
| P3 | `cli.py:857-899` | Every hook event resolves the engine first, which cold-starts a 1 GB JVM when none runs, before checking that the write touched a Groovy file or that the stop gate is on; each hook also pays ~0.7 s importing the MCP SDK | Medium |
| P4 | `daemon.py:128-136`, engine `status` | The liveness probe is a 5 s `status` call that walks the source tree; on a busy daemon it times out, the CLI tries to start a second JVM, which exits because the socket is live, and the caller falls back to a cold engine or skips | Medium |
| P5 | `mcp_server.py:503-535`, `classpath.py` | `refresh_classpath` can outlive Kiro's 120 s budget, the timeout kills only the wrapper shell (the Gradle daemon keeps building), a retry starts a second build; the trust flag is read once at server start | Medium |
| P6 | `mcp_understanding.py:177-193`, `mcp_server.py:644`, `impact_of`, `profile_status` | `output.maxBytes` is not enforced on several answers (`explain_code` items, `fix`/`format` `rejected`, impact reports); hundreds of kB can reach the agent | Medium |
| P7 | `daemon.py:189`, `cli.py:438-444`, `kiro.py:283-290` | Nothing under the cache directory is ever pruned: daemon logs grow forever, one CDS archive per engine build, one symbol store per root, `.bak.N` backups on every repair; `doctor` reports none of it | Medium |
| P8 | `kiro.py:121-139` | `kiro repair` and the automatic refresh at agent start reset scalar edits the user made to `mcp.json` (a raised `timeout`, a log level) because the template wins | Medium |
| E6 | `daemon/WorkspaceRegistry.java:57`, `LanguageServices.java:371`, `FileWalker.java:42` | The registry never evicts a root; symbol-store saves queue without bound and rewrite the whole table; a static 10,000-entry set stops reporting unreadable files once full | Medium to low |
| P9 | `hooks.py:138-148` | The stop gate parses porcelain v1 without `-z`: non-ASCII paths are quoted and skipped, so the gate passes with errors; a slow `git status` (60 s runner timeout) also passes silently. It also gates on every uncommitted change, the user's own included, not only what the agent changed this session | Low to medium |
| E7 | `workspace/WorkspaceModel.java:125,296` | `maxFileBytes` is not applied by `text()` (format and code actions lint any size); an open document outside the root is compiled in; a cross-connection `didClose` can drop another connection's overlay | Low |
| P10 | `edits.py:124-136`, `mcp_server.py:680-691`, `daemon.py:199-214`, `checkup.py:220` | Writes race their own reads (a save between read and write is overwritten; a symlink is replaced by a file); a spawned daemon is never reaped; `checkup` prints `engine ?` because it reads the wrong key | Low |

Checked and sound: download verification and atomic writes in `fetch` and `provision`, JAR checksum on start
(36 ms for 32 MB), path traversal guards on every `uris` and `drawio_path` input, RPC framing limits and
out-of-order reply handling, LSP4J error responses, stdout discipline (everything but frames goes to stderr),
the resolver and quality caches under concurrency.

Smaller items noticed during the stages: `uv.lock` drifts after every release because release-please bumps
`pyproject.toml` only (every `uv run` rewrites it); the Homebrew formula in the tap was still 0.7.0 on
2026-10-18 morning although the tap's daily workflow should follow PyPI; `test_deep_merge_identity` is a
hypothesis flake (passes on re-run); the hook's default 45 s engine budget may exceed Kiro 2.x's hook
timeout if that is 30 s (unconfirmed); `diagnostics.push: open` assumes Kiro's `get_diagnostics` opens the
document or pulls, which the owner's check confirms.

## 2. MCP protocol features: what the SDK offers, what Kiro honours, what groovyscope uses

| Feature | SDK | Kiro CLI | groovyscope | Worth it |
|---|---|---|---|---|
| Tools with annotations (`readOnlyHint` and the other hints) | yes | lists tools; ignores the hints | 27 tools, hints set | done; the hints are why the approval rules come from the catalogue instead |
| Prompts | yes | yes, under `/` | 12 since 0.14.0 | done |
| Resources and templates | yes | yes, user initiated (`#`) | none | low: the effective configuration, the practices catalogue and the last review as `#` references |
| **Elicitation** (the server asks the user a question mid-call) | yes, `ctx.elicit` with a schema | **yes**: Kiro shows the server and the request, the user may decline | none | **high**: `rename`, `fix`, `format` with `apply`, `refresh_classpath` and a draw.io write can ask the user directly ("apply these 14 edits in 3 files?") instead of trusting the agent to do the dry run first; `trust this workspace?` becomes a question the agent cannot answer for the user |
| Progress notifications | yes, `ctx.report_progress` | not documented | none | medium, harmless: `refresh_classpath` and a cold first `check` report progress instead of looking hung |
| Logging notifications | yes, `ctx.info` and friends | not documented | Python logging to stderr only | low: engine warnings (stale classpath, JDK) into Kiro's MCP log |
| Long-running tasks (the 2025-11 revision) | yes | "current protocol revision" in V3, unconfirmed | none | medium: `refresh_classpath` as a task sidesteps the 120 s client budget (finding P5) |
| Argument completion for prompts | yes | unconfirmed | none | low: symbol names for `/groovy-impact` |
| Sampling (the server borrows the client's model) | yes | not documented | none | skip: nothing needs a second model |
| Roots | yes | not documented | `--root` from the launch | skip until a client sends them |
| Structured content (`outputSchema`) | yes | reads the text | optional (`output.structured`) | done |

## 3. Kiro CLI features beyond the protocol

- Autonomous mode (`/autonomous on`, 2.31) makes the after-write check and the stop gate more valuable, not
  less: they are the only feedback the agent gets between writes. The gate needs a session baseline (P9).
- Workflows (2.26, 2.27): a shipped "review a merge request" workflow that chains the reviewer agent and the
  GitLab posting is possible once the workflow file format is seen on an install.
- Powers: packaging waits for the plugin schema (recorded in stage 15).
- Knowledge bases: `docs/practices.md` and `docs/rules.md` as a `knowledgeBase` resource of the agent, so
  "why is this a bad practice" answers come from the catalogue without loading it into context.
- ACP (`kiro-cli acp` for Zed and JetBrains): the LSP front end already serves an editor; nothing to add.
- Steering `inclusion: fileMatch` and the `skill://` resource form wait for the owner's Kiro 3 check.

## 4. Proposed plan

| Stage | Scope | Owner's part |
|---|---|---|
| **17. Front-end resilience** (Python) | P1: one reader thread per engine session demultiplexing replies by id, each tool waiting on its own future with a timeout under the client's budget and a "still running, retry" answer; `$/cancelRequest` on timeout. P2: connect before touching stdin in the relay, one retry of `ensure_running`. P3 and P4: hooks return before resolving the engine and never cold-start it; the probe distinguishes refused, busy and incompatible; `status` made O(1). P5: `refresh_classpath` kills the process group, refuses a second concurrent build, reads trust live. P6: caps on every answer. P7: log rotation and a cache sweep reported by `doctor`. P8: user scalars in `mcp.json` kept. P9: `git status -z`, a session baseline for the gate. P10 and the `engine ?` key | none; re-check the hooks afterwards |
| **18. Engine resilience** (Java) | E1: a phase-aware cascade guard and a wall-clock budget per compile, the same for the type check, and one type check per `fix` call (E4). E2: a closeable loader owned by the snapshot and closed when the next one is published; last-good entries kept as index data, not `ModuleNode`s; the call graph rebuilt or nulled in `refresh`; `-XX:+ExitOnOutOfMemoryError` with the front end respawning. E3: per-module containment in the index and a bisecting crash path. E5: a bounded executor per server with a small pool for cheap requests. E6 and E7: registry eviction, coalesced symbol saves, `maxFileBytes` in `text()`. A spec per finding and the performance spec extended with a "syntax error in a base class" edit | none |
| **19. Elicitation and progress** | `rename`, `fix`, `format` with `apply`, `refresh_classpath` and draw.io writes ask the user through elicitation when the client supports it (the dry-run convention stays for clients that do not); progress on `refresh_classpath` and the cold first request; `refresh_classpath` as a task where the client negotiates the 2025-11 revision; the knowledge base resource | one Kiro session to see the elicitation prompt and the progress line |

Recommended order: 17 first (the hangs an agent user meets daily, a day of work), then 18 (the large
workspace cases, two to three sessions), then 19 (small, after the Kiro 3 check). Stage 16 (tool surface and
context) still waits for the `/tools` and `/context` numbers and can run alongside any of these.

## 5. Revised plan after Stage 17: asynchronous calls and out-of-memory guardrails

Written on 2026-10-18 after Stage 17 (PR #65) at the owner's request: the remaining work is re-cut around two
questions, what happens when calls overlap and what happens when memory runs out, and every item names the
guardrail it adds and the practice it follows. Stage 17 left the engine with a bounded compile, one generation
in memory at a time and a JVM that exits on `OutOfMemoryError`. What it does not yet have is a way to stop
work that nobody waits for any more, a way to refuse work it cannot afford, and a front end that survives the
exit without a crash loop.

### 5.1 How the system behaves under concurrency today

- The MCP server holds one engine session and one `threading.Lock` around every request
  (`mcp_server.py`, `session_call`). Kiro calls tools in parallel; the second call waits for the first, up to
  the 600 s engine timeout, while Kiro itself gives up at 120 s. A cancelled client request keeps the worker
  thread and the engine busy.
- The engine answers every request on `ForkJoinPool.commonPool()` (`CompletableFutures.computeAsync`), with no
  `CancelChecker` passed into the services, so `$/cancelRequest` from LSP4J is accepted and ignored. A compile
  or a type check runs to the end even when the request that asked for it is gone.
- `LanguageServices.refresh()` runs under one monitor; every request that needs a fresh snapshot blocks on
  it. That is correct (one compile per change) but a waiter cannot give up, and `status`, which should be
  cheap, walks the file tree on the reader thread.
- When the JVM exits on `OutOfMemoryError`, the relay's exit code tells Kiro to restart the server, and the
  MCP server reconnects on the next tool call. Both start a JVM with the same heap, which loads the same
  workspace and dies the same way: a crash loop that costs a JVM start per request and tells the user nothing
  until they run `doctor`.

### 5.2 Guardrails, by layer

**Engine (Java)**

| # | Guardrail | Practice it follows |
|---|---|---|
| G1 | **Cooperative cancellation end to end.** Every `computeAsync` handler passes its `CancelChecker` into the services; the compiler checks it between rounds, the type checker and CodeNarc between files, the call graph between classes. `$/cancelRequest` then stops the work within one unit. The daemon protocol exposes the same cancel to the MCP front end | Cancellation is cooperative in the JVM: check a flag at safe points, never `Thread.stop`. LSP4J's `CancelChecker` is the standard carrier |
| G2 | **Bounded request executors with two lanes.** One fixed pool for heavy requests (`daemon.workerThreads`, default the core count, at least 2) and a small pool for cheap ones (hover, definition, outline, status), both with bounded queues. A full heavy queue answers `ServerBusy` (LSP error `RequestFailed`) at once instead of queuing without limit | Bulkhead and bounded queue: a slow type check cannot starve navigation, and back pressure reaches the caller instead of building up in memory |
| G3 | **Single-flight compile with cancellable waiters.** The monitor in `refresh()` becomes a `CompletableFuture<Snapshot>` that the first requester owns and later requesters join; a cancelled waiter leaves without cancelling the compile the others still need | Request coalescing: one compile per change, but no request is pinned to another's lifetime |
| G4 | **Memory pressure guard.** A `MemoryGuard` registers a collection usage threshold on the tenured pool (`MemoryPoolMXBean`, 85%, the figure `doctor` already uses). When it fires: a warning in the log, `memory.pressure: true` in `status`, derived caches dropped (call graph, quality and lint caches, in-memory symbol table), and whole-workspace requests (`review` of everything, `find_duplicates`, `workspace_overview`) refused with the `daemon.heap` advice while cheap requests keep working. `OutOfMemoryError` is still never caught: with `-XX:+ExitOnOutOfMemoryError` the JVM exits and the front end handles it | Measure, then shed load and degrade in a declared order; do not catch `OutOfMemoryError` (the heap state after it is undefined) |
| G5 | **Workspace size budget.** `workspace.maxFiles` (default 20,000) and `workspace.maxTotalBytes` (default 256 MB) in `.groovyscope.yaml`. Past the budget the compile covers the source roots in order until it is spent and reports one `GS101` warning naming the roots left out, instead of loading everything and dying | Admission control before the expensive step, with the limit visible in the configuration file |
| G6 | **Heap dump on request.** `daemon.heapDumpOnOutOfMemory: false` by default; when on, `-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=<cache dir>`; `doctor` reports dumps present and their size and the sweep (G13) removes old ones | Diagnostics opt in: a dump the size of the heap must never appear on a user's disk unasked |
| G7 | **Containment and eviction (E3, E6, E7).** Per-module containment in `AstIndex.build` so one bad tree fails one file; `WorkspaceRegistry` evicts a root idle for `daemon.workspaceIdleMinutes` (default 30) and releases its generation; symbol saves coalesce; `maxFileBytes` applied in `text()`; `status` answers from counters kept by the model, not a tree walk | Fail small, release what is idle, keep the liveness probe O(1) |

**Front end (Python, asyncio and the MCP SDK)**

| # | Guardrail | Practice it follows |
|---|---|---|
| G8 | **Demultiplexed session with per-call futures.** One reader thread per engine session matches replies to requests by id; each tool awaits its own future with `asyncio.wait_for` under a budget `mcp.requestTimeoutSeconds` (default 100 s, under Kiro's 120 s). No lock is held for the duration of a call; the only serialisation left is frame writing | Never block the event loop; one connection, many outstanding requests, as JSON-RPC intends |
| G9 | **Timeout and cancel propagation.** On the budget, the tool answers "still running" with the request id and sends `$/cancelRequest` to the engine (G1 makes it effective); an MCP client cancel (`CancelledError` in the handler) does the same. An identical call arriving while the first still runs joins it (single flight keyed by method and parameters) rather than doubling the work | A timed-out request must release everything it holds; idempotent retries join in-flight work |
| G10 | **Concurrency limit.** `mcp.maxInFlight` (default 4) as an `asyncio.Semaphore` in front of the engine; waiters time out on the same budget with a message that names the limit | A bulkhead on the client side too, so a parallel-tool agent cannot flood the engine or the socket |
| G11 | **Crash-loop guard and heap escalation (OOM self-healing).** The daemon's exit is recorded in a per-workspace state file in the cache directory (`daemon-state.json`: last exit cause, restarts in the last ten minutes, current heap). A respawn waits a jittered backoff (1 s, 2 s, 4 s, cap 30 s) and is refused after `daemon.maxRestartsPerTenMinutes` (default 3), answering every request with the `daemon.heap` advice instead. When the previous exit was an `OutOfMemoryError`, the next start multiplies the heap by `daemon.heapGrowthFactor` (default 1.5) up to `daemon.maxHeap` (default 25% of physical memory, capped at 4g), keeps the grown value in the state file and says so in the log and in `daemon status`, `doctor` and `checkup`. At `maxHeap` the escalation stops and the guard above takes over | Exponential backoff with jitter and a restart budget; grow the resource once, within a ceiling the user set; make the state visible |
| G12 | **Relay and probe (P2, P4).** The relay connects before attaching stdin and retries `ensure_running` once; the probe tells refused, busy and incompatible apart; hooks (P3) return before resolving the engine and never cold-start it | Fail before taking input you cannot give back |
| G13 | **Bounded files on disk (P5 to P8).** `refresh_classpath` kills the process group on timeout, refuses a concurrent build and reads trust live; `output.maxBytes` enforced on every answer; the daemon log rotates at `daemon.logMaxBytes` (default 5 MB, three kept); `doctor` reports and `checkup --fix` sweeps old CDS archives, symbol stores of roots that no longer exist, `.bak.N` files and heap dumps; user scalars in `mcp.json` kept on repair | Everything that grows has a cap and an owner |
| G14 | **Stop gate (P9, P10).** `git status -z` and a session baseline; writes compare the content they read before replacing it; the spawned daemon is reaped | Correctness fixes with no new surface |

**Process: merge and release without a human in the loop**

| # | Change | Why |
|---|---|---|
| G15 | Repository setting **Allow auto-merge** on (one tick by the owner in Settings, General, Pull requests). Each PR is then marked to auto-merge (squash) when its checks pass; until the setting is on, PRs are merged through the API after CI is green | GitHub's native auto-merge waits for the required checks and merges with the author's identity, so release-please still fires |
| G16 | **A fine-grained token for release-please** (`RELEASE_PLEASE_TOKEN`: contents and pull requests, write, this repository only) used by `release-please.yml`, which then also enables auto-merge on the release PR it opens | Events raised by `GITHUB_TOKEN` do not start workflows and PRs it opens need manual approval of their runs; a token owned by a person removes both blocks, and auto-merge finishes the release PR when its CI is green |
| G17 | **The publish verified by the session that cut the release**: tag present, workflow run green, PyPI version live, the Homebrew tap updated by its daily workflow or by hand | A release is done when the package is installable, not when the tag exists |

### 5.3 Stages

| Stage | Scope | Done when |
|---|---|---|
| **18. Asynchronous guardrails** | G1, G2, G3, G7 (engine); G8, G9, G10, G12 (front end) | Specs: a cancel stops a compile within one round; a full heavy queue answers busy while hover still answers; a cancelled waiter leaves the compile running for the others; Python tests: two parallel tool calls on one session answer independently, a timed-out call sends the cancel and a repeat joins the in-flight one; CI green, merged, released |
| **19. Out-of-memory self-healing** | G4, G5, G6 (engine); G11, G13 (front end); G14 | Specs: the pressure guard drops caches and refuses a whole-workspace request (threshold injected); the size budget leaves a root out with one warning; Python tests with a fake runner: a crash loop stops after the budget, the heap grows once after an OOM exit and stops at `maxHeap`, the state file and `doctor` show it; CI green, merged, released |
| **20. Elicitation and progress** | Unchanged from the earlier Stage 19 | Unchanged |
| Process | G15, G16, G17 | Applied with the next release and recorded in `docs/ci.md` |

Order: 18 then 19, because a cancel that works (G1) is what makes a timeout (G9) and the pressure guard's
refusals (G4) cheap; the heap escalation (G11) is small and could be pulled forward if an out-of-memory exit is
seen in practice before Stage 19 starts. Every new value above lives in `.groovyscope.yaml` with a default in
`defaults.yaml` and a rule in `config-schema.json`; nothing is hardcoded.
