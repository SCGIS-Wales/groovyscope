# groovyscope: edit intelligence plan

Plan for Claude Code. Repository: `SCGIS-Wales/groovyscope`. Work from `main`, one branch and one PR per phase, released through `release-please` as today. Read `AGENTS.md`, `docs/roadmap.md` and `docs/improvement-plan.md` first and keep every rule in them (functional Python, no classes beyond `TypedDict` and exceptions, `mypy --strict`, ruff, 90% Python coverage gate, Java `-Xlint:all -Werror`, templates as the single source of Kiro assets, `scripts/sync_templates.py`, Conventional Commits, roadmap updated at every stage, nothing hardcoded that belongs in `defaults.yaml` and `config-schema.json`).

## 1. What the owner reported

Observed in Kiro CLI sessions on real Groovy repositories with groovyscope 0.28:

1. Inline comments the agent writes are long and not concise; the tools do nothing about it.
2. The agent duplicates code instead of reusing what the workspace already has; the owner must say so every time.
3. The agent mixes value types, above all String against Integer and Boolean, and lists against arrays.
4. Unit tests bloat: more test methods than behaviours. The owner wants coverage at 80% or higher enforced, and the integration test code kept in line with every change, without being told each time.
5. Edits are not object oriented: the agent treats a file as text, not as classes and members with responsibilities.
6. Kiro applies edits with its built in `write` tool (delete and add). The owner wants groovyscope to drive the edit because it understands the code, while keeping what `write` gives: a green and red diff and the y, n, t approval before anything lands.

The common cause: groovyscope reviews after a write and advises through steering, but nothing sits at the edit boundary where the problems appear. The agent forgets instructions; a gate does not.

## 2. Design decisions

Confirm these with the owner at the start of Phase 1 before building; the open questions are listed in section 6.

### 2.1 groovyscope plans, Kiro writes, groovyscope verifies

Kiro's terminal renders a coloured diff and the y, n, t prompt only for its own `write` tool. An MCP tool cannot draw that. So the default edit path becomes:

1. The agent asks groovyscope for the edit as an object operation (`edit_symbol`, `change_signature`, `extract_method`). The dry run returns the exact `old_str` and `new_str` for Kiro's `write` in `str_replace` mode, a unified diff, a `plan_id`, and the gate findings on the candidate text (section 2.2).
2. The agent applies the plan with Kiro's `write`. The user sees Kiro's native diff and answers y, n or t. Nothing changes in Kiro.
3. The `PostToolUse` hook recognises the planned write by `plan_id` (the plan is cached under the workspace cache keyed by path), confirms the file now equals the planned result, and runs the usual after write check.

A second path, `apply: true` on the edit tool, lets groovyscope write the file itself: the MCP elicitation question carries the diff (the client renders it as text), Kiro's own tool approval still asks y, n, t because the edit tools are never in `allowedTools` and the V3 rules say `ask`, and the write is recompiled and rolled back on new compile errors exactly as `rename` does today. Clients that render elicitation well (Claude Code, Kiro terminal UI once confirmed) can use this path.

### 2.2 One gate at the edit boundary, enforced by a hook, configured once

A new engine request `groovyscope/preview` takes candidate texts for workspace paths (the file as it would be after the write), compiles them as an overlay on the current snapshot (the `compileOnly` path from the faster after write check), and runs: parse, compile, practices, the new comment, type, reuse, test and design checks, and a diff based policy on the change itself (added comment share, duplicated added blocks). It writes nothing.

The same preview runs in three places so the agent cannot skip it:

* inside every edit tool's dry run;
* in a new `PreToolUse` hook on Kiro's `write` for Groovy, Gradle and Jenkinsfile paths (`hooks.preWrite`), which rebuilds the candidate text from `tool_input` (`create` gives `file_text`; `str_replace` and `insert` are applied to the file on disk), runs the preview, and either blocks the write (exit 2, reasons on stderr for the model) or lets it through with the findings;
* in `review_changes` and `verify`, for what already landed.

