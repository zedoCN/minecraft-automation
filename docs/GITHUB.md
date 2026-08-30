# GitHub repository settings

Public repository: [zedoCN/minecraft-automation](https://github.com/zedoCN/minecraft-automation).
Publishing changes or creating releases requires maintainer authorization; these instructions do
not publish anything by themselves.

Preserve upstream history and the original MIT copyright. Review the pending diff and Git
history before the initial push; a pattern scan is not a complete secret audit. Do not force-add
ignored worlds, credentials, server folders or downloaded dependencies. In the maintainer checkout,
`origin` points to Minecraft Automation and `upstream` preserves Etoryx/mcpfabric. Never push the
fork to upstream by accident.

The files in this repository cover CI, security scanning, issue forms, pull requests, ownership,
support, conduct, contributing, releases, and security reporting. The remaining presentation and
governance settings live in GitHub and require repository-admin access.

## About section

| Field | Value |
| --- | --- |
| Repository | `zedoCN/minecraft-automation` |
| Description | Structured, verified Minecraft automation for Codex and other MCP clients. |
| Website | Leave empty until a dedicated project page or release exists. |
| Topics | `minecraft`, `minecraft-mod`, `fabric`, `fabricmc`, `mcp`, `model-context-protocol`, `ai-agent`, `automation`, `java`, `typescript` |
| Project icon | `docs/assets/minecraft-automation-icon-512.png`; use a separate wide image for social preview |

Enable **Releases**, **Issues**, and **Discussions**. Use Discussions for setup questions and ideas
once it is enabled; keep reproducible defects in Issues.

## Security and analysis

Enable these settings for the public repository:

- dependency graph and Dependabot alerts;
- Dependabot security updates;
- secret scanning and push protection;
- private vulnerability reporting;
- CodeQL code scanning via `.github/workflows/codeql.yml`.

## Main branch ruleset

Protect `main` with:

- pull requests required before merge;
- at least one approving review;
- dismiss stale approvals after new commits;
- conversation resolution required;
- required checks after their first successful run: `Build mod (26.2)`, `MCP server (typecheck + tests)`,
  `Analyze (java-kotlin)`, and `Analyze (javascript-typescript)`;
- linear history and deletion protection;
- no force pushes and no bypass except emergency maintainers.

Prefer squash merges with Conventional Commit-style PR titles so generated release notes remain
readable.
