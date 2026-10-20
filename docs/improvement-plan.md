# groovyscope improvement plan

Audited: `SCGIS-Wales/groovyscope` main at 0.21.0 (archive of 18 October 2026)
Author: Dejan Gregor
Audience: Claude Code, working through this file phase by phase

> **Changes since the audit.** Work started at 0.24.1. Since 0.24.0, GS013 to GS016 are the test-double checks
> (stub on a private method, metaClass override bypassed by `@CompileStatic`, metaClass on a double, test without
> an assertion). The new checks Phase 5 planned under those numbers move to free codes: SQL by concatenation
> GS013 → **GS035**, `Sql.newInstance` without close GS014 → **GS036**, `return` inside `each` GS015 → **GS037**,
> mutable static state GS016 → **GS038**. Every other code keeps its number. Findings of the probes in Phase 0
> are recorded in section 13.

---

## 0. How Claude Code must use this document

1. Read `AGENTS.md`, then this file. Work on **one phase per branch** (`fix/phase-N-<slug>`), one PR per phase, Conventional Commit titles, squash merge. Never start phase N+1 until the phase N PR is green and merged.
2. Inside a phase, work through the items **in the order listed**. Every item names: where, what is wrong, the change, and the test that proves it. Write the failing test first, then the fix.
3. Line numbers are hints from the audited commit. Locate code by **class, method or function name**; if the code has moved, search for the quoted symbol.
4. Items tagged **[verified]** were read again in source by the reviewer. Items tagged **[reported]** come from the audit and must be confirmed by reading the code before changing it. If one turns out to be wrong, record that in the PR description and skip it.
5. Keep the project rules: functional Python with `TypedDict` only (exception classes allowed, document that in `AGENTS.md`), `mypy --strict`, ruff, 90% Python coverage, Java `-Xlint:all -Werror`, 85% JaCoCo, nothing hardcoded that belongs in `defaults.yaml`, mirrors in `_templates` kept in sync, no JARs or `.kiro/` committed.
6. At the end of every phase: run the full gates (`./gradlew build` in `engine/`, then `uv run ruff check .`, `uv run ruff format --check .`, `uv run mypy`, `uv run pytest --cov` in `python/`), update `docs/roadmap.md` and `CHANGELOG` notes, and tick the boxes in section 11 of this file.
7. Done for an item means: the test exists, it failed before the change, it passes after it, and the gates are green.

### Prompt to start each phase

```text
Read AGENTS.md and docs/improvement-plan.md. Implement Phase <N> only, items in order, on branch
fix/phase-<N>-<slug>. For each item: confirm the finding in code, write the failing test, fix, rerun the
gates. Items you cannot confirm: skip and explain in the PR body. Open the PR when the phase is complete.
```

---

## 1. Verdict

groovyscope has the right architecture and a broad capability set for assessing Groovy quality: CodeNarc 4 in process, Groovy 6 type checkers, 21 project specific practice checks, a confidence model on every semantic answer, structural search, clone detection, impact analysis, a merge request reviewer and solid self healing work. It is **not yet safe to run as an autonomous quality gate** or to apply fixes unattended. Four reasons:

1. **Security:** an untrusted repository can make groovyscope append text to any user file (`logging.file`), and the Kiro agent auto approves `groovyscope .*` and `./gradlew .*`, so a prompt injection can self grant trust and run build scripts.
2. **Fix safety:** the bulk `fix` applies changes that alter behaviour, and the `UnnecessaryGString` quick fix rewrites the wrong span and silently changes string values. The `@CompileStatic` verdict tests only one class per file.
3. **Fidelity:** Jenkins shared library `src/` code is linted with the default profile (and offered `@CompileStatic`), CodeNarc `enhanced` and `jenkins.xml` rules probably run without a classpath, and implicit `this` calls inside closures are labelled `STATIC` when dispatch is really to the delegate.
4. **Reproducibility:** every build, including the PyPI release, embeds whichever CodeNarc is newest on Maven Central; nothing requires CI before a release merges; `uv.lock` says 0.12.0 while the package is 0.21.0.

Concurrency and resilience are much improved since the 18 October review (P1 to P8 and E1 to E5 are mostly fixed), but the new two lane executor is bypassed in practice and several channel and restart paths still wedge.

### Capability scorecard

| Area | Present | Quality | Main gap |
|---|---|---|---|
| CodeNarc 4 defensive profile | yes | strong | `junit.xml` missing, no Gradle profile, no unknown rule pruning |
| CodeNarc enhanced and Jenkins CPS rules | nominal | weak | no workspace classpath reaches CodeNarc |
| Groovy 6 type checkers | yes | partial | only literal `@CompileStatic`/`@TypeChecked`; config scripts and aliases ignored |
| Practice checks GS001 to GS012 | yes | partial | precise detection, unsafe fixes |
| Naming checks GS020 to GS027 | yes | partial | noisy for properties and idiomatic abbreviations |
| Safe fix pipeline | yes | weak | parse only guard, behaviour changing fixes applied by default |
| Formatter (`basic`) | yes | weak | can change multiline string contents; whitespace only |
| Structural search, duplicates | yes | strong / partial | `find_code` silent false negatives on property receivers and GString parts |
| Call graph, impact, explain | yes | partial | closure delegates, extension methods, overload ambiguity |
| Jenkins shared library quality | partial | weak | `src/` misrouted; no CPS, serialisation or credential checks |
| Gradle build script quality | no | missing | default profile floods `def` findings |
| Spock quality | GS011 only | weak | no interaction, data table or naming checks |
| Secrets, dependency risk, licence headers | minimal | weak | no hardcoded credential check |
| Daemon resilience | yes | partial | bulkhead bypassed, channel corruption, heap override |
| Supply chain and release | yes | partial | live CodeNarc resolution, no required checks |

---

## 2. Which Claude model to use

Model aliases as documented for Claude Code (`/model`, `/effort`): `fable` (Fable 5.1, hardest and longest autonomous tasks, bills usage credits), `opus` (Opus 5.5, complex reasoning, the default on the Anthropic API), `sonnet` (Sonnet 5.5, daily coding with clear scope), `haiku` (Haiku 5.5, fast simple tasks), `opusplan` (Opus plans, Sonnet executes).

| Phase | Model | Effort | Why |
|---|---|---|---|
| 0 Baseline and probes | `sonnet` | medium | Running builds, unzipping a JAR, adding guard tests |
| 1 Security and fix safety | `opus` | high | Security reasoning and behaviour preserving AST edits; mistakes here reach users' files |
| 2 Concurrency and resilience | `opus` (or `fable` when credits allow) | xhigh | Cross thread and cross process races, lock ordering, LSP4J lifecycle; the hardest phase |
| 3 Release and supply chain | `sonnet` | high | Well specified workflow and Gradle changes |
| 4 Analysis fidelity | `opus` | high | Groovy semantics: delegates, categories, CPS, type checker wiring |
| 5 New quality checks | `opusplan` | high | Opus designs each check and its false positive tests; Sonnet writes the visitors |
| 6 Hygiene and deduplication | `sonnet` | medium | Mechanical refactors guarded by existing tests |
| 7 Docs, Kiro assets, polish | `haiku` for docs counts, `sonnet` for the rest | low to medium | Text and template edits |

Rules of thumb: switch to `opus` with `/effort xhigh` for any item that touches locks, sockets or class loaders, even inside a Sonnet phase. Use a fresh subagent on `opus` to review each Phase 1 and Phase 2 PR before merge, so the work is not graded by the session that wrote it.

---

## 3. Phase 0: baseline and probes (no behaviour change)

Goal: turn the four "could not verify" questions into facts before any fix depends on them.

| ID | Task | Acceptance |
|---|---|---|
| 0.1 | Build the engine and run all gates on main; record timings and coverage in the PR body | Gates green, numbers recorded |
| 0.2 | Inspect the shaded JAR: `unzip -p engine/build/libs/groovyscope-engine-*-all.jar META-INF/groovy/org.codehaus.groovy.runtime.ExtensionModule` and `META-INF/services/*`. Record whether every Groovy module (nio, dateutil, datetime, json, xml, sql, typecheckers, contracts) is listed | Finding D-02 confirmed or rejected |
| 0.3 | Run `TypeCheckAnalyzerSpec` strict NullChecker feature and record whether `NullChecker(strict: true)` loads on Groovy 6.0.0 | Q-17 resolved; fix the roadmap text accordingly |
| 0.4 | Spike test: does LSP4J cancel inbound request futures when the socket closes? Small Spock spec with a blocking request and a closed stream | E-15 confirmed or rejected |
| 0.5 | Spike test: does a CodeNarc enhanced rule (for example `CloneWithoutCloneable`) or a `jenkins.xml` rule fire on a fixture today | Q-05 confirmed or rejected |

