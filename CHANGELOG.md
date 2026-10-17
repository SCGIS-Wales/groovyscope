# Changelog

## [0.7.0](https://github.com/SCGIS-Wales/groovyscope/compare/v0.6.2...v0.7.0) (2026-10-17)


### Features

* groovy practices knowledge base, runtime profiles and global kiro setup ([#32](https://github.com/SCGIS-Wales/groovyscope/issues/32)) ([9ebb20a](https://github.com/SCGIS-Wales/groovyscope/commit/9ebb20aac9e7d96be706c93d849b684e29693386))


### Documentation

* record the first credit benchmark run ([#30](https://github.com/SCGIS-Wales/groovyscope/issues/30)) ([30f70e5](https://github.com/SCGIS-Wales/groovyscope/commit/30f70e50f995d44a4edf5aa148a69eb800601f02))

## [0.6.2](https://github.com/SCGIS-Wales/groovyscope/compare/v0.6.1...v0.6.2) (2026-10-17)


### Bug Fixes

* **mcp:** keep the engine session quiet and bounded ([#28](https://github.com/SCGIS-Wales/groovyscope/issues/28)) ([71d106f](https://github.com/SCGIS-Wales/groovyscope/commit/71d106f5ae5d77b7613d2c56ae9abda0dd324163))

## [0.6.1](https://github.com/SCGIS-Wales/groovyscope/compare/v0.6.0...v0.6.1) (2026-10-17)


### Bug Fixes

* **engine:** report execute() only where it can launch a process ([#27](https://github.com/SCGIS-Wales/groovyscope/issues/27)) ([eb64ccf](https://github.com/SCGIS-Wales/groovyscope/commit/eb64ccf3329a7a606a2dec565d1ad16d160292e8))
* **engine:** resolve joint groovy/java projects and stop retry cascades ([#25](https://github.com/SCGIS-Wales/groovyscope/issues/25)) ([066afb7](https://github.com/SCGIS-Wales/groovyscope/commit/066afb71ebc613e941e549fcd8eabc54b5102642))
* **kiro:** write the groovy agent in the format Kiro CLI 2.x loads ([#26](https://github.com/SCGIS-Wales/groovyscope/issues/26)) ([bd91fdc](https://github.com/SCGIS-Wales/groovyscope/commit/bd91fdcda1f58c4ec8494b8d566a2e5e632e595c))


### Dependencies

* Bump actions/attest-build-provenance from 3.0.0 to 4.2.2 ([#5](https://github.com/SCGIS-Wales/groovyscope/issues/5)) ([db84cd8](https://github.com/SCGIS-Wales/groovyscope/commit/db84cd8bf7cf68a71abe0179c1fac0f4140de714))
* Bump actions/upload-artifact from 5.0.0 to 7.0.2 ([#4](https://github.com/SCGIS-Wales/groovyscope/issues/4)) ([d0226e0](https://github.com/SCGIS-Wales/groovyscope/commit/d0226e0066dd0a241d87d9317c68d3e1ffc2c7c3))
* Bump astral-sh/setup-uv from 7.6.0 to 10.3.0 ([#7](https://github.com/SCGIS-Wales/groovyscope/issues/7)) ([1f9912d](https://github.com/SCGIS-Wales/groovyscope/commit/1f9912d9df2e4f5aa2e3b77389dd23247a7274c8))
* Bump googleapis/release-please-action from 4.4.1 to 5.0.0 ([#3](https://github.com/SCGIS-Wales/groovyscope/issues/3)) ([ca0191e](https://github.com/SCGIS-Wales/groovyscope/commit/ca0191ed4fcc290019f62d98bf73b6beb5988c16))
* Bump gradle/actions/setup-gradle from 5.0.2 to 6.4.0 ([#6](https://github.com/SCGIS-Wales/groovyscope/issues/6)) ([69b935c](https://github.com/SCGIS-Wales/groovyscope/commit/69b935c9d6a1fce69eaba499110dfbfedc218354))
* bump org.cyclonedx.bom from 1.4.0 to 3.5.1 in /engine ([#2](https://github.com/SCGIS-Wales/groovyscope/issues/2)) ([676ec44](https://github.com/SCGIS-Wales/groovyscope/commit/676ec44dd5c32ef8143f1edc0631f9dc560f3c60))


### Documentation

* log the v0.6.0 release and the completed roadmap ([3eb91d4](https://github.com/SCGIS-Wales/groovyscope/commit/3eb91d47be38969174f7876221e6f907537121a1))

## [0.6.0](https://github.com/SCGIS-Wales/groovyscope/compare/v0.5.0...v0.6.0) (2026-10-17)


### Features

* mcp server with nine tools and a dynamic code finder (stage 5) ([#18](https://github.com/SCGIS-Wales/groovyscope/issues/18)) ([6f6210f](https://github.com/SCGIS-Wales/groovyscope/commit/6f6210f2d1b00a5e73523fb7ece3880d634aaef1))
* packaging extras: fetch, Temurin provisioning and a Homebrew tap (stage 6) ([#21](https://github.com/SCGIS-Wales/groovyscope/issues/21)) ([5885ecf](https://github.com/SCGIS-Wales/groovyscope/commit/5885ecf56f6465d3077b644785853aaa174bdf49))


### Bug Fixes

* macos-hermetic jdk provisioning test, docs polish and the benchmark protocol (stage 7) ([#22](https://github.com/SCGIS-Wales/groovyscope/issues/22)) ([2de6900](https://github.com/SCGIS-Wales/groovyscope/commit/2de6900d3b74d0f75fc1f046720711db12e10b24))


### Documentation

* mark stage 5 merged and stage 6 in progress ([f574c51](https://github.com/SCGIS-Wales/groovyscope/commit/f574c512821cc7f8a218c13b637475b6763ec35f))

## [0.5.0](https://github.com/SCGIS-Wales/groovyscope/compare/v0.4.0...v0.5.0) (2026-10-17)


### Features

* call graph, impact analysis, call hierarchy and semantic tokens (stage 4) ([#15](https://github.com/SCGIS-Wales/groovyscope/issues/15)) ([3dd9a17](https://github.com/SCGIS-Wales/groovyscope/commit/3dd9a1701e774bd58e5147fd92470b4923cd84ce))


### Bug Fixes

* **engine:** keep edits that arrive during a compile from being lost ([#17](https://github.com/SCGIS-Wales/groovyscope/issues/17)) ([f8b0d13](https://github.com/SCGIS-Wales/groovyscope/commit/f8b0d13e048ec1344ac3c5a7559f3a5148aba700))


### Documentation

* mark stage 4 merged and stage 5 in progress ([7effd09](https://github.com/SCGIS-Wales/groovyscope/commit/7effd099f233ea00d79a5fed36deb23d8687ba90))

## [0.4.0](https://github.com/SCGIS-Wales/groovyscope/compare/v0.3.0...v0.4.0) (2026-10-17)


### Features

* lint, type-check and format (stage 3) ([#13](https://github.com/SCGIS-Wales/groovyscope/issues/13)) ([cdf61d5](https://github.com/SCGIS-Wales/groovyscope/commit/cdf61d5772f6c6534518fc78dce81430f9f072cb))


### Documentation

* mark stage 3 merged and stage 4 in progress ([e053092](https://github.com/SCGIS-Wales/groovyscope/commit/e0530926021cbc4180e77726fcfc58d16eeab47d))

## [0.3.0](https://github.com/SCGIS-Wales/groovyscope/compare/v0.2.0...v0.3.0) (2026-10-17)


### Features

* warm engine daemon with stdio relay and symbol cache (stage 2) ([#11](https://github.com/SCGIS-Wales/groovyscope/issues/11)) ([d56858d](https://github.com/SCGIS-Wales/groovyscope/commit/d56858d24311233279627c793327dbddf8e7067e))

## [0.2.0](https://github.com/SCGIS-Wales/groovyscope/compare/v0.1.0...v0.2.0) (2026-10-17)


### Features

* engine navigation over LSP (stage 1) ([#10](https://github.com/SCGIS-Wales/groovyscope/issues/10)) ([6e56b92](https://github.com/SCGIS-Wales/groovyscope/commit/6e56b9212216df675a280fd3bd9f6e4e89340934))


### Documentation

* mark stage 0 released and stage 1 in progress ([1ce6c6a](https://github.com/SCGIS-Wales/groovyscope/commit/1ce6c6ab045f22eaf5a7e44c68e5830513fcb225))
* mark stage 1 merged and stage 2 in progress ([cf660ff](https://github.com/SCGIS-Wales/groovyscope/commit/cf660ffb1c341ec780e9ed9c3e6a891fd9dc8afd))

## 0.1.0 (2026-10-17)


### Features

* groovyscope foundation ([#1](https://github.com/SCGIS-Wales/groovyscope/issues/1)) ([64ad057](https://github.com/SCGIS-Wales/groovyscope/commit/64ad05779d70dc0ec626726177b1d2f73d50824c))


### Documentation

* record stage 0 merge and the release-please blocker in the roadmap ([1e99fa4](https://github.com/SCGIS-Wales/groovyscope/commit/1e99fa4b1b772d4bb3cca04d4629b070cd83f0ca))
* record the repository transfer to SCGIS-Wales ([97529a8](https://github.com/SCGIS-Wales/groovyscope/commit/97529a82166095bcede3eecedcba01f0ec16aaf5))

## Changelog

All notable changes to this project are documented in this file. The format is maintained by
[release-please](https://github.com/googleapis/release-please) from Conventional Commits.
