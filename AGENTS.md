# Agent guide for groovyscope

Read `docs/roadmap.md` first: it says which stage is in progress and what "done" means for it.
`docs/improvement-plan.md` is the owner's audit, worked phase by phase; its section 11 tracks progress.

## Layout and commands

- `engine/` is a Gradle (Kotlin DSL) project. Build and test with `./gradlew build` (JDK 21 toolchain,
  bytecode target 17). The shaded JAR lands in `engine/build/libs/groovyscope-engine-<ver>-all.jar`.
  CodeNarc is pinned in `gradle/libs.versions.toml` (Dependabot bumps it, so releases are reproducible); the weekly
  `codenarc-canary.yml` builds with the latest stable release (`-PcodenarcResolve=true`). Never write a CodeNarc
  version anywhere else (code, Python, docs); read it from the engine (`groovyscope version`).
- `python/` is a uv project. Run `uv sync`, then `uv run ruff check .`, `uv run ruff format --check .`,
  `uv run mypy`, `uv run pytest --cov` (90% gate). `python3 scripts/bundle_jar.py` copies the JAR into the
  package so `groovyscope doctor` and the integration test can find it.
- `python/src/groovyscope/_templates/` is the only source of the Kiro assets, the CodeNarc profiles and the
  practices catalogue. Edit it, then run `python3 scripts/sync_templates.py` to refresh the root `kiro/` and
  `config/` copies (CI runs it with `--check`, and a test fails when they drift). The engine keeps no copy: Gradle's
  `processResources` takes the profiles and `practices.json` from the templates.

## Style rules

- Python: functional. No user-defined classes except `TypedDict` declarations and exception classes. Type hints everywhere,
  `mypy --strict` and ruff clean, line length 120. Pure functions with injected collaborators
  (`runner`, `which`, `environ`) so tests do not touch the real machine.
- Java: `-Xlint:all -Werror`. The engine API must stay free of LSP4J types outside `org.groovyscope.engine.lsp`.
  Groovy is used only for Spock specs (no `@CompileStatic` on specs; Spock rewrites them).
- Nothing hardcoded that belongs in `.groovyscope.yaml` (see `python/src/groovyscope/defaults.yaml` and
  `config-schema.json`). Safety flags (`groovy.grape.enable=false`, `groovy.asttest.enable=false`) are the
  exception: they are deliberately not configurable.
- stdout of the engine is the LSP channel. Log to stderr only.
- Runtime profiles (`docs/profiles.md`) are generic. Never name a real private platform, its packages or services in
  code, comments, fixtures, docs or commit messages; tests use the neutral `demo-runtime` profile and
  `profile-sample` fixture, and real profiles live outside the repository (`~/.groovyscope/profiles/`).

## Process

- Conventional Commits; PR titles are checked. Squash merge.
- Releases: release-please opens a release PR on `main`; merging it tags `vX.Y.Z` and dispatches
  `.github/workflows/workflow.yml` (its name is tied to the PyPI trusted publisher; do not rename it, and do
  not call it as a reusable workflow: PyPI checks that the signing certificate names it). With the
  `RELEASE_PLEASE_TOKEN` secret the release PR is opened as a maintainer, its checks run unattended and it
  merges itself once "Allow auto-merge" is on; without the secret its runs sit at "action required" until
  approved in the Actions tab or re-run through the API (a re-run by a maintainer starts them). The publish can
  also be run by hand with `gh workflow run workflow.yml --ref vX.Y.Z -f tag=vX.Y.Z`.
- Merging: a PR merges (squash) as soon as its checks are green, by auto-merge or through the API; never before
  its checks have run, release PRs included. Merge the release PR on its own and let its Release Please run
  finish before merging anything else: two pushes to `main` seconds apart make the second run fail on the tag
  the first one just created. A release PR merged by the built-in token starts no workflow, so its tag only
  appears on the next push to `main`; run `release-please.yml` by hand (`gh workflow run release-please.yml`) to
  tag it at once.
- The Homebrew formula is rendered by `scripts/homebrew_formula.py` and lives in `SCGIS-Wales/homebrew-tap`,
  whose daily `groovyscope formula` workflow updates it from PyPI (run it by hand with
  `gh workflow run groovyscope.yml --repo SCGIS-Wales/homebrew-tap`);
  test a change through a throwaway local tap (`brew tap-new --no-git local/test`, copy the formula into its
  `Formula/`, `brew install --build-from-source local/test/groovyscope`, `brew test`, then untap).
- `docs/benchmark.md` is the credit benchmark protocol; runs cost Kiro credits, so never start one on a
  free account without the owner's say-so.
- Pin third-party GitHub Actions to full commit SHAs.
- Never commit JARs, `_manifest.json`, `.kiro/` directories or `*.bak` files.
- Update `docs/roadmap.md` whenever a stage advances.
