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

## No JDK at hand: let groovyscope fetch one (opt-in)

```bash
groovyscope jdk install            # latest Temurin 21 from the Adoptium API, SHA-256 checked, unpacked in the cache
groovyscope doctor                 # reports it as "provisioned"
```

`jdk.autoProvision: true` in `.groovyscope.yaml` does the same automatically the first time `groovyscope lsp`
finds no JDK meeting `jdk.minimum`. A JDK you installed yourself always wins over a provisioned one. Supported:
macOS and Linux on x64 and arm64.

## How the JDK is found

In order: `jdk.home` in `.groovyscope.yaml`, `JAVA_HOME`, `/usr/libexec/java_home -v 17+` (macOS), `java` on
`PATH`, then JDKs under the cache directory from `groovyscope jdk install`. The first one that meets
`jdk.minimum` wins. `doctor` reports which one was chosen.

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

## Cache directory

`GROOVYSCOPE_CACHE_DIR` overrides the default (`~/Library/Caches/groovyscope` on macOS,
`$XDG_CACHE_HOME/groovyscope` or `~/.cache/groovyscope` on Linux). It holds the daemon logs, the symbol
caches, the CDS archive, fetched engine JARs (`engine/`) and provisioned JDKs (`jdk/`). Deleting it is safe.
