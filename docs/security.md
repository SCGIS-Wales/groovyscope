# Security posture

Compiling Groovy is not a safe halfway step short of running it: global AST transforms, `@Grab` and `@ASTTest`
execute at compile time. The engine therefore treats workspace code as untrusted by default.

## Non-negotiable JVM flags

`groovyscope lsp` always starts the engine with `-Dgroovy.grape.enable=false` and
`-Dgroovy.asttest.enable=false` (`launch.py`, `SAFETY_FLAGS`). They are not configurable.

## Trust gate (Stage 1)

Global AST transforms found on the workspace classpath are loaded only when `.groovyscope.yaml` sets
`workspace.trust: true`. The classpath itself comes from `workspace.classpath` or an explicit Gradle export
task, never from silent inference over the workspace contents.

## Supply chain

- The wheel bundles the engine JAR with a SHA-256 manifest; `doctor` verifies it. `GROOVYSCOPE_JAR` bypasses
  the check and `doctor` says so.
- Releases publish to PyPI through trusted publishing (OIDC, no API tokens) with provenance attestations for
  the wheel, sdist and JAR, plus CycloneDX SBOMs on the GitHub Release.
- Third-party GitHub Actions are pinned to commit SHAs; Dependabot keeps them and the Gradle and uv
  dependencies current. CodeQL runs on every PR for Python and Java.

## Reporting

Open a private security advisory on the GitHub repository rather than a public issue.
