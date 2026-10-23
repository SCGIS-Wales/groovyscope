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

## Phase 4 calibration

`groovyscope/similar` (`search/ReuseFinder.java`) was run with the default settings (`reuse.callOverlap: 0.6`, the
default `reuse.ignoreCalls`, `duplicates.minLines: 6`, `duplicates.minStatements: 3`) on the same CodeNarc 4.0.0
`src/main` copy, 529 Groovy files, compiled as one workspace in one JVM. Each query names an existing method by
`uri` and `symbol`, so the method itself is never reported.

Timings: the workspace compile took 12 to 14 s (it is not part of `similar`; any request pays it once). The first
`similar` builds the workspace facts (statement hashes, name stems, parameter types and calls of every method) in
250 to 310 ms; they are kept per snapshot, so a later query costs only the search. Each query then answered in 12 to
79 ms, and the slowest of a sweep over 334 methods took 120 ms: well inside the 2 s budget, with no further index.

| # | Query | Top matches | Judged | ms |
|---|---|---|---|---|
| 1 | `XmlReportWriter.writeReport` | calls 0.53 `BaselineXmlReportWriter.writeReport` (8 of 12 calls) | useful: the sibling writer, the same body but for two statements | 79 |
| 2 | `XmlReportWriter.buildPackageAttributeMap` | exact 1.0 `JsonReportWriter.buildPackageAttributeMap` (also calls 0.80, signature 0.70) | useful: a verbatim copy | 42 |
| 3 | `XmlReportWriter.isRoot` | signature 0.7 `JsonReportWriter.isRoot` | useful: the same one line helper in a sibling | 37 |
| 4 | `JsonReportWriter.buildPackageElements` | signature 0.7 `XmlReportWriter.buildPackageElements`; calls 0.53 `XmlReportWriter.buildPackageElement` | useful: the sibling's walk over packages | 37 |
| 5 | `TextReportWriter.writeFileViolations` | none | missed: see below | 40 |
| 6 | `HtmlReportWriter.buildBodySection` | calls 0.48 `SortableHtmlReportWriter.buildBodySection` (6 of 10 calls) | useful: the other HTML writer's page body | 78 |
| 7 | `BaselineXmlReportWriter.buildFileElement` (line 99) | signature 0.7 `JsonReportWriter.buildFileElement` and `XmlReportWriter.buildFileElement` (also calls 0.53) | useful as siblings; each builds its own format | 12 |
| 8 | `SarifReportWriter.buildSarifResults` | none | correct: no other writer builds SARIF results | 12 |
| 9 | `JUnitUtil.isSetUpMethod` | similar 0.9 `JUnitUtil.isTearDownMethod` | useful: the same condition with other literals | 27 |
| 10 | `XmlReportUtil.removeIllegalCharacters` | none | correct: nothing else does it | 17 |

The report writers come back: every query on a writer that has a sibling returned it, except query 5.
`CompactTextReportWriter.writeFileViolations` overrides it with a body that differs in two of its four statements and
inside the closure; an override of the same name is left out of signature matches on purpose (polymorphism, not a
copy), shape matching compares whole statements, and the calls left after `reuse.ignoreCalls` (`find`, `findAll`,
`each` and `println` are all ignored) are too few to compare.

The text form was checked on the same corpus: lines 94 to 106 of `XmlReportWriter.groovy` (the whole of
`buildPackageAttributeMap`) sent as `text` returned both copies, the Json one and the Xml one, as exact at 1.0, in 48 ms.

To see what the defaults let through, every method of `report`, `rule/junit`, `rule/basic` and `util` (334 methods) was
queried. They returned 117 matches: 65 calls, 34 signature, 16 similar and 2 exact.

