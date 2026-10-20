# Security policy

## Supported versions

Only the latest release on PyPI (`pip install --upgrade groovyscope`) receives security fixes. A fix ships as a
new release; older versions are not patched.

## Reporting a vulnerability

Please report a vulnerability privately through GitHub's private vulnerability reporting: open the
**Security** tab of [SCGIS-Wales/groovyscope](https://github.com/SCGIS-Wales/groovyscope/security) and choose
**Report a vulnerability**. Do not open a public issue, pull request or discussion for it.

Include what you can of:

- the groovyscope version (`groovyscope version`), the JDK and the operating system;
- what an attacker controls (a repository's files, a `.groovyscope.yaml`, an MCP request, a network
  location) and what they gain;
- the steps or a minimal workspace that shows it.

You will get an answer within a week. Once a fix is released, the advisory is published with credit to you,
unless you ask otherwise.

## What groovyscope protects

[docs/security.md](docs/security.md) describes the threat model: compiling a repository can run its code, so
settings that would run code from the repository are ignored until the user trusts the workspace, Grape and
`@ASTTest` are always off, and the engine never fetches configuration over the network. A way around any of
these is a vulnerability; please report it as above.
