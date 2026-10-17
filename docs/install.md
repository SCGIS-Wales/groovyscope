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

## macOS (Homebrew)

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

## How the JDK is found

In order: `jdk.home` in `.groovyscope.yaml`, `JAVA_HOME`, `/usr/libexec/java_home -v 17+` (macOS), then
`java` on `PATH`. The first one that is 17 or newer wins. `doctor` reports which one was chosen.

## Corporate networks

Nothing is downloaded at runtime. The wheel contains the engine JAR and its SHA-256, which `doctor` verifies.
Set `GROOVYSCOPE_JAR=/path/to/groovyscope-engine-<ver>-all.jar` to run a JAR from elsewhere (not checksum
verified; `doctor` says so).

## Interim option: a community Groovy language server

Until Stage 1 lands, groovyscope's LSP answers only the lifecycle. For navigation today you can register the
community server from the research instead (`trustytrojan/groovyls`, Apache 2.0) in
`.kiro/settings/lsp.json`; see the research document under `docs/research/` for the exact entry.
