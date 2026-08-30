# Architecture

```text
MCP host
  -> index.ts                    registration and invocation
  -> catalog.ts + tools.ts       discovery, schemas and metadata
  -> bridge.ts                   authenticated HTTP
  -> HttpBridgeServer            routing and game-thread dispatch
  -> common/client handlers      Minecraft APIs
  -> readback                    confirmation and errors
```

## Source map

- `mcp-server/src/tools.ts`: command definitions and schemas.
- `mcp-server/src/catalog.ts`: metadata, availability and postconditions.
- `src/main/java/dev/mcpfabric/bridge/`: transport and dispatch.
- `src/main/java/dev/mcpfabric/handlers/`: common/server operations.
- `src/client/java/dev/mcpfabric/client/handlers/`: player, placement, GUI and inventory.
- `src/client/java/dev/mcpfabric/client/nav/`: built-in and optional Baritone navigation.
- `src/main/java/dev/mcpfabric/config/`: local capability configuration.

## Execution rules

The MCP process runs outside Minecraft. Game operations execute on the relevant game thread.
Discovery does not grant permissions. Mutations can consume materials or change the world:
inspect readback before continuing; a timeout does not prove that nothing happened.

Multi-step operations are not universal transactions. Earlier successful steps can remain after
a later failure. Creative editing/rollback does not imply general survival rollback.

Integrated-server readback can consult authoritative state. Remote-client confirmation relies on
synchronized client state and preserves that distinction. Navigation can be asynchronous; use
status/stop controls. There is no universal durable task engine resuming arbitrary construction
across process restarts.

## Extension rule

Keep each command's schema, handler, capability gate and confirmation aligned. Prefer generic
inventory/widget operations for mod compatibility. Add a mod-specific adapter only when the generic
contract cannot describe the interaction. Java scratch is a high-trust development escape hatch,
not a safe unattended fallback.