---

## 4. Phase 1: security and fix safety (Critical and High)

Branch `fix/phase-1-security-fix-safety`. Model `opus`, effort high.

### 1.1 Untrusted `logging.file` writes anywhere [verified] (E-10, P-15) Critical
- **Where:** `python/src/groovyscope/trust.py` `GATED_KEYS`; `diagnostics.py` `configure`; engine `log/EngineLog.java` (constructor resolving `logFile`, `log` appending with `Files.writeString(... CREATE, APPEND)`); `Main.configureLogging`.
- **Problem:** a repository `.groovyscope.yaml` can set `logging.file: ~/.bashrc`; log lines include workspace controlled file names, so a file name with a newline injects lines into a startup file. No trust step required.
- **Change:**
  - Add `("logging", "file")`, `("jdk", "autoProvision")`, `("jdk", "vendor")`, `("daemon", "heapDumpOnOutOfMemory")` to `GATED_KEYS`; cap `daemon.heap` and `daemon.maxHeap` for untrusted workspaces at a configured ceiling.
  - Confine `reports.sarif` and `reports.jacoco` to the workspace root unless trusted.
  - Engine: accept a log path only under the cache or log directory, open with `NOFOLLOW_LINKS`, create with `0600`, keep one open stream instead of reopening per line, rotate by `daemon.logMaxBytes`.
  - Escape control characters (`\r`, `\n`, other C0) in every log record in both Python and Java.
  - Python: if the handler cannot be created, warn once and fall back to stderr instead of a traceback.
- **Tests:** untrusted workspace with `logging.file` outside the cache is ignored with a warning; a file named `a\nb.groovy` produces one escaped log line; symlinked log path refused; unwritable path does not crash `lsp` or `mcp`.

### 1.2 Kiro agent can grant itself trust [verified] (D-13) High
- **Where:** `kiro/agents/groovy.json` and its `_templates` mirror, `toolsSettings.shell.allowedCommands`; `kiro_agent.py` generator; `trust` command in `cli.py`; steering `groovyscope.md`.
- **Problem:** `groovyscope .*`, `\./gradlew .*` and `git diff.*` are auto approved. A prompt injection in repository text can run `groovyscope trust` then `groovyscope classpath` (executes build scripts), any Gradle task, or `git diff --output=<path>`.
- **Change:**
  - Auto approve only read only subcommands: `groovyscope (check|review|explain|naming|coverage|impact|callgraph|explain-code|graph --format (mermaid|json)|version|doctor|checkup|daemon status|logs)( .*)?`.
  - Remove `trust`, `classpath`, `jdk`, `fetch`, `kiro`, `profile install`, `fix --apply`, `format --apply` from auto approval.
  - Gradle: `\./gradlew (test|check|jacocoTestReport)( --[a-z-]+)*` only. Git: `git status`, `git diff` without `--output` and without `--ext-diff`.
  - `groovyscope trust` refuses when stdin is not a TTY or when a Kiro hook or agent environment variable is set, unless `--yes-i-am-the-user` is passed interactively. Document it.
  - Generate the allow list from `tools_catalog` and a new `SAFE_SHELL_COMMANDS` constant so the template cannot drift.
- **Tests:** `test_kiro.py` asserts no allowed pattern matches `groovyscope trust`, `groovyscope classpath`, `./gradlew foo`, `git diff --output=x`; `trust` without a TTY exits non zero.

### 1.3 `UnnecessaryGString` fix corrupts strings [verified] (Q-01) Critical
- **Where:** `engine/.../practices/PracticeFixes.java` `DOUBLE_QUOTED` and `singleQuotes`.
- **Problem:** the regex runs on the whole line and takes the first match. On `def s = "$a" + "b"` it matches `" + "` and produces `def s = "$a' + 'b"`, which still parses, so the guard in `QualityServices` accepts it.
- **Change:** take the exact range of the flagged `ConstantExpression` from the AST (line and column from the CodeNarc violation, matched to the node), requote only that node, and escape any `'` or `\` inside it.
- **Tests:** mixed GString and String on one line; two plain strings on one line; string containing an apostrophe; the AST string values before and after the fix are equal.

### 1.4 Bulk `fix` applies behaviour changing fixes (Q-06) High [reported]
- **Where:** `practices/Practice*` model, `practices.json` (all entries have `fix: true`), `QualityServices` fix path, `PracticeFinder` GS001, GS002, GS004, GS007.
- **Problem:** GS001 turns `hits / total` into `intdiv` (0.25 becomes 0); GS002 changes behaviour for `''`; GS004 picks one of two intents (since Groovy 5 `if (file)` means exists); GS007 removes `@Grab` and leaves uncompilable code.
- **Change:**
  - Add `safety: preserving | changing` to each catalogue entry and to the engine `Practice` record; validate in `test_practices_catalog.py`.
  - Bulk `fix` and `source.fixAll.groovyscope` apply only `preserving`. `changing` fixes run only when the code is named explicitly (`--code GS001`) and are listed as "needs review" otherwise.
  - GS001 fires only when the result is not narrowed to an integral type; cover `/=`.
  - GS004 offers two code actions: `!= null` and `.exists()`.
  - GS007 offers no automatic fix; the message names the build file dependency to add.
- **Tests:** bulk fix on a fixture containing every changing fix leaves it unchanged and reports them; explicit `--code` applies them.

### 1.5 `@CompileStatic` verdict tests one class per file (Q-03) High [verified]
- **Where:** `QualityServices` method building `candidates` (loop `candidates.put(uri, PracticeFixes.apply(text, ...))`), `TypeCheckAnalyzer.analyze`.
- **Problem:** with two classes in a file, each iteration overwrites the previous trial, so only the last class is checked, yet the verdict applies to all. Files skipped (unresolved imports, deadline, crash) return an empty list and count as passing.
- **Change:** accumulate every class annotation edit into one trial text per URI; make `analyze` return a per URI status (`CHECKED`, `SKIPPED`, `TIMED_OUT`, `CRASHED`) with diagnostics; choose `@CompileStatic` only for `CHECKED` with no errors. Run the verdict with core STC only (no strict NullChecker), and when it fails insert `@CompileDynamic` with `// TODO justify` and report the class as "left" (Q-18).
- **Tests:** a file with one class that type checks and one that does not yields `@CompileStatic` on the first only; a skipped file yields no annotation.

### 1.6 `GStringAsMapKey` fix produces a syntax error (Q-08) Medium [reported]
- **Where:** `PracticeFixes.mapKeyToString`.
- **Change:** emit `("${k}".toString()): v`. Add a spec that compiles the result.

### 1.7 GS005 wrong fix for JUL and commons logging (Q-02) High [reported]
- **Where:** `PracticeFinder` `LOGGER_TYPES` and the GS005 check.
- **Change:** offer the placeholder fix only for SLF4J and Log4j2 types proven from the declared type or from `@Slf4j`/`@Log4j2`; drop name only matching (`log`, `logger`); with a `@Slf4j` family annotation lower severity to info and change the message to the real concerns (secrets in interpolation, consistency), since the AST transform already guards the level.
- **Tests:** `@Log` (JUL) gets no fix; `@Slf4j` gets info; a plain `def log` gets nothing.

### 1.8 Formatter changes string contents (Q-19) High [reported]
- **Where:** `format/BasicFormatter.java` (`togglesTripleQuote`, line ending handling).
- **Problem:** does not distinguish `'''` from `"""`, ignores slashy and dollar slashy strings, toggles on `/* """ */`, and rewrites CRLF inside strings.
- **Change:** mark string and comment spans with the Groovy lexer (`GroovyLangLexer` tokens) and never edit inside them; keep the file's line endings.
- **Tests:** property based idempotence (`format(format(x)) == format(x)`); AST constant values identical before and after on fixtures with each multiline string kind and CRLF files.

### 1.9 One daemon serves any root with one trust decision (E-20) Medium [reported]
- **Where:** engine `GroovyscopeLanguageServer.initialize` and `requireServices`, `WorkspaceRegistry`.
- **Change:** pass `--root` to the daemon and refuse any other root in `initialize` and in the `root` request parameter (error naming both roots); or hold config and trust per root. Prefer the refusal: the Python side already uses one socket per root.
- **Tests:** a second root on the same daemon is refused.

### 1.10 Path confinement leftovers (E-21) Medium [reported]
- **Where:** `WorkspaceModel` (open documents added to compile sources; `sourceRoots` resolved with `normalize()`; `isExcluded`).
- **Change:** filter open documents and source roots with `contains()`; refuse non `file:` schemes; log once what was refused.
- **Tests:** `sourceRoots: ["../.."]` is refused; an open document outside the root is not compiled.

