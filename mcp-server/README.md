# Minecraft Automation MCP server

Bridges an MCP client (Codex / Claude Desktop / Claude Code / any MCP host) to the in-game HTTP
bridge exposed by the **mcpfabric** Fabric mod.

The default `catalog` mode exposes six stable MCP tools while preserving the full command catalog:

- `minecraft_status`
- `command_catalog`
- `command_describe`
- `command_describe_many`
- `command_invoke`
- `command_batch`

This keeps the initial model context small. Command names are exact, schemas are loaded only when
needed, arguments are validated before dispatch, and mutations are never retried automatically.

## Install & build

For Baritone navigation, use `baritoneAllowParkour` to toggle native parkour and `sprint` to allow
sprint-assisted jumps. The legacy `maxGapJumpBlocks` remains compatible but is not a Baritone
distance cap (0=off, positive=on, 3 additionally permits sprint); the built-in backend retains its
hard zero/one-block limit. Explicit `baritoneAllowParkour` overrides the legacy on/off hint.
World/inventory mutations remain separate opt-ins and false by default.

```bash
npm install
npm run build       # -> dist/index.js
npm run typecheck   # tsc --noEmit
```

## Run

The MCP client normally launches this process. Manually:

```bash
MCPFABRIC_URL=http://127.0.0.1:25599 MCPFABRIC_TOKEN_FILE=/path/to/config/mcpfabric.config.json node dist/index.js
```

## Environment

| Variable               | Default                  | Meaning                                        |
|------------------------|--------------------------|------------------------------------------------|
| `MCPFABRIC_URL`        | `http://127.0.0.1:25599` | In-game bridge base URL.                        |
| `MCPFABRIC_TOKEN`      | —                        | Explicit bearer token; takes precedence over the token file. |
| `MCPFABRIC_TOKEN_FILE` | —                        | Read `token` directly from the mod JSON config so clients do not store a copied secret. |
| `MCPFABRIC_TIMEOUT_MS` | `15000`                  | Base per-call timeout; command `timeoutMs` receives 2 s transport headroom. |
| `MCPFABRIC_TRANSPORT`  | `stdio`                  | `stdio` (default) or `http`.                    |
| `MCPFABRIC_HTTP_PORT`  | `25600`                  | Port for the streamable-HTTP transport (`/mcp`).|
| `MCPFABRIC_TOOL_MODE`  | `catalog`                | `catalog` (6 tools), `hybrid` (catalog + raw commands), or `all` (raw commands only). |

## Codex

Codex can launch the local stdio server directly:

```bash
codex mcp add mcpfabric \
  --env MCPFABRIC_TOOL_MODE=catalog \
  --env MCPFABRIC_TOKEN_FILE=/absolute/path/to/config/mcpfabric.config.json \
  -- node /absolute/path/to/mcp-server/dist/index.js
```

Use `hybrid` or `all` only when a client specifically needs every raw MCP tool to be model-visible.
The catalog mode does not reduce configured Minecraft permissions. Live catalog results include
`available` / `unavailableReason`, and `command_catalog` defaults to `availableOnly=true`, so a
remote multiplayer client does not advertise unavailable server writes.

The expanded fork also exposes exact vanilla/mod key bindings, semantic nested-widget discovery,
precise block-face hit points, generic GUI/container input, and `java_scratch`. The latter executes
arbitrary authenticated Java inside the Minecraft JVM and has
the Minecraft process's normal file, network, process, and OS-user permissions. Keep loopback and
authentication enabled; see the repository `SECURITY.md` before using it.

## Architecture

`src/tools.ts` remains the single source of truth: each entry maps a command to a bridge RPC method
and zod input schema. `src/catalog.ts` derives searchable summaries, exact JSON schemas, schema
hashes, and delivery/postcondition metadata from that table. `src/index.ts` registers the compact
catalog and/or raw tools and forwards validated calls to `POST /rpc`; screenshots remain image
content blocks. `src/bridge.ts` is the HTTP client; `src/config.ts` reads environment config.

Keep tool names/methods in sync with the Java handler registry in the mod
(`dev.mcpfabric.handlers.*` and `dev.mcpfabric.client.handlers.*`).
