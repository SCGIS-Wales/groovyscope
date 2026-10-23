---
name: groovy-tests
description: Add only the tests a change needs: test_plan for what reaches it and what is missing, data tables before new feature methods, coverage of every changed class at 80% with groovyscope verify --coverage.
---
# Tests for a change

Use for "write tests for this", "raise coverage", "what tests do I need", and before finishing a change. Target:
`$ARGUMENTS` (a method as `Class.method`, or nothing for the branch's changed methods).

## Steps

1. `groovyscope.test_plan(symbol)` (or `test_plan()` for the branch): the tests that already reach each method,
   unit apart from integration with their commands, the reaching tests that repeat another, and `needed`.
2. Add only what `needed` lists, one test per behaviour. Extend an existing `where:` table (or
   `@ParameterizedTest`) before adding a feature method; delete or merge a test `redundant` names.
3. `groovyscope.explain_code` on the method under test for its collaborators and how to replace them; stub only
   package-private or public methods.
4. `groovyscope verify --coverage` (shell): the tests run, and every changed class must reach 80% line coverage;
   it names the classes under it and their missed lines. Repeat from step 1 for what is still missing.
5. A changed public method updates its integration tests in the same change: `groovyscope verify --integration`,
   or list the ones that need what this machine lacks.

## Report

The tests added and why each was needed, the coverage per changed class before and after, and the integration tests
left to the pipeline.
