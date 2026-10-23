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
