# groovyscope in CI

groovyscope fits a pipeline that already runs `./gradlew test` with JaCoCo and `./gradlew spotbugsMain` with a
SARIF artifact. Two jobs: one that gives the pipeline groovyscope's findings as a SAST report, and one that
reviews a merge request the way the agent does locally.

## GitLab

```yaml
groovyscope-check:
  stage: test
  image: python:3.13
  variables:
    GROOVYSCOPE_WORKSPACE__TRUST: "true"      # the runner's environment, not the repository, grants trust
  before_script:
    - pip install --quiet groovyscope
    - groovyscope classpath --timeout 900      # resolves dependencies through the project's Gradle wrapper
  script:
    - groovyscope check --format sarif --import-sarif build/reports/spotbugs/spotbugs.sarif.json > groovyscope.sarif.json
    - groovyscope check --format short
  needs:
    - job: spotbugs-scan
      artifacts: true
  artifacts:
    reports:
      sast: groovyscope.sarif.json
    paths: [groovyscope.sarif.json]
    when: always
  allow_failure: false        # check exits 1 at or above lint.failOnPriority

groovyscope-review:
  stage: test
  image: python:3.13
  rules:
    - if: $CI_PIPELINE_SOURCE == "merge_request_event"
  variables:
    GIT_DEPTH: 0                              # review_changes needs the merge base
    GROOVYSCOPE_WORKSPACE__TRUST: "true"
  before_script:
    - pip install --quiet groovyscope
    - git fetch origin "$CI_MERGE_REQUEST_TARGET_BRANCH_NAME"
    - groovyscope classpath --timeout 900
  script:
    - groovyscope review --changed --base "origin/$CI_MERGE_REQUEST_TARGET_BRANCH_NAME" | tee review.txt
  needs:
    - job: unit-test
      artifacts: true                         # brings build/reports/jacoco/test/jacocoTestReport.xml
    - job: spotbugs-scan
      artifacts: true
  artifacts:
    paths: [review.txt]
    when: always
```

`groovyscope review --changed` exits 1 when a blocking finding sits on a changed line, so the job fails the
merge request the way a reviewer would request changes. With the JaCoCo and SpotBugs artifacts present, the
review also names the methods in the change that no test executed and merges SpotBugs findings into the
blocking and should-fix lists. Post `review.txt` as the merge request note from your GitLab MCP server or a
`curl` to the notes API if you want it in the thread.

The JDK comes from the image or the runner; `groovyscope doctor` prints which one. A JDK 17 or newer is
enough for groovyscope itself; the project's build may need its own toolchain, which `classpath` runs with.

## Merge request review with Kiro

The same review the agent gives interactively, as a pipeline job. `kiro-cli` must be installed on the runner and
signed in (a token in a CI variable); groovyscope's MCP server must start before the prompt runs
(`--require-mcp-startup`), and the output is JSON lines (`--output-format stream-json`), one object per event,
so the final answer can be picked out and posted.

```yaml
groovyscope-review:
  stage: test
  image: python:3.13
  variables:
    GROOVYSCOPE_WORKSPACE__TRUST: "true"
  before_script:
    - pip install --quiet groovyscope
    - groovyscope kiro init                  # .kiro/agents/groovy-reviewer.json, mcp.json, steering, lsp.json
    - groovyscope classpath --timeout 900
  script:
    - >
      kiro-cli chat --no-interactive --agent groovy-reviewer --require-mcp-startup
      --trust-tools read,code,@groovyscope --output-format stream-json
      "Review this branch as a merge request reviewer against $CI_MERGE_REQUEST_TARGET_BRANCH_NAME"
      > review.jsonl
    - python3 -c "import json,sys; [print(e.get('content','')) for e in map(json.loads, open('review.jsonl')) if e.get('type')=='assistant']" > review.md
  artifacts:
    paths: [review.md, review.jsonl]
    when: always
  allow_failure: true          # the verdict is for people; `groovyscope check` is the gate
```

The reviewer agent only reads, so `--trust-tools` lists read, `code` and the groovyscope server; with a V3 agent
the same comes from its `permissions.rules`. Posting `review.md` to the merge request is one `curl` against the
GitLab notes API with a project token, or a GitLab MCP server in an interactive session (the steering covers
that). The event shapes of `stream-json` are Kiro's and move between releases; the one-liner above keeps the
assistant text and ignores the rest.

## Local parity

The same three commands give the same answers on a developer machine, and the agent calls the same tools:

```bash
groovyscope check --format short            # what the pipeline sees
groovyscope review --changed --base main    # what the merge request reviewer sees
groovyscope coverage                        # what coverage_gaps sees (after ./gradlew test jacocoTestReport)
```

## Exit codes

| Command | Exit 1 when |
|---|---|
| `groovyscope check` | a finding at or above `lint.failOnPriority` (default 2: errors and warnings) |
| `groovyscope review --changed` | a blocking finding on a changed line, or git could not describe the change |
| `groovyscope review` (whole workspace or paths) | an error-level group |
| `groovyscope coverage` | no JaCoCo report, or an unreadable one |
