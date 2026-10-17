## Summary

<!-- What changes and why. Link the roadmap stage in docs/roadmap.md if applicable. -->

## Checklist

- [ ] PR title follows Conventional Commits (`feat:`, `fix:`, `docs:`, `chore:` ...)
- [ ] `./gradlew build` passes (engine)
- [ ] `uv run ruff check . && uv run ruff format --check . && uv run mypy --strict src && uv run pytest` pass (python)
- [ ] No user-defined classes added to the Python package (functional style)
- [ ] `docs/roadmap.md` updated if a stage advanced
- [ ] No secrets, tokens or real credentials in code, tests or fixtures