### 1.11 Shaded JAR merging (D-02) High, only if 0.2 confirmed it
- **Where:** `engine/build.gradle.kts` `tasks.shadowJar`.
- **Change:**
  ```kotlin
  tasks.shadowJar {
      duplicatesStrategy = DuplicatesStrategy.INCLUDE
      mergeServiceFiles()
      mergeGroovyExtensionModules()
      filesNotMatching(listOf("META-INF/services/**", "META-INF/groovy/**")) {
          duplicatesStrategy = DuplicatesStrategy.EXCLUDE
      }
      exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.EC")
  }
  ```
  Then remove the manual ELK provider registration in `graph/DiagramLayout` if ServiceLoader now finds it.
- **Tests:** a Python integration test through the shaded JAR type checks `Path.text` and a `LocalDate` extension under `@CompileStatic` with no errors.

**Phase 1 exit:** all of the above merged; an `opus` reviewer subagent has read the diff without the author's notes; a release is cut.

---

## 5. Phase 2: concurrency and resilience

Branch `fix/phase-2-resilience`. Model `opus` at xhigh, or `fable`. Write each race as a deterministic test (latches, injected clocks), never `sleep`.

### Engine

| ID | Severity | Where | Change | Test |
|---|---|---|---|---|
| E-11 | High | `GroovyscopeTextDocumentService.hover` (`fullHover` runs CodeNarc and practices), `LanguageServices.snapshot` compile ownership, `references`, `DiagnosticsPublisher` | Light requests answer from the current snapshot and never own a compile (stale while revalidating); compile runs on a dedicated compile executor; hover serves lint notes from cache only; `references` and rename move to the heavy lane with `Cancellation.checkpoint()` in the loop; diagnostics publishing goes through the heavy lane | Edit then two hovers: both answer within 200 ms while a compile runs |
| E-12 | High | `QualityServices` type check cache (`typeCache` check then act) | Per version `CompletableFuture` single flight like `CompileJob`; type check `current.result().sources()` not fresh sources; call `MemoryGuard.requireHeadroom()` first | Three parallel `check` calls run one type check |
| E-13 | Medium | `LanguageServices` background `refresh()` | Background task calls `snapshot()`; `refresh()` returns early when not dirty; replace the monitor with `ReentrantLock.lockInterruptibly` | Background plus foreground request compiles once; a waiter can be cancelled |
| E-14 | Medium | `DaemonServer` idle exit, `RequestExecutors.shutdown` | Stop accepting, wait the grace period, then shut down executors; when shut down, answer a distinct "daemon is exiting" error code that Python treats as reconnect | Client accepted during grace is served |
| E-15 | Medium | `RequestExecutors.submit`, `connectionClosed` | Track futures per connection; cancel them all on close (if 0.4 showed LSP4J does not) | Closing the client cancels a running type check |
| E-16 | Medium | `GroovyCompiler.compile`, `compileOnce`, `scopeVariables`, `finishSemanticAnalysis` | Wrap every loader in `LoaderHandle` at creation; hold attempts in a list closed in `finally` unless ownership transfers; catch `StackOverflowError` in visitor helpers | Cancel during the parse only pass closes the loader (spy) |
| E-17 | Medium | `TypeCheckAnalyzer`, `AstIndex`, `GroovyCompiler` crash path | One `Containment.recoverable(Throwable)` helper (RuntimeException, LinkageError, AssertionError, GroovyBugError, StackOverflowError) used by every analyser; on a whole unit crash bisect sources to isolate the culprit | 5,000 deep nested expression file: one diagnostic, other files analysed |
| E-18 | Medium | `MemoryGuard` `onPressure`, `LanguageServices.shed` | Shed without taking the services lock (fields are volatile) or hand off to an executor; consult `MemoryGuard.pressure()` before any compile or type check | Shed during a compile returns immediately |
| E-19 | Medium | `WorkspaceRegistry` eviction, `LanguageServices.close` | Reference count connections per root; evict via `computeIfPresent` at zero; a `closed` flag makes `snapshot()` fail fast; drain `background` before closing SQLite | Eviction racing `apply()` never uses a closed store |
| E-22 | Low | `syncWithDisk` on every request, `diskFingerprint` | Rate limit the fingerprint (configurable `daemon.diskCheckMillis`), use a 64 bit hash, one walk per refresh | Fingerprint called once per window |
| E-23 | Low | `SymbolStore` | Hash once per URI, upsert changed URIs only, `PRAGMA journal_mode=WAL` and `busy_timeout` | Second JVM on same store does not log SQLITE_BUSY |
| E-24 | Low | `LanguageServices` rename | Compute prepare and rename from one snapshot | Edit between steps gives a clean error |
| E-25 | Low | `DaemonServer` socket ownership via `user.name` | Compare against the owner of a file the process just created (or `unix:uid`) | Arbitrary UID container starts |
| E-28 | Low | `Main` | Warn and report `oomExit: false` in `status` when `-XX:+ExitOnOutOfMemoryError` is absent | `doctor` shows it |
| E-27b | Low | `GroovyCompiler` static initialiser sets `groovy.grape.enable` and `groovy.asttest.enable` | Set them first thing in `Main` (and keep the static block as a second line of defence) | Spec asserts both properties before any compile |

### Python front end

| ID | Severity | Where | Change | Test |
|---|---|---|---|---|
| P-11 [verified] | High | `mcp_server.py` `dispatch` calls `apply_configuration()` outside the reconnecting `try`, under `session_lock` | Move it inside the `try`; treat `OSError` as reconnect then apply; send the reload outside the lock with compare and swap on `state["applied"]` | Config push on a dead channel reconnects; other calls not blocked by a 60 s reload |
| P-12 [verified] | High | `selfheal.py` `plan_restart` (`current = state["heap"] or configured_heap`) | When `state["configured"] != configured_heap` drop the grown heap; use the grown heap only when larger than the configured one | Grow to 1536m, set 8g, next start uses `-Xmx8g` |
| P-13 [verified] | High | `rpc.py` channel: `CHANNEL_POLL_SECONDS = 0.5` set by `settimeout` and shared by `sendall` | Write under the write lock with a separate long timeout (or a writer thread); on any partial write close the channel; raise `ChannelBrokenError(ConnectionError)` so the dispatcher reconnects | 1 MB frame to a slow reader is delivered or the channel is rebuilt; no desync |
| P-14 | Medium/High | `cli.py` puts the whole engine config into an environment variable | Write the engine view to a `0600` file in the cache (atomic) and pass `--config-file`; keep only the hash in the environment; stop mutating `os.environ` from MCP threads | 2,000 entry classpath starts the daemon on Linux |
| P-16 | Medium | `cli.py` falls back to a direct JVM after a crash loop refusal | Dedicated `CrashLoopRefused`; never fall back on it; apply `plan["heap"]` to the direct command; carry the reason to the agent | Refusal yields the reason, no JVM started |
| P-17 | Medium | `refresh_classpath`, `classpath.DEFAULT_TIMEOUT_SECONDS = 900` | Add `classpath.timeoutSeconds` to `defaults.yaml` and schema; MCP runs the export in the background and returns "started"/"still running" within `mcp.requestTimeoutSeconds`; status in `workspace_status`; cap the agent's timeout | Tool returns within budget while export continues |
| P-18 | Medium | `mcp_server.py` gate and leader timeouts, one thread per call | One deadline at entry shared by gate and engine; bounded `ThreadPoolExecutor`; schema `minimum: 1`, `maximum: 115` on `mcp.requestTimeoutSeconds` | Leader never exceeds budget |
| P-19 | Medium | `hook_command`, subprocess text decoding in `changes.py`, `java.py`, `kiro_agent.py`, `checks.py`, `classpath.py` | Wrap the hook body in one catch all that logs and exits 0; one shared runner with `encoding="utf-8", errors="surrogateescape"` | Broken YAML and a non UTF-8 file name do not fail the hook |
| P-20 | Medium | `provision.py` concurrent JDK installs | `fcntl` lock on `install_dir/.lock`; recheck after acquiring; never `rmtree` an existing release | Two concurrent installs, one download |
| P-21 | Medium | umask toggled in `kiro.py` and `classpath.py` | Read umask once at import, or create temp files with `os.open(..., O_CREAT|O_EXCL, 0o666)` | No `os.umask(0)` left in `src/` (grep test) |
| P-22 | Low/Medium | `daemon.start_lock` without deadline; fixed temp names in `selfheal.py`, `fetch.py`, `trust.py` | Non blocking lock loop bounded by the start budget; take the lock in `stop`; unique temp names | Two writers never lose the state file |
| P-23 | Low/Medium | OOM detection by substring in `selfheal.py` and `daemon.py` | Grow the heap only on `Terminating due to java.lang.OutOfMemoryError: Java heap space`; other OOM kinds get their own advice; one `exit_cause` used by both | Metaspace OOM does not grow `-Xmx` |
| P-24 | Low | `rpc.split_frame` accepts non object JSON; per `recv` timeout | Raise `ValueError` for non dict bodies; total deadline in `request` | Array frame does not kill the reader thread |
| P-25 | Low | `relay.py` no back pressure, `print` | `await writer.drain()`; log through `groovyscope.relay` | Slow consumer keeps buffer bounded |
| P-27 | Low | `groovyscope classpath` stops a healthy daemon when nothing changed | Tri state result `unchanged | reloaded | failed`; stop only on `failed` | Unchanged export keeps the daemon pid |
| P9/P10 rest | Low | Stop gate has no session baseline; daemon `Popen` never reaped; `edits.py` replaces symlinks, TOCTOU | Record a baseline of dirty files at session start and gate only on new ones; keep and `poll()` the `Popen`; write to `path.resolve()` and compare `st_mtime_ns` just before `os.replace` | One test each |

