# Groovy Code Intelligence for Kiro CLI: Status Verification, Strategic Architecture and Build Plan (17 October 2026)

There is no released, official Apache Groovy language server today, but Kiro CLI can already speak to any stdio LSP server through `.kiro/settings/lsp.json`. The strongest long term design is therefore a hybrid: a warm JVM engine built on the Groovy compiler, CodeNarc and the Groovy Eclipse formatter, exposed to Kiro as an LSP server for navigation and as an MCP server for lint, format, security and impact analysis, with Python limited to the installer, CLI and MCP bridge.

## TL;DR

- **Apache Groovy LSP status:** the merge request you remember is almost certainly apache/groovy PR #2936, "GROOVY-12411: Add a first-party Language Server Protocol implementation (groovy-lsp)" by Daniel Sun. It was opened in September 2026 and was still in review iteration when last checked.\[1\] It is not listed among the Groovy 6.0 modules, and the live `AGENTS.md` on master does not list it.\[2\] Treat it as "in progress, not shipped" and design so you can adopt it later.
- **Kiro CLI mechanism:** Kiro CLI natively spawns arbitrary LSP servers over stdio, configured per workspace in `.kiro/settings/lsp.json`, and exposes them to the agent through the built in `code` tool.\[3\] Groovy is not among the 18 `tree-sitter` languages the Kiro built in tools documentation lists ("Bash, C, C++, C#, Elixir, Go, Java, JavaScript, Kotlin, Lua, PHP, Python, Ruby, Rust, Scala, Swift, TSX, TypeScript"), nor among the preconfigured servers, so without a Groovy LSP the agent falls back to `grep`, `sed` and file reads. MCP servers are configured separately in `.kiro/settings/mcp.json` or in agent profiles.
- **Recommendation:** start this week by registering the community Prominic `groovy-language-server` (or the more active `trustytrojan/groovyls` fork) in `lsp.json`. Then build `groovy-intel`: a JVM daemon on Groovy 6 APIs, CodeNarc 4 and Groovy Eclipse formatting. It speaks LSP to Kiro's `code` tool and serves agent tools (impact analysis, call graph, lint, format, security findings) through a thin Python MCP bridge. Ship it as a PyPI wheel with the shaded JAR bundled, published by release please and PyPI trusted publishing.

## Key Findings

### 1. Verification: did a Groovy language server merge into Apache Groovy?

| Question | Finding | Confidence |
|---|---|---|
| Is there a first party Apache Groovy LSP effort? | Yes. PR #2936 on apache/groovy, tracked as JIRA GROOVY-12411, titled "Add a first-party Language Server Protocol implementation (groovy-lsp)", authored by Daniel Sun (daniellansun), opened 14 September 2026.\[1\] | High\[1\] |
| Was it merged? | Not verified. The fetched PR page showed review iteration, no merged banner, no merge commit and no target branch. | Medium |
| Is `subprojects/groovy-lsp` on master? | Conflicting evidence. A search index snippet of `AGENTS.md` listed a `groovy-lsp` subproject row ("Language Server Protocol, LSP4J transport, compiler-backed features, position encoding, capability policy"). The live fetch of the same file in October 2026 showed only the `groovy-groovysh` subproject guide.\[4\] The most likely reading is that the row was indexed from a transient or cached version. | Medium |
| Did it ship in a release? | No evidence. The Groovy 6.0 release notes summarise the release as "8 new optional modules, 250+ new GDK methods, 13 incubating features, tested on JDK 17-27". The module table lists ten entries, eight new optional modules plus two split out of core (`groovy-callsite`, `groovy-concurrent-java`, `groovy-csv`, `groovy-grape-ivy`, `groovy-grape-maven`, `groovy-http-builder`, `groovy-markdown`, `groovy-reactor`, `groovy-rxjava`, `groovy-test-junit6`), and none is an LSP. Groovy 7.0 release notes exist as a page, but their content could not be fetched.\[5\] | High for 6.0, unverified for 7.0\[5\] |
| Did Prominic's server get donated to Apache? | No. `GroovyLanguageServer/groovy-language-server` remains an independent Apache 2.0 repository with no published releases.\[6\]\[7\] | High\[6\] |

What the Apache effort looks like, from the PR discussion:

- **Design:** Daniel Sun describes it as "A compiler-backed Language Server for Groovy: same in-tree `CompilationUnit` (through `SEMANTIC_ANALYSIS`, no Groovy classgen), client-supplied classpath, conservative edits", with compiler level joint Groovy and Java compilation through `JavaAwareCompilationUnit`.\[1\]
- **Explicit non goals:** "Not GDSL, not Gradle/Maven invocation, not DAP, not a test runner, not identifier-scan rename. Not a full Java language server."\[1\]
- **Configuration:** settings live in the `groovy` section of `workspace/didChangeConfiguration`. The keys are `classpath`, `sourcePaths`, and `grapeEnabled` and `astTestEnabled` (both default `false`, a sensible security posture).\[1\]
- **Commits and CI:** commits 2b7a787 (initial implementation) and ce5b6ae (lint gate fixes); two flaky Windows JDK 17 tests in `:groovy-lsp:test` failing with an `InvalidPathException` on a drive letter path.\[1\]
- **PMC direction (most important for your strategy):** Paul King proposed a "first Minimal Viable Product (MVP)" with the extension SPI dropped. He framed the durable asset as "a language-services library, with the LSP server as its first client", from which "Console, groovysh, a notebook kernel, an MCP server" could pull answers. He also proposed surfacing `NullChecker`, `PurityChecker` and `SqlInjectionChecker` results as diagnostics, and flagged "a trust question around classpath inference from workspace contents that I'd want closed before any release".\[1\]
- **Response:** Daniel Sun reported that the MVP cut was implemented, and that "`groovy-mcp` itself only needs to wrap the process".\[1\]
- **Review concerns:** Jochen Theodorou (blackdrag) questioned scope and warned that the server "will most probably have performance problems with anything but a small project".\[1\]
- **Not verified:** merge date, target branch, JIRA fix version, Maven coordinates (no `org.apache.groovy:groovy-lsp` artefact was found), the JDK floor for the subproject specifically, and any dev mailing list thread. The repository wide build requires JDK 17+.

Your previous conversations and steering documents were not available to me as a knowledge source, so I could not cross check your earlier discussion. The PR above is the only Apache candidate that matches your recollection.

### 2. Kiro CLI: exactly how code intelligence, LSP and MCP work today

**The `code` tool.**
- The CLI ships a built in `code` tool that combines `tree-sitter` analysis (symbol search, document symbols, AST pattern search and pattern rewrite) with optional LSP integration for "find references, go-to-definition, rename, and diagnostics".\[8\]\[9\]
- The `tree-sitter` layer covers 18 languages: Bash, C, C++, C#, Elixir, Go, Java, JavaScript, Kotlin, Lua, PHP, Python, Ruby, Rust, Scala, Swift, TSX and TypeScript.\[9\]\[10\] **Groovy is absent**, so the structural pattern search and rewrite tools do not work on `.groovy` files today.

**LSP transport.**
- The documentation is explicit: "Kiro CLI spawns LSP server processes in the background that communicate via JSON-RPC over stdio."\[3\]
- `/code init` creates `.kiro/settings/lsp.json`. After that, the CLI "automatically initializes code intelligence on startup when `.kiro/settings/lsp.json` exists in the workspace".\[3\]
- Configuration is per workspace, not global. `chat.enableCodeIntelligence` lives in `~/.kiro/settings/cli.json`, and "V3 enables knowledge and code intelligence by default".\[3\]\[11\]\[12\]

