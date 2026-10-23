# Edit gate: facts from Kiro and baseline numbers (2026-10-23)

Phase 0 of the edit intelligence plan (Stages 24 onwards in [docs/roadmap.md](../roadmap.md)). Two kinds of
facts calibrate the gate: what Kiro CLI sends a hook and does with its answer, and how much comment and test code
real Groovy projects carry.

## What Kiro sends and does

Captured with `groovyscope hook --capture`, which saves every payload it receives under
`<cache>/hooks/<event>-<timestamp>.json` (readable by the user only) and then runs the hook as usual. To capture,
add `--capture` to the agent's hook commands (`"command": "groovyscope hook --capture"`), attach a
`preToolUse` hook with matcher `write` and a `userPromptSubmit` hook to the agent, then in one session: create a
file, replace a line in it (`str_replace`), insert a line (`insert`), and send one prompt. Send the files from
`~/.cache/groovyscope/hooks/` (`~/Library/Caches/groovyscope/hooks/` on macOS).

| Question | Documented (Kiro docs, gap analysis of 2026-10-18) | Confirmed on the owner's Kiro |
|---|---|---|
| `write` field names in `tool_input` | `command` (`create`, `str_replace`, `insert`, `append`), `path`, `file_text`, `old_str`, `new_str`, `insert_line` | pending the capture |
| `PreToolUse` exit 2 blocks the write and shows stderr to the model | yes | pending |
| `UserPromptSubmit` stdout reaches the model's context | yes | pending |
| A multi-line MCP elicitation message in the terminal UI | rendered as text | pending |

Until confirmed, the gate reads both the documented names and the V3 spellings (`file_path`, `content`), treats
an unknown `command` as a write it cannot rebuild (it lets it through and says so), and defaults to
`hooks.preWrite.mode: warn`, so a wrong guess about exit 2 never blocks a session.

## Baseline numbers

Measured with `scripts/edit_gate_metrics.py` (method ranges from the engine's `groovyscope/parse`, comments
from a small lexer) on the bundled CodeNarc 4.0.0 `src/main`, a mature, well commented project:

Files: 529, methods and constructors: 1821.

| Measure | Count | Median | P90 | Max |
|---|---|---|---|---|
| commentLinesPerMethod | 1821 | 0.00 | 0.00 | 8.00 |
| commentDensityOfMethodsOver5Lines | 1149 | 0.00 | 0.03 | 0.80 |
| longestCommentRun | 1821 | 0.00 | 0.00 | 7.00 |
| javadocLines | 175 | 2.00 | 5.00 | 21.00 |
| testToCodeRatio | 0 | 0.00 | 0.00 | 0.00 |
| testMethodsPerClass | 0 | 0.00 | 0.00 | 0.00 |

- methodsWithDensityOver025: 13
- methodsWithRunOver2: 10
- javadocOver3: 40
- testClasses: 0

Javadoc lines count text lines only (the `/**` and `*/` lines are not counted).

What the numbers mean for the defaults of Stage 25 (comments):

- `comments.maxDensity: 0.25` flags 13 of 1,149 methods of five or more lines (1.1%): kept.
- `comments.maxBlockLines: 2` flags 10 of 1,821 methods (0.5%): kept.
- `comments.maxJavadocLines: 3` would flag 40 of 175 documented members (23%) of a project whose Javadoc is
  deliberate; the default becomes 5, the 90th percentile, which leaves 10% and still stops the multi paragraph
  blocks an agent writes.

Not measurable on this corpus, measured later:

- Test lines against main lines and test methods per class: the corpus has no tests. Measured on the owner's
  project when it is available, and on the fixture in the meantime.
- Variables assigned values of more than one type: needs the static type checker's metadata, measured with
  GS071 in Stage 26.

## Fixture

`engine/src/test/resources/projects/edit-gate`, copied byte for byte in `python/tests/fixtures/edit-gate` (a
test compares them): a verbose method (restating comments, a long comment block, Javadoc on a private method with
a restating tag, commented out code, a banner, a bare TODO), a String compared with an int, a `def` reassigned
from a number to a String, a List read with `.length`, a String used as a boolean, mixed return types, a map
key written with two kinds of value, a block copied from `Strings.slug`, a test class five times its subject with
duplicated and trivial tests and names without behaviour, and an integration test in `src/it/groovy` calling a
public method.