Thresholds live in `.groovyscope.yaml` sections `comments`, `types`, `reuse`, `coverage`, `tests`, `design` and `hooks.preWrite`. The steering states each rule once in one line; the gate does the remembering.

### 2.3 Checks are facts, not opinions

Every new GS code follows the existing contract: a message that says what it means, whether it is a defect, a gap or by design, and the fix; `explain` works on it; the catalogue entry in `practices.json` has why, bad form, good form and fix; false positives on the CodeNarc corpus sampled at 20 findings per code stay at or under 10%, as Phase 5 of the improvement plan did.

## 3. Phases

Codes reserved: comments GS060 to GS065, types GS070 to GS077, reuse GS080 to GS082, tests GS044 to GS049 (the `tests` group continues from GS043), design GS090 to GS097. New groups in `practices.json`: `comments`, `design` (`reuse` joins `style`; test codes join `tests`; type codes join `types`).

### Phase 0: facts from the owner's Kiro, baseline numbers

Branch `feat/edit-gate-baseline`. Nothing here needs a design decision.

1. Add `groovyscope hook --capture` (writes the raw hook payload to `<cache>/hooks/<event>-<timestamp>.json`, never fails). Ask the owner to run one `write` in each mode (`create`, `str_replace`, `insert`) and one prompt with the `userPromptSubmit` and `preToolUse` hooks attached, and to send the files. Confirm from them: the field names of `tool_input` for `write` (expected `command`, `path`, `file_text`, `old_str`, `new_str`, `insert_line`), whether a `PreToolUse` exit 2 blocks the write and shows stderr to the model (the gap analysis has this unconfirmed), whether `UserPromptSubmit` stdout reaches the context, and how the terminal UI renders a multi line elicitation message. Record the answers in `docs/research/2026-10-23-edit-gate-facts.md`.
2. Measure on the bundled CodeNarc corpus and on the owner's own project through the daemon: comment lines per method, comment share of method bodies, Javadoc length distribution, test lines to main lines per class, number of test methods per class under test, methods with mixed assignment types (from the STC metadata already in `Resolver`). Write the numbers into the research doc; they calibrate every default threshold below.
3. Add a `test_fixtures` project `python/tests/fixtures/edit-gate` (and the engine equivalent under `engine/src/test/resources`) with deliberate cases: a verbose method, a method restating itself in comments, a String compared to an int, a `def` reassigned across types, a List used as an array, a copied block, a test class four times its subject, an integration test class in `src/it/groovy` calling a public method.

Done when: the research doc has the numbers and the owner's answers, fixtures exist, CI green.

### Phase 1: the edit gate, planned writes and the first edit tool

Branch `feat/edit-gate`. This phase ships the infrastructure; the checks that use it come in phases 2 to 6.

Engine:

1. `groovyscope/preview` in `QualityRequests`: `{texts: [{uri, text}], include: [...]}` returns, per uri, syntax errors, compile diagnostics from an overlay compile (reuse `LanguageServices.compileOnly` with the candidate text substituted for the document; the snapshot stays untouched), practice findings, and a `change` block computed against the current text: added lines, removed lines, added comment lines, added code lines, added blocks of `duplicates.minLines` or more that match existing workspace code (phase 4 fills this). Bounded by `hooks.timeoutSeconds`; cancellation checkpoints as the other heavy requests.
2. `groovyscope/edit`: `{uri, symbol, line?, operation, text?, position?}` with operations `replace` (the whole declaration, comments and annotations above it included, as `read_symbol` delimits it), `replace_body`, `insert_before`, `insert_after`, `delete`, `add_member` (into a class at `position: start|end|after:<member>`), `add_import`. Resolution through `Declarations` and `AstRanges`, overloads picked by `line`. Returns the candidate text for the file, the exact replaced span (`old_str`, `new_str` with enough context lines on each side that `old_str` is unique in the file), and nothing else; the Python side runs the preview on it.