**Custom servers.**
- Custom servers are declared under `languages` with these fields: `name`, `command`, `args`, `file_extensions`, `file_patterns`, `project_patterns`, `exclude_patterns`, `multi_workspace`, `initialization_options` and `request_timeout_secs` (default 60).\[3\] `file_patterns` matches filenames without a standard extension (for example `Jenkinsfile`).
- After editing, restart Kiro CLI.\[3\]
- Diagnostic commands: `/code status`, `/code logs -l ERROR` and `/code init -f` (restarts all servers).\[3\]

**Agent access.**
- If a custom agent lacks the tool, Kiro reports "Code tool is not enabled for this agent". The fix is to add `"code"`, `@builtin` or `@builtin/code` to the agent's tools.\[3\]
- Symbol lookups inside the workspace run without prompts. Renames and formats ask per file and refuse stale edits.\[3\]

**Known rough edges** (from GitHub issues; verify against your build):
- Issue #6040 (Apex) reported hardcoded file extensions and a missing `rootUri`/`workspaceFolders` in `initialize`;\[13\] the current documentation's `file_extensions` field suggests the first point has been addressed.
- Issue #10684 reports that `/code init` overwrites custom entries in `lsp.json`.\[14\] Commit `lsp.json` to git and avoid rerunning `/code init` after customising.

**IDE versus CLI.**
- In the IDE, LSP capabilities come from installed editor extensions. A practitioner report confirms that "the Kiro IDE does not use these LSP settings".\[3\]\[15\]
- `.kiro/steering` and `.kiro/settings/mcp.json` are shared across both.\[16\]

**MCP.**
- Servers live in `.kiro/settings/mcp.json` (workspace) and `~/.kiro/settings/mcp.json` (global). Precedence is "Agent Config > Workspace MCP JSON > Global MCP JSON".\[17\]
- Agent and MCP configurations hot reload on save.\[17\]
- V3 agent profiles (JSON or Markdown) support `tools`, `excludedTools`, `includeMcpJson`, `resources` (for example `file://.kiro/steering/**/*.md`), `permissions` and `hooks`. MCP servers support stdio and HTTP, with `timeout` in milliseconds and `${VAR}` expansion.\[18\]

**Steering.**
- `.kiro/steering/*.md` and `AGENTS.md` are loaded automatically for the default agent.\[19\]
- "When using custom agents, steering files are not automatically included". You must list them in `resources`.\[19\]

**Version context.**
- The official CLI changelog shows weekly releases into October 2026, with V3 being rolled out gradually ("This release shows a startup prompt inviting you to switch to V3").\[20\] A third party tracker also lists a "v3.0.0" on 5 October 2026 alongside 2.30.0 on 12 October, so run `kiro-cli --version` locally.\[21\]

**Answer to your core question.** Kiro CLI does natively speak LSP to arbitrary servers. The agent does not see raw LSP. It calls the `code` tool, which Kiro translates into LSP requests. MCP is a separate, complementary channel for anything LSP cannot express: call graphs, impact reports, lint, security findings and formatting policy.

### 3. Open source building blocks: what to reuse

