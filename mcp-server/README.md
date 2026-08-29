# mcpfabric MCP server

Bridges an MCP client (Codex / Claude Desktop / Claude Code / any MCP host) to the in-game HTTP
bridge exposed by the **mcpfabric** Fabric mod.

The default `catalog` mode exposes five stable MCP tools while preserving all 53 game commands:

- `minecraft_status`
- `command_catalog`
- `command_describe`
- `command_invoke`
- `command_batch`

This keeps the initial model context small. Command names are exact, schemas are loaded only when
needed, arguments are validated before dispatch, and mutations are never retried automatically.

## Install & build

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
| `MCPFABRIC_TIMEOUT_MS` | `15000`                  | Per-call timeout.                               |
| `MCPFABRIC_TRANSPORT`  | `stdio`                  | `stdio` (default) or `http`.                    |
| `MCPFABRIC_HTTP_PORT`  | `25600`                  | Port for the streamable-HTTP transport (`/mcp`).|
| `MCPFABRIC_TOOL_MODE`  | `catalog`                | `catalog` (5 tools), `hybrid` (catalog + 53 raw), or `all` (53 raw only). |

## Codex

Codex can launch the local stdio server directly:

```bash
codex mcp add mcpfabric \
  --env MCPFABRIC_TOOL_MODE=catalog \
  --env MCPFABRIC_TOKEN_FILE=/absolute/path/to/config/mcpfabric.config.json \
  -- node /absolute/path/to/mcp-server/dist/index.js
```

Use `hybrid` or `all` only when a client specifically needs every raw MCP tool to be model-visible.
The catalog mode does not reduce Minecraft permissions: commands such as `run_command`, world
writes, inventory actions, movement, combat, and screenshots remain available through
`command_invoke`.

## Architecture

`src/tools.ts` remains the single source of truth: each entry maps a command to a bridge RPC method
and zod input schema. `src/catalog.ts` derives searchable summaries, exact JSON schemas, schema
hashes, and delivery/postcondition metadata from that table. `src/index.ts` registers the compact
catalog and/or raw tools and forwards validated calls to `POST /rpc`; screenshots remain image
content blocks. `src/bridge.ts` is the HTTP client; `src/config.ts` reads environment config.

Keep tool names/methods in sync with the Java handler registry in the mod
(`dev.mcpfabric.handlers.*` and `dev.mcpfabric.client.handlers.*`).
