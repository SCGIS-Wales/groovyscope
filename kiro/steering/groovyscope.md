---
inclusion: always
---
# Groovy navigation and quality policy

- For any Groovy, Gradle or Jenkinsfile question, use the `code` tool (find references, go to definition,
  hover, diagnostics, rename with dry run) or `groovyscope` MCP tools. Do not use grep, rg, sed, awk,
  head or tail to locate Groovy symbols.
- Before changing a public method, class or closure parameter, call `groovyscope.impact_of` and report
  affected files.
- After every edit to a .groovy file, call `groovyscope.check` on the changed files and fix all
  priority 1 and 2 findings before continuing.
- Format only through `groovyscope.format`; never hand align code.
- Target Groovy 6.0 on JDK 21 (code must also compile on Groovy 5.1 / JDK 17 unless marked otherwise).
  Prefer @CompileStatic; justify every @CompileDynamic in a comment.
- Never enable @Grab or @ASTTest; never build SQL with string concatenation; never deserialise untrusted data.