## Phase 2 calibration

The comment checks GS060 to GS065 (`practices/CommentChecks.java`) were run with the default `comments` settings
on CodeNarc 4.0.0 `src/main`, 529 Groovy files: the benchmark copy groovyscope unpacks into
`~/.groovyscope/benchmark/codenarc/`, because the Gradle cache holds only the binary CodeNarc jar and no sources jar.
Each file was parsed on its own (the syntax only tree that `groovyscope/parse` uses) and every practice finder ran on
it. The comment checks took 330 to 370 ms for the 529 files in one JVM, warm up included (under 1 ms a file).

| Check | Findings | Files | Judged | Correct | False |
|---|---|---|---|---|---|
| GS060 comment restates the code | 4 | 4 | all 4 | 4 | 0 |
| GS061 comment block in a method | 9 | 9 | all 9 | 9 | 0 |
| GS062 Javadoc that adds nothing | 41 | 22 | 10 | 10 | 0 |
| GS063 commented out code | 5 | 4 | all 5 | 5 | 0 |
| GS064 decoration | 40 | 20 | 10 | 10 | 0 |
| GS065 more comment than code | 6 | 6 | all 6 | 6 | 0 |

GS060 and GS065 are at 0% false positives on their samples, inside the 10% the plan allows. Both report few
findings on this corpus: they are tuned for precision, since a gate that blocks on a good comment teaches the agent to
ignore it. GS062 is 13 Javadocs over 5 text lines (of the 175 documented methods, under the 10% the default of 5 was chosen
for), 10 Javadocs on private members and 18 tags that repeat the signature.

How a finding was judged: correct when the comment says what the code already says (its names, its condition, its
call), narrates the steps, is code or decoration, or breaks the stated limit; false when it gives a reason, a caveat
or a reference the code cannot carry and the check still counts it.

GS060, all four:

| Where | Comment, then the code | Verdict |
|---|---|---|
| `CodeNarc.groovy:318` | `// Output file` above `reportWriter.outputFile = parts[1]` | correct |
| `results/DirectoryResults.groovy:99` | `// Missing DirectoryResults: create it` above `new DirectoryResults(subDir)` | correct |
| `rule/design/CloneableWithoutCloneRule.groovy:40` | `// is this class a Cloneable?` above `ClassHelper.make(Cloneable)` | correct |
| `rule/formatting/IndentationRule.groovy:400` | `// If this is a nested class, then add one to the outer class level` above `node.outerClass ? ... + 1 : 0` | correct |

GS065, all six:

| Where | Comment lines to code lines | Verdict |
|---|---|---|
| `report/AbstractJsonReportWriter.groovy:30` `writeReport` | 5 to 18: a block narrating the two output branches | correct |
| `results/DirectoryResults.groovy:61` `addFileResultRecursive` | 5 to 18: a comment above every branch saying what it does | correct |
| `rule/design/ReturnsNullInsteadOfEmptyCollectionRule.groovy:53` `handleClosure` | 3 to 5: a question restating the call, and a commented out `super` call | correct |
| `rule/groovyism/CollectAllIsDeprecatedRule.groovy:40` `visitMethodCallExpression` | 4 to 7: a block describing the AST shape the next line tests | correct |
| `rule/groovyism/ExplicitCallToDivMethodRule.groovy:47` `shouldIgnoreViolationForMethodCall` | 2 to 7: "Ignore if argument is a Map, Closure or a String" restates the return expression | correct |
| `rule/unnecessary/UnnecessarySetterRule.groovy:41` `visitExpressionStatement` | 2 to 6: a narration and a commented out `super` call | correct |

GS061, all nine (correct by the rule; four of them are reasons, with an example or a link, that one line and a link
would carry): `report/AbstractJsonReportWriter.groovy:38`, `report/GitlabCodeQualityReportWriter.groovy:49`,
`report/XmlReportUtil.groovy:32`, `rule/FieldReferenceAstVisitor.groovy:91`, `rule/groovyism/UseCollectManyRule.groovy:42`,
`rule/groovyism/UseCollectNestedRule.groovy:82`, `rule/junit/SpockUtil.groovy:98`,
`rule/unused/ReferenceCollector.groovy:41`, `rule/unused/UnusedVariableRule.groovy:145`.

