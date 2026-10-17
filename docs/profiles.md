# Runtime profiles

Some Groovy is not run by the build that compiles it. A plugin platform may load plugin classes at run time
with `GroovyClassLoader.parseClass()`, on an older Groovy, with a smaller classpath and its own conventions
for parameters and outputs. A local green build then proves little: a construct can compile on Groovy 6 and
fail on the runtime's Groovy 5, a library can be on the compile classpath and missing at run time, and a
renamed parameter can break operators and downstream systems that depend on it.

A **runtime profile** describes such a runtime. groovyscope uses it to report what the local compiler cannot
know (RP codes), to fix the mechanical cases, to navigate between code and its descriptor, and to steer the
agent. Profiles are plain YAML or JSON files, so a profile for an in-house platform can stay private: keep it
in your home directory and nothing about the platform enters the repository.

## Where profiles are found

In this order; the first profile with a given `name` wins:

1. the files and directories listed under `profiles.paths` in `.groovyscope.yaml`;
2. `<workspace>/.groovyscope/profiles/*.yaml|*.yml|*.json`;
3. `~/.groovyscope/profiles/*.yaml|*.yml|*.json`.

`profiles.discover: false` limits the search to the configured paths. Check what is active with:

```bash
groovyscope profile list
groovyscope profile validate ~/.groovyscope/profiles/my-runtime.yaml
groovyscope profile status path/to/SomePlugin.groovy
groovyscope doctor                     # the "profiles" line
```

The daemon reads profiles when it starts; run `groovyscope daemon stop` after editing one.
[config/profiles/example-runtime.yaml](../config/profiles/example-runtime.yaml) is a complete, commented
example; the schema is `python/src/groovyscope/profile-schema.json`.

## What a profile says

| Section | Meaning |
|---|---|
| `activation` | A Groovy file is governed when it matches one of `files` (globs) or `imports` (prefixes) **and** one of the `markers` (for example the plugin descriptor) is found in its directory or above. A file is governed by at most one profile. |
| `target` | `groovy`: the version the runtime runs. `groovy6Checkers`: `on`, `advisory` (warnings at most) or `off` for governed files. `compiledBy`: labels naming the service that compiles a file, used in messages. `emulationJars`: the runtime's own Groovy jars (and libraries) for the emulation compile. |
| `constructs` | What is known about each construct on the runtime: `status` is `blocked`, `unproven`, `discouraged`, `proven-elsewhere` or `proven`, matched by `imports`, `classes`, `calls`, `constructors` or `syntax` (`switch-arrow`, `multi-catch`, `extra-class`, `nested-class`, `record`, `sealed`, `as-coercion:<Type>`), optionally only in some `files`. `evidence` and `replacement` go into the message. |
| `descriptor` | The file that declares the parameters the runtime supplies: where the list is, the field names, the typed `accessors` (method, name and type argument positions), `typeMap` (accessor class to descriptor types), `rawMaps` that should not be read directly, and `forbiddenValues`. |
| `infrastructure` | Terraform variable files: no `default` and no `validation` block for variables the descriptor already supplies. |
| `secrets` | Name fragments that mark a value as secret, and the sinks it must not reach: logs, exception text, `maps`, `calls`. |
| `network` | Network calls and the only methods allowed to make them (so retry, timeouts and logging stay in one place). |
| `sdk` | Hints: hand-written code the platform's SDK already provides. |
| `codenarc.disable` | CodeNarc rules switched off for governed files only. |
| `contract` | What `groovyscope baseline save` records: exception classes whose texts are frozen, frozen methods, the output calls whose keys are frozen, and where the tests that define seams live. |
| `protected` | Methods, or regions between two marker comments, that fixes and code actions never edit. |
| `guidance` | Text for the agent; `groovyscope kiro init` writes it to `steering/groovyscope-profiles.md`. |

## Findings

| Code | Default | Finding | Fix |
|---|---|---|---|
| RP001 | by status | A construct the runtime blocks, has not proven, discourages, or has proven only in another service | |
| RP002 | error | Groovy syntax or API newer than `target.groovy` (GS010 against the profile's version) | |
| RP004 | error | A forbidden value in the descriptor | |
| RP010 | error | A parameter read that the descriptor does not declare | |
| RP011 | error | A typed read whose class does not match the declared type | |
| RP012 | warning | A fallback (`?:`) on a declared parameter, which the runtime always supplies | removes it |
| RP013 | warning | A declared parameter read from a raw map | typed accessor |
| RP014 | error | A Terraform `validation` block for a descriptor-supplied variable | |
| RP015 | warning | A Terraform `default` for a descriptor-supplied variable | |
| RP020 | error | A recorded contract item that changed or disappeared | |
| RP021 | error | A test seam whose visibility changed | |
| RP022 | error | A secret-named value flowing into a log, exception text, map or call | |
| RP030 | info | Code that duplicates an SDK capability | |
| RP032 | warning | A network call outside the designated methods | |
| RP050 | warning | The file does not compile with the runtime's own Groovy (`target.emulationJars`) | |

Severities follow `practices.severity` in `.groovyscope.yaml` (for example `RP030: off`). `groovyscope
explain RP010` prints the why and the fix for any code.

## Contract baseline

```bash
groovyscope baseline save     # writes .groovyscope/contract-baseline.json; commit it
groovyscope baseline check    # exit 1 when something recorded changed or disappeared
```

The baseline records, per governed file: parameter names and types, the class, constructors, frozen and
`@Override` method signatures, test seam visibility, exception texts and output keys. Additions are never
findings; removals and changes are (RP020, RP021), in `check`, in `review` and in the editor.

## Emulation compile

With `target.emulationJars` set to the runtime's Groovy jar (and any library jars it provides), `check`
compiles the governed files with that Groovy, in an isolated class loader, up to class generation. Nothing it
produces is loaded or run, Grape and `@ASTTest` stay off, and AST transformations from the workspace
classpath load only with `workspace.trust: true`. Each compile error becomes an RP050 finding. Results are
cached until a governed file changes. groovyscope never downloads these jars: point at the ones your build
cache already holds, for example under `~/.gradle/caches/modules-2/files-2.1/org.apache.groovy/groovy/`.

## Navigation in governed files

On the name argument of a typed accessor (`getParameter('TOPIC_NAME', String.class)` in the example):

- **go to definition** jumps to the descriptor entry and the matching Terraform variable;
- **find references** lists every read of the parameter in governed files, plus the declarations;
- **hover** shows the declared type, default, description and how many places read it.

## For agents

The MCP tool `profile_status(path)` returns the profile, target Groovy, compiling service, descriptor
parameters, test seams, protected methods, whether a baseline is recorded, and the guidance. `review` names
the profiles governing the paths it was given. The steering file tells the agent to call `profile_status`
before editing a governed file and to treat RP001 and RP002 as blocking.