**Phase 2 exit:** an `opus` reviewer subagent reviews the diff for lock ordering and leaks; the performance spec shows hover latency during a compile under the target.

---

## 6. Phase 3: release, build and supply chain

Branch `fix/phase-3-supply-chain`. Model `sonnet`, effort high.

| ID | Severity | Where | Change |
|---|---|---|---|
| D-01 | High | `engine/settings.gradle.kts` `LatestStableCodenarc`, `libs.versions.toml` | Pin `codenarc` in the catalogue and let Dependabot bump it; make the resolver opt in (`-PcodenarcResolve=true`) used only by a scheduled canary workflow that opens a PR; update `AGENTS.md` (which currently forbids pinning) and `docs/security.md` |
| Q-15 | Medium | `CodeNarcAnalyzer` rule set loading | Prefilter configured rule names against CodeNarc's `RuleRegistry`; unknown names become one `lint-config` warning, never an `IllegalStateException` that fails every lint |
| D-03 | High | repository settings, `workflow.yml` | Ruleset on `main`: PR required, linear history, required checks (both OS builds, Python matrix, CodeQL both languages, PR title). First job in `workflow.yml` fails unless the tagged commit's checks succeeded. Document the settings the owner must click in `docs/ci.md` |
| D-04 | Medium | `workflow.yml` `workflow_dispatch` | Validate `tag` with `^v\d+\.\d+\.\d+$` and `git rev-parse --verify refs/tags/$TAG`; assert tag equals pyproject, `gradle.properties` and JAR name; remove the unused `workflow_call`; protect the `pypi` environment to `v*` tags |
| D-05 | Medium | `workflow.yml` | `concurrency: publish-${{ inputs.tag }}`; `release-assets` needs `pypi`; drop `--clobber`; attest `SHA256SUMS.txt` and both SBOMs |
| D-06 [verified] | Medium | `python/uv.lock` (records 0.12.0), `release-please-config.json`, `scripts/bundle_jar.py` | Add `uv lock --check` to CI; update the lock in the release PR; `bundle_jar.py` fails when JAR version, `engine-version.properties` and pyproject disagree, and picks the JAR by version not mtime |
| D-07 | Medium | `ci.yml` | Run the Python integration tests against the built JAR on JDK 17, 21 and 25 (Temurin and Corretto 21); otherwise change the documented floor |
| D-08 | Medium | workflows | Gradle dependency submission on main; `dependency-review-action` on PRs; CodeQL `actions` language; OpenSSF Scorecard with SARIF; zizmor; review whether `groovy-ant` and `groovy-templates` are needed at runtime |
| D-09 | Medium | `engine/` | `dependencyLocking { lockAllConfigurations() }` with lockfiles; `--write-verification-metadata sha256`; move JaCoCo and `junit-platform-launcher` versions into the catalogue; delete unused Spotless entries |
| D-10 | Medium | `python/NOTICE`, pyproject `license`, shadow | Make `python/NOTICE` a test enforced copy of the root NOTICE; `license = "Apache-2.0 AND EPL-2.0"` plus others found; Apache NOTICE and licence transformers or a generated third party report in `license-files` |
| D-11 | Medium | `release-please.yml` `RELEASE_PLEASE_TOKEN` PAT | Replace with a GitHub App token via `actions/create-github-app-token` (pinned SHA) |
| D-12 | Medium | `CODEOWNERS` (`* @api-py`) | Point at the current owner, move to `.github/CODEOWNERS`, require code owner review |
| D-22, P-31 | Low | `ci.yml` and pyproject | `timeout-minutes` on every job; delete the dead "Upload distributions" step; ruff and mypy over `scripts/`; `GROOVYSCOPE_REQUIRE_ENGINE=1` in CI turns integration skips into failures; run integration tests on every Python in the matrix; remove `sleep` based timing in `test_interaction.py` and `test_relay.py` |
| D-23 | Low | `scripts/homebrew_formula.py` | Generate `resource` blocks or install from a hashed requirements export with `--require-hashes` |
| D-20 | Low | `scripts/bundle_benchmark.py`, wheel | Licence files mandatory (raise if missing); consider fetching the corpus on demand verified by the manifest SHA |

**Phase 3 exit:** two builds of the same tag produce byte identical JARs, or the differences are listed and explained.

---

## 7. Phase 4: analysis fidelity

Branch `fix/phase-4-fidelity`. Model `opus`, effort high. Add a fixture per item under `engine/src/test/resources/projects`.

