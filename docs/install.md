# Install

groovyscope ships as a pure-Python wheel that bundles the engine JAR. The only external prerequisite is a JDK
(17 minimum, 21 recommended). `groovyscope doctor` checks everything.

## Ubuntu 22.04 / 24.04

```bash
sudo apt-get update && sudo apt-get install -y curl unzip zip git
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
sdk install java 21.0.8-tem          # any JDK 21 LTS build; list with: sdk list java
curl -LsSf https://astral.sh/uv/install.sh | sh
uv tool install groovyscope
groovyscope doctor
```

## macOS (Homebrew tap)

The tap formula installs groovyscope into its own virtualenv, depends on `openjdk@21`, and wraps the command
so `JAVA_HOME` points at that JDK:

```bash
brew install SCGIS-Wales/tap/groovyscope
groovyscope doctor
```

The formula is rendered by `scripts/homebrew_formula.py` from the PyPI sdist of each release. A daily workflow
in [SCGIS-Wales/homebrew-tap](https://github.com/SCGIS-Wales/homebrew-tap) moves it to each new release after
`brew install` and `brew test` pass on macOS, so the tap trails PyPI by at most a day. The same formula works with Linuxbrew.

## macOS (uv and a Homebrew JDK)

```bash
brew install openjdk@21 uv git
sudo ln -sfn "$(brew --prefix)/opt/openjdk@21/libexec/openjdk.jdk" /Library/Java/JavaVirtualMachines/openjdk-21.jdk
uv tool install groovyscope
groovyscope doctor
```

The symlink lets `/usr/libexec/java_home` find the JDK. Without it, set `JAVA_HOME` explicitly:

```bash
export JAVA_HOME="$(brew --prefix)/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
```

## macOS or Linux with Amazon Corretto

Any Corretto 17 or newer works; 21 is recommended. Install it the usual way (the `.pkg` installer, `brew install
--cask corretto@21`, or the Linux packages), or unpack the tarball into your user folder without admin rights:

```bash
mkdir -p ~/Library/Java/JavaVirtualMachines
curl -fL https://corretto.aws/downloads/latest/amazon-corretto-21-aarch64-macos-jdk.tar.gz -o corretto-21.tar.gz
curl -fsS https://corretto.aws/downloads/latest_sha256/amazon-corretto-21-aarch64-macos-jdk.tar.gz   # compare
shasum -a 256 corretto-21.tar.gz
tar -xzf corretto-21.tar.gz -C ~/Library/Java/JavaVirtualMachines
groovyscope doctor     # jdk  JDK 21 (Corretto-21.0.x..., 21.0.x) at .../amazon-corretto-21.jdk/Contents/Home
```

Or let groovyscope do it, checksum included: `groovyscope jdk install --vendor corretto`.

## No JDK at hand: let groovyscope fetch one (opt-in)

```bash
groovyscope jdk install                    # latest Temurin 21 (Adoptium API), SHA-256 checked, unpacked in the cache
groovyscope jdk install --vendor corretto  # or Amazon Corretto 21, checked against the SHA-256 Amazon publishes
groovyscope doctor                         # reports it as "provisioned"
```

`jdk.autoProvision: true` in `.groovyscope.yaml` does the same automatically the first time `groovyscope lsp`
finds no JDK meeting `jdk.minimum` (`jdk.vendor: corretto` picks Corretto). A JDK you installed yourself always wins over a provisioned one. Supported:
macOS and Linux on x64 and arm64.

## Per project: `init`, `trust`, `classpath`

All three are optional; the engine answers from the project's own sources without them. The groovy agent's
start hook (`groovyscope checkup --agent`) writes the project's LSP entry itself and offers `trust` and
`classpath` in the first message, so in a Kiro session you only answer yes. The commands below are the same
thing from a terminal.

```bash
cd your-groovy-project
groovyscope init          # .groovyscope.yaml with the source roots and Groovy version the build files declare
groovyscope trust         # record, in your home folder, that this repository may run its build tool
groovyscope classpath     # Gradle (bundled init script) or Maven writes .groovyscope/classpath.txt
groovyscope check         # library classes now resolve; no GS100 "missing dependencies" warnings
```

`init` reads the build files as text and runs nothing; settings you already wrote win over what it detects.
`classpath` runs `./gradlew` (or `gradle`) with the init script `config/gradle/groovyscope-classpath.gradle`,
which registers a `groovyscopeClasspath` task printing the resolved compile and test classpath of every
project plus the compiled class folders, or `mvn dependency:build-classpath` for Maven. Resolution is lenient:
a dependency that cannot be fetched is skipped, and paths that do not exist yet (not built) are reported and
left out. Everything the build needs to configure itself (its Gradle version, a pinned Java toolchain, network
access to its repositories) is needed here too: the command is the build, not a parser of it. The list lands in `workspace.classpathFile` (`.groovyscope/classpath.txt` by default; add it to
`.gitignore`, it holds absolute paths), its entries join `workspace.classpath`, and a running daemon is stopped
so the next request starts with the new classpath. Rerun it after dependency changes. Because the build
scripts execute, the command is refused in a workspace you have not trusted ([security](security.md)).

Until a classpath is configured, imports of library classes fold into one `GS100` warning per file naming the
missing packages, rather than one error per import and per use. `practices.severity: {GS100: off}` silences
it.

## How the JDK is found

In order, the first JDK that meets `jdk.minimum` (17 unless configured higher) wins:

1. `jdk.home` (from your environment, an explicit `--config`, or a trusted workspace's `.groovyscope.yaml`);
2. `JAVA_HOME`;
3. `/usr/libexec/java_home -v <minimum>+` on macOS;
4. `java` on `PATH` (version-manager shims such as asdf, jenv, mise and SDKMAN are followed to the real home);
5. the conventional folders: `~/Library/Java/JavaVirtualMachines` and `/Library/Java/JavaVirtualMachines` on
   macOS, `/usr/lib/jvm`, `/usr/java` and `/opt/java` on Linux;
6. JDKs from `groovyscope jdk install`.

A JDK's version and vendor are read from its `release` file (every modern JDK, Corretto included, has one), so
no JVM is started to find out; `java -version` is the fallback. `JAVA_HOME` and `jdk.home` may point at the JDK
home, at a macOS `.jdk` bundle (its `Contents/Home` is used), at its `bin` folder or at the `java` binary. When
the one you named cannot be used (not a JDK, or older than `jdk.minimum`), `doctor` and every command say so and
name the JDK used instead, rather than switching silently. `doctor` shows the vendor and full version:

```text
ok    jdk          JDK 21 (Corretto-21.0.12.12.1, 21.0.12.1) at ~/Library/Java/JavaVirtualMachines/amazon-corretto-21.jdk/Contents/Home (from JAVA_HOME)
```

## Engine JAR without a wheel: `groovyscope fetch`

The PyPI wheel bundles the engine JAR. A package built elsewhere (a distribution package, a vendored copy)
may not; `doctor` then says `no engine JAR bundled`. Fetch the release JAR into the cache instead:

```bash
groovyscope fetch                                   # the GitHub Release matching the installed version
groovyscope fetch --from https://mirror/groovyscope-engine-0.6.0-all.jar --sha256 <hex>
groovyscope fetch --from /mnt/share/groovyscope-engine-0.6.0-all.jar   # SHA256SUMS.txt next to it is used
```

The download is refused unless its SHA-256 matches `--sha256` or the `SHA256SUMS.txt` published next to the
JAR. Only `https://` sources are downloaded. The fetched JAR is checksummed again on every start, like the
bundled one. `GROOVYSCOPE_JAR` still overrides both (not verified; `doctor` says so).

## Corporate networks

Nothing is downloaded at runtime unless you ask: the wheel contains the engine JAR and its SHA-256, `fetch`
and `jdk install` are explicit commands, and `jdk.autoProvision` is off by default. Mirror the release JAR and
its `SHA256SUMS.txt` on an internal server and point `fetch --from` at it; set `jdk.home` to a managed JDK.

## The benchmark corpus

The wheel bundles CodeNarc 4.0.0's `src/main` (Apache 2.0) as the fixed workspace of
[docs/benchmark.md](benchmark.md) and of the reference prompts. `groovyscope kiro init --global` and the agent
start hook unpack it into `~/.groovyscope/benchmark/codenarc/` with a ready `.groovyscope.yaml`;
`groovyscope benchmark path` prints the location (and unpacks when needed), `--to DIR` puts it elsewhere,
`--refresh` restores the bundled files, and `GROOVYSCOPE_BENCHMARK_DIR` moves the parent folder. A machine
that cannot reach GitHub therefore still has the corpus. No build is included: its library imports show as
GS100 warnings, by design.

## Runtime profiles on a second machine

A private profile stays outside every repository. Copy it with `groovyscope profile install profile.yaml`:
the file is validated against the profile schema and written to `~/.groovyscope/profiles/` readable by you
only (`--force` replaces one of the same name). Then `groovyscope daemon restart` in any open workspace.

## Cache directory

`GROOVYSCOPE_CACHE_DIR` overrides the default (`~/Library/Caches/groovyscope` on macOS,
`$XDG_CACHE_HOME/groovyscope` or `~/.cache/groovyscope` on Linux). It holds the daemon logs, the symbol
caches, the CDS archive, fetched engine JARs (`engine/`) and provisioned JDKs (`jdk/`). Deleting it is safe.
