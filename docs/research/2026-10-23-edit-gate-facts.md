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