Python:

1. `plans.py`: a plan is `{plan_id, path, before_hash, old_str, new_str, after_hash, created}` stored under `<cache>/<workspace>/plans/<path-hash>.json`, at most `edits.plansKept` per path, expired after `edits.planTtlMinutes`. `plan_id` is the SHA 256 of path, before text and after text. Pure functions with injected clock and store, as everywhere else.
2. `gate.py`: the policy evaluation over a preview answer: which findings block, which warn, from the `hooks.preWrite.mode` (`block` or `warn`) and per code `practices.severity`. Returns `{decision: block|warn|pass, reasons: [...], findings: [...]}` in the same row shape the after write hook renders.
3. `hooks.py`: a `preToolUse` branch. For a write tool on a Groovy, Gradle or Jenkinsfile path inside the workspace: rebuild the candidate text from `tool_input`; if `old_str` and `new_str` match a stored plan whose `before_hash` matches the file, mark the write `planned` and skip the gate when `hooks.preWrite.trustPlans` is true (the plan already passed it); otherwise run `groovyscope/preview` and apply `gate.py`. Exit 2 with the reasons on stderr when blocked; exit 0 with a `groovyscope preview <file>` block on stdout when it passes with findings. `hooks.preWrite.requirePlan: true` blocks any unplanned write to a Groovy file larger than `hooks.preWrite.requirePlanAboveLines` with the message to use `edit_symbol` first. Never fails the session; a timeout lets the write through and says so, as the after write hook does.
4. `hooks.py` `postToolUse`: find the plan for the written path; report `planned write applied` or `planned write drifted` (file differs from `after_hash`); then the existing check.
5. `hooks.py` `userPromptSubmit`: when the prompt mentions Groovy, Gradle, Jenkinsfile or a `.groovy` path, print the policy summary from `policy_summary()` (one line per rule, built from the configuration so it names the real thresholds). No engine call; under 50 ms.
6. MCP tool `edit_symbol(path, symbol, operation, text?, position?, line?, apply?)`: dry run returns `{diff, plan: {plan_id, old_str, new_str}, gate, applyWith: "write str_replace"}`; `apply: true` goes through `interaction.confirm` with the diff in the question (first `edits.diffLinesInQuestion` lines), then `edits.write_document` with `expected`, then the recompile and rollback already used by `rename` (factor that flow out of the rename code into `changes.py` so both share it).
7. `tools_catalog.py`: `edit_symbol` is not read only, destructive, not idempotent. `kiro_agent.py`: never in `allowedTools`; V3 rule `ask`. Tool surface budget in `test_tool_surface.py`: raise it to cover the new schemas (section 6 has the alternative).
8. Configuration: `hooks.preWrite: {enabled: true, mode: warn, trustPlans: true, requirePlan: false, requirePlanAboveLines: 40}`, `edits: {plansKept: 5, planTtlMinutes: 120, diffLinesInQuestion: 40}`. Schema, defaults, `config validate`, `doctor` lines.
9. Kiro agent template: `hooks.preToolUse` matcher `write` command `groovyscope hook`, `hooks.userPromptSubmit` command `groovyscope hook`; `kiro attach` adds both to other agents; `checkup` reports agents missing them.
10. MCP server `INSTRUCTIONS`: add one line: "Edit Groovy through `edit_symbol` (dry run gives the `write` arguments and the gate's verdict); never rewrite a declaration by hand."

Tests: Spock specs for `preview` (overlay compile reports an error in a candidate while the snapshot stays clean; a candidate for a file that does not exist; a candidate over `maxFileBytes`), `edit` (each operation on the fixture, overload by line, comments above a method travel with `replace`, `old_str` unique); pytest for `plans.py`, `gate.py`, every hook branch with captured payloads from Phase 0 as fixtures, `edit_symbol` dry run and apply with rollback, the budget test, template sync.

