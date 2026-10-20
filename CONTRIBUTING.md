# Contributing to groovyscope

Thank you for helping. This page is the short version of [AGENTS.md](AGENTS.md), which coding agents and
people follow alike; read `docs/roadmap.md` first to see what is in progress.

## Layout

- `engine/`: the Java engine (Gradle, Kotlin DSL; JDK 21 toolchain, Java 17 bytecode). Groovy appears only in
  the Spock specs.
- `python/`: the `groovyscope` package (uv project): CLI, MCP server, Kiro integration.
- `python/src/groovyscope/_templates/`: the only source of the Kiro assets, the CodeNarc profiles and the
  practices catalogue. Edit them there, then run `python3 scripts/sync_templates.py` to refresh the root
  `kiro/` and `config/` copies; the engine takes the profiles and the catalogue from the templates at build
  time.
- `docs/`: user and design documentation; `docs/architecture.md` must name every module and package (a test
  checks it).

## Gates

Run these before you push; CI runs the same:

```bash
cd engine && ./gradlew build              # specs, JaCoCo gate, -Xlint:all -Werror, doclint
cd .. && python3 scripts/bundle_jar.py    # puts the engine JAR into the package for the integration tests
cd python && uv sync
uv run ruff check . && uv run ruff format --check .
uv run mypy
uv run pytest --cov                       # 90% coverage gate
cd .. && python3 scripts/sync_templates.py --check
```

## Style

- Python is functional: no user-defined classes except `TypedDict` declarations and exception classes; type
  hints everywhere; `mypy --strict` and ruff clean; line length 120. Pass collaborators (`runner`, `which`,
  `environ`) in, so tests never touch the real machine.
- Java builds with `-Xlint:all -Werror`; LSP4J types stay inside `org.groovyscope.engine.lsp`.
- Nothing that belongs in `.groovyscope.yaml` is hardcoded: add the setting to `defaults.yaml` and
  `config-schema.json` instead.
- The engine's stdout is the LSP channel; log to stderr only.
- Never write the CodeNarc version anywhere but `engine/gradle/libs.versions.toml`.
- Runtime profiles stay generic: no real private platform, package or service names anywhere in the
  repository.
- Prose in British English.

## Commits and pull requests

- Conventional Commits (`feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `build:`, `ci:`, `chore:`); the pull
  request title is checked, and pull requests are squash merged.
- Fill in the pull request template's checklist; update `docs/roadmap.md` when a stage advances.
- Write the failing test first where you can.
- Never commit JARs, `_manifest.json`, `.kiro/` folders or `*.bak` files.
- Pin third-party GitHub Actions to full commit SHAs.

## Reporting problems

Use the bug report form, and attach the archive `groovyscope doctor --bundle` writes. Report vulnerabilities
privately instead, as [SECURITY.md](SECURITY.md) describes.