1. Shape (18): all real copies or near copies, mostly template visitors whose bodies differ in literals
   (`BooleanGetBooleanAstVisitor`, `ThreadYieldAstVisitor` and `SystemExitAstVisitor.visitMethodCallExpression`;
   `JUnitSetUpCallsSuperAstVisitor` and `JUnitTearDownCallsSuperAstVisitor.visitMethod`;
   `UseAssertTrueInsteadOfNegationAstVisitor` and `UseAssertFalseInsteadOfNegationAstVisitor`), and
   `ConsecutiveUtils.isJoinableType`, copied into `ConsecutiveStringConcatenationAstVisitor`.
2. Signature (34): the writers' parallel builders (`buildReportElement`, `buildProjectElement`, `buildFileElement`,
   `buildViolationElement`, `buildRulesElement` in the Json, Xml and baseline writers) and two helpers that two Spock
   visitors each declare (`updateCurrentLabel`, `visitIfInSpockClass`). Useful to know; the writers' builders produce
   different formats, so they are siblings to follow rather than methods to call.
3. Calls (65): 30 are one family, six `visit` methods of `SpockMissingAssertAstVisitor` making the same three calls (a
   real template copy inside one class); 4 are noise (`ImportUtil.packageNameForImport` matched to a constructor by
   `lastIndexOf` and `endsWith`, `MathRandomTracker` matched to two unrelated classes by `pop` and `push`, and one
   `isTrue` and `isFalse` pair); the rest are siblings or copies.

What the first run found and what changed:

1. A text parsed on its own and the workspace's resolved trees normalised differently (`String` against
   `java.lang.String`, `Math` as a name against a class expression, `helper(x)` against a static call), so no text
   ever matched. `CloneFinder.looseHash` hashes both alike: simple type names, a class used as a receiver or operand
   as a name, a static call without receiver as a plain call. `find_duplicates` keeps the strict hash.
2. Calls matched 317 times in the sweep, nearly all rule visitors sharing framework calls (`addViolation`,
   `AstUtil.isMethodCall`). Calls that more than 2% of the workspace's methods make (and more than 20 methods) are now
   left out of the comparison on their own, as are calls on `super` (how an override joins its parent): 65 remain.
3. One statement methods such as `isSetUpMethod` could never match a window of three statements. A member of
   `minLines` or more whose body is shorter than a window now matches whole bodies of the same shape, and nothing else.
4. A script's generated `main` is marked as script body too and was read as code (`InvokerHelper.runScript` among its
   calls); only `run` is.

The fixture's `demo/Copied.groovy`: its method `pageName` sent as text returns `demo.Strings.slug` as exact, score 1.0,
lines 6 to 9; sent by symbol, or as the added range 5 to 10, it returns the same without itself.

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

## Phase 3 calibration

