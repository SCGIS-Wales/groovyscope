# Troubleshooting

Start with `groovyscope doctor`. It prints one line per check (`ok`, `warn`, `FAIL`, `info`) and exits 1 on
hard failures.

| Symptom | Cause and fix |
|---|---|
| `FAIL jdk no JDK found` | Install JDK 21 (docs/install.md) or set `JAVA_HOME`; on macOS add the `/Library/Java/JavaVirtualMachines` symlink |
| `FAIL jdk JDK 11 ... below the minimum 17` | Groovy 6 needs 17+; point `jdk.home` or `JAVA_HOME` at a newer JDK |
| `FAIL engine jar no engine JAR bundled` | Development checkout: run `./gradlew build` in `engine/` then `python3 scripts/bundle_jar.py`, or set `GROOVYSCOPE_JAR` |
| `FAIL engine jar SHA-256 mismatch` | The bundled JAR was modified; reinstall the wheel |
| `FAIL config ...` | The message names the key (`daemon.heap: 'lots' does not match ...`); fix `.groovyscope.yaml` or the `GROOVYSCOPE_*` variable |
| Kiro `/code status` does not list groovyscope | Run `groovyscope kiro init` in the workspace root, restart Kiro CLI; check `/code logs -l ERROR` |
| groovyscope entry vanished from `lsp.json` | `/code init` overwrote it (Kiro#10684); run `groovyscope kiro repair` |
| `groovyscope mcp` exits 3 | Not implemented yet (Stage 5); `kiro init` warns about this |
| `daemon unavailable (...); running the engine directly` on stderr | The daemon could not start; `groovyscope daemon status` shows the log path, read it, then `groovyscope daemon start` |
| Stale answers after switching branches | The daemon recompiles on file events from Kiro; if in doubt, `groovyscope daemon stop` and let the next session restart it |
| Socket path too long (`AF_UNIX path too long`) | Set `GROOVYSCOPE_SOCKET_DIR` to a short directory such as `/tmp/gs` |
| `kiro init` fails with "not valid JSON" | An existing `.kiro/settings/*.json` is broken; fix it or rerun with `--force` (a `.bak` is written) |

## Logs

`GROOVYSCOPE_LOG=debug groovyscope lsp --stdio` logs to stderr. The engine never writes to stdout except LSP
messages, so stderr is safe to inspect while Kiro is attached. Kiro's own view: `/code logs -l ERROR`.
