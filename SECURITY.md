# Minecraft Automation Security Policy

## The threat model you should understand before running this

Minecraft Automation can give an AI agent **operator-level control** in a local/admin context: running
arbitrary commands, editing the world, moving and managing players, and reading or operating the
screen. It does not grant OP on a remote server. It also supports arbitrary in-process Java execution. The mod exposes
these capabilities through a local HTTP bridge.

The default configuration:

- binds to **`127.0.0.1`**, and
- requires a **bearer token** stored in `config/mcpfabric.config.json` and never printed to logs.

Treat the token like a password. Anyone who can reach the bridge and present the token can do
anything an operator can do. Do **not** expose the bridge to other interfaces or forward the port
without putting an authenticated reverse proxy in front of it, and understand that doing so hands
remote control of your game to whoever holds the token.

You can narrow what the bridge allows with the `enable*` flags in `config/mcpfabric.config.json`
(`enableWorldWrite`, `enableCommands`, `enablePlayerControl`, `enableVision`, `enableUnsafeJava`).

The current development defaults enable these groups, including `enableUnsafeJava`. Review and
disable what you do not need before connecting an agent. Changing source defaults does not rewrite
existing user configuration. Do not treat bearer authentication as a sandbox.

`enableUnsafeJava` is not merely operator-level Minecraft access. It can read and write any file
available to the Minecraft process, open network connections, launch child processes, inspect or
mutate JVM state, freeze or crash the game, and corrupt local worlds. It inherits the permissions
of the OS user running Minecraft; it does not inherently grant root or bypass a remote multiplayer
server's authority. The handler refuses to run if bearer authentication is disabled and writes a
hash-only record for each attempt to `config/mcpfabric-java-audit.jsonl`.

## Supported versions

Current fork validation focuses on Minecraft 26.2. Other version nodes are inherited upstream
configuration, not a promise of security maintenance or runtime compatibility.

## Reporting a vulnerability

Please report security issues **privately** — do not open a public issue for anything exploitable.

- Use GitHub's [private vulnerability reporting](https://github.com/zedoCN/minecraft-automation/security/advisories/new)
  ("Report a vulnerability"), or
- email the maintainer at the address on their GitHub profile.

Include a description, affected versions, and reproduction steps. You can expect an initial
response within a few days. Please give a reasonable window to ship a fix before public disclosure.
