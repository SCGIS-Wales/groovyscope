# groovyscope

Groovy 5/6 code intelligence for AI coding agents. One warm JVM engine (Groovy compiler, CodeNarc, Groovy
Eclipse formatter) exposed as an LSP server for editors and Kiro CLI's `code` tool, and as an MCP server for
higher-order questions such as impact analysis, lint, format and security findings.

```bash
uv tool install groovyscope
groovyscope doctor
groovyscope kiro init
```

Full documentation lives in the repository: https://github.com/SCGIS-Wales/groovyscope