Done when: on the fixture, a `write` that introduces a compile error is blocked in `block` mode and reported in `warn` mode; a planned write passes without a second preview; `edit_symbol` replaces a method and the file compiles; CI green; roadmap Stage 24 written; released.

### Phase 2: concise comments

Branch `feat/comments`. Engine checks in a new `practices/CommentChecks.java` reading the token stream and the AST (comments are not AST nodes; use the lexer tokens through `TextLines` and the `AstRanges` of the enclosing method).

1. GS060 comment restates the code: the comment's words (stop words removed) are at least `comments.restateShare` (default 0.7) covered by the identifiers and keywords of the statement that follows (`// increment the counter` before `counter++`). Fix: remove the comment; safe, but applied only when its code is named (`--code GS060`), because intent is involved.
2. GS061 comment block in a method body longer than `comments.maxBlockLines` (default 2). Message says the line count and that a why in one line is wanted, or none.
3. GS062 Javadoc problems: on a private or package private member, or longer than `comments.maxJavadocLines` (default 3) on a method, or a tag that repeats the signature (`@param name the name`, `@return the result`). Fix for the tag case: remove the tag line.
4. GS063 commented out code: a run of comment lines that parses as Groovy statements (`groovyscope/parse` on the joined text after stripping the markers; cheap pre filter: contains `(`, `=`, `{` or ends with `;` or `)`). Fix: remove the run.
5. GS064 decoration: banner lines (`// -----`, `// =====`, `/* **** */`), empty comments, `// TODO` without an owner or ticket when `comments.todoNeedsOwner` is true.
6. GS065 comment density: comment lines over code lines in a method of five lines or more above `comments.maxDensity` (default 0.25), reported once per method with the ratio.
7. Gate policy on the change: added comment lines over added lines above `comments.maxAddedShare` (default 0.2) in a write that adds ten lines or more blocks in `block` mode (message lists the comment lines); `comments.exemptPublicJavadoc: true` leaves one Javadoc line per public member out of the count.
8. `format` gains nothing here; comment removal stays a fix, never a formatter action.

Steering (one line): "Comments say why, never what; one line; none on code that reads itself; Javadoc one sentence on public members only."

Calibration: the corpus has CodeNarc's own comments; GS060 and GS065 defaults must leave its well commented rules at or under 10% false positives. Record the sample in the research doc.

Done when: the fixture's verbose method produces GS060, GS061 and GS065; a write adding five comment lines to ten code lines is blocked in `block` mode; the corpus sample is recorded; released.

### Phase 3: value types

Branch `feat/type-discipline`. Most of the mixing happens in dynamic code where the compiler says nothing, so two things are needed: checks on dynamic code, and the static type checker run speculatively.

