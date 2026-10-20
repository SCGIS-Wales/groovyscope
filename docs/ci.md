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
    GROOVYSCOPE_WORKSPACE__TRUST: "true"      # grants trust; better as a protected CI/CD variable (see below)
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

**Where trust comes from.** Trust lets `groovyscope classpath` run the project's build and lets global AST
transformations from the classpath run in the engine, so it must not be something a merge request can grant
itself. A `.groovyscope.yaml` in the repository cannot (see [security.md](security.md)), but the `variables:`
above live in `.gitlab-ci.yml`, which is in the repository too: a merge request could add them. Declare
`GROOVYSCOPE_WORKSPACE__TRUST` instead as a CI/CD variable in the project settings, marked protected, so only
pipelines on protected branches see it, and keep the line above only where every branch is trusted. A merge
request pipeline then runs untrusted: `groovyscope classpath` refuses, so write that step as
`groovyscope classpath --timeout 900 || true` and the review works from the sources alone (unresolved library
classes are folded into one GS100 warning per file).

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

## Repository settings for maintainers

The workflows enforce what they can; these settings are clicked by an owner of `SCGIS-Wales/groovyscope` and
complete the release chain.

- **Ruleset on `main`** (Settings, Rules, Rulesets): require a pull request before merging, linear history, and
  these status checks: `build (ubuntu-latest)`, `build (macos-latest)`, `python 3.13`, `python 3.14`,
  `python 3.15`, `engine on temurin 17`, `engine on temurin 21`, `engine on corretto 21`, `engine on temurin 25`,
  `analyze (python)`, `analyze (java-kotlin)`, `analyze (actions)`, `dependency review`,
  `audit the workflows (zizmor)` and `Conventional Commit title`. Approvals are optional: requiring one, or a
  code owner review (`.github/CODEOWNERS`), means an owner approves every PR, the release PRs included.
- **`pypi` environment** (Settings, Environments): deployment branches and tags limited to tags matching `v*`,
  so only a release tag can publish; `workflow.yml` checks the same and also that the tag's commit passed CI.
- **Release App** (optional, preferred over a personal token): create a GitHub App with contents and pull
  requests set to read and write, install it on this repository only, then add its ID as the variable
  `RELEASE_APP_ID` and a private key as the secret `RELEASE_APP_PRIVATE_KEY`. `release-please.yml` then opens and
  merges release PRs with short-lived installation tokens and the `RELEASE_PLEASE_TOKEN` secret can be deleted.
- **Allow auto-merge** (Settings, General): lets the release PR merge itself once its checks pass.

## Dependency findings in the same report

`check` and `review` merge any SARIF log listed under `reports.sarif` (or passed with `--import-sarif`) into their
findings, so a dependency scan can travel with the code findings:

- **OWASP Dependency-Check.** The `org.owasp.dependencycheck` Gradle plugin writes SARIF when asked for it
  (`dependencyCheck { formats = ['SARIF', 'HTML'] }`, then `./gradlew dependencyCheckAnalyze`), by default to
  `build/reports/dependency-check-report.sarif`. List that path:

  ```yaml
  reports:
    sarif: [build/reports/dependency-check-report.sarif, build/reports/spotbugs/spotbugs.sarif.json]
  ```

  Its results point at library files rather than workspace sources, so they are reported against the path the
  scanner gives; the level of each result (error, warning, note) becomes the finding's severity.
- **Gradle dependency verification.** `gradle/verification-metadata.xml` makes the build itself fail on a checksum
  or signature mismatch; it writes an HTML report, not SARIF, so there is nothing to merge. Keep the build step
  that fails, and let the SARIF of the scanners above carry the rest.

A listed SARIF path must stay inside the workspace; one that is missing or not SARIF 2.1 is noted on stderr and the
other findings are still reported.
