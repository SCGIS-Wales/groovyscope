# Troubleshooting

Start with `groovyscope doctor`. It prints one line per check (`ok`, `warn`, `FAIL`, `info`) and exits 1 on
hard failures.

| Symptom | Cause and fix |
|---|---|
| `FAIL jdk no JDK found` | Install JDK 21 (docs/install.md) or set `JAVA_HOME`; on macOS add the `/Library/Java/JavaVirtualMachines` symlink |
| `FAIL jdk JDK 11 ... below the minimum 17` | Groovy 6 needs 17+; point `jdk.home` or `JAVA_HOME` at a newer JDK |
| `warn jdk ... JAVA_HOME (...) is JDK 11, below the minimum 17; ignored` | The JDK you named is too old (or not a JDK); another one is used. Point `JAVA_HOME` at a JDK 17+ home, for example `~/Library/Java/JavaVirtualMachines/amazon-corretto-21.jdk` |
| `warn config ... ignored in an untrusted workspace` | The repository's `.groovyscope.yaml` sets `jdk.home`, a rule set or `workspace.trust`; run `groovyscope trust` if you trust the repository |
| `the daemon was started with a different configuration` | `.groovyscope.yaml`, a profile or the environment changed since the daemon started; `groovyscope daemon restart` |
| `socket path ... is longer than ... bytes` | Set `GROOVYSCOPE_SOCKET_DIR` to a short directory such as `/tmp/gs` |
| `... belongs to another user; set GROOVYSCOPE_SOCKET_DIR` | The socket folder was created by someone else; point `GROOVYSCOPE_SOCKET_DIR` at a folder you own |
| `doctor` warns `kiro agent ... universal-out-of-sync` | The agent was edited after Kiro generated its V3 rules; `groovyscope kiro repair` re-stamps it |
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