GS063, all five, all correct: `report/HtmlReportWriter.groovy:187` (`def violations = results.violations`), and four
commented out `super.visit...(...)` calls kept below a sentence saying why they are not made
(`ReturnsNullInsteadOfEmptyCollectionRule.groovy:59` and `:119`, `GStringExpressionWithinStringRule.groovy:57`,
`UnnecessarySetterRule.groovy:46`); the sentence says it, the dead call is noise.

GS062, every fourth finding, all correct: `CodeNarcRunner.groovy:58` (21 text lines), `results/DirectoryResults.groovy:85`
(private method), `results/Results.groovy:52` (`@return the number of violations with the specified priority` on
`getNumberOfViolationsWithPriority`), `rule/AbstractRuleTestCase.groovy:193` (9 lines), `rule/AstVisitor.groovy:30`
(`@param rule - the Rule`), `rule/grails/GrailsUtil.groovy:32` (private constructor), `rule/junit/JUnitUtil.groovy:63`
(6 lines), `ruleregistry/PropertiesFileRuleRegistry.groovy:43` (`@param ruleName - the rule name`),
`source/SourceCodeCriteria.groovy:64` (`@param sourceCode - the SourceCode`), `util/SourceCodeUtil.groovy:45` (9 lines;
correct by the limit, though its tags carry real contracts such as "has to be greater than zero").

GS064, every fourth finding, all correct: eight `//------` banner lines around "Helper Methods" headings
(`AbstractHtmlReportWriter.groovy:68`, `HtmlReportWriter.groovy:57`, `InlineXmlReportWriter.groovy:32`,
`SortableHtmlReportWriter.groovy:61`, `AbstractRuleTestCase.groovy:52`, `ClassReferenceAstVisitor.groovy:141`,
`IndentationRule.groovy:342`, `XmlReaderRuleSet.groovy:67`) and two TODOs without an owner
(`JUnitStyleAssertionsRule.groovy:41`, `UnnecessarySafeNavigationOperatorRule.groovy:56`).

What the first run found and what changed. The first run reported GS060 7 times (2 false: a comment whose second half
gives a reason, "it may have an annotation", and an item of a list explaining what `'*'` means), GS063 12 times
(7 false: examples after "e.g.:" and bullets such as "- Checks spaces only (not tabs)") and GS065 14 times (4 false
and 2 borderline: methods whose comments are a reason with an example or a link, as in `UnusedVariableRule`,
`ReferenceCollector`, `FieldReferenceAstVisitor` and `SpockUtil`). The defaults stayed as planned; the heuristics
changed:

1. A comment that gives a reason or a caveat (because, since, so that, in case, cannot, must, need, may, might,
   could, e.g., the case, false negatives and similar words) is a why: GS060 leaves it alone and GS065 does not count
   its lines. GS061 still limits how long it may be.
2. GS060 leaves list items (a bullet or a number in front) and runs ending with a colon alone.
3. GS063 reads a line as code only when it has the shape of code (a bracket, `=`, `{`, a closing `;` or `)`) and either
   an operator standing alone (`=`, `{`, `->`, `&&`) or no more than half its tokens are words, brackets and quotes
   stripped first; bullets are prose; code lines introduced by a colon, "e.g." or "for example" are an example and are
   left alone.
4. GS064 does not take an ellipsis (`...`) for a banner.
5. GS061 counts text lines only, not the `/*` and `*/` delimiter lines, as GS062 does for Javadoc.
6. Methods a transformation wrote (`@Generated`) are skipped.

The fixture's `demo/Verbose.groovy` reports GS060 (lines 23, 25, 33), GS061 (28, 4 lines), GS062 (14, Javadoc on a
private method; 16, `@return the counter` on a method returning `counter`), GS063 (35, two lines), GS064 (8 and 10,
banners; 37, a bare TODO) and GS065 (22, 10 comment lines to 8 code lines).

## Phase 5 calibration

