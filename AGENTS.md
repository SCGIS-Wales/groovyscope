# Agent guide for groovyscope

Read `docs/roadmap.md` first: it says which stage is in progress and what "done" means for it.

## Layout and commands

- `engine/` is a Gradle (Kotlin DSL) project. Build and test with `./gradlew build` (JDK 21 toolchain,
  bytecode target 17). The shaded JAR lands in `engine/build/libs/groovyscope-engine-<ver>-all.jar`.
- `python/` is a uv project. Run `uv sync`, then `uv run ruff check .`, `uv run ruff format --check .`,
  `uv run mypy`, `uv run pytest --cov` (90% gate). `python3 scripts/bundle_jar.py` copies the JAR into the
  package so `groovyscope doctor` and the integration test can find it.
- `config/` and `kiro/` mirror `python/src/groovyscope/_templates/`; a test fails when they drift. Edit the
  package copy and copy it to the root (or the reverse), never only one side.

## Style rules

- Python: functional. No user-defined classes except `TypedDict` declarations. Type hints everywhere,
  `mypy --strict` and ruff clean, line length 120. Pure functions with injected collaborators
  (`runner`, `which`, `environ`) so tests do not touch the real machine.
- Java: `-Xlint:all -Werror`. The engine API must stay free of LSP4J types outside `org.groovyscope.engine.lsp`.
  Groovy is used only for Spock specs (no `@CompileStatic` on specs; Spock rewrites them).
- Nothing hardcoded that belongs in `.groovyscope.yaml` (see `python/src/groovyscope/defaults.yaml` and
  `config-schema.json`). Safety flags (`groovy.grape.enable=false`, `groovy.asttest.enable=false`) are the
  exception: they are deliberately not configurable.
- stdout of the engine is the LSP channel. Log to stderr only.

## Process

- Conventional Commits; PR titles are checked. Squash merge.
- Releases: release-please opens a release PR on `main`; merging it tags `vX.Y.Z` and runs
  `.github/workflows/workflow.yml` (its name is tied to the PyPI trusted publisher; do not rename it).
- Pin third-party GitHub Actions to full commit SHAs.
- Never commit JARs, `_manifest.json`, `.kiro/` directories or `*.bak` files.
- Update `docs/roadmap.md` whenever a stage advances.
