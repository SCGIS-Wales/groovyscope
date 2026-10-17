# Defensive coding rules for Groovy 5.x and 6.x

These are the policies groovyscope enforces (lint in Stage 3, impact analysis in Stage 4) and the steering
file installs for Kiro agents. The CodeNarc profiles live in `config/codenarc/`.

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

Both profiles load cleanly against CodeNarc 4.0.0 (the engine's specs assert it). The `enhanced` rules are
included; they need the compile classpath, which comes from `workspace.classpath` today and from the Gradle
export task later. `groovyscope check` applies the Jenkins profile to `Jenkinsfile*` and `vars/` scripts
automatically, or everywhere with `--profile jenkins`.

## Formatter

`config/format/greclipse.properties`: four-space indentation, 120 columns, same-line opening braces, unnecessary
semicolons removed. Pair it with `.editorconfig` (already in this repository).