The test bloat checks GS044 to GS048 (`practices/TestBloatChecks.java` with `TestMethods`, `TestSize` and
`DataTableFix`) were run with the default `tests` settings. CodeNarc's own tests are not in the benchmark corpus
(`~/.groovyscope/benchmark/codenarc/` holds `src/main` only, and its `AbstractRuleTestCase` is a base class, not a
test), and neither the Gradle cache nor a Maven repository on this machine holds a sources jar with Groovy tests. The
corpus is therefore groovyscope's own Spock specifications, `engine/src/test/groovy`: 103 files (the 102 that existed
before this phase and its own `TestBloatChecksSpec`), 100 test classes, 575 feature methods (29 data driven) and 2,290
conditions. Each file was compiled on its own with Spock on the classpath, at its real path so that GS046 found the
class under test in `engine/src/main/java` beside it, and every practice finder ran on it. The test bloat checks took
538 ms for the files in one JVM, warm up included (about 5 ms a file).

| Check | Findings | Judged | Correct | False |
|---|---|---|---|---|
| GS044 tests repeating one shape | 0 | none to judge | 0 | 0 |
| GS045 trivial test | 0 | none to judge | 0 | 0 |
| GS046 test class out of proportion | 0 | none to judge | 0 | 0 |
| GS047 test repeating another's inputs | 0 | none to judge | 0 | 0 |
| GS048 test name without behaviour | 0 | none to judge | 0 | 0 |

No false positive, so every check is inside the 10% the plan allows; the corpus is a lean, deliberately written test
suite, so it says nothing about how many repeats the checks miss. Grouping the same feature methods with the
normalisation of `find_duplicates` (names read as `ID`) finds no repeated body either, so GS044's stricter shape (names
kept, literals and descriptions set aside) loses nothing here and leaves out tests that differ only in which fixture
they read.

The numbers Phase 0 could not measure, on this corpus: test file lines over the lines of the file of the class under
test, for the 49 specifications whose class under test exists by name: median 0.44, maximum 2.46 (`ProfilesSpec`, 313
lines for 127); feature methods per test class (100 classes): median 4, P90 12, maximum 22 (`EditRequestSpec`,
`LanguageServicesSpec`). `tests.maxTestToCodeRatio: 3.0` and `tests.maxMethodsPerSubject: 40` leave every class of it
alone, as a deliberate suite should be left, and the defaults stay as the plan set them.

A second sample, the test sources of the fixture projects under `engine/src/test/resources/projects` (deliberate cases
for this and other checks; 11 test classes, 32 tests), reports 12 findings, all judged, all correct:

| Check | Where | Verdict |
|---|---|---|
| GS044 | `edit-gate` `SubjectSpec.groovy:12`, `:17`, `:22`, `:35`: four features of `test1`'s shape, `square(n) == m` | correct |
| GS045 | `edit-gate` `SubjectSpec.groovy:27`: `new Subject()` then `noExceptionThrown()` | correct |
| GS046 | `edit-gate` `SubjectSpec.groovy:5`: 39 lines for the 8 of `Subject` (4.9 times) | correct |
| GS046 | `test-doubles-sample` `StoreTest.groovy:5`: 58 lines for the 19 of `Store` (3.1 times) | correct by the limit |
| GS047 | `edit-gate` `SubjectSpec.groovy:22`: `square(2) == 4` again | correct |
| GS048 | `edit-gate` `SubjectSpec.groovy:7` `test1`, `:35` `shouldWork` | correct |
| GS048 | `lint-profiles` `SampleTest.groovy:10` `alwaysPasses`; `test-doubles-sample` `StoreTest.groovy:17` `stubsSeams` | correct: the mechanism, not an expectation |

What changed during the calibration: a Spock block description (`given: 'a shop'`) is documentation, so GS044's shape
and GS047's comparison leave it out, and the fix keeps the first feature's descriptions. When a specification does not
compile with the classes it tests (Spock missing from the classpath, an unresolved import), the compiler parses it on
its own; GS046 then reads the class under test from disk beside the test, and the `where:` columns fall back to `a`,
`b` where the parameter names cannot be resolved.

The fixture's `src/test/groovy/demo/SubjectSpec.groovy` reports GS044 (12, 17, 22, 35, naming `test1`), GS045 (27),
GS046 (5, 39 lines for 8, 6 test methods), GS047 (22) and GS048 (7, 35); `src/it/groovy/demo/SubjectIT.groovy`
reports nothing.