| ID | Severity | Where | Change | Test fixture |
|---|---|---|---|---|
| Q-04 [verified] | High | `QualityServices.profileFor` (only `Jenkinsfile*` and `/vars/` are Jenkins), `WorkspaceModel` source roots | Detect a shared library (`vars/` beside `src/` or `resources/`), apply the Jenkins profile to its `src/**`, never offer `@CompileStatic` there; add `src` and the root `Jenkinsfile` to discovery when `vars/` exists | `jenkins-lib/src/org/acme/Deployer.groovy` gets the Jenkins profile and no CompileStatic finding |
| Q-05 | High | `CodeNarcAnalyzer` uses a plain `StringSourceAnalyzer` with no class loader | Give it a `GroovyClassLoader` over `workspace.classpath()` set as the context class loader around `analyze` (mirroring `TypeCheckAnalyzer`), closed afterwards | One enhanced rule and one `jenkins.xml` rule fire on fixtures |
| Q-09 | High | `semantics/Resolver` implicit this receiver inside closures | Inside a `ClosureExpression`, downgrade implicit this calls to `DYNAMIC_UNKNOWN` unless the closure parameter carries a resolvable `@DelegatesTo`, then use that type as `INFERRED` | Enclosing method shadowing a delegate method; rename refuses |
| Q-10 | Medium | `Resolver` returns null for classpath receivers without the method | Look up workspace static methods whose first parameter accepts the receiver (extension modules, `@Category`) and emit `INFERRED` or `DYNAMIC_UNKNOWN` | Extension method has callers in impact |
| Q-11 | Medium | `Resolver`, `CallGraph.overridesOf`, `ImpactAnalyzer` | Add `AMBIGUOUS` confidence (or count multiple candidates as dynamic); compare erased parameter types for overrides | `save(String)` and `save(Order)` are not each other's callers |
| Q-12 | Medium | `ImpactAnalyzer` drops transitive dynamic edges, "no test" advice | Collect dynamic transitive sites; dynamic sites in test files are "possible tests"; suppress "no test" when present | Spock spec with `def` receiver counts as possible test |
| Q-13 | Medium | `semantics/DynamicCodeFinder` (returns early on non constant names; dead `else if`) | Report `obj."$m"()` as `dynamic-method-name`; add `use(Category)`, `Expando`, `@Mixin`, `@Delegate`, `GroovyInterceptable`, `ExpandoMetaClass.enableGlobally()`, `Class.forName(x)`; delete the dead branch | Each construct reported |
| Q-14 | Medium | `search/CodeSearch` private `receiverType`, `visitGStringExpression` | Reuse `Resolver.receiverOf`; emit `receiverKnown: false` matches when a type filter is set; search the literal parts of GStrings | `def sql = Sql.newInstance(..); sql.rows(..)` found; `"SELECT * FROM ${t}"` found by `SELECT` |
| Q-16 | Medium | `TypeCheckExtensionCustomizer`, `TypeCheckAnalyzer` | Add `typecheck.configScript` (default the project's `config/groovyc/compile-static.groovy` when present) applied in `compileOnce`; resolve import aliases and `@AnnotationCollector`; `check` reports "N classes dynamic, not type checked" | Config script project gets type checked |
| Q-17 | Medium | `EngineConfig` NullChecker mode | Validate `off | lenient | strict` as an enum (schema and Java); correct the roadmap per 0.3 | `Strict ` with a space is rejected |
| Q-21 | Medium | `config/codenarc/*.groovy` | Add `rulesets/junit.xml` with Spock aware exclusions; add `size.xml` to the Jenkins profile; add a Gradle profile for `*.gradle` without the `*TypeRequired` and `NoDef` rules; route `.gradle` files to it | Gradle fixture produces no `def` noise |
| Q-20 | Medium | profile and practices | Reenable `ImplementationAsType` at priority 3; GS017 and GS029 move to Phase 5 | |
| Q-27 | Low | `CodeNarcAnalyzer` `lint.rules` | Unknown rule names surface as a `lint-config` diagnostic in `check`; support `{Rule: on}` to enable an excluded rule | Typo appears in `check` output |
| Q-22, Q-23, Q-24, Q-25 | Low | `NamingFinder`, `PracticeFinder`, `practices.json` | GS020 skips properties (avoids `isIsActive`); GS002 info for CharSequence and collections; move `dir`, `impl`, `init`, `sub`, `prev`, `doc`, `org`, `lib` to allowed abbreviations; GS003 drops `boolean` and covers return positions; reword GS011 rationale | Negative fixtures for each |

---

## 8. Phase 5: new quality checks

Branch `feat/phase-5-checks`, split into PRs per group if large. Model `opusplan`. Each check needs: an entry in `config/practices/practices.json` (with `safety`), the engine resource mirror, positive and negative fixtures, a `explain` text, and docs in `docs/practices.md`. Detection must report only what is proven from types; anything inferred from names is info level.

| Priority | Code | Check | Home |
|---|---|---|---|
| High | GS035 (was GS013) | SQL text built by concatenation, `.toString()` of a GString, or a String variable assigned from a GString, passed to a `groovy.sql.Sql` query method; extend GS012 to `'%${x}%'` | `PracticeFinder` SQL check |
| High | GS019 | Hardcoded secret: literal assigned to password, secret, token or apiKey names; AWS, GitHub, JWT and private key patterns; configurable allow list in `defaults.yaml` | new `practices/SecretFinder` |
| High | GS030 | Jenkins: credential or `env` secret interpolated into a `sh`, `bat` or `powershell` GString (use single quotes and environment variables) | new `practices/JenkinsFinder` |
| High | GS031 | Jenkins: `@NonCPS` method calling a pipeline step (AST fallback when no Jenkins classpath) | `JenkinsFinder` |
| High | GS032 | Jenkins: non serialisable local (`Matcher`, `Iterator`, `LazyMap`) live across a step | `JenkinsFinder` |
| High | GS038 (was GS016) | Mutable static state; high severity in Jenkins libraries | `PracticeFinder` and `JenkinsFinder` |
| Medium | GS036 (was GS014) | `Sql.newInstance` without `close` or `withInstance` | `PracticeFinder` |
| Medium | GS033 | `@CompileStatic` or `new File(...)` in a Jenkins shared library | `JenkinsFinder` |
| Medium | GS037 (was GS015) | `return` inside `each` or `eachWithIndex` (acts as continue) | `PracticeFinder` |
| Medium | GS018 | GString used in `contains`, `equals`, `indexOf` or a `Set<String>` or `Map<String, ?>` lookup | `PracticeFinder` |
| Medium | GS028 | Numeric truthiness in conditions (0 is false) | `PracticeFinder` |
| Medium | GS017 | Public `Closure` parameter without `@DelegatesTo` or `@ClosureParams` | `PracticeFinder` |
| Medium | GS040 to GS043 | Spock: interaction in `then:` without cardinality; mock created in `then:`; unused or duplicate data table column; redundant `@Unroll`; `thrown()` not last | new `practices/SpockFinder` |
| Medium | GS050 to GS052 | Gradle: eager task API (`task x`, `tasks.create`); dynamic versions (`+`, `latest.*`); `http://` repositories or `mavenLocal()` | new `practices/GradleFinder` |
| Low | GS029 | Multi line closure using implicit `it` | `PracticeFinder` |
| Low | GS034 | Record or `@Immutable` with a mutable component not defensively copied | `PracticeFinder` |
| Low | none | Licence header via CodeNarc `RequiredRegex`, off by default, configured in `defaults.yaml` | CodeNarc profile |
| Low | none | Document OWASP Dependency Check or Gradle verification SARIF in `reports.sarif` | `docs/ci.md` |

Build the shared context first (Phase 6 item 6.3, `PracticeContext`) if it is not done, so the new finders reuse one visitor pass.

**Phase 5 exit:** the CodeNarc 4.0.0 benchmark corpus and the `jenkins-lib` fixture run with the new checks; record findings per code and the false positive rate from a manual sample of 20 in `docs/benchmark.md`.

---

## 9. Phase 6: code hygiene and deduplication

Branch `refactor/phase-6-hygiene`. Model `sonnet`, effort medium. No behaviour change: existing tests must pass unchanged, and coverage must not drop.

### 6.1 Split god classes (E-26)
| Class (LOC) | Split into |
|---|---|
| `lsp/GroovyscopeLanguageServer` (1,127) | `StatusRequests`, `QualityRequests` (check, fix, format), `ExplainGraphRequests`, `SearchRequests`, plus a `RequestParams` helper (`text`, `flag`, `number`, `stringList`, `targets`) |
| `services/QualityServices` (914) | `LintService`, `TypeCheckService` (owns the Phase 2 single flight), `FixService`, `ProfileService` |
| `services/LanguageServices` (897) | `SnapshotManager` (refresh, single flight, loader ownership, persistence) and `NavigationQueries` |
| `python/src/groovyscope/cli.py` (2,017) | package `cli/` with `daemon.py`, `kiro.py`, `quality.py`, `graph.py`, `checks.py` registering on one `main` group; move `if __name__ == "__main__"` to `__main__.py` (today it sits before the `checks` group) |
| `mcp_server.py` (1,548) `*_tools` closures (up to 152 lines) | one module level function per tool with `functools.partial` for `root`, `call`, `cap`; the CLI reuses them so logic lives once |

### 6.2 Remove duplication
| Logic | Today | Single home |
|---|---|---|
| Atomic write | `kiro.py`, `classpath.py`, `edits.py`, `fetch.py`, `selfheal.py`, `trust.py` | `fsutil.write_atomically(path, data, *, mode=None)` with unique temp, umask once, optional `fsync` |
| Subprocess runner | `changes.py`, `java.py`, `kiro_agent.py`, `checks.py`, `classpath.py` | `proc.run(cmd, cwd, env, timeout)` with UTF-8 and `surrogateescape` |
| Fix and format result shaping | `cli.py` and `mcp_server.py` | shared tool function, CLI only renders |
| `write_if_unchanged` | `cli.py`, `mcp_server.py` | `edits.py` |
| URI and path helpers, input path guard | seven places; `cli.to_uris` unguarded | one `paths.py` (rename `mcp_server_support.py`), guarded everywhere |
| `RELOAD_METHOD`, `SEVERITY_NAMES`, `OOM_MARKER`, environment variable names, Groovy suffixes | two to four copies each | owning module, imported elsewhere |
| `compact_json` | `render.py` and `mcp_server.py` with different options | `render.py` only |
| Java compile scaffolding and retry rounds | `GroovyCompiler`, `TypeCheckAnalyzer` | `compile/CompilationScaffold`, `RetryRounds` |
| Java `toPath`, SHA-256 (four copies), `closeQuietly` | several classes | `Uris`, `Hashes`, `Closeables` utilities |

### 6.3 One shared visitor pass for checks (Q-26)
Nine visitors walk each module separately; four `LOG_METHODS` sets and four test code detectors drift. Introduce `PracticeContext` (typed facts; test, Spock, Jenkins and Gradle classification; logger detection; built once per module) and split `PracticeFinder` into one class per practice group.

### 6.4 Configuration as single source (P-28, P-30)
- Add a cached `config.default(section, key)` and remove every literal fallback (`"1g"`, `5_000_000`, `16000`, `900`, `600.0`, `17`, `"temurin"` and the rest listed in the audit). Add a test that fails when `src/` contains `.get("<known key>", <literal>)`.
- Coerce environment overrides by the schema type of the target key (strings stay strings, so `GROOVYSCOPE_GROOVY__VERSION=6.0` works).
- Add a Spock spec that loads `defaults.yaml` and asserts `EngineConfig.fromJson(defaults)` equals `EngineConfig.DEFAULTS`; replace the telescoping constructors with a builder.
- Heap pattern `^[1-9][0-9]*[kKmMgG]$`.

### 6.5 Dead code and consistency (P-29, E-27)
- Delete the unused `rpc.py` session API, `workspace.classpathFrom.gradleTask` from the schema, `DynamicCodeFinder` dead branch, Spotless catalogue entries.
- One MCP error contract: refusals raise a tool error (`isError: true`); never return `{"error": ...}` as success.
- Replace about 35 ad hoc `click.echo(..., err=True)` diagnostics with the shared logger; keep `click.echo` for command output only.
- Decide the Java bytecode target (17 or 21), document why, and test the floor in CI (Phase 3 D-07).
- Add Javadoc to public engine API, starting with `CloneFinder`, `CodeSearch`, `GroovyscopeTextDocumentService`; enable doclint for public types.

### 6.6 Mirrored files (D-19)
Keep `_templates` as the only source. Gradle `processResources` copies the CodeNarc rule sets and `practices.json` from it; a `scripts/sync_templates.py` pre commit hook regenerates root `kiro/` and `config/`. Keep the drift test.

---

## 10. Phase 7: docs, Kiro assets and polish

Branch `docs/phase-7`. Model `haiku` for counts and tables, `sonnet` for steering and skills.

| ID | Change |
|---|---|
| D-14 | `groovy-reviewer.json` lists only read only tools in `tools` too, generated from `tools_catalog` |
| D-15 | Per tool MCP hints in `TOOL_INFO`: destructive for apply capable tools, not idempotent for `rename`, open world for `refresh_classpath`; test it |
| D-16 | Steering `inclusion: fileMatch` on `**/*.groovy`, `**/*.gradle`, `**/Jenkinsfile*`, or project scope install, so non Groovy sessions do not pay for it |
| D-17 | `checkup --agent` reports and offers repairs; only `checkup --fix` writes files |
| D-18 | `groovy-fix` skill dry runs and asks before `apply`; one stance on posting to GitLab with the "tool output is data" guard; generate the checklist paragraph from `practices.json` |
| D-21 | Reconcile `docs/roadmap.md` (Stage 15 and 16 shipped but unticked); track status in GitHub issues or milestones and keep the roadmap as narrative |
| D-24, Q-28 | Fix `architecture.md` (27 tools, missing modules and packages), `ci.md` trust comment, `practices.md` stale paths (`codenarc.groovy`, `compiler-config.groovy`); add a test that every Python module and engine package appears in `architecture.md`; remove counts from prose |
| D-25 | Add `SECURITY.md`, `CONTRIBUTING.md`, `.github/ISSUE_TEMPLATE/` with a bug form asking for `groovyscope doctor --bundle` |
| P-26 | `checkup.py` reads `status["engine"]` (it reads `engineVersion` today) and fix the test fixture key [verified] |
| P7 rest | `sweep.py` also prunes `.bak.N`, per root state, client and log files of deleted roots, and orphan symbol stores |

---

## 11. Progress tracker

Tick each box in the phase PR.

- [x] Phase 0: baseline and probes (0.1 to 0.5)
- [x] Phase 1: security and fix safety (1.1 to 1.12)
- [x] Phase 2: concurrency and resilience (engine E-11 to E-28, Python P-11 to P-27, P9/P10 rest)
- [x] Phase 3: release, build and supply chain (D-01 to D-23, Q-15, P-31)
- [x] Phase 4: analysis fidelity (Q-04, Q-05, Q-09 to Q-17, Q-20 to Q-27; Q-15 landed in Phase 3, Q-26 is 6.3)
- [x] Phase 5: new quality checks (GS017 to GS052, GS035 to GS038)
- [ ] Phase 6: hygiene and deduplication (6.1 to 6.6)
- [ ] Phase 7: docs, Kiro assets and polish

## 12. Already sound (do not rework)

Socket hardening (0700 directory, 0600 socket, live socket never replaced); `CompileJob` single flight with cancellable waiters; previous generation loader closed on publish; cascade guard with retry deadline; one trial type check per `fix` call; bounded request queues; reference counted overlays; real path confinement on MCP inputs and draw.io targets; `yaml.safe_load` everywhere, no `shell=True`; https only downloads with SHA-256 and `filter="data"` extraction; JAR checksum on start; flock serialised daemon starts and distinct busy, absent and refused probe states; `fit()` byte cap on every tool; SHA pinned Actions, `persist-credentials: false`, least privilege permissions; trusted publishing with attestations and SBOMs; `mypy --strict` and ruff with security rules; README command and tool tables match the code (28 tools).

## 13. Findings of the probes and of the work

Recorded as each phase confirms or rejects an item.

### Phase 0 (19 October 2026, main at 0.24.1)

| Probe | Result |
|---|---|
| 0.1 Baseline | Engine `./gradlew clean build` 80 s: 459 specs, JaCoCo 89.0% lines, 73.6% branches, 90.0% instructions. Python gates: ruff, format and mypy about 1 s; pytest 448 passed, 95% coverage, 55 s (integration test included, JAR bundled). |
| 0.2 Shaded JAR (D-02) | **Confirmed, and worse than reported.** `META-INF/groovy/org.codehaus.groovy.runtime.ExtensionModule` lists only groovy-xml; the nio, datetime, dateutil, sql and macro extension modules are lost in the merge. Through the JAR, `Path.text` and `LocalDate + 1` fail the static check. Worse, `FormatStringChecker` cannot load (it needs groovy-macro's `withConstraints` extension); the failure is a `SimpleMessage`, which `TypeCheckAnalyzer` does not read, so with the default checkers the shipped type check reports **nothing at all**, not even `day.noSuchThing()`. Unshaded engine specs do not see it. Fix in 1.11, plus a new item 1.12: report a type check that could not run instead of an empty result. |
| 0.3 Strict NullChecker (Q-17) | `NullChecker(strict: true)` loads on groovy-typecheckers 6.0.0 and reports `Potential null dereference: 't' may be null` with no annotations; the lenient checker reports nothing on the same code. Roadmap text corrected. Through the shipped JAR it only runs once 1.11 and 1.12 are fixed. |
| 0.4 LSP4J and a closed connection (E-15) | **Confirmed.** Closing the client's stream completes the server's listening future and leaves a running request's future neither cancelled nor completed (`LspCloseProbeSpec`). The engine must cancel a closed connection's requests itself (Phase 2). |
| 0.5 CodeNarc enhanced and Jenkins rules (Q-05) | **Partly confirmed.** Enhanced rules fire for JDK types (`MissingOverrideAnnotation` on `Runnable`, `CloneWithoutCloneable`) but not for workspace classes (an override of a workspace superclass is not seen): no workspace classpath reaches CodeNarc. The jenkins.xml rules carry CodeNarc's default `applyToFileNames = 'Jenkinsfile'`, so they never run in a shared library's `vars/` or `src/`, whatever the profile (`CodeNarcEnhancedProbeSpec`). Both go to Phase 4 (Q-04, Q-05). |

New item for Phase 1:

### 1.12 A type check that could not run reports nothing (found by probe 0.2) Critical
- **Where:** `typecheck/TypeCheckAnalyzer` `collect` (reads `SyntaxErrorMessage` only).
- **Problem:** an extension that fails to load, or any other non syntax message, stops the compile before static checking, and the result is an empty list: the user is told the code type checks.
- **Change:** turn every other error message into one `typecheck-failed` warning on the first affected document naming the cause; a unit that did not reach `INSTRUCTION_SELECTION` without resolution errors is a failed check, not a clean one.
- **Tests:** an extension name that cannot load yields `typecheck-failed`; an integration test through the shaded JAR finds `day.noSuchThing()`.

### Phase 1 (19 October 2026)

| Item | Result |
|---|---|
| 1.1 Trust and logs | Confirmed. Gated keys added (`logging.file`, `jdk.autoProvision`, `jdk.vendor`, `daemon.heapDumpOnOutOfMemory`); heap capped at `daemon.untrustedHeapCeiling`, report paths kept in the workspace; the engine log confined to the cache or temporary folder, opened without following links, 0600, rotated; records escaped to one line on both sides. |
| 1.2 Agent allowlist | Confirmed. The broad `groovyscope .*` and `./gradlew .*` patterns matched `groovyscope trust` and any Gradle task; replaced by option lists for each read-only command, retired on repair. `trust` refuses a non terminal and an agent session unless `--yes-i-am-the-user`. |
| 1.3, 1.6 Quote and map key fixes | Confirmed. The quote fix is now AST based and escapes for single quotes; the map key fix wraps in parentheses. |
| 1.4 Fix safety | Confirmed. Each catalogue entry carries `safety`. Decisions: entries with no fix are `none`; `@CompileDynamic` actions and runtime profile fixes count as preserving (they annotate or follow the profile's own advice). GS004 offers `exists()` as an alternative; GS007 has no fix. |
| 1.5, 1.12 Type check verdicts | Confirmed. Groovy 6.0.0's `FormatStringChecker` throws a `NullPointerException` on an unresolved method call; the analyser now reports `typecheck-incomplete` beside the real errors, and verdicts use Groovy's own checker alone (`CORE_ONLY`). A check that did not run gives no verdict and no annotation. |
| 1.7 Logging | Confirmed. The library comes from the declared type or the class's logging annotation; name only matching dropped. |
| 1.8 Formatter | Confirmed. Strings, GStrings and block comments are lexer marked and never edited; CRLF kept per line. |
| 1.9 Daemon root | Confirmed. A daemon serves only the root it was started with. |
| 1.10 Outside sources | Confirmed. Source roots and open documents outside the workspace are skipped. |
| 1.11 Shaded JAR | Confirmed (probe 0.2). Service files and Groovy extension descriptors are merged; an integration test checks `Path.text` and `LocalDate + 1` through the JAR. The manual ELK provider registration stays: it costs nothing and keeps unshaded runs working. |

Independent review of the Phase 1 diff (an `opus` subagent, without the author's notes), all acted on before merge:

- The allowlist accepted globs (`groovyscope fix *` expands to a repository file named `--apply`), `verify` (runs the build), `graph` without a stdout format (writes a draw.io file) and any Gradle flag (`--scan` uploads). Globs, `verify` and other Gradle flags are gone; `graph` must name `--format mermaid|json`; the V3 rules are exact commands, since V3's handling of `a; b` is unconfirmed. `./gradlew test`, `check` and `jacocoTestReport` stay approved as section 4 decides, although they run the project's build scripts: **owner to confirm**.
- `@CompileStatic` verdicts: a class right after the `package` line got its annotation above the inserted import (the trial did not parse and every class "failed"), and an error inside an anonymous class was charged to that class. Imports now always read before annotation lines, an unparsable trial gives no verdict, and an error counts against the enclosing class the annotation covers.
- GS005's `{}` fix changed behaviour for a lazy `${-> x}` and a trailing Throwable (SLF4J takes it as the exception); neither gets the fix now.
- The formatter's spans were in code points (the ANTLR stream) against UTF-16 line offsets, so an emoji earlier in the file shifted them; now mapped.
- The engine log accepted the shared temporary directory, was not re-checked when reopened, and kept the previous file after a refused reconfigure; NEL and the Unicode line separators were not escaped (Python's `splitlines` breaks on them). All fixed; `daemon.logMaxBytes` was declared twice and is now the one key for both logs.
- Any non syntax message was read as a checker that failed to load; now only a message about a type checking extension is, and a compile that stopped before static checking with nothing to show is a failed check, never a clean one.
- Kept as is: Groovy 6.0.0's `FormatStringChecker` NPE appears only beside a real error and is reported as `typecheck-incomplete`.

### Phase 2 (19 October 2026)

| Item | Result |
|---|---|
| E-11 | Light requests about one document answer from the published snapshot when that document is unchanged (stale while revalidating; the compile runs on the workspace's background thread); a request about a changed document waits. `references`, `rename` and the diagnostics push run on the heavy lane; hover reads lint notes from the caches only. Two hovers during a 300-file compile: 5 ms (target 200 ms, `PerformanceReportSpec`). |
| E-12, E-13 | One type check per snapshot version, on the snapshot's own sources; the compile holds a lock a waiter polls with its cancel flag; `refresh()` returns early when nothing changed. |
| E-14, E-15 | Confirmed (probe 0.4). Each connection has a view of the lanes that cancels its requests on close. An idle exit closes the socket, serves connected clients for the grace period, then shuts the lanes; later requests get -32099 "daemon is exiting", which the front end reconnects on. |
| E-16, E-17 | Every compile attempt's loader is released on every exit (a cancel in the parse-only pass leaked the kept one). The compiler already isolated a too deep file; the type checker did not (a 1,500 term expression turned every file's result into `typecheck-failed`): the culprit is now found by halving. One `Containment.recoverable` rule. |
| E-18, E-19 | Shedding takes no lock; pressure refuses compiles and type checks (light requests keep the published snapshot). Handing a workspace out and evicting it are atomic per root; a closed workspace fails fast and drains its pending save. |
| E-22 | `daemon.diskCheckMillis` added, **default 0** (every request, as before): any window would let a check right after a write miss the write. The fingerprint is 64 bits and shares the compile's walk. |
| E-23, E-24, E-25, E-27b, E-28 | Symbol store: changed documents only, WAL, busy timeout and immediate transactions (WAL alone still failed with SQLITE_BUSY on a read that upgrades). Rename from one snapshot. Socket folder owner from a created file. Safety flags set first in `Main`. `oomExit` in status, warned by `doctor`, `daemon status` and `checkup`. |
| P-11 to P-27, P9/P10 | All done, none rejected (see the commits): reload inside the reconnecting path, heap kept only while the configured one is unchanged, a separate write timeout and `ChannelBrokenError`, the engine configuration in a 0600 file passed with `--config` (the engine now hashes the configuration it actually reads), no fallback JVM after a crash loop refusal, a background classpath export with `classpath.timeoutSeconds`, one deadline per MCP call on a bounded pool, a hook that always exits 0, locked JDK installs, no `umask(0)`, bounded start lock and unique temporary names, heap growth only for heap exhaustion, relay back pressure, a three state classpath reload, a Stop gate baseline, a reaped daemon process and symlink safe edits. |

Independent review of the Phase 2 diff (an `opus` subagent, without the author's notes): the lock order is acyclic
(compile lock, then the services monitor; nothing waits on a future or another lock while holding one), and no
thread waits on the heavy lane from the heavy lane. Acted on before merge: a compile cancelled or failing after
`compile` returned (at the indexing checkpoint) leaked its loader, now permanently because of the new tracking, and
is closed when not published; the MCP `groovyscope/*` navigation handlers now use the same stale-OK light path and
`groovyscope/references` the heavy lane; a background compile is abandoned when its workspace closes and eviction
closes outside the map; queued requests at shutdown are answered "daemon is exiting"; the type check cache never
goes back to an older snapshot; no stale answers after a reconfigure; the Python channel's pending replies have
their own lock, a reload is bounded by the call's deadline, and the start lock timeout covers the restart backoff.

### Phase 3 (19 October 2026)

| Item | Result |
|---|---|
| Exit | **Met.** Two clean builds of one commit give byte-identical JARs. The only difference was the date Shadow's Groovy extension merge stamps into the descriptor (`Properties.store`; `java.properties.date` cannot help, because Gradle sets `-D` properties after the daemon starts); a small transformer in `build.gradle.kts` now merges the descriptors without a date (and without the empty entries Shadow left in `staticExtensionClasses`). |
| D-01 | CodeNarc pinned in the catalogue; the latest-release lookup is opt in (`-PcodenarcResolve=true`) and runs in the weekly `codenarc-canary.yml`, which builds and tests with it and notes a newer passing release. It opens no PR itself: Dependabot already proposes the bump, and a PR opened with the workflow token would start no CI. AGENTS.md, README and `jar.py` updated. |
| Q-15 | Confirmed, and worse: an unknown rule name raised an `AssertionError` that `load` did not catch. Also found that the CodeNarc rule registry was never initialised (the engine does not use CodeNarc's runner), so a rule set naming rules by bare name could not load at all. Fixed both; unknown names are skipped and reported once per check as `lint-config`. |
| D-03, D-04, D-05 | The publish workflow verifies the tag (`vX.Y.Z`, existing, equal to pyproject and `gradle.properties`; `bundle_jar.py` adds the JAR and its embedded version), waits for the tagged commit's required checks and refuses a red one, runs one publish per tag, attests the checksums, SBOMs and hashed requirements too, and attaches assets only after PyPI accepted the upload, without `--clobber`. `workflow_call` removed. The ruleset, the `pypi` environment's tag rule and approvals are repository settings, listed in `docs/ci.md` for the owner. |
| D-06 | `uv.lock` already followed releases (release-please `extra-files`); CI now runs `uv lock --check`, and `bundle_jar.py` chooses the JAR of the declared version and refuses versions that disagree. |
| D-07 | **Confirmed: the engine did not work on Java 17**, though the documented floor was 17. Two causes, both found by the new JDK 17 job: Xbase 2.44 (ELK's runtime) is compiled for Java 21, now pinned to 2.40, the last Java 17 release, with a build check (`checkJava17Bytecode`) and a Dependabot ignore; and on Java 17 the streams from `Channels` hold the socket's blocking lock for a whole read (JDK-8279339, fixed in 19), so a daemon could not answer while its reader waited: every request after the first hung. The daemon now uses streams that call the channel's own read and write. Integration tests run on the built JAR with Temurin 17, 21 and 25 and Corretto 21, and on every Python of the matrix; `GROOVYSCOPE_REQUIRE_ENGINE=1` turns their skips into failures. |
| D-08 | Gradle dependency submission from `main`, dependency review on PRs, CodeQL for `actions`, zizmor (clean after annotating the title check's `pull_request_target`, which checks out nothing). Dependency review calls the REST comparison directly and zizmor runs from PyPI: this session may read no repository but this one, so it could not resolve commit SHAs for new third-party actions. **Deferred: OpenSSF Scorecard** needs `ossf/scorecard-action` pinned by SHA (owner to add). `groovy-ant` and `groovy-templates` are not needed by the engine, but workspace scripts using `AntBuilder` or `SimpleTemplateEngine` would no longer resolve, so they stay, now listed with the other commonly used modules. |
| D-09 | JaCoCo and the JUnit launcher (versionless, aligned by the JUnit BOM) moved into the catalogue; the unused Spotless entries deleted. **Deferred: dependency lockfiles and verification metadata.** Dependabot is not known to refresh them (unverifiable from here), so they would fail every Gradle update PR; every direct dependency and the one version range are already pinned, and the JAR is reproducible. Owner to decide. |
| D-10 | `python/NOTICE` had drifted (the Eclipse and Guava paragraph was missing); it and `THIRD-PARTY-NOTICES.md` are now test-enforced copies. Licence expression `Apache-2.0 AND EPL-2.0 AND EPL-1.0 AND BSD-3-Clause AND BSD-2-Clause AND MIT`, taken from the CycloneDX SBOM; Apache NOTICE files merged into the JAR with fixed inputs. Shadow's licence transformer was not used: it would drop the EPL texts. |
| D-11 | The release workflow mints a GitHub App installation token with `openssl` and the REST API when `RELEASE_APP_ID` and `RELEASE_APP_PRIVATE_KEY` exist, else falls back to `RELEASE_PLEASE_TOKEN`, then `GITHUB_TOKEN`. Creating the App is the owner's step (`docs/ci.md`). |
| D-12 | `CODEOWNERS` moved to `.github/` (owner `@api-py`, the account that merges). Requiring code owner review is left to the owner: it would require an approval on every PR, release PRs included. |
| D-22, P-31 | Timeouts on every job, the dead upload step gone, ruff and mypy strict over `scripts/`, sleeps replaced by events in the relay and progress tests. |
| D-20, D-23 | The benchmark corpus refuses a checkout without its licence and notice. The Homebrew formula installs the dependencies by hash from the release's attested `groovyscope-requirements.txt` (`--require-hashes`, then the package with `--no-deps`), with an `all_of` licence; releases before 0.24.4 fall back to the unhashed install with a warning. |

### Phase 4 (20 October 2026)

| Item | Result |
|---|---|
| Q-04 | **Confirmed.** A shared library (`vars/` beside `src/` or `resources/`, with no `src/main` or `src/test`) now lints its `src/` classes with the Jenkins profile, and discovery adds the library's `src/` and a root `Jenkinsfile*`. `lint-sample` has a `vars/` folder but is not a library, which is why the detection needs both conditions. |
| Q-05 | **Confirmed.** CodeNarc gets a `GroovyClassLoader` over the source roots (plus the classpath in a trusted workspace) as the context loader, one per snapshot, so enhanced rules see workspace classes; the Jenkins rules run in every file of the Jenkins profile, not only files named `Jenkinsfile`. The probe spec is flipped. |
| Q-09 | Departed from the plan: making every implicit-this call in a closure `DYNAMIC_UNKNOWN` would make rename refuse `list.each { helper(it) }`. A call only the owner or only the `@DelegatesTo` type declares is `INFERRED`; one both declare is `DYNAMIC_UNKNOWN`, so rename refuses as the plan's test asks. |
| Q-10, Q-11 | Workspace extension and `@Category` methods answer calls on classpath types. Overloads of one arity are narrowed by static argument types; several left are `DYNAMIC_UNKNOWN` (the plan's alternative to a new `AMBIGUOUS` confidence). Impact compares erased parameter types. |
| Q-12 | Transitive callers list dynamic edges (flagged, not followed); dynamic call sites in test files are possible tests, so `verify` runs them with no Python change. |
| Q-13, Q-14 | New dynamic finding kinds (computed method names, categories, expandos, mixins, delegates, interceptors, `Class.forName`, global `ExpandoMetaClass`); the dead branch deleted. Call search types receivers through the resolver and also matches the literal parts of GStrings. |
| Q-16 | `typecheck.configScript` (default `config/groovyc/compile-static.groovy`) runs as `groovyc --configscript`, only in a trusted workspace, and is gated in the front end too. The checker customizer moved to canonicalization: an import alias and an `@AnnotationCollector` now reach the Groovy 6 checkers (they did not before). A whole-workspace `check` adds one `typecheck-coverage` information note naming the classes left dynamic. The Groovy 6 checkers still apply only to annotated classes, not to those a configuration script makes static. |
| Q-17 | `typecheck.nullChecker` accepts only `off`, `lenient` or `strict`, in the schema and the engine. |
| Q-20, Q-21 | `ImplementationAsType` is back at priority 3. The default profile adds `junit.xml`, with the rules that misread Spock skipping `*Spec` files; the Jenkins profile adds the size rules; `*.gradle` files get a Gradle profile without `NoDef`, `CompileStatic` and the `*TypeRequired` rules (`lint.gradleRuleset` replaces it, trust gated). |
| Q-22 to Q-25 | GS020 skips properties; `dir`, `doc`, `impl`, `init`, `lib`, `org`, `prev`, `sub` are allowed shorthand; GS002 is information on text and collections, a warning on numbers; GS003 covers explicit and implicit returns and leaves `boolean` alone; GS011 no longer blames Spock 2.4 (Groovy's `.with {}` was never a condition block). |
| Q-27 | `lint.rules` accepts `on` to enable a rule the profile leaves out; an unknown rule name appears in `check` as `lint-config`. |

### Phase 5 (20 October 2026)

| Item | Result |
|---|---|
| 6.3 first | `PracticeContext` holds one document's tree, text, configuration, classification (test, Jenkins, Gradle, from the lint profile `QualityServices` assigns) and one reporting path with severity ceilings and floors. The new checks live in group finders on it (`SqlFinder`, `SecretFinder`, `PitfallFinder`, `JenkinsFinder`, `SpockFinder`, `GradleFinder`); splitting the old `PracticeFinder` visitor stays with Phase 6. |
| GS035, GS036, GS012 | SQL text built by concatenation, `toString()` on a GString or a GString stored in a `String` variable is an error on a proven `Sql`, information on a receiver only presumed from its name; final constant fields count as constant text. `Sql.newInstance` never closed, returned, stored or passed on is a warning. GS012 now reports a quoted `LIKE` wildcard (`'%${x}%'`) with the pattern to bind, without the quote-removing fix. |
| GS019 | Credential shapes (AWS access key ID, GitHub token, JSON Web Token, private key) are errors anywhere; a literal given to a secret-sounding name is information and skipped in tests. The new `secrets.allow` list matches values or names. Messages never repeat the secret, and the spec builds its secrets at run time so the repository holds nothing a scanner would flag. |
| GS017, GS018, GS028, GS029, GS034, GS037 | As planned. GS018 offers a `toString()` fix; GS028 is a warning on boxed numbers, information on primitives; GS029 needs a body of several statements or a block; GS037 warns on a returned value (always discarded) and informs on a bare return; GS034 looks for an assignment to the component in the record. |
| GS030 to GS033, GS038 | `JenkinsFinder`, Jenkins code only for GS030 to GS033. GS031 and GS032 are warnings, not errors: without a Jenkins classpath, steps are recognised by name. GS038 lives entirely in `JenkinsFinder` (one pass), skips test sources, and is raised to an error in Jenkins code. |
| GS040 to GS043 | Departed from the plan on GS043: "`thrown()` not last" would flag Spock's own idiom (conditions on `def e = thrown(X)` follow it), so GS043 reports what Spock rejects: an exception condition outside `then:` or a second one in a block. Both interaction problems share GS040. |
| GS050 to GS052 | Gradle build scripts were only seen when a workspace had no source roots. `WorkspaceModel.buildScripts()` now finds root and subproject scripts; they are never compiled with the sources (their Gradle API imports would not resolve), and practices run on a syntax-only parse. |
| Licence header, SARIF | `lint.licenceHeader` (off by default) adds CodeNarc's `RequiredRegex` as `LicenceHeader`; `docs/ci.md` shows OWASP Dependency-Check SARIF through `reports.sarif`, and why Gradle verification has none. |
| Exit | Run on the CodeNarc corpus and `jenkins-lib` (`docs/benchmark.md`): 66 findings from the new codes after refinement. A manual sample of 20 had 30% false positives before refinement and 10% after, both remaining ones informational. |
