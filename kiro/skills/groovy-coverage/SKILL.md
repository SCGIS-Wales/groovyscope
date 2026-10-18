---
name: groovy-coverage
description: List the classes and methods no test executes, from the build's JaCoCo report, and start with the ones that matter most.
---
# Untested code

Use for "what has no tests", "coverage gaps". Needs the build's JaCoCo XML (`./gradlew test jacocoTestReport`;
the path is `reports.jacoco` in `.groovyscope.yaml`). Scope: `$ARGUMENTS` when given.

## Steps

1. `groovyscope.coverage_gaps(paths)`: `uncovered` methods have no executed line, `partial` ones have some.
2. For a method to test: `groovyscope.read_symbol` for its body and `groovyscope.call_graph(direction="in")`
   for how it is called, before writing a Spock spec.

## Report

Most important first: public methods of production classes, then the rest. Offer "write a Spock spec for
`<Class.method>`" with behaviour names, `where:` tables and `with(obj)`.
