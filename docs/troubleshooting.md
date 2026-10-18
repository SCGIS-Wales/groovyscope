# Troubleshooting

Start with `groovyscope doctor`. It prints one line per check (`ok`, `warn`, `FAIL`, `info`) and exits 1 on
hard failures.

| Symptom | Cause and fix |
|---|---|
| `FAIL jdk no JDK found` | Install JDK 21 (docs/install.md) or set `JAVA_HOME`; on macOS add the `/Library/Java/JavaVirtualMachines` symlink |
| `FAIL jdk JDK 11 ... below the minimum 17` | Groovy 6 needs 17+; point `jdk.home` or `JAVA_HOME` at a newer JDK |
| `warn jdk ... JAVA_HOME (...) is JDK 11, below the minimum 17; ignored` | The JDK you named is too old (or not a JDK); another one is used. Point `JAVA_HOME` at a JDK 17+ home, for example `~/Library/Java/JavaVirtualMachines/amazon-corretto-21.jdk` |
| `warn config ... ignored in an untrusted workspace` | The repository's `.groovyscope.yaml` sets `jdk.home`, a rule set or `workspace.trust`; run `groovyscope trust` if you trust the repository |
| `GS100 missing dependencies` warnings, or `missingDependencies` in a `check`/`review` answer | No classpath is configured, so imports of library classes cannot resolve; `groovyscope trust` then `groovyscope classpath`, or list the jars under `workspace.classpath`; `practices.severity: {GS100: off}` silences the warning |
| `` `classpath` runs the project's build tool ... run `groovyscope trust` first `` | The workspace is not trusted and `classpath` would execute its build scripts; trust it, or write `workspace.classpath` by hand |
| `classpath`: `no Gradle or Maven build at ...` or `gradle is not installed and the project has no wrapper` | Pass `--tool`, add a wrapper, or list the jars under `workspace.classpath`; a build failure prints the build tool's last lines |
| `classpath`: `N listed path(s) do not exist yet (not built?)` | The build declares output folders it has not produced; run the build once and rerun `groovyscope classpath` |
| `classpath`: `Cannot find a Java installation ... matching: {languageVersion=11 ...}` | The project's build pins a Gradle toolchain this machine lacks; install that JDK (Gradle finds it in the standard folders) or add its path to `org.gradle.java.installations.paths` in `~/.gradle/gradle.properties`. The requirement is the build's, not groovyscope's |
| `classpath` wrote fewer entries than the build declares | Gradle resolves leniently, so a dependency the repository could not fetch (offline, rate limited) is skipped silently; rerun once the network is back |
| `groovyscope init` says `already has these settings` | Nothing to add: the existing `.groovyscope.yaml` already covers what the layout and build files suggest |
| `the daemon runs an older configuration and could not reload it` | A changed `.groovyscope.yaml`, profile or classpath is normally applied in place; this daemon refused it (an older engine, or an invalid document named in the message); `groovyscope daemon restart` |
| `classpath.state: stale` in a `check`, `review` or `checkup` answer | A build file changed after `groovyscope classpath` ran, so new dependencies may not resolve; rerun it, or let the agent call `refresh_classpath` |
| `refused: the export runs the project's build scripts` from `refresh_classpath` | The workspace is not trusted; `groovyscope trust`, then `groovyscope classpath` or the tool again |
| `socket path ... is longer than ... bytes` | Set `GROOVYSCOPE_SOCKET_DIR` to a short directory such as `/tmp/gs` |
| `... belongs to another user; set GROOVYSCOPE_SOCKET_DIR` | The socket folder was created by someone else; point `GROOVYSCOPE_SOCKET_DIR` at a folder you own |
| `doctor` warns `kiro agent ... universal-out-of-sync` | The agent was edited after Kiro generated its V3 rules; `groovyscope kiro repair` re-stamps it |
| `doctor` warns `kiro agent ... its tools list hides these MCP servers: ...` | The agent was written before 0.9.0 with `tools: ["code", "@groovyscope"]`, so Kiro hides your other servers from it; `groovyscope kiro repair --global` sets `tools: ["*"]` |
| `doctor` says `kiro agent ... this kiro-cli can load it in both engines` | Informational: `groovyscope kiro repair --agent-format universal` converts the V2 agent so `kiro-cli --v3` loads it too |
| `doctor` prints `info kiro files ... not in a Groovy project here` | Expected outside a project: the global setup is present and this folder has no Groovy sources or build file |
| The agent does not open with a checkup line | The start hook did not run: the agent file predates 0.10.0 (`groovyscope kiro repair --global` adds `hooks.agentSpawn`), or this Kiro CLI does not support agent hooks; `groovyscope checkup` shows the same report in a terminal |
| The agent's first message says `groovyscope checkup could not run` | The hook ran but the command failed before reporting; run `groovyscope --debug checkup` in the project and read the cause |
| `checkup` says `written by groovyscope 0.9.0, package is 0.10.0` | Normal after an upgrade; the start hook repairs it, or run `groovyscope kiro repair --global` |
| `review --changed` says `unknown base 'main'` | The branch has no `main` locally; pass `--base origin/main` (fetch it first) or the branch the request targets |
| `review --changed` says `git is not installed` or `not inside a git repository` | Review needs git for the changed lines; pass paths to `groovyscope review` instead |
| `coverage` says `no JaCoCo report at build/reports/jacoco/test/jacocoTestReport.xml` | Run the build's tests with the JaCoCo report (`./gradlew test jacocoTestReport`), or set `reports.jacoco` to where your build writes it |
| `check` prints `... is not a SARIF log` on stderr | A file in `reports.sarif` or passed to `--import-sarif` is not SARIF 2.1; the other findings are still reported |
| SARIF findings show an odd `path` | The tool reported a path relative to its own root; groovyscope matches it to a unique file under the source roots and otherwise keeps it as reported |
| `benchmark path` fails with `could not unpack` | The home folder is not writable or full; pass `--to DIR` or set `GROOVYSCOPE_BENCHMARK_DIR` |
| `graph` says `too many nodes for one readable page` | The scope is too wide for A4 at a readable font; seed with fewer symbols, lower `--depth`, raise `--max-nodes` only if you print on A3 |
| `relationship_graph` answers `must end in .drawio` or `outside the workspace` | The draw.io file is written only inside the workspace with the `.drawio` extension; pass a path such as `docs/classes.drawio` |
| `rename` answers `rolledBack: true` | The rename left new compile errors (often a same-named member in another class); the files were restored; check the symbol with `find_references` and rename by hand |
| Too many GS02x naming findings | They are info level by default; list your shorthand under `naming.allowedAbbreviations`, your verbs under `naming.vocabulary`, or set `naming.enabled: false` |
| Kiro's `/tools` lists only `code` and groovyscope under `--agent groovy` | Same cause as the hidden-servers warning; repair the agent |
| An MCP `find_code` answer is `unknown kind` | The kinds are listed in the answer: `catch`, `throw`, `call`, `new`, `return-null`, `string-literal`, `annotation`, `def`, `empty-block`, `field`, `method` |
| `read_symbol` answers `found: false` with `candidates` | The name matched nothing exactly; pick one of the candidates (`Class.name`, or `line` for an overload) |
| `FAIL engine jar no engine JAR bundled` | Development checkout: run `./gradlew build` in `engine/` then `python3 scripts/bundle_jar.py`; a package without the JAR: `groovyscope fetch`; or set `GROOVYSCOPE_JAR` |
| `fetch failed: no checksum` | Pass `--sha256`, or put the release's `SHA256SUMS.txt` next to the JAR you point `--from` at |
| `FAIL jdk no JDK found` | Install a JDK (docs/install.md), or `groovyscope jdk install`, or set `jdk.autoProvision: true` |
| `JDK provisioning failed: no Temurin build for ...` | Adoptium has no build for this OS/arch; install a JDK yourself and set `jdk.home` |
| `FAIL engine jar SHA-256 mismatch` | The bundled JAR was modified; reinstall the wheel |
| `FAIL config ...` | The message names the key (`daemon.heap: 'lots' does not match ...`); fix `.groovyscope.yaml` or the `GROOVYSCOPE_*` variable |
| Kiro `/code status` does not list groovyscope | Run `groovyscope kiro init` in the workspace root, restart Kiro CLI; check `/code logs -l ERROR` |
| Kiro says `agent "groovy" not found, using "kiro_default"` | The agent was written by groovyscope 0.6.x or earlier with a `permissions` block Kiro CLI 2.x ignores; run `groovyscope kiro repair`, then `kiro-cli agent list` should show `groovy` without "(v3 format)" |
| groovyscope entry vanished from `lsp.json` | `/code init` overwrote it (Kiro#10684); run `groovyscope kiro repair` |
| Kiro shows `1 MCP failure` | `groovyscope mcp` could not start or reach the daemon; run `groovyscope doctor`, then `groovyscope daemon status` and read its log |
| An MCP tool answers `... failed: ...` | The engine refused the request (unknown symbol, bad position, unknown change kind); the message names the cause |
| `daemon unavailable (...); running the engine directly` on stderr | The daemon could not start; `groovyscope daemon status` shows the log path, read it, then `groovyscope daemon start` |
| An MCP tool answers `groovyscope engine is busy: ...` | The heavy lane's queue is full (`daemon.requestQueue` waiting behind `daemon.workerThreads` running); retry in a moment, or raise either in `.groovyscope.yaml` on a large machine |
| An MCP tool answers `... did not answer within 100s; the engine was told to stop it` | The request was over `mcp.requestTimeoutSeconds` and cancelled on the engine; narrow the paths, or raise the budget (keep it under Kiro's tool timeout in `mcp.json`) |
| An MCP tool answers `... another call is still running it` | An identical call is in flight and this one waited its budget for it; the first call's answer arrives when the work is done, so retry rather than start a different request |
| An MCP tool answers `groovyscope engine is low on heap: ...` | Memory pressure: the engine shed its caches and refuses whole-workspace requests until a collection frees memory; single-document requests still work. Raise `daemon.heap`, then `groovyscope daemon restart` |
| A `GS101` warning on the first file: `N file(s) left out of the analysis` | The workspace is over `workspace.maxFiles` or `workspace.maxTotalBytes`; raise them in `.groovyscope.yaml` or narrow `workspace.sourceRoots` |
| `the daemon was started N times in the last ten minutes and keeps exiting` | The crash-loop guard stopped restarting it; read the daemon log, raise `daemon.heap` or `daemon.maxHeap`, then `groovyscope daemon start` |
| `daemon status` notes `the last start used a 1536m heap (grown after an out-of-memory exit)` | The self-healing grew the heap once; set `daemon.heap` to that value to keep it, or raise `daemon.maxHeap` to let it grow further |
| `daemon status` or `doctor` says `exited with OutOfMemoryError` | The workspace needs more heap than `daemon.heap` (default 1g) allows; raise it in `.groovyscope.yaml`, then `groovyscope daemon restart` |
| `doctor` warns `heap ... in use` | The daemon is near its limit; raise `daemon.heap` before it runs out, or lower `workspace.javaSources` and the source roots to what the agent needs |
| Stale answers after switching branches | Every CLI and MCP request re-checks the files on disk; if an editor session still looks stale, `groovyscope daemon restart` |
| `check` exits 1 | A finding at or above `lint.failOnPriority` (1 = error, 2 = warning, 3 = info); lower the bar or fix the code; `--json` lists every finding |
| `format` exits 1 | Files would change; rerun with `--apply`, or treat the exit code as "needs formatting" in CI |
| `cannot load CodeNarc rule set` | `lint.ruleset` / `lint.jenkinsRuleset` points at a missing or broken file; set them to `null` for the bundled profiles |
| `formatter engine 'greclipse' is not bundled` | Set `format.engine: basic`; the Groovy Eclipse formatter is a Stage 6 packaging item |
| `kiro init` fails with "not valid JSON" | An existing `.kiro/settings/*.json` is broken; fix it or rerun with `--force` (a `.bak` is written) |

## Debug logging

Any command takes `--debug` (or `-v` / `--verbose` for less) and `--log-file PATH`:

```bash
groovyscope --debug doctor                          # every discovery step: config, JDK candidates, JAR, Kiro
groovyscope --debug --log-file /tmp/gs.log check    # and the engine's request log in the same file
```

To get debug output from the processes Kiro starts (`groovyscope lsp` and `groovyscope mcp`), set it in the
workspace's `.groovyscope.yaml`, then `groovyscope daemon restart`:

```yaml
logging:
  level: debug                      # debug | info | warning | error
  file: ~/groovyscope-debug.log     # optional; both the CLI/MCP side and the engine append here
```

At debug level the engine logs every JSON-RPC request with its method and duration (no source text), compile
times and any failure with its stack trace; the MCP server logs each tool call with its arguments and duration.

| Command | Shows |
|---|---|
| `groovyscope logs [-n 200] [-f]` | The engine daemon's log for this workspace (where engine output goes) |
| `groovyscope logs --kiro` | groovyscope's lines from Kiro CLI's own LSP and MCP logs (`$TMPDIR/kiro-log`) |
| `groovyscope doctor --bundle report.zip` | A zip for a bug report: the doctor report, versions, configuration, redacted environment, the daemon log and Kiro's groovyscope lines; no source code, no profile contents, no Kiro chat |

Inside Kiro: `/code logs -l ERROR` for the language server, `/mcp` for the MCP server. The engine never writes
anything but LSP messages to stdout, so stderr is always safe to read.
