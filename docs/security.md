# Security posture

Compiling Groovy is not a safe halfway step short of running it: global AST transforms, `@Grab` and `@ASTTest`
execute at compile time. The engine therefore treats workspace code as untrusted by default.

## Non-negotiable JVM flags

`groovyscope lsp` always starts the engine with `-Dgroovy.grape.enable=false` and
`-Dgroovy.asttest.enable=false` (`launch.py`, `SAFETY_FLAGS`). They are not configurable.

## Workspace trust is the user's decision

A repository cannot trust itself. Trust is recorded per workspace, by you, in
`~/.groovyscope/trusted-workspaces.json`:

```bash
groovyscope trust            # trust the current workspace
groovyscope trust --revoke   # stop trusting it
groovyscope trust --list
```

In an untrusted workspace, settings in the repository's own `.groovyscope.yaml` that would run code from the
repository are ignored, and `doctor` and every command name them:

| Setting | Why it is gated |
|---|---|
| `workspace.trust` | Global AST transformations from the workspace classpath run inside the engine at compile time |
| `jdk.home` | groovyscope starts `<jdk.home>/bin/java` |
| `lint.ruleset`, `lint.jenkinsRuleset` | CodeNarc rule sets are Groovy scripts the engine evaluates; remote (`http(s)`) rule sets are always refused |
| emulation jars of a runtime profile kept in the repository | The emulation compile loads those jars |

Your environment (`GROOVYSCOPE_*` variables) and a configuration file you name with `--config` are your own
choice and are not gated. The classpath itself comes from `workspace.classpath` or an explicit Gradle export
task, never from silent inference over the workspace contents.

## Containment

- The engine reads, checks, fixes and formats only files under the workspace root (symlinks resolved); requests
  naming anything else are ignored. Files larger than `workspace.maxFileBytes` are skipped.
- The CLI and the MCP server write edits only to Groovy sources inside the workspace, atomically, keeping each
  file's mode and line endings; MCP paths that leave the workspace are rejected.
- The daemon's socket folder is created private (0700) and refused when another user owns it or can write to
  it; the socket itself is 0600. A second daemon never removes a live daemon's socket.

## Supply chain

- The wheel bundles the engine JAR with a SHA-256 manifest; `doctor` verifies it. `GROOVYSCOPE_JAR` bypasses
  the check and `doctor` says so.
- Nothing is downloaded at runtime unless asked. `groovyscope fetch` installs a release JAR only when its
  SHA-256 matches `--sha256` or the release's `SHA256SUMS.txt`, over `https://` only (redirects to plain
  `http` are refused), and the result is checksummed again on every start. A checksum from `SHA256SUMS.txt`
  next to the JAR guards against corruption, not against a compromised source; pass `--sha256` from a channel
  you trust for that. `groovyscope jdk install` (and `jdk.autoProvision`, off by default) takes the latest
  Temurin build from the Adoptium API or the latest Amazon Corretto build from corretto.aws and verifies the
  checksum the vendor publishes before unpacking; archive members cannot escape the target folder.
- The Homebrew formula installs the PyPI sdist (bundled JAR, same checksums) and Homebrew's `openjdk@21`.
- Releases publish to PyPI through trusted publishing (OIDC, no API tokens) with provenance attestations for
  the wheel, sdist and JAR, plus CycloneDX SBOMs on the GitHub Release. The SBOM tool is pinned and runs before
  the build, apart from the files that are attested and published.
- Third-party GitHub Actions are pinned to commit SHAs; Dependabot keeps them and the Gradle and uv
  dependencies current. CodeQL runs on every PR for Python and Java.

## Reporting

Open a private security advisory on the GitHub repository rather than a public issue.