1. GS077 type preview: for each dynamic class in the candidate (no `@CompileStatic` or `@TypeChecked`), run Groovy's static type checker as `FixService` already does per class for the `@CompileStatic` verdict, and report each STC error as a warning `GS077 would not type check: <message>` with `INFERRED` confidence. Skip classes that `DynamicCodeFinder` marks as relying on `methodMissing`, `propertyMissing`, `metaClass`, builders or `Expando`, and files under a runtime profile that forbids static compilation; `types.preview: true`, `types.previewMaxPerFile: 10`. This catches `int n = '5'`, `send(5)` into `send(String)`, `list.length`, and the rest in one pass. Measure the cost on the corpus (the STC pass per class is already bounded by `RetryBudget`); keep it inside the hook budget or run it only in the edit tool and `review_changes` when it is not.
2. GS070 literal against a different type: a literal of one kind assigned, compared (`==`, `!=`, `<`, `in`) or passed to something whose declared or inferred type is another kind (String against number or boolean, number against boolean). STATIC when declared, INFERRED from STC metadata via `Resolver`.
3. GS071 one variable, many types: a local, field or parameter assigned values of different inferred types in its scope (`def total = 0` then `total = 'n/a'`), null excluded. Message names both lines and both types; fix is a declared type or a second variable.
4. GS072 String as a boolean: `if (text)` where `text` is inferred String and compared elsewhere to `'true'` or `'false'`; `x == 'true'`; `Boolean.valueOf` missing. Message explains Groovy truth (`'false'` is true).
5. GS073 mixed return types: a method that returns values of different inferred types on different paths (boolean and String, number and String), null excluded; records the lines.
6. GS074 arrays and lists: `.length` on a List, `List` literal passed where the parameter is `T[]` (STATIC call sites), `==` between an array and a list literal, `as String[]` missing before a Java call that declares an array, `in` on an array of one kind with a literal of another. Fix for `.length` on a List: `.size()`.
7. GS075 mixed map value types: the same literal key written with values of different kinds in one scope.
8. GS076 implicit conversion at a call: an argument's inferred type differs from the parameter's declared type at a STATIC call site, and Groovy would coerce or fail at runtime (int into String, String into int, GString into char).
9. Fixes: `types.declareOnEdit: true` makes `edit_symbol` refuse `def` on new parameters and return types in the text it is given (gate finding with the declared type inferred from the body when STC can tell it), and the existing `NoDef` fix with the imported type is offered.
10. `hover` and `symbol_context` add `inferredTypes` for a local assigned more than once (all the types seen), so the agent sees the mixing before it edits.

Steering (two lines): "New classes carry `@CompileStatic` unless they need dynamic features, and then say why in one line. Declare every field, parameter and return type; `List<String>` not raw `List`; arrays only at a Java boundary, converted with `as`."

Done when: the fixture's four type cases report GS070 to GS074 and GS077 says the same in STC's words; corpus sample recorded; the hook on a 3,000 line file still answers under its budget (measure, record); released.

### Phase 4: reuse before writing

Branch `feat/reuse`.

