# Defensive coding rules for Groovy 5.x and 6.x

These are the policies groovyscope enforces (through lint, the type checkers, its GS practice checks, impact
analysis and `find_dynamic_code`) and the steering file installs for Kiro agents. The full knowledge base,
with the reasoning, examples and enforcement map for each practice, is [practices.md](practices.md); code
under a runtime with its own Groovy and conventions is covered by [profiles.md](profiles.md). The CodeNarc
profiles live in `config/codenarc/`.

1. **Static by default.** Compile production code with `@CompileStatic` through the configuration script
   `config/groovyc/compile-static.groovy`. Allow `@CompileDynamic` only on builders and DSL entry points, with
   a justification comment. CodeNarc's `CompileStatic` rule checks that classes carry an explicit choice.
2. **Null safety.** Groovy 6: `NullChecker` in strict mode for new modules. Use `?.` and `?:` only at
   boundaries. Return empty collections, never `null` (`ReturnsNullInsteadOfEmptyCollection`).
3. **Immutability.** `record` or `@Immutable` for value types, `val` (Groovy 6) for local finals, nested
   `copyWith` instead of setters.
4. **Injection.** `groovy.sql.Sql` with GString parameters or `?` placeholders; never concatenate SQL. Enable
   Groovy 6's `SqlInjectionChecker`. Never pass user input into `Eval`, `GroovyShell`, `String.execute()` or
   templates. No GStrings as map keys.
5. **Deserialisation.** No `ObjectInputStream` on untrusted data; if unavoidable, an `ObjectInputFilter`
   allow list. Prefer typed JSON parsing.
6. **Closures and delegates.** `@DelegatesTo` and `@ClosureParams` on DSL parameters so the type checker can
   resolve them. No implicit `it` in multi-line closures.
7. **Dynamic dispatch risk.** Flag `def` on public API, `methodMissing`, `propertyMissing` and metaclass
   mutation in production code. Unresolved call sites are "unknown impact", never guessed.
8. **Regex and parsing.** `@SafeRegex` (Groovy 6) on patterns applied to external input; secure-by-default XML.
9. **Supply chain.** `@Grab` is off in tooling and banned in production code; dependencies through Gradle
   with verification metadata.
10. **Every edit is gated.** A write that adds a syntax, compile or type error, repeats existing code, or is mostly
    comment is reported before it lands (`hooks.preWrite.mode: block` refuses it); edits go through `edit_symbol`,
    `change_signature`, `extract_method` and `move_member` so call sites change together.
11. **Concise comments.** Why, never what; one line; Javadoc one sentence on public members (GS060 to GS065).
12. **One kind per place.** Declared types, no mixed kinds in a variable, key, comparison, argument or return
    (GS070 to GS077).
13. **Lean tests, 80% coverage.** One test per behaviour, data tables before new feature methods, no repeated tests,
    every changed class at 80% line coverage, integration tests updated with the signature they call (GS044 to
    GS049).
14. **State with its methods.** Small, cohesive classes; no feature envy, public mutable state or stateless
    instance methods (GS090 to GS097).

## Profiles

- `config/codenarc/groovyscope.groovy`: the defensive profile for Groovy 5/6 code (CodeNarc 4.x). Size limits:
  methods 60 lines, cyclomatic complexity 12, 5 parameters, nesting depth 4, lines 120 columns. The JUnit rules
  run too; the ones that misread Spock (a feature asserts in its `then:` and `expect:` blocks) skip `*Spec`
  files, and `ImplementationAsType` is advice at priority 3.
- `config/codenarc/jenkins.groovy`: pipeline code runs on Jenkins' CPS Groovy runtime, not Groovy 5/6, so it
  gets its own profile with `rulesets/jenkins.xml` and the same size limits.
- `config/codenarc/gradle.groovy`: Gradle build scripts (`*.gradle`) are a dynamic DSL, so their profile leaves
  out `NoDef`, `CompileStatic` and the `*TypeRequired` rules. `lint.gradleRuleset` replaces it.

The profiles load cleanly against the embedded CodeNarc (`groovyscope version` names it; the engine pins it in
its version catalogue, see [architecture.md](architecture.md), and its specs assert the profiles load). The `enhanced` rules are
included; they need the compile classpath, which comes from `workspace.classpath` or from `groovyscope
classpath` (the project's own Gradle or Maven build, in a trusted workspace). `lint.rules` in
`.groovyscope.yaml` switches single rules off or changes their priority on top of any profile
(`{CompileStatic: off, LineLength: {priority: 3}}`), and `lint.publishMaxPriority` keeps priority 3 findings
out of the editor and the agent's diagnostics while `check` and `review` still report them. `groovyscope check` applies the Jenkins profile to `Jenkinsfile*`, `vars/` scripts and the
`src/` classes of a shared library, and the Gradle profile to `*.gradle`, automatically; `--profile` picks one for
every file.

### Licence header

`lint.licenceHeader` (null by default) is a regular expression every file linted with the default profile must
contain; CodeNarc's `RequiredRegex` rule checks it and reports a missing header as `LicenceHeader`, priority 2.
Anchor it at the start of the file, for example `"\\A/\\*\\n \\* Copyright"`. A pattern that does not compile is
reported once as `lint-config` and no header is required.

## Running the same checks in the build

Gradle 9 still defaults to an older CodeNarc, so pin the release groovyscope embeds (`groovyscope version`
prints it as `codenarc <version>`) and point the plugin at groovyscope's profile:

```groovy
def codenarcVersion = '<the version groovyscope version prints>'
codenarc {
    toolVersion = codenarcVersion
    config = resources.text.fromFile('config/codenarc/groovyscope.groovy')
}
dependencies {
    codenarc "org.codenarc:CodeNarc:${codenarcVersion}"
    codenarc 'org.apache.groovy:groovy:5.1.3'
}
```

For CI annotations, `groovyscope check --format sarif > groovyscope.sarif` produces SARIF 2.1.0 with the rule
metadata of every finding, and `--format short` prints `file:line:col: severity: message [code]` like
`groovyc --error-format short`.

## Formatter

`groovyscope format` ships the `basic` engine: tabs to spaces, trailing whitespace and extra blank lines
removed, one final newline, semicolons removed where CodeNarc flagged them. It never re-flows code, so it is
safe to run from an agent. `config/format/greclipse.properties` (four-space indentation, 120 columns,
same-line opening braces, unnecessary semicolons removed) is the matching Groovy Eclipse configuration for
teams that run that formatter through Spotless in their build; it is not bundled in the engine (see
docs/architecture.md). Pair either with `.editorconfig` (already in this repository).
