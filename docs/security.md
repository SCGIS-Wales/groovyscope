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

`groovyscope trust` asks a person: it is refused when standard input is not a terminal, and inside an agent
session (an environment variable such as `KIRO_*` or `CLAUDECODE` is set) unless you add
`--yes-i-am-the-user`. `--revoke` and `--list` always work. The groovy agent's shell allowlist never matches
`groovyscope trust`, so the agent cannot grant trust on its own.

In an untrusted workspace, settings in the repository's own `.groovyscope.yaml` that would run code from the
repository are ignored, and `doctor` and every command name them:

| Setting | Why it is gated |
|---|---|
| `workspace.trust` | Global AST transformations from the workspace classpath run inside the engine at compile time |
| `jdk.home` | groovyscope starts `<jdk.home>/bin/java` |
| `lint.ruleset`, `lint.jenkinsRuleset`, `lint.gradleRuleset` | CodeNarc rule sets are Groovy scripts the engine evaluates; remote (`http(s)`) rule sets are always refused |
| `typecheck.configScript` | A compiler configuration script is Groovy code; the engine runs it only in a trusted workspace, whatever path is set |
| emulation jars of a runtime profile kept in the repository | The emulation compile loads those jars |
| `logging.file` | The engine would write wherever it names |
| `jdk.autoProvision`, `jdk.vendor` | They download and run a JDK |
| `daemon.heapDumpOnOutOfMemory` | A heap dump holds every open document |

Two settings are capped rather than ignored: `daemon.heap` and `daemon.maxHeap` never exceed
`daemon.untrustedHeapCeiling` (which the repository's file cannot raise), and `reports.sarif` and
`reports.jacoco` are kept to paths inside the workspace.

Your environment (`GROOVYSCOPE_*` variables) and a configuration file you name with `--config` are your own
choice and are not gated. The classpath itself comes from `workspace.classpath`, from the file
`workspace.classpathFile` names, or from `groovyscope classpath`, never from silent inference over the
workspace contents.

## `groovyscope classpath` runs the build tool

Resolving a project's dependencies means running its Gradle or Maven build, and a build script is code from
the repository: `build.gradle`, `settings.gradle`, plugins and init scripts all execute. `groovyscope
classpath` therefore runs only in a workspace you have trusted and only when you invoke it; no other command,
LSP request or MCP tool runs it on its own, and there is no setting that automates it. The
Gradle init script it uses is bundled with the package (`config/gradle/groovyscope-classpath.gradle`), never
taken from the workspace. The result is a plain list of paths in `.groovyscope/classpath.txt`. Reading jars
from that list for name resolution runs no code from them; global AST transformations found on the
classpath still load only with `workspace.trust`, which itself needs the trust record. `groovyscope init`
reads build files as text and runs nothing.

The MCP tool `refresh_classpath` is the same export for the agent and is gated the same way: in an untrusted
workspace it returns a refusal naming `groovyscope trust`, and it never runs on its own; the agent offers it
when `check` reports the classpath as stale.

## What runs at agent start

The groovy agent's start hook runs `groovyscope checkup --agent` in the project folder. It reports, and writes
one thing: the `.groovyscope/` entry in the repository's `.gitignore` (added once, atomically, only when git
does not already ignore the folder; `workspace.gitignore: false` turns it off). The only subprocess it starts is
`git check-ignore` for that test; it runs no `kiro-cli` and no build tool. Every repair (the Kiro files, the
project's LSP entry, the benchmark corpus, the cache sweep) is proposed as `groovyscope checkup --fix`, and the
two actions that run repository code (`trust`, `classpath`) are proposed on their own; the agent runs any of
them only after you say yes. It never grants trust, never starts or stops a daemon and never reads source code,
and it finishes within Kiro's hook budget or is cut off without effect.

## The edit gate and planned writes

The `preToolUse` hook reads the text a write would leave from Kiro's hook payload (the tool's own input) and asks the
engine to preview it: an overlay compile against the last snapshot, under the same safety flags and trust gate as any
compile, with nothing written. It never starts the engine and never writes a file; a blocked write is Kiro's refusal,
on the hook's exit code. Plans (`edit_symbol` and the refactoring tools' dry runs) are kept under the cache directory,
one JSON file per source file, readable by you only (mode 0600), and expire after `edits.planTtlMinutes`. The edit
tools write only with `apply: true`, after asking when the client supports it, and restore every file when the
recompile finds new errors.

## Reports are data

`check`, `review` and `review_changes` read the build's SARIF logs, and `coverage_gaps` reads the JaCoCo XML
report, as data: JSON and XML parsed with the standard library (no external entities), bounded in size, with
file paths resolved only inside the workspace. `review_changes` runs git plumbing (`merge-base`, `diff`) and
nothing else.

## Containment

- The engine reads, checks, fixes and formats only files under the workspace root (symlinks resolved); requests
  naming anything else are ignored. Files larger than `workspace.maxFileBytes` are skipped.
- The CLI and the MCP server write edits only to Groovy sources inside the workspace, atomically, keeping each
  file's mode and line endings; MCP paths that leave the workspace are rejected.
- Source roots that resolve outside the workspace, and open documents outside it, are never analysed.
- A daemon serves only the workspace it was started for (`--root`); a client naming another root is refused.
- The engine's log file must lie under the cache folder or the system temporary folder; it is opened without
  following symlinks, created 0600 and rotated past `daemon.logMaxBytes`. Each log record is one line, with
  control characters escaped, so a file name cannot forge log lines.
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
- The Homebrew formula installs the PyPI sdist (bundled JAR, same checksums) and Homebrew's `openjdk@21`; the
  Python dependencies come from the release's `groovyscope-requirements.txt` (exported from `uv.lock` with every
  hash, attested) with `--require-hashes`, and the package itself with `--no-deps`.
- A release publishes only a tag that names the version every build file declares and whose commit passed CI
  (the publish workflow waits for the checks and refuses a red one). PyPI receives it through trusted publishing
  (OIDC, no API tokens), with provenance attestations for the wheel, sdist, JAR, checksums, SBOMs and hashed
  requirements; the GitHub Release gets its assets only after PyPI accepted the upload, and never replaces one.
  The SBOM tool is pinned and runs before the build, apart from the files that are attested and published.
- Two builds of one commit produce byte-identical engine JARs (fixed timestamps and file order, and the merged
  Groovy extension descriptor written without a date). CodeNarc is pinned like every other dependency.
- The wheel's licence metadata (`Apache-2.0 AND EPL-2.0 AND EPL-1.0 AND BSD-3-Clause AND BSD-2-Clause AND MIT`)
  covers what the JAR bundles; `THIRD-PARTY-NOTICES.md` lists each component, and the Apache NOTICE files of the
  bundled libraries are merged into the JAR's.
- Third-party GitHub Actions are pinned to commit SHAs; Dependabot keeps them and the Gradle and uv
  dependencies current. CodeQL runs on every PR for Python, Java and the workflows themselves; zizmor audits the
  workflows, a PR that adds a dependency with a known high or critical vulnerability fails, and the Gradle
  dependency graph is submitted from `main` so Dependabot alerts cover the engine too.

## Reporting

Open a private security advisory on the GitHub repository rather than a public issue.