1. Engine `groovyscope/similar`: `{text?, uri?, symbol?, paths?, minLines?, limit?}`. For a candidate text (a method or block the agent is about to write) or an existing symbol: `CloneFinder` shape matches against the workspace with a score (EXACT or SIMILAR and the share of matching statements), plus signature matches (same name stem and parameter types elsewhere in the package or its superclasses), plus "same calls" matches (methods whose set of called APIs from `CodeFacts` overlaps by `reuse.callOverlap`, default 0.6). Each match names the symbol, location, score and a one line reason.
2. MCP tool `suggest_reuse(text?, symbol?, paths?, limit?)` on it. Steering: call it before writing a helper, a parser, a retry loop, a formatter or any method over `reuse.minLines`.
3. Gate: GS080 an added block of `duplicates.minLines` or more matches existing code (the preview's `change.duplicates`); blocks in `block` mode, names the existing symbol and says "call it, or extract a shared method with `extract_method`". GS081 a new method with the same name stem and parameter types as one already in the package or a superclass. GS082 a new private helper whose body is one statement delegating to an existing utility (inline it).
4. `explain_code` answers `helpers`: the utilities in the class, its superclasses, traits and package that a method could call (public and package private static methods and small pure instance methods), with their signatures, so the agent sees what exists while reading what it is about to change.
5. `review_changes` adds `duplicatesAdded` (blocks the verdict at `reuse.blockReview: true`).

Done when: writing the fixture's copied block through `write` is blocked and the message names the original; `suggest_reuse` on the block returns it with a score; corpus sample recorded (CodeNarc has real duplicates, the report writers; they must come back); released.

### Phase 5: tests kept lean, coverage at 80, integration tests in line

Branch `feat/test-discipline`.

Coverage:

1. Configuration `coverage: {minimumPercent: 80, scope: changed, requireFreshReport: true}`. `scope: changed` means every changed class must reach the threshold; `overall` means the workspace line coverage.
2. `verify --coverage` exits 1 under the threshold and names the classes under it with their percentage and missed lines. `verify` without `--coverage` says in one line whether a fresh JaCoCo report exists and what it says, when `coverage.minimumPercent` is set.
3. `review_changes` adds `coverage: {overall, threshold, changedClasses: [{class, percent, missedLines}], reportAge, verdict}`; a fresh report (newer than the last change to the changed files) under the threshold makes the verdict blocking; a stale or missing report adds the command to produce it and never blocks. `coverage_gaps` gains `threshold` and `below` so it answers the same question on its own.
4. Stop gate: `hooks.coverageGate: false` by default; when on and a fresh report exists, the turn is refused under the threshold with the classes named. The gate reads the report; it never runs Gradle.

Test bloat checks (test sources only, `TestSources.isTest`):

1. GS044 duplicate test methods: feature methods or `@Test` methods whose bodies have the same shape (`CloneFinder` on test methods, literals ignored). Message: "merge into one data driven test (`where:` table or `@ParameterizedTest`)". Fix for Spock when the bodies differ only in literals: rewrite as one feature method with a `where:` table; behaviour changing, so `--code` only.
2. GS045 trivial test: asserts only a getter returning what the setter or constructor received, or only that a constructor does not throw.
3. GS046 test size: test lines for a class under test above `tests.maxTestToCodeRatio` (default 3.0) times its main lines, or more test methods than `tests.maxMethodsPerSubject` (default 40), reported once per test class with both numbers.
4. GS047 same inputs: two tests calling the same method under test with identical literal arguments and identical assertions on the result.
5. GS048 test name without behaviour (`test1`, `testMethod`, `shouldWork`): no verb or expectation words; configurable `tests.namePattern`.
6. GS049 integration test out of line: `review_changes` only: a changed public signature with callers in integration sources (`src/it`, `integrationTest`, `*IT`, `*ITCase`, `*IntegrationTest`) that the branch did not change, or that no longer compile. Blocking.

Tool `test_plan(symbol?, base?, staged?)`: for a changed method, or every changed declaration of the branch: the tests that already reach it (`verify.affected_tests`, with confidence), split `unit` and `integration` with each task's Gradle command; the method's branches from `CodeFacts` and which are uncovered when a fresh JaCoCo report exists; the tests among the reaching ones that look redundant (GS044, GS047); and a `needed` list: one line per uncovered branch or boundary (null, empty, each enum value), nothing else. The steering says: add only what `needed` lists; extend a data table before adding a feature method.

Integration tests kept in line:

1. Confirm by test that `check`, the after write hook, the pre write gate and the stop gate compile every source set `workspace.sourceSets` adds (`src/it`, `src/integrationTest`); a compile error in an integration test after a signature change must appear in the after write block.
2. `verify --integration` runs `verify.integrationTasks` (default `["integrationTest"]`) locally when `verify.integrationNeeds` is empty; otherwise lists the tests with the reason from that setting (for example `credentials`). `review_changes.tests` and `test_plan` always list integration tests reached, so the agent names them even when it cannot run them.
3. `impact_of` already lists tests; add `integrationTests` as its own list.

Steering (three lines): "Coverage of every changed class stays at 80% or above; `test_plan` says which tests to add and which already exist. One test per behaviour; a data table before a new feature method; never a test that repeats another. A changed public method updates its unit tests and its integration tests in the same change; list the integration tests you could not run."

Done when: the fixture's oversized test class reports GS044, GS046 and GS047; a JaCoCo report under 80% fails `verify --coverage` and blocks `review_changes`; a signature change that breaks `src/it` shows in the after write block and as GS049; released.

### Phase 6: object oriented edits and design findings

Branch `feat/design-edits`.

Design facts and checks (engine `practices/DesignChecks.java` on `CodeFacts` and the call graph):

1. `groovyscope/design` and MCP tool `class_design(symbol | path)`: responsibilities (public methods grouped by verb, as `explain_code` does), cohesion (LCOM4 over field use per method, with the groups of methods that share no fields), fields read by one method only (candidates to become locals), methods using another class's fields or accessors more than their own (feature envy, with the target class), parameter lists shared by three or more methods (parameter object candidate), `instanceof` or `switch` on class chains (polymorphism candidate), public mutable fields, static mutable state, size metrics; each with a suggested refactor and the edit tool call that performs it.
2. GS090 god class (`design.maxMethods` 25, `design.maxFields` 12, `design.maxLines` 600, any one over). GS091 feature envy. GS092 long parameter list (over `design.maxParameters`, default 4). GS093 `instanceof` chain of three or more. GS094 public mutable field or a collection returned by reference from a getter. GS095 anemic data class: only getters and setters while another class owns all the logic over its fields. GS096 primitive obsession: three or more String parameters named like identifiers, paths or codes on one method. GS097 method that uses no instance state (make it static, or move it to the class whose state it uses). All information level except GS094 (warning); reported once per class.

Semantic edit tools, on the Phase 1 infrastructure:

1. `change_signature(symbol, parameters: [{name, type, default?}], returnType?, apply?)`: rewrites the declaration and every STATIC call site (argument order, added defaults), refuses when a call site is matched by name only, exactly as `rename` refuses; dry run gives one plan per file.
2. `extract_method(path, startLine, endLine, name, apply?)`: the selected statements become a method; parameters and return value derived from the data flow (variables read before the block and written in it); the call replaces the block; `suggest_reuse` runs on the new body first and the dry run says when an existing method already does it.
3. `move_member(symbol, toClass, apply?)` for a static method or a method that uses no instance state (GS097), with every STATIC call site updated.
4. `edit_symbol` gains `doc: concise` which puts one Javadoc sentence built from `CodeFacts` (what it returns, what it touches) above a public member, and nothing on the rest.
5. Every edit tool's dry run returns the gate's `advice` block: duplicates, type mixing, comment share, design findings on the touched class, tests reaching the changed symbol. The agent fixes the text and calls again before applying; the steering says so.
6. LSP: the fixes that are safe surface as code actions through `FixService` (remove restating comment, `.length` to `.size()`, add import with type); `extract_method` as a refactor code action on a range.

Steering (two lines): "Before changing a class, `class_design` names its responsibilities; put a method where its state lives, and extract shared code with `extract_method`. Change a public signature with `change_signature`, never by editing call sites one at a time."

Done when: `class_design` on the corpus's `CodeNarcRunner` reports its groups and candidates; `change_signature` on a fixture method updates three call sites and refuses on a dynamic one; `extract_method` produces a compiling method with the right parameters; released.

### Phase 7: Kiro assets, documentation, release

Branch `docs/edit-intelligence`.

1. Steering: add the lines quoted in phases 2 to 6 under one heading "Every edit", keep the file under 75 lines; the `userPromptSubmit` policy line repeats only the thresholds.
2. Skills (templates, one per recipe, same shape as the twelve): `groovy-edit` (plan, inspect the diff and the advice, apply with `write`, read the after write block), `groovy-tests` (`test_plan`, add only what is needed, `verify --coverage`), `groovy-design` (`class_design`, refactor with the edit tools), `groovy-reuse` (`suggest_reuse` before writing). Serve them as MCP prompts too.
3. Agent template: `allowedTools` unchanged (read only tools only), the edit tools and `test_plan` with Gradle side effects left out so Kiro asks; V3 permission rules `ask` for every tool that writes; `preToolUse` and `userPromptSubmit` hooks; `groovy-reviewer` gains `class_design`, `test_plan` and `suggest_reuse` (all read only).
4. Docs: README (new tools, the planned write flow in one paragraph, new configuration keys), `docs/kiro.md` (the gate, the hooks, the diff and y, n, t story, what blocks and what warns), `docs/practices.md` and `docs/rules.md` (every new code), `docs/prompts.md` (four recipes), `docs/architecture.md` (`preview`, `edit`, `similar`, `design`, `plans.py`, `gate.py`; the architecture test must pass), `docs/troubleshooting.md` (a blocked write and how to override once: `hooks.preWrite.mode: warn`, `GROOVYSCOPE_HOOKS__PREWRITE__MODE=warn` for one session), `docs/security.md` (the gate reads candidate text from the hook payload and never writes; plans live under the cache with 0600).
5. `docs/roadmap.md`: Stages 24 to 30, one per phase, with the owner checks listed apart as the roadmap does.
6. Benchmark: repeat `docs/benchmark.md` tasks 3 and 4 with the gate on and record credits and minutes against the 0.28 numbers (owner's credits; ask first).

## 4. Order and sizing

Phase 0 is half a day of code plus the owner's capture. Phase 1 is the largest (engine request, edit request, hooks, plans, one tool) and unlocks everything; do it first and release it alone. Phases 2, 3 and 4 are independent of each other after Phase 1 and can run in parallel branches if desired; Phase 5 depends on nothing but Phase 1; Phase 6 depends on phases 1 and 4. Phase 7 closes.

Each phase: branch, specs and tests first for the fixture cases, implementation, corpus calibration recorded in the research doc, templates synced, docs touched by the phase updated (the architecture test enforces part of it), roadmap entry, PR with CI green, release.

## 5. Owner checks after each release (Kiro CLI)

1. Phase 1: write a broken method through `write`; expect the block (or the warning) before the file changes. Ask for a method replacement; expect the agent to call `edit_symbol`, show the diff, then `write` with the y, n, t prompt and Kiro's coloured diff; expect `planned write applied` in the after write block.
2. Phase 2: ask for a method with "good comments"; expect at most a one line why, and a GS060 or GS065 refusal when the agent overdoes it.
3. Phase 3: ask for a method that parses a count from a map; expect declared types and no GS070 or GS077 in the after write block.
4. Phase 4: ask for a retry helper that already exists under another name; expect `suggest_reuse` to name it and no new method.
5. Phase 5: change a public signature with an integration test caller; expect GS049 in `review_changes` and the integration test updated in the same turn; run `verify --coverage` below 80 and expect exit 1 with the classes named.
6. Phase 6: ask to "tidy" a large class; expect `class_design` first and `extract_method` or `move_member`, never a hand rewrite.

Record each in the roadmap's owner checks.

## 6. Decisions for the owner before Phase 1

1. Tool surface: the new tools (`edit_symbol`, `change_signature`, `extract_method`, `move_member`, `suggest_reuse`, `test_plan`, `class_design`) add roughly 3,000 bytes of schema. Either raise the budget in `test_tool_surface.py` to about 18,500, or drop `goto_definition`, `hover` and `find_references` from the default agent as `docs/kiro.md` already suggests (`symbol_context` covers them) and stay near 16,000.
2. Gate default: `hooks.preWrite.mode: warn` on first release and `block` from the next, or `block` from the start. Suggested: `warn` for one release so the owner sees what it would have blocked, then `block`.
3. `hooks.preWrite.requirePlan`: whether a raw `write` of a Groovy declaration over 40 lines is refused outright with "use `edit_symbol`". Suggested: off by default, on in the owner's own projects.
4. Coverage scope: per changed class (suggested) or overall.
5. Whether GS077 (the type preview) runs in the hook on every write or only in the edit tools and `review_changes`, to be decided from the Phase 3 timing on the owner's 3,000 line file.
6. Whether `apply: true` on the edit tools (groovyscope writes, elicitation shows the diff) is offered to the agent at all in Kiro, or kept for Claude Code and CI where the native `write` diff does not exist. Suggested: offered, with the steering preferring the planned `write` path in Kiro.
