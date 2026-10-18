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

## Profiles

- `config/codenarc/groovyscope.groovy`: the defensive profile for Groovy 5/6 code (CodeNarc 4.x). Size limits:
  methods 60 lines, cyclomatic complexity 12, 5 parameters, nesting depth 4, lines 120 columns.
- `config/codenarc/jenkins.groovy`: pipeline code runs on Jenkins' CPS Groovy runtime, not Groovy 5/6, so it
  gets its own profile with `rulesets/jenkins.xml`.

Both profiles load cleanly against the embedded CodeNarc (4.0.0 or newer: the build resolves the latest stable
release, see [architecture.md](architecture.md); the engine's specs assert it). The `enhanced` rules are
included; they need the compile classpath, which comes from `workspace.classpath` or from `groovyscope
classpath` (the project's own Gradle or Maven build, in a trusted workspace). `lint.rules` in
`.groovyscope.yaml` switches single rules off or changes their priority on top of either profile
(`{CompileStatic: off, LineLength: {priority: 3}}`), and `lint.publishMaxPriority` keeps priority 3 findings
out of the editor and the agent's diagnostics while `check` and `review` still report them. `groovyscope check` applies the Jenkins profile to `Jenkinsfile*` and `vars/` scripts
automatically, or everywhere with `--profile jenkins`.

## Running the same checks in the build

Gradle 9 still defaults to an older CodeNarc, so pin 4.0.0 explicitly and point it at groovyscope's profile:

```groovy
codenarc {
    toolVersion = '4.0.0'
    config = resources.text.fromFile('config/codenarc/groovyscope.groovy')
}
dependencies {
    codenarc 'org.codenarc:CodeNarc:4.0.0'
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