| Component | Licence | Activity (2026) | Groovy 5/6 | Interface | Verdict |
|---|---|---|---|---|---|
| Prominic `GroovyLanguageServer/groovy-language-server` | Apache 2.0 | Repository updated 19 May 2026; 235 stars and 89 forks on GitHub; no GitHub releases | A search snippet of its `build.gradle` shows `org.apache.groovy:groovy:4.0.26` with LSP4J 0.12.0 (not confirmed by a full fetch), so a Groovy 5/6 upgrade is needed | LSP over stdio via LSP4J; completion, definition, documentSymbol, hover, references, rename, signatureHelp, symbol, typeDefinition; settings `groovy.java.home`, `groovy.classpath`\[22\] | **Use now** as the Kiro LSP; fork as seed |
| `trustytrojan/groovyls` (fork) | Apache 2.0 | Active, PR merged September 2026 (closed file definitions, default argument overloads) | Not verified | LSP; adds Jenkins GDSL, semantic tokens, better hover and definition | **Best drop in today** if it builds cleanly against your code\[23\]\[24\] |
| `albertocavalcante/gvy` | Apache 2.0 | 821 commits, 209 open issues, 2 stars; self described "LSP wip"\[25\] | Requires Java 17+, Groovy 4.0+\[25\] | Kotlin monorepo, LSP 3.17 with LSP4J 0.24.0; diagnostics via compiler plus CodeNarc, OpenRewrite formatting, Jenkins and Spock modules\[25\] | **Mine for ideas and modules**; too early and too single maintainer to depend on |
| `tomaszrup` Groovy language server (EDT) | Not verified | Published on VS Code Marketplace | Targets Groovy 5.0\[26\] | Headless Eclipse Equinox with patched JDT; JDK 21+; needs Red Hat Java extension for classpath\[26\] | Accurate but heavy and VS Code coupled; **benchmark only**\[26\] |
| Apache `groovy-lsp` (PR #2936) | Apache 2.0 | In review | Same compiler as the language | LSP4J; compiler backed; conservative rename | **Plan to adopt** when released |
| Eclipse LSP4J | EPL 2.0 / EDL | Mature | n/a | JVM library | **Reuse** for protocol |
| Groovy compiler APIs (`CompilationUnit`, `ClassCodeVisitorSupport`, STC) | Apache 2.0 | Groovy 6.0.0 released September 2026 | Native | JVM API; Groovy 6 adds `groovyc --error-format short` and a "Fluent AST Query API (incubating)" | **Core engine**\[2\] |
| CodeNarc 4.0.0 (released 18 July 2026 on GitHub, 19 July on Maven Central) | Apache 2.0 | Active; 4.1.0 in progress fixing Groovy 5 rule crashes | "#804: Support Groovy 5.x". The JDK floor for the default artefact is inconsistent across official sources: the CHANGELOG says "requires at least Java 11 at runtime", while codenarc.org says "Java 17 or later for the default CodeNarc artifact (Groovy 5)" | JVM API, CLI, Gradle plugin | **Core lint engine**, run in process |
| npm-groovy-lint 18.0.0 (30 June 2026) | MIT | Active | Bundles CodeNarc 3.7.0 as of 16.0.0, so it lags CodeNarc 4 | Node CLI with a warm CodeNarcServer; `--fix`, `--format`, SARIF output | **Reuse its fix rules as reference**, not as a runtime dependency\[27\]\[28\] |
| Spotless `greclipse()` | Apache 2.0 | Active. Issue #2950 (opened 29 May 2026) reports that "groovyGradle/greclipse fails with 'roundtripStateInternal or equalityStateInternal should be non-null'" from 8.6.0, only on a fresh cache. Issue #2952 proposes "officially deprecating the GrEclipse-based formatter". Plugin 8.10.3 had shipped by October 2026, and 8.10.0 includes "Fix GrEclipse initialization warnings (#3097)" | Default Groovy Eclipse 5.7.0 pulls Groovy 5.0.0 | Gradle and Maven plugins, plus a library | **Formatter of record** for CI; watch the deprecation discussion |
| Groovy Eclipse (Greclipse) 6.3.0 | EPL | The groovy-eclipse wiki records "September 30th, 2026, Groovy-Eclipse 6.3.0 released!" and "September 22nd, 2026, Groovy 6.0.0 and Groovy 5.1.3 support is available in the latest snapshot builds" | Yes | Eclipse plugin and formatter | **Formatter engine** via Spotless |
| IntelliJ Groovy plugin | Proprietary integration | Active | Yes | Not reusable | Feature benchmark only\[29\] |
| `tree-sitter-groovy` | MIT (community grammars) | Not verified in this research | Partial syntax only | C library with Python bindings | Optional fast fallback for broken files; **not** a semantic source |

Sonar's Groovy plugin and Jenkins pipeline linters were not verified in this research and are out of scope for terminal agent use. If any Unity Deploy logic runs as Jenkins pipeline code, it executes on Jenkins' embedded CPS Groovy runtime, not on Groovy 5 or 6, so lint it with a separate profile (CodeNarc's Jenkins rules).

### 4. Groovy platform facts that drive the design

- **Groovy 5.0:** announced 24 August 2025. "Groovy 5 requires JDK17+ to build and JDK11 is the minimum version of the JRE that we support", tested on JDK 11 to 25.\[30\]\[31\] Groovy 5.1 followed in August 2026, with 5.1.3 in September 2026.\[32\]
- **Groovy 6.0.0:** the official changelog dates the aggregate release 2026-09-22; endoflife.date lists 19 September 2026.\[32\]\[33\] "JDK 17 is now the minimum supported runtime", tested across JDK 17 to 27.\[2\]
- **Groovy 6 static analysis features relevant to defensive coding:**
  - a new `NullChecker` "with an optional flow-sensitive `strict` mode";\[2\]
  - `@Modifies` and `@Pure` verified by `ModifiesChecker` and `PurityChecker`;\[2\]
  - the `SqlInjectionChecker` at compile time plus runtime rejection in `groovy-sql`;\[2\]
  - `@SafeRegex` regex timeouts against ReDoS;\[2\]
  - secure by default XML parsing;\[2\]
  - the `val` keyword for final declarations;\[2\]
  - nested `copyWith` for `@Immutable` and records.\[2\]
- **Explicit agent framing:** the Groovy 6 release notes include a section titled "Designed for Human and AI Reasoning", and they describe `groovyc --error-format short` as emitting "one `file:line:column: severity: message` line per diagnostic, the shape editors, CI log parsers and coding agents already understand".\[2\]
- **Security posture:** Apache Groovy's own agent guide warns that "compiling a reproducer is not a safe halfway step short of running it" because global AST transforms, `@Grab` and `@ASTTest` execute at compile time.\[4\] Any analysis daemon must therefore default `groovy.grape.enable=false` and `groovy.asttest.enable=false`.
- **Your JDK baseline:** pick **JDK 21 LTS** as the daemon runtime. It satisfies Groovy 6's JDK 17 floor and CodeNarc 4's runtime floor, whichever official figure applies (the CodeNarc CHANGELOG says Java 11, codenarc.org says Java 17). It also enables virtual threads. Your steering doc's Java 8 era guidance is obsolete for any Groovy 5 or 6 target.

## Strategic Architecture Recommendation

### Options compared

| Criterion | (a) Adopt and extend community LSP only | (b) Python MCP server driving a JVM engine | (c) Hybrid: JVM engine speaking LSP plus a thin Python MCP bridge |
|---|---|---|---|
| Works with Kiro `code` tool (definition, references, rename, diagnostics, hover) | Yes | No (agent must call MCP tools instead) | Yes |
| Impact analysis, call graph, lint, format, security as structured tools | No (LSP has no impact or lint policy verbs that Kiro surfaces) | Yes | Yes |
| One warm JVM, shared index | Yes | Yes | Yes (single daemon per workspace) |
| Upgrade path to Apache `groovy-lsp` | Swap server | Swap engine | Swap the LSP front end, keep tools |
| Fit with your Python preference | Low | High | Python owns the CLI, installer, config and MCP layer |

**Verdict: option (c).** It is the only option that lets Kiro's native `code` tool work for Groovy and also gives the agent higher order, token cheap answers ("what breaks if I change this signature"). It also mirrors the shape the Apache PMC itself is converging on: one language services library with LSP and MCP as clients.

### Language choice per component

| Component | Language | Why |
|---|---|---|
| Parsing, symbol resolution, type inference, references, call graph, CodeNarc, formatting | **JVM (Groovy 6 with `@CompileStatic`, or Java 21)** | Groovy's AST, static type checker and CodeNarc exist only on the JVM. Reimplementing semantics in Python, Rust or Go would lag every Groovy release, which is exactly the "community servers lag the language" problem the Apache PR calls out. |
| LSP protocol endpoint | JVM (LSP4J) | Same process as the index; no serialisation hop. |
| Daemon lifecycle, CLI, YAML configuration, installer, JAR and JDK discovery, MCP server | **Python 3.12+** (functional style, `click`, official MCP Python SDK) | Lightweight, your strength, ideal for packaging on PyPI and for composing tool results into compact JSON for the agent. |
| Rust or Go | **Not recommended** | They add a third runtime with no access to Groovy semantics. |

Python is the right choice for the edges and the wrong choice for the core. Every semantic question must be answered by the JVM.

### Performance and reliability design

- **Warm daemon:** one JVM per workspace root, started on first use. It listens on a Unix domain socket under `$XDG_RUNTIME_DIR/groovy-intel/` (macOS: `~/Library/Caches/groovy-intel/`) and exits after a configurable idle timeout. This is the same pattern as the Gradle daemon and npm-groovy-lint's CodeNarcServer (which "auto kills itself after 1h idle").\[34\]
- **LSP entry point:** Kiro's `lsp.json` command is `groovy-intel lsp`. That is a Python relay that connects stdio to the daemon socket, or, with `daemon.enabled: false`, execs the JVM directly. LSP traffic and MCP traffic then hit the same index and the same memory.
- **Incremental compilation:** compile to `SEMANTIC_ANALYSIS` or `CANONICALIZATION` only (never class generation). Do it per changed file, with a dependency graph so dependents recompile lazily. Cache the symbol and reference index on disk (SQLite) keyed by file content hash, so a cold start reuses the last index.
- **Startup latency and memory:** use JDK 21 with Class Data Sharing (`-XX:SharedArchiveFile`, created at first run with `-XX:ArchiveClassesAtExit`) and a default heap of `-Xmx1g`, configurable in YAML.
- **Safety:** Grape and `@ASTTest` off by default; global AST transforms from the project classpath loaded only when `trust.workspace: true`; classpath taken from YAML or a Gradle export task, never inferred silently (this mirrors Paul King's trust concern).
- **Token economy:** every MCP tool returns compact, paginated JSON (paths relative to the root, line and column, a symbol kind, and at most N results plus a continuation token). It never returns file bodies. One `impact_of` call replaces many cycles of `grep -rn`, `sed -n` and file reads. I found no published benchmark quantifying Kiro credit savings from LSP use, so measure it yourself: compare credits for the same five tasks with and without the server.

## Step by Step Implementation

### Phase 0 (today, about one hour): get Groovy navigation into Kiro CLI with an existing server

**Ubuntu 22.04 or 24.04**

```bash
sudo apt-get update && sudo apt-get install -y curl unzip zip git
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
sdk install java 21.0.8-tem     # any JDK 21 LTS build; list with: sdk list java
sdk install groovy 6.0.0
curl -LsSf https://astral.sh/uv/install.sh | sh
java -version && groovy --version && uv --version
```

**macOS (Homebrew)**

```bash
brew install openjdk@21 groovy uv git
sudo ln -sfn "$(brew --prefix)/opt/openjdk@21/libexec/openjdk.jdk" /Library/Java/JavaVirtualMachines/openjdk-21.jdk
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
java -version && groovy --version
```

**Build the community server** (pick one; `groovyls` is the more active fork):

```bash
mkdir -p ~/.local/share/groovy-lsp && cd ~/.local/share/groovy-lsp
git clone https://github.com/trustytrojan/groovyls.git && cd groovyls
./gradlew build -x test
ls build/libs/   # expect groovyls-all.jar (Prominic upstream produces groovy-language-server-all.jar)
cat > ~/.local/bin/groovyls <<'EOF'
#!/usr/bin/env bash
exec java -Xms256m -Xmx1g -Dgroovy.grape.enable=false \
  -jar "$HOME/.local/share/groovy-lsp/groovyls/build/libs/groovyls-all.jar" "$@"
EOF
chmod +x ~/.local/bin/groovyls
```

**Configure Kiro CLI** (in the repository root):

```text
kiro-cli
> /code init
```

Then replace or merge `.kiro/settings/lsp.json` and commit it (issue #10684 means `/code init` can overwrite custom entries):

```json
{
  "languages": {
    "groovy": {
      "name": "groovyls",
      "command": "groovyls",
      "args": [],
      "file_extensions": ["groovy", "gvy", "gy", "gsh", "gradle"],
      "file_patterns": ["Jenkinsfile", "Jenkinsfile.*", "*.groovy.template"],
      "project_patterns": ["settings.gradle", "build.gradle", "pom.xml", ".git"],
      "exclude_patterns": ["**/build/**", "**/.gradle/**", "**/target/**", "**/node_modules/**"],
      "multi_workspace": false,
      "initialization_options": {},
      "request_timeout_secs": 120
    }
  }
}
```

These servers read the classpath from the `groovy.classpath` workspace setting.\[35\] Whether Kiro forwards `workspace/configuration` is not verified; if it does not, add the jars in the wrapper script.

**Verify:**

```text
> /code status          # expect: ✓ groovyls (groovy) - initialized
> /code logs -l ERROR   # empty is good
> Find the definition of ApigeeProxyDeployer and list every reference to its deploy method
```

If a custom agent is used, ensure its `tools` include `"code"` or `"@builtin"`.

### Phase 1 (week one): add lint, format and security as tools, without writing a server yet

Install CodeNarc 4 and Spotless in the Gradle build. Then expose them through a Kiro hook or a minimal MCP server (Phase 2 replaces this).

`build.gradle` excerpt:

```groovy
plugins {
    id 'groovy'
    id 'codenarc'
    id 'com.diffplug.spotless' version '8.10.3' // 8.10.0+ includes GrEclipse fixes (#3097); retest issue #2950 on a fresh cache
}

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

dependencies {
    implementation 'org.apache.groovy:groovy:6.0.0'
    codenarc 'org.codenarc:CodeNarc:4.0.0'
}

codenarc {
    configFile = file('config/codenarc/groovy-intel.groovy')
    reportFormat = 'sarif'
    ignoreFailures = false
    maxPriority1Violations = 0
    maxPriority2Violations = 0
}

spotless {
    groovy {
        target 'src/**/*.groovy', 'vars/**/*.groovy'
        importOrder('java|javax', 'groovy', 'org', 'com', '')
        removeSemicolons()
        greclipse().configFile('config/format/greclipse.properties')
        trimTrailingWhitespace()
        endWithNewline()
    }
    groovyGradle {
        target '*.gradle', '**/*.gradle'
        greclipse().configFile('config/format/greclipse.properties')
    }
}
```

Check the `sarif` report format and `removeSemicolons()` step against your plugin versions; fall back to `xml` and the formatter if unavailable.

### Phase 2 (weeks two to six): build `groovy-intel` with Claude Code

Use the prompt in the "Claude Code Build Prompt" section below. When it is done:

```bash
uv tool install groovy-intel         # or: pipx install groovy-intel
groovy-intel doctor                  # checks JDK 21, JAR checksum, socket dir, Kiro files
cd ~/src/unity-deploy
groovy-intel kiro init               # writes .kiro/settings/lsp.json, .kiro/settings/mcp.json,
                                     # .kiro/agents/groovy.json, .kiro/steering/groovy-intel.md,
                                     # .groovy-intel.yaml (merging, never clobbering)
kiro-cli --agent groovy
```

Kiro files that `groovy-intel kiro init` must produce:

`.kiro/settings/lsp.json`

```json
{
  "languages": {
    "groovy": {
      "name": "groovy-intel",
      "command": "groovy-intel",
      "args": ["lsp", "--stdio"],
      "file_extensions": ["groovy", "gvy", "gy", "gsh", "gradle"],
      "file_patterns": ["Jenkinsfile", "Jenkinsfile.*"],
      "project_patterns": [".groovy-intel.yaml", "settings.gradle", "build.gradle", "pom.xml"],
      "exclude_patterns": ["**/build/**", "**/.gradle/**", "**/target/**"],
      "multi_workspace": false,
      "initialization_options": { "configFile": ".groovy-intel.yaml" },
      "request_timeout_secs": 120
    }
  }
}
```

`.kiro/settings/mcp.json`

```json
{
  "mcpServers": {
    "groovy-intel": {
      "command": "groovy-intel",
      "args": ["mcp", "--config", ".groovy-intel.yaml"],
      "env": { "GROOVY_INTEL_LOG": "warning" },
      "timeout": 120000
    }
  }
}
```

`.kiro/agents/groovy.json`

```json
{
  "name": "groovy",
  "description": "Groovy 5/6 engineer that navigates code through LSP and groovy-intel tools instead of text search",
  "tools": ["read", "write", "shell", "code", "@groovy-intel"],
  "includeMcpJson": true,
  "resources": [
    "file://.kiro/steering/**/*.md",
    "file://AGENTS.md"
  ],
  "permissions": {
    "rules": [
      { "capability": "mcp", "match": ["groovy-intel/*"], "effect": "allow" },
      { "capability": "shell", "match": ["./gradlew *", "groovy-intel *", "git status", "git diff *"], "effect": "allow" },
      { "capability": "shell", "match": ["grep *", "rg *", "sed *", "awk *", "head *", "tail *", "find * -name *.groovy*"], "effect": "ask" }
    ]
  },
  "welcomeMessage": "Groovy agent ready. Code intelligence: LSP + groovy-intel."
}
```

The permission effect values other than `allow` (for example `ask` or `deny`) and the `@server` tool selector syntax should be confirmed against the V3 permissions documentation for your installed version.

`.kiro/steering/groovy-intel.md`

```markdown
---
inclusion: always
---
# Groovy navigation and quality policy
- For any Groovy, Gradle or Jenkinsfile question, use the `code` tool (find references, go to definition,
  hover, diagnostics, rename with dry run) or `groovy-intel` MCP tools. Do not use grep, rg, sed, awk,
  head or tail to locate Groovy symbols.
- Before changing a public method, class or closure parameter, call `groovy-intel.impact_of` and report
  affected files.
- After every edit to a .groovy file, call `groovy-intel.check` on the changed files and fix all
  priority 1 and 2 findings before continuing.
- Format only through `groovy-intel.format`; never hand align code.
- Target Groovy 6.0 on JDK 21 (code must also compile on Groovy 5.1 / JDK 17 unless marked otherwise).
  Prefer @CompileStatic; justify every @CompileDynamic in a comment.
- Never enable @Grab or @ASTTest; never build SQL with string concatenation; never deserialise untrusted data.
```

### Example Kiro CLI interactions

```text
> Where is ApigeeProxyDeployer.deploy defined, and who calls it?
  (using tool: code) Go to definition -> src/main/groovy/deploy/ApigeeProxyDeployer.groovy:42:5
  (using tool: code) Find references -> 7 references in 4 files

> If I add a required "environmentGroup" parameter to deploy(), what breaks?
  (using tool: groovy-intel/impact_of) 3 direct callers, 2 transitive callers, 1 Spock spec, 0 dynamic call sites
  flagged as unresolved (review manually): vars/deployProxy.groovy:18 (dynamic dispatch)

> Dry run: rename resolveTargetServer to resolveTargetServers
  (using tool: code) Dry run: would rename 9 occurrences in 5 files

> Check the files I changed for lint, bugs and security problems
  (using tool: groovy-intel/check) 2 errors (CodeNarc security: InsecureRandom; Groovy 6 NullChecker),
  4 warnings (design: ReturnsNullInsteadOfEmptyCollection ...)

> Format src/main/groovy/deploy and show me only the diff summary
  (using tool: groovy-intel/format) 3 files changed, 41 lines
```

## Defensive Coding Ruleset for Groovy 5.x and 6.x

### Policies the tool enforces

1. **Static by default.** Compile production code with `@CompileStatic` through a compiler configuration script, not per class annotations:

```groovy
// config/groovyc/compile-static.groovy  (groovyc --configscript, or GroovyCompile.groovyOptions.configurationScript)
withConfig(configuration) {
    ast(groovy.transform.CompileStatic)
}
```

   Allow `@CompileDynamic` only on builders and DSL entry points, with a justification comment. CodeNarc's `CompileStatic` convention rule checks that classes carry an explicit choice.

2. **Null safety.**
   - On Groovy 6, enable `@TypeChecked(extensions = 'groovy.typecheckers.NullChecker')` in strict mode for new modules.
   - Use `?.` and Elvis `?:` only at boundaries.
   - Return empty collections, never `null` (CodeNarc `ReturnsNullInsteadOfEmptyCollection`, `ReturnsNullInsteadOfEmptyArray`).

3. **Immutability.** Use `record` (Groovy 4+) or `@Immutable` for value types, the `val` keyword on Groovy 6 for local finals, and nested `copyWith` instead of setters. Fields default to `final`.

4. **Injection.**
   - Use `groovy.sql.Sql` with GString parameters (which bind as prepared statement parameters) or explicit `?` placeholders. Never concatenate SQL Strings.
   - Enable Groovy 6's `SqlInjectionChecker` where `groovy-sql` is used.
   - Never pass user input into `Eval`, `GroovyShell`, `String.execute()` or templates.
   - Avoid GStrings as map keys (CodeNarc `GStringAsMapKey`).

5. **Deserialisation.** Do not use `ObjectInputStream` on untrusted data. Where unavoidable, install a JDK `ObjectInputFilter` allow list. Prefer JSON with typed parsing (Groovy 6 typed parsing across JSON, CSV, TOML, YAML and XML).\[2\]

6. **Closures and delegates.** Annotate DSL parameters with `@DelegatesTo(strategy = Closure.DELEGATE_FIRST)` and `@ClosureParams` so the static type checker can resolve them. Avoid implicit `it` in multi line closures. Never rely on the owner fallback for side effects.

7. **Dynamic dispatch risk.** Flag `def` on public API, `methodMissing` and `propertyMissing`, and metaclass mutation in production code. Treat unresolved call sites as "unknown impact" in impact analysis rather than guessing (the same conservative stance as the Apache PR's "refuse-to-guess rename").

8. **Regex and parsing.** Use `@SafeRegex` (Groovy 6) on code that applies patterns to external input, and secure by default XML parsing.\[2\]

9. **Supply chain.** `@Grab` is off in tooling and banned in production code. Resolve dependencies through Gradle with verification metadata.

### CodeNarc 4 ruleset (`config/codenarc/groovy-intel.groovy`)

```groovy
ruleset {
    description 'groovy-intel defensive profile for Groovy 5.x and 6.x (CodeNarc 4.x)'

    ruleset('rulesets/basic.xml')
    ruleset('rulesets/braces.xml')
    ruleset('rulesets/concurrency.xml')
    ruleset('rulesets/convention.xml') {
        'CompileStatic' { priority = 2 }
        'NoDef' { priority = 2; doNotApplyToFilesMatching = '.*/(vars|scripts)/.*\\.groovy' }
        exclude 'TrailingComma'
        exclude 'ImplicitClosureParameter'
        exclude 'IfStatementCouldBeTernary'
        exclude 'CouldBeSwitchStatement'
    }
    ruleset('rulesets/design.xml') {
        exclude 'Instanceof'
        exclude 'NestedForLoop'
    }
    ruleset('rulesets/exceptions.xml')
    ruleset('rulesets/formatting.xml') {
        'LineLength' { length = 120 }
        exclude 'ClassStartsWithBlankLine'
        exclude 'ClassEndsWithBlankLine'
        exclude 'SpaceAroundMapEntryColon'
    }
    ruleset('rulesets/groovyism.xml')
    ruleset('rulesets/imports.xml')
    ruleset('rulesets/jdbc.xml')
    ruleset('rulesets/logging.xml')
    ruleset('rulesets/naming.xml') {
        exclude 'FactoryMethodName'
    }
    ruleset('rulesets/security.xml')
    ruleset('rulesets/serialization.xml')
    ruleset('rulesets/size.xml') {
        'MethodSize' { maxLines = 60 }
        'CyclomaticComplexity' { maxMethodComplexity = 12 }
        'ParameterCount' { maxParameters = 5 }
        'NestedBlockDepth' { maxNestedBlockDepth = 4 }
        exclude 'CrapMetric'     // needs a Cobertura report
    }
    ruleset('rulesets/unnecessary.xml') {
        exclude 'UnnecessaryReturnKeyword'  // explicit returns preferred in typed code
    }
    ruleset('rulesets/unused.xml')
    ruleset('rulesets/enhanced.xml')        // requires the compile classpath (the daemon supplies it)
}
```

Keep a separate `jenkins.groovy` profile (`rulesets/jenkins.xml`) for pipeline code, and run `codenarc` once with this file to prune any rule that CodeNarc 4 reports as unknown.

### Formatter configuration (`config/format/greclipse.properties`)

```properties
org.eclipse.jdt.core.formatter.tabulation.char=space
org.eclipse.jdt.core.formatter.tabulation.size=4
org.eclipse.jdt.core.formatter.indentation.size=4
org.eclipse.jdt.core.formatter.lineSplit=120
org.eclipse.jdt.core.formatter.comment.line_length=120
groovy.formatter.braces.start=same
groovy.formatter.braces.end=next
groovy.formatter.multiline.indentation=2
groovy.formatter.longListLength=30
groovy.formatter.remove.unnecessary.semicolons=true
```

Pair it with an `.editorconfig` (`indent_style = space`, `indent_size = 4`, `max_line_length = 120`, `insert_final_newline = true`). Four space indentation without tabs matches Apache Groovy's own convention ("Groovy source uses 4-space indent, no tabs").\[4\] Validate the `groovy.formatter.*` keys against the Groovy Eclipse version that Spotless resolves.\[36\]

## Packaging and Distribution

| Option | Plug and play | Corporate network friendly | Verdict |
|---|---|---|---|
| **PyPI wheel with the shaded JAR bundled** (`uv tool install groovy-intel`) | One command on Ubuntu and macOS | No runtime downloads; checksum verified at build | **Primary** |
| PyPI wheel plus JAR fetched from GitHub Releases at first run | Smaller wheel | Blocked by egress proxies; adds a trust step | Fallback via `groovy-intel fetch --from <url>` |
| JDK auto provisioning (Adoptium API; npm-groovy-lint's 18.0.0 changelog of 30 June 2026 notes that after "Upgrade java-caller to v5 (#584)… the JRE that java-caller auto-downloads now comes from Eclipse Temurin (api.adoptium.net)") | Convenient | Often blocked; a licence review is needed | Opt in only (`jdk.autoProvision: false` default) |
| Homebrew tap (`depends_on "openjdk@21"`) | Good on macOS | Good | **Secondary**, generated from the release |
| Debian package, jbang or SDKMAN | Heavier to maintain | Mixed | Defer; use `uv tool install` in images |

A pure Python wheel that contains a platform independent JAR stays a `py3-none-any` wheel, so one artefact serves Ubuntu and macOS. The JDK is the only external prerequisite, and `groovy-intel doctor` checks it.

## CI/CD Design (GitHub Actions)

1. **Pull requests:** `ci.yml` (ruff, mypy, pytest with a coverage gate, Gradle build with JUnit 5, Spock, JaCoCo and CodeNarc on Ubuntu and macOS), `semantic-pr.yml` (Conventional Commit PR titles, squash merge), `codeql.yml` (Python and Java/Kotlin) and weekly Dependabot for `pip`/`uv`, `gradle` and `github-actions`.
2. **After merge to `main`:** `release-please.yml` (googleapis `release-please-action`, manifest mode, `release-type: python`, JAR version synced through `extra-files`) maintains a release PR with the audited `CHANGELOG.md`. Merging it creates the `vX.Y.Z` tag and the GitHub Release.
3. **On release published:** `publish.yml` builds the shaded JAR and CycloneDX SBOMs, runs `uv build`, attests provenance with `actions/attest-build-provenance` (Sigstore), publishes to PyPI with `pypa/gh-action-pypi-publish` using **trusted publishing** (OIDC, `id-token: write`, a protected `pypi` environment, no API tokens), and uploads all artefacts and checksums to the GitHub Release.
4. **Optional:** publish the engine JAR to Maven Central through the Central Portal (JReleaser or the `vanniktech` plugin) with GPG signing.

These practices were not re-verified against live PyPA and GitHub documentation in this session; pin action versions by commit SHA.

## Claude Code Build Prompt (ready to paste)

```text
You are building "groovy-intel": production grade Groovy code intelligence for AI coding agents, first
consumer AWS Kiro CLI (V3). Reuse open source components; do not reinvent parsers, linters or formatters.

GOAL
Give the agent semantic, token cheap answers about Groovy 5.x/6.x code (symbols, definitions, references,
call graph, impact of a change, diagnostics, lint, format, security findings) so it stops using grep, sed,
head and tail. Two front ends over one warm JVM engine:
  1. An LSP server (stdio) that Kiro CLI spawns from .kiro/settings/lsp.json and uses through its built in
     `code` tool (definition, references, rename, diagnostics, hover, document/workspace symbols, completion).
  2. An MCP server (stdio) that exposes higher order tools Kiro cannot express through LSP.

REPOSITORY LAYOUT (monorepo)
groovy-intel/
  engine/                         # JVM, Gradle (Kotlin DSL), JDK 21 toolchain, bytecode target 17
    settings.gradle.kts, build.gradle.kts, gradle/libs.versions.toml (version catalog)
    src/main/java or src/main/groovy (Groovy sources must be @CompileStatic)
      org/groovyintel/engine/
        workspace/   WorkspaceModel (roots, source sets, classpath from config), FileIndex (content hashes)
        compile/     IncrementalCompiler: org.codehaus.groovy.control.CompilationUnit up to
                     Phases.CANONICALIZATION, never class generation; per file invalidation with a
                     dependency graph; CompilerConfiguration with groovy.grape.enable=false and
                     groovy.asttest.enable=false; global AST transforms disabled unless trust.workspace=true
        index/       SymbolIndex + ReferenceIndex built with ClassCodeVisitorSupport; persisted in SQLite
                     (xerial sqlite-jdbc) keyed by content hash for warm restarts
        semantics/   resolution via STC metadata (StaticTypesMarker.INFERRED_TYPE,
                     DIRECT_METHOD_CALL_TARGET); unresolved dynamic calls recorded as UNKNOWN, never guessed
        callgraph/   CallGraph (incoming/outgoing), ImpactAnalyzer (transitive callers, overriding methods,
                     Spock specs touching a symbol, confidence per edge: STATIC | INFERRED | DYNAMIC_UNKNOWN)
        lint/        CodeNarc 4.x in process (org.codenarc:CodeNarc:4.0.0) with a ruleset file path from
                     config; maps violations to diagnostics with rule id, priority, ruleset, fix hint
        typecheck/   optional Groovy 6 type checking extensions (groovy.typecheckers.NullChecker,
                     PurityChecker, ModifiesChecker, SqlInjectionChecker) surfaced as diagnostics
        format/      Groovy Eclipse formatting via Spotless library (com.diffplug.spotless:spotless-lib and
                     spotless-lib-extra GrEclipseFormatterStep) with the properties file from config;
                     returns LSP TextEdits, never rewrites files itself
        lsp/         LSP4J (org.eclipse.lsp4j 0.24.x) server: initialize honours rootUri/workspaceFolders and
                     falls back to cwd; capabilities: definition, typeDefinition, references, rename +
                     prepareRename (refuse when target UNKNOWN), hover, documentSymbol, workspaceSymbol,
                     completion, signatureHelp, publishDiagnostics + pull diagnostics, formatting,
                     codeAction (CodeNarc quick fixes), callHierarchy, semanticTokens
        daemon/      Unix domain socket server (JDK 16+ UnixDomainSocketAddress), one per workspace, idle
                     timeout; speaks LSP JSON-RPC plus custom methods "groovyIntel/*" for the MCP bridge
      SEED: fork the Apache 2.0 Prominic server (github.com/GroovyLanguageServer/groovy-language-server) and
      the trustytrojan/groovyls fork for the AST visitor, position lookup and provider code; keep NOTICE and
      licence headers; upgrade to Groovy 6.0.x APIs. Study albertocavalcante/gvy (Apache 2.0) for CodeNarc
      diagnostics wiring and test scenarios. Keep the engine API independent of LSP4J types so it can later be
      swapped for, or contribute to, Apache Groovy's groovy-lsp (GROOVY-12411, PR #2936).
    src/test/   JUnit 5 + Spock 2.4 for Groovy 4/5 compatible tests; fixtures under src/test/resources/projects/
    shadowJar (com.gradleup.shadow) producing engine/build/libs/groovy-intel-engine-<ver>-all.jar
    JaCoCo with a 85% line coverage gate; CodeNarc on the engine's own code using the shipped ruleset
  python/
    pyproject.toml (PEP 621, build backend hatchling, package data includes groovy_intel/_jar/*.jar,
                    console script: groovy-intel = groovy_intel.cli:main), uv.lock
    src/groovy_intel/
      cli.py        click group: doctor, lsp (--stdio relay to daemon or direct exec), mcp, daemon
                    {start,stop,status}, check, format, impact, kiro init, config validate, version
      config.py     load and validate .groovy-intel.yaml (PyYAML + jsonschema), defaults in
                    src/groovy_intel/defaults.yaml; env overrides GROOVY_INTEL_*; no hardcoded values
      java.py       JDK discovery (JAVA_HOME, PATH, macOS /usr/libexec/java_home); require >= 17,
                    recommend 21; optional opt in Temurin provisioning
      jar.py        locate bundled JAR, verify SHA256 from a generated _manifest.json
      daemon.py     start/connect/health check/stop; socket path under XDG_RUNTIME_DIR or ~/Library/Caches
      relay.py      asyncio stdio <-> Unix socket byte relay for `lsp --stdio`
      rpc.py        minimal JSON-RPC client (Content-Length framing) to the daemon
      mcp_server.py MCP server using the official `mcp` Python SDK (FastMCP decorators on plain functions)
      kiro.py       idempotent writers/mergers for .kiro/settings/lsp.json, .kiro/settings/mcp.json,
                    .kiro/agents/groovy.json, .kiro/steering/groovy-intel.md (merge JSON, never clobber,
                    back up originals, print a diff)
      render.py     compact output shaping: relative paths, line:col, kinds, truncation with continuation
                    tokens, token budget per response (config: output.maxResults, output.maxBytes)
    tests/          pytest, pytest-cov (gate 90%), hypothesis for config and render, a fake daemon fixture,
                    and an integration test that boots the real JAR against fixture projects
    STYLE: functional Python, no user defined classes (use dicts, tuples, functools, pure functions; the only
    allowed class statements are TypedDict declarations if a library schema needs them); type hints
    everywhere; ruff (lint + format) and mypy --strict clean.
  config/
    codenarc/groovy-intel.groovy      # defensive ruleset (Groovy 5.x/6.x), plus codenarc/jenkins.groovy
    format/greclipse.properties       # 4 spaces, 120 columns, same line braces, remove semicolons
    groovyc/compile-static.groovy     # withConfig(configuration) { ast(groovy.transform.CompileStatic) }
    defaults.yaml                     # the documented default .groovy-intel.yaml
  kiro/                               # templates installed by `groovy-intel kiro init`
    lsp.json, mcp.json, agents/groovy.json, steering/groovy-intel.md
  docs/ install.md (Ubuntu, macOS), kiro.md, rules.md, architecture.md, security.md, troubleshooting.md
  .github/ workflows (see below), dependabot.yml, CODEOWNERS, PULL_REQUEST_TEMPLATE.md
  release-please-config.json, .release-please-manifest.json, CHANGELOG.md, LICENSE (Apache-2.0), NOTICE

MCP TOOLS (all return compact JSON; never return whole files)
  find_symbol(query, kind?, limit?)                 -> [{name, kind, path, line, col, container}]
  goto_definition(path, line, col)                  -> location
  find_references(symbol | path+line+col, limit?)   -> locations + count + confidence
  call_graph(symbol, direction=in|out, depth=2)     -> nodes/edges with confidence
  impact_of(symbol, change=signature|rename|delete|behaviour) -> direct/transitive callers, overrides,
                                                       tests, dynamic unknowns, risk score, suggested checks
  check(paths?, profile=default|jenkins, include=lint,compile,typecheck,security) -> diagnostics grouped
                                                       by severity and rule, with fix hints
  format(paths, apply=false)                        -> per file edit counts + unified diff summary
  find_dynamic_code(paths?)                         -> classes/methods without @CompileStatic, metaclass
                                                       usage, methodMissing, @Grab, Eval/GroovyShell
  workspace_status()                                -> daemon, index freshness, classpath, Groovy/JDK versions

CONFIGURATION (.groovy-intel.yaml, everything overridable; nothing hardcoded)
  groovy: { version: "6.0", compat: ["5.1"] }
  jdk: { home: null, minimum: 17, autoProvision: false }
  workspace: { sourceRoots: ["src/main/groovy","src/test/groovy","vars"], classpath: [],
               classpathFrom: { gradleTask: null }, exclude: ["**/build/**"], trust: false }
  daemon: { enabled: true, idleTimeoutMinutes: 60, heap: "1g", cds: true }
  lint: { ruleset: "config/codenarc/groovy-intel.groovy", jenkinsRuleset: "config/codenarc/jenkins.groovy",
          failOnPriority: 2 }
  typecheck: { nullChecker: "strict", sqlInjection: true, purity: false }
  format: { engine: greclipse, properties: "config/format/greclipse.properties" }
  output: { maxResults: 50, maxBytes: 16000 }
  logging: { level: warning, file: null }

PACKAGING
  The release builds the shaded JAR, copies it into python/src/groovy_intel/_jar/, writes _manifest.json
  (version, sha256), then `uv build` produces a py3-none-any wheel and an sdist. Users install with
  `uv tool install groovy-intel` or `pipx install groovy-intel`.

GITHUB ACTIONS
  ci.yml: on pull_request and push to main; matrix ubuntu-latest, macos-latest; setup-java (temurin 21),
    gradle/actions/setup-gradle, astral-sh/setup-uv; run ./gradlew build (tests, JaCoCo gate, CodeNarc),
    uv run ruff check, ruff format --check, mypy, pytest --cov --cov-fail-under=90; upload SARIF from
    CodeNarc to code scanning.
  semantic-pr.yml: enforce Conventional Commits on PR titles (amannn/action-semantic-pull-request).
  codeql.yml: languages python and java-kotlin.
  dependabot.yml: pip (uv), gradle, github-actions; weekly; grouped minor/patch.
  release-please.yml: on push to main, googleapis/release-please-action (manifest mode, release-type python,
    extra-files to bump the engine version in gradle.properties); produces release PR, tag vX.Y.Z, GitHub
    Release with changelog.
  publish.yml: on release published; jobs: build (JAR + wheel + sdist + CycloneDX SBOMs + sha256sums),
    attest (actions/attest-build-provenance for all artefacts), pypi (environment "pypi", permissions
    id-token: write, pypa/gh-action-pypi-publish with trusted publishing, attestations enabled; no tokens),
    release-assets (upload JAR, wheel, sdist, SBOMs, checksums to the GitHub Release), homebrew (open a PR to
    the tap). Pin every third party action to a full commit SHA. Least privilege permissions per job.

TESTS AND QUALITY GATES
  Fixture projects: plain Groovy 6 app; Groovy 5.1 library; Gradle multi project; Spock specs; Jenkins shared
  library (vars/, src/); a project with @CompileDynamic DSLs; a broken file (parse error) to prove graceful
  degradation. Golden tests for every MCP tool and LSP request. Performance tests: warm `find_references`
  under 150 ms and incremental re-check of one file under 500 ms on a 2,000 file fixture (report, do not
  fail CI on shared runners). Security tests: @Grab and @ASTTest never execute; a malicious global AST
  transform on the classpath is not loaded when trust=false.

DOCS AND KIRO INTEGRATION
  `groovy-intel kiro init` writes the four Kiro files from templates (merge, back up, diff) and prints next
  steps (`kiro-cli --agent groovy`, then `/code status`). Warn that `/code init` may overwrite custom
  lsp.json entries (Kiro issue #10684); offer `groovy-intel kiro repair`.

DELIVERY ORDER
  1) engine compile + index + definition/references over LSP with tests; 2) daemon + Python relay + Kiro
  init; 3) CodeNarc + formatter + diagnostics; 4) call graph + impact; 5) MCP server; 6) packaging and
  workflows; 7) docs. Commit with Conventional Commits; open small PRs; keep coverage gates green.
```

## Caveats

- **Apache `groovy-lsp` status is not fully verified.** I could not fetch the JIRA issue, the subproject directory or the Groovy 7.0 release notes. Recheck PR #2936 and GROOVY-12411 monthly. If it ships, swap your LSP front end to it and keep the MCP tools.
- **Kiro specifics may move quickly.** The CLI releases weekly. Field names in `lsp.json`, agent `permissions` effects and the `@server` tool selector should be validated against `kiro-cli --version` and the live documentation. Issues #6040 and #10684 indicate integration gaps for non bundled languages, so test `/code status` and a references query on day one.
- **Community servers' Groovy versions are likely behind.** Prominic's server has no releases, and a search snippet of its `build.gradle` shows Groovy 4.0.26 with LSP4J 0.12.0 (not confirmed by a full fetch). Syntax introduced in Groovy 5 or 6 may therefore produce false diagnostics until you upgrade the dependency.
- **Spotless greclipse:** issue #2950, opened 29 May 2026, reports a regression from 8.6.0 that appears only on a fresh cache. Version 8.10.0 includes "Fix GrEclipse initialization warnings (#3097)", and issue #2952 proposes "officially deprecating the GrEclipse-based formatter". Pin a tested plugin version, retest on a clean cache, and track the deprecation discussion.
- **Token and credit savings** from LSP use are plausible but unmeasured in any public source I found. Benchmark them on your own repositories.

## Sources

1. [GROOVY-12411: Add a first-party Language Server Protocol implementation (groovy-lsp) by daniellansun · Pull Request #2936 · apache/groovy · GitHub](https://github.com/apache/groovy/pull/2936)
2. [The Apache Groovy programming language - Groovy 6.0 release notes](https://groovy-lang.org/releasenotes/groovy-6.0.html)
3. [Code intelligence - Built-in tools - Features - Docs - Kiro](https://kiro.dev/docs/tools/code-intelligence/)
4. [groovy/AGENTS.md at master · apache/groovy](https://github.com/apache/groovy/blob/master/AGENTS.md)
5. [The Apache Groovy programming language - Release notes](https://groovy-lang.org/releases.html)
6. [GroovyLanguageServer · GitHub](https://github.com/GroovyLanguageServer)
7. [Releases · GroovyLanguageServer/groovy-language-server · GitHub](https://github.com/GroovyLanguageServer/groovy-language-server/releases)
8. [Built-in tools - Features - Docs - Kiro](https://kiro.dev/docs/tools/)
9. [Changelog - Page 12 - Kiro](https://kiro.dev/changelog/page/12/)
10. [Skills, Custom Diff Tools, Improved Code Intelligence, and Conversation Compaction - Kiro](https://kiro.dev/changelog/cli/1-24/)
11. [Settings - Commands and Reference - Docs - Kiro](https://kiro.dev/docs/reference/settings/)
12. [What's new in V3 - CLI - Docs - Kiro](https://kiro.dev/docs/cli/v3/)
13. [Add Apex Language Support to Code Intelligence · Issue #6040 · kirodotdev/Kiro · GitHub](https://github.com/kirodotdev/Kiro/issues/6040)
14. [CLI Code Intelligence: built-in C#/Roslyn LSP support (+ non-destructive \`/code init\`, \`DOTNET\_ROOT\` fix) · Issue #10684 · kirodotdev/Kiro · GitHub](https://github.com/kirodotdev/Kiro/issues/10684)
15. [Using C#/Roslyn LSP with Kiro-CLI](https://www.jasonpenniman.com/roslyn-kiro)
16. [Bring Kiro agents to your terminal with Kiro CLI - Kiro](https://kiro.dev/blog/introducing-kiro-cli/)
17. [Configuration - MCP - Features - Docs - Kiro](https://kiro.dev/docs/mcp/configuration/)
18. [Agent config changes - What's new in V3 - CLI - Docs - Kiro](https://kiro.dev/docs/cli/v3/agent-config/)
19. [Steering - Features - Docs - Kiro](https://kiro.dev/docs/steering/)
20. [CLI changelog - Kiro](https://kiro.dev/changelog/cli/)
21. [Kiro CLI Changelog: Latest v2.30.0 (Oct 12, 2026)](https://www.havoptic.com/tools/kiro)
22. [GitHub - GroovyLanguageServer/groovy-language-server: A language server for Groovy — designed for Moonshine IDE, but may be useful in other environments · GitHub](https://github.com/GroovyLanguageServer/groovy-language-server)
23. [GitHub - trustytrojan/groovyls: Fork of groovy-language-server trying to provide the same DX as Eclipse JDT LS · GitHub](https://github.com/trustytrojan/groovyls)
24. [Fix closed file definitions (and more) by trustytrojan · Pull Request #6 · trustytrojan/groovyls · GitHub](https://github.com/trustytrojan/groovyls/pull/6)
25. [GitHub - albertocavalcante/gvy: Tools for Apache Groovy (LSP wip)](https://github.com/albertocavalcante/gvy)
26. [Language Support for Groovy - Visual Studio Marketplace](https://marketplace.visualstudio.com/items?itemName=TomaszRup.groovy-spock-support)
27. [GitHub - tysontrinh-tru/npm-groovy-lint: Lint, format and auto-fix your Groovy / Jenkinsfile / Gradle files using command line](https://github.com/tysontrinh-tru/npm-groovy-lint)
28. [Changelog - NPM Groovy Lint](https://nvuillam.github.io/npm-groovy-lint/CHANGELOG/)
29. [IntelliJ IDEA](https://en.wikipedia.org/wiki/IntelliJ_IDEA)
30. [The Apache Groovy programming language - Groovy 5.0 release notes](https://groovy-lang.org/releasenotes/groovy-5.0.html)
31. [\[ANNOUNCE\] Apache Groovy 5.0.0 Released!-Apache Mail Archives](https://lists.apache.org/thread/b61fxz91c9qod5c05sfbp8x0lphqhmol)
32. [Apache Groovy](https://endoflife.date/apache-groovy)
33. [The Apache Groovy programming language - Changelogs](http://groovy-lang.org/changelogs.html)
34. [NPM GROOVY LINT (and FIX !)](https://www.npmjs.com/package/npm-groovy-lint/v/3.2.1)
35. [groovy-language-server/README.md at master · GroovyLanguageServer/groovy-language-server · GitHub](https://github.com/GroovyLanguageServer/groovy-language-server/blob/master/README.md)
36. [spotless/plugin-maven/README.md at main · diffplug/spotless · GitHub](https://github.com/diffplug/spotless/blob/main/plugin-maven/README.md)