The value type checks GS070 to GS076 (`practices/TypeMixChecks.java`) and the type preview GS077
(`typecheck/TypePreview.java`) were run with the default `types` settings over the same CodeNarc 4.0.0 `src/main`,
529 Groovy files. Unlike the comment checks, these need resolved types, so the corpus was compiled as a workspace (the
engine's `LanguageServices`, as `check` does) with its libraries on `workspace.classpath` from the Gradle cache
(groovy-xml, groovy-json, groovy-templates, groovy-ant and groovy-test 6.0.0, GMetrics 3.0.0, slf4j-api, JUnit 4,
hamcrest-core, Ant). Two files stay unresolved (they import JUnit 5) and are left out by both, as `check` leaves them.
GS077 ran on each file alone against the classes of the full compile, which is what the check after a write does.
Machine: 4 cores (Xeon at 2.1 GHz), OpenJDK 21; timings vary by up to a third between runs, so ranges over runs are
given.

| Check | Findings | Files | Judged | Correct | False |
|---|---|---|---|---|---|
| GS070 literal of another kind | 0 | 0 | none to judge | | |
| GS071 one variable, several kinds | 0 | 0 | none to judge | | |
| GS072 text used as a boolean | 1 | 1 | all 1 | 1 | 0 |
| GS073 returns of different kinds | 0 | 0 | none to judge | | |
| GS074 arrays and lists mixed | 0 | 0 | none to judge | | |
| GS075 one map key, several kinds | 0 | 0 | none to judge | | |
| GS076 argument of another kind | 0 | 0 | none to judge | | |
| GS077 would not type check | 251 | 116 | 10 | 10 | 0 |

GS070, GS071 and GS073 to GS076 find nothing in CodeNarc: they report only what a literal or a declared type proves, or
what every value a `def` local is assigned agrees on, and CodeNarc mixes no kinds where its code says what they are.
The fixture and the specs (`TypeMixChecksSpec`) show each check firing, and their negatives (well typed and dynamic
code, null assignments, a `def` assigned one kind, appends, a counter fed back to itself, overloads, varargs, calls in
closures, classes with `methodMissing`) staying silent. The variables assigned more than one type, which Phase 0 could
not count: GS071 finds none in this corpus.

GS072, the one finding: `ruleset/RuleSetUtil.groovy:54`, `(System.getProperty(CLASS_LOADER_SYS_PROP) == 'true')`, a
flag kept as text and compared with `'true'` (`Boolean.getBoolean` reads it): correct.

GS077, every 25th finding, all correct (the checker's complaint holds for the declared types; the code works only
through the runtime type, a conversion, or a private field Groovy reaches dynamically):

| Where | The checker's words | Verdict |
|---|---|---|
| `CodeNarc.groovy:311` | No such property: writeToStandardOut for class: org.codenarc.report.ReportWriter | correct: the interface has no such property, its implementations do |
| `rule/basic/BrokenNullCheckRule.groovy:56` | Cannot find matching method ...BrokenNullCheckAstVisitor#isComparisonWithNull(Expression, String) | correct: declared `Expression`, the method takes a subtype |
| `rule/concurrency/DoubleCheckedLockingRule.groovy:73` | Cannot return value of type Statement for method returning SynchronizedStatement | correct: an implicit downcast |
| `rule/convention/CouldBeSwitchStatementRule.groovy:123` | No such property: variable for class: org.codehaus.groovy.ast.expr.Expression | correct |
| `rule/dry/DuplicateListLiteral.groovy:60` | Cannot call java.util.Collection#add(java.util.Map) with arguments [ListExpression] | correct: a `Collection<Map>` given list expressions |
| `rule/generic/IllegalClassMemberRule.groovy:138` | Cannot find matching method ModifiersUtil#matchesAnyModifiers(int, Collection<Integer>) | correct: the method takes `(Integer, List<Integer>)` |
| `rule/groovyism/ClosureAsLastMethodParameterRule.groovy:60` | No such property: ignoreCallsToMethodNames for class: org.codenarc.rule.Rule | correct |
| `rule/junit/SpockIgnoreRestUsedRule.groovy:44` | No such property: specificationClassNames for class: org.codenarc.rule.Rule | correct |
| `rule/security/InsecureRandomRule.groovy:64` | No such property: objectExpression for class: org.codehaus.groovy.ast.expr.Expression | correct |
| `rule/unnecessary/UnnecessaryOverridingMethodRule.groovy:43` | No such property: statements for class: org.codehaus.groovy.ast.stmt.Statement | correct |

How a GS077 finding was judged: correct when a real `@TypeChecked` compile of the class would report the same
error for the same reason in the code; false when the error comes from the preview itself or says only that dynamic
code is dynamic (a value nothing is known about, a variable or method a binding or a closure's delegate supplies).

What the first runs found and what changed:

1. GS077 first reported 600 findings in 163 files (10 a file at most). 320 named `java.lang.Object`, the type the
   checker gives a value it knows nothing about (`def` locals, untyped parameters); 17 were variables a binding or a
   delegate supplies (`The variable [x] is undeclared`); others were builder calls inside closures
   (`HtmlReportWriter#h1` under a markup builder), properties of `groovy.xml.Namespace`, which Groovy reads through
   its `get(String)`, and type parameters (`#T#trim()`). None of those is a mix of kinds: the code is dynamic and
   nothing is known there. The preview now drops an error that names `Object` or a type parameter, an undeclared
   variable, a computed property name, a member of the enclosing class asked for inside a closure (its delegate may
   answer), and a member of a type that answers anything (a dispatch hook such as `getProperty`, `propertyMissing`,
   `methodMissing` or `invokeMethod`, or a `get(String)`). That left 251.
2. GS072 first reported 2; one was false: `rule/naming/PackageNameMatchesFilePathRule.groovy:42`, `return groupId` from
   `boolean isReady()`, Groovy truth on an identifier on purpose. Text stored or returned as a boolean is now
   reported only when the class also compares that variable with `'true'` or `'false'`, so it is a flag kept as text.
3. A whole-workspace preview compiles every file at once and a file that does not resolve stopped the checker for
   all of them (0 findings); the preview now drops the files that do not resolve and compiles the rest again, as the
   type check does (258 findings in that mode).

Timings (warm, the first four of fourteen rounds dropped):

| Measure | Result |
|---|---|
| GS077 per corpus file, each alone against the full compile's classes | 510 files previewed; total 6.9 to 14.0 s; median 10 to 19 ms, 90th percentile 22 to 47 ms, max 235 to 507 ms |
| GS077 alone on a 3,268 line file (eight renamed copies of `IndentationRule.groovy`, the corpus's largest file) | median 187 to 228 ms, 90th percentile 259 to 271 ms |
| Check after a write to that file, `compile`, `lint`, `practices` without GS077 | median 951 to 1,452 ms, 90th percentile 1,098 to 1,696 ms |
| The same check with GS077 | median 926 to 1,173 ms, 90th percentile 1,269 ms (one round of 2,045 ms while warming up) |
| Whole workspace `check` of the type preview alone | 7.7 to 7.9 s for the 530 files |

The check after a write lints the file on another thread while it compiles and runs the practices, and CodeNarc on a
3,000 line file is the slower of the two, so the preview's second compile is mostly hidden: the check stays near one
second, under the 2 second mark. Decision: GS077 runs by default with the practices in a `check` of named files (the
check after a write, `review_changes`) and in `groovyscope/preview` (the edit gate), and `include: ["types"]` asks for it
anywhere. A whole-workspace `check` runs it only when `types` is named, since there it adds a second compile of every
file (7.5 s here). `types.preview: false` turns it off. The Python side needs to pass nothing for the default; to get
the type errors of classes already static in the edit gate's preview, it adds `types` to its include list.

The fixture's `demo/Types.groovy` reports GS070 (6, `age > 18` on a `String`), GS071 (12, `sum` holds Integer from
line 10 and String from line 12), GS074 (18, `items.length`, fix `items.size()`), GS072 (23, `if (flag)`, and 26,
`flag == 'true'`), GS073 (29, `status` returns Boolean on line 31 and String on line 33) and GS075 (39,
`map.retries` holds Integer and String). GS077 says the same about lines 6 and 18 in the checker's words:
`Cannot call java.lang.String#compareTo(java.lang.String) with arguments [int]` and
`No such property: length for class: java.util.List`. The other cases type check: the checker's flow typing accepts
a `def` that changes kind, a `def` method's mixed returns, Groovy truth on text and a raw map's values.

## Phase 6 calibration: design

The design facts (`explain/DesignFacts.java`, read by `groovyscope/design` and by the checks GS090 to GS097 in
`practices/DesignChecks.java`) were run with the default `design` settings (`maxMethods: 25`, `maxFields: 12`,
`maxLines: 600`, `maxParameters: 4`, `envyMinUses: 3`) on the same CodeNarc 4.0.0 `src/main/` copy, compiled as one
workspace in one JVM (544 compiled sources, the 529 Groovy files of `src/main/groovy` among them; 912 classes, 1,821
methods and constructors). The facts need the resolved tree: feature envy and the anemic data class read the types of
receivers, and a method uses no instance state only when every call in it resolves.

Timings: the workspace compile took 11 to 12 s (paid once by any request). The first `groovyscope/design` request
took 1.0 to 1.4 s, building the call graph `explainCode` also reads and the workspace index the facts keep per
compilation (which classes read and write which fields, which methods subclasses declare again). After that a
request answered in 22 to 38 ms for `CodeNarcRunner`, and a sweep over all 894 top level classes took 6 ms at the
median, 9 to 11 ms at the 90th percentile and 58 to 99 ms at worst. The facts of all 912 classes took 109 to 112 ms in
one pass; every practice check of the 544 sources, the design checks included, took 720 to 730 ms.

`groovyscope/design` on `org.codenarc.CodeNarcRunner` (178 lines, 18 methods, 7 fields):

- Responsibilities: `register` (`registerPlugin`, `registerPluginsForClassNames`), `execute`, `get` (`getPlugins`).
- Cohesion: LCOM4 1, because `execute` calls every helper; with calls left out (`byFields`) three jobs show: the
  plugins (`registerPlugin`, `getPlugins`, `initializePlugins`, `applyPluginsProcessRules`, the two
  `applyPluginsProcessViolations*` methods and `writeReports`, over `plugins` and `reportWriters`), loading the rule
  set (`execute` and the three `createInitialRuleSet*` methods, over `ruleSetFiles`, `ruleSetString` and
  `sourceAnalyzer`), and configuring it (`buildRuleSet`, over `propertiesFilename` and `ruleSetConfigurer`). The
  plugins group calls nothing outside itself, so it is `separable`.
- Candidates: `getPlugins` hands out the mutable `plugins` list (GS094); `initializeRuleRegistry` and
  `buildCountsText` use no instance state (GS097), and `buildCountsText` makes all its calls on `Results`.
- Suggestions, each with its tool call: move the plugins group to a class of its own
  (`move_member(symbol: "org.codenarc.CodeNarcRunner.registerPlugin", toClass: "org.codenarc.<NewClass>")`),
  return `plugins.asImmutable()` (`edit_symbol ... replace_body`), make `initializeRuleRegistry` static, and move
  `buildCountsText` to `org.codenarc.results.Results` (`move_member`). No feature envy, shared parameter list, type
  switch, static mutable state, long parameter list or local in disguise is reported, and none is there.

| Check | Findings | Files | Judged | Correct | False |
|---|---|---|---|---|---|
| GS090 god class | 5 | 5 | all 5 | 5 | 0 |
| GS091 feature envy | 1 | 1 | all 1 | 1 | 0 |
| GS092 long parameter list | 3 | 2 | all 3 | 3 | 0 |
| GS093 chain of type tests | 7 | 7 | all 7 | 7 | 0 |
| GS094 state anyone may change | 2 | 2 | all 2 | 2 | 0 |
| GS095 anemic data class | 1 | 1 | all 1 | 1 | 0 |
| GS096 primitive obsession | 0 | 0 | none | 0 | 0 |
| GS097 method uses no instance state | 142 | 89 | 10 | 10 | 0 |

How a finding was judged: correct when the code is what the check describes and its fix would apply (the method
could be static or moved, the list could be handed out, the branches dispatch on a type); false when the code only
looks so (a field made unmodifiable elsewhere, a method bound to a supertype, data that many classes consume).

GS090, all correct by the limits: `CodeNarc` (18 fields, its command line options), `ant/CodeNarcTask` (13 fields),
`rule/generic/IllegalClassMemberRule` (17 fields), `rule/size/CrapMetricRule` (13 fields) and
`rule/formatting/IndentationRule`'s `IndentationAstVisitor` (29 methods). The first four hold configuration
properties; past the limit is still past the limit, and the message names which.

GS091: `rule/formatting/BracesForIfElseRule.groovy:92` `visitElse` reads the rule's settings five times and writes
two of them (defaulting `elseOnSameLineAsClosingBrace` and `elseOnSameLineAsOpeningBrace` from `sameLine`): logic
that belongs in the rule.

GS092: `rule/AbstractRuleTestCase.groovy:112` and `:205` (`assertTwoViolations`, 5 and 7 parameters, a test base
shipped in `src/main`) and `util/SourceCodeUtil.groovy:57` `sourceLinesBetween` (5).

GS093, all seven dispatch on the type of one value with a branch per type: `DeadCodeRule.statementForcesMethodReturn`,
`CouldBeSwitchStatementRule.isSameLeftExpressionAsPreviousIfStatement`, `BooleanMethodReturnsNullRule.checkReturnValues`
(an else-if chain), `SpaceAroundOperatorRule.rightMostColumn`, `GetterMethodCouldBePropertyRule.createMessage`,
`JUnitUtil.isLiteralWithValueThatEvaluatesTo` and `PropertyUtil.setPropertyFromString` (a `switch` on a `Class`
value converting a String per type). Every type tested is a Groovy AST class or a JDK type, which the workspace
cannot give a method, so the message offers a visitor or a map from type to handler instead of polymorphism.

GS094: `CodeNarcRunner.getPlugins` and `results/DirectoryResults.getChildren` return a list the class creates with
`[]` and adds to.

GS095: `ClassConstraintsAndMapping` (in `rule/grails/GrailsDomainStringPropertyMaxSizeRule.groovy`), two fields that
only `GrailsDomainStringPropertyMaxSizeAstVisitor` writes and reads, with the lookups over them in the visitor.

GS097, every fourteenth finding from the eighth, all correct (each reads no field, calls no instance method and does
not use `this`): `AbstractReportWriter.getDescriptionProperty`, `EmptyClassRule.hasAnnotation`,
`IfStatementCouldBeTernaryRule.getReturnValue`, `CloneableWithoutCloneRule.hasCloneMethod`,
`BracesForClassRule.definesAnnotationType`, `SpaceInsideParenthesesRule.startOfCommentIndex`,
`AbstractImportRule.findLineNumberOfFirstClassDeclaration`, `UnsafeImplementationAsMapRule.getMethodsImplementedByCoercion`,
`UnnecessaryGetterRule.hasClosureAsLastArgument` and `RuleRegistryInitializer.initializeRuleRegistry`. 142 of 1,821
methods (7.8%) is many, but CodeNarc's rules are full of small private predicates over AST nodes, and each is a
static helper in all but its modifier. It is information only.

GS096 reports nothing here: no CodeNarc method takes three String identifiers. The specs cover it.

What the first run found and what changed. The first run reported GS091 9 times, GS093 8 times, GS094 9 times and
GS095 2 times; the counts of the others did not move.

1. GS091 (8 of 9 false): six report writers and the test base read `Violation` or `FileResults` to format or assert
   them, and `ClassEndsWithBlankLineRule.addViolation` built a `Violation` with four setters. Writes no longer count
   as envy (setting another object's fields is building it), and data that three workspace classes or more read is
   shared: no one reader envies it.
2. GS094 (7 of 9 false): `ListRuleSet`, `XmlReaderRuleSet` and `JsonReaderRuleSet` make their list unmodifiable in
   the constructor, `XmlFileRuleSet`, `JsonFileRuleSet` and `GroovyDslRuleSet` take it from a reader that did, and
   `FileResults.getRawViolations` returns the list its caller passed in. A getter is now reported only when every
   value the class gives the field (its initial value and each assignment) is a list or map literal or a new
   collection; a getter of a property's own field is not reported, as properties are not.
3. GS093 (1 of 8 false): `CouldBeSwitchStatementRule.isSupportedLeftExpressionType` lists three class cases that fall
   through to one `return true`, a membership test. Only cases with code of their own count as branches.
4. GS095 (1 of 2 false): `InlineViolationsParser.ParseResult`, a result holder nested in the parser that fills it. A
   data class nested in its owner, or holding it, is the outer class's own business and is not reported.
5. The split suggestion: LCOM4 alone gave `CodeNarcRunner` one group, since a coordinating method joins everything.
   `byFields` and `separable` were added so the answer names the jobs it ties together and the one that can move.

