# NeoForge 26.2

Minecraft Automation supports NeoForge **26.2.0.88** and Minecraft **26.2**, using Java **25**.
The mod ID, config filename, authentication, RPC methods, MCP catalogue and TypeScript service
stay `mcpfabric`. Install one loader-specific jar per instance.

## Build and install

```sh
./gradlew -p neoforge build
```

Gradle itself must run on JDK 25 (`JAVA_HOME`). The artifact is
`neoforge/build/libs/mcpfabric-0.3.0-zedo.31-neoforge+26.2.jar`.
The existing Fabric command remains `./gradlew :26.2:build`.

The NeoForge build first regenerates and compiles Fabric 26.2 through Stonecutter, then uses the
version-specific common/client Java sources with NeoForge adapters. It packages no Fabric Loader
or Fabric API. The game handlers and the existing mixin accessors are shared rather than copied.
No additional library is required beyond NeoForge.

## Isolated testing

```sh
./gradlew -p neoforge runClient
./gradlew -p neoforge runServer
```

These use ignored profiles `local/neoforge-client` and `local/neoforge-server`. They do not open
HMCL or use an existing world. Server tests need the EULA accepted in that profile. Put optional
test mods in the profile's `mods` directory. Assign each simultaneous bridge a different config
`port`, and each simultaneous server a different Minecraft `server-port`.

The bridge config is `<profile>/config/mcpfabric.config.json`; defaults bind to loopback on 25599.
Use the existing MCP service with `MCPFABRIC_URL` pointing to that bridge and
`MCPFABRIC_TOKEN_FILE` pointing to the profile's config. Credentials remain inside ignored profiles.
The Node MCP tools and their schemas are identical to Fabric.

## Loader behavior

All existing game RPC handlers remain available according to their original capability gates.
Client setup registers the same player, inventory, screen, vision, placement, navigation and Java
scratch handlers. Common handlers run on dedicated and integrated servers. A dedicated-server
stop closes the bridge; leaving an integrated world preserves it until client exit.

Lifecycle, chat, login/logout and damage/death subscriptions use NeoForge events. Client chat
resolves sender names from the player-list profile. NeoForge damage events report the original
incoming amount. `entity_death` / `player_death` observes NeoForge's uncancelled `LivingDeathEvent`
at LOWEST priority; this hook occurs before completion of `die`, whereas Fabric's hook is
`AFTER_DEATH`. Mods cancelling later at the same priority may affect that observation.
Baritone remains optional and requires a build compatible with the active loader; the built-in
navigation backend stays available.

## Validation on 2026-10-07

- Fabric 26.2 common/client compilation and NeoForge build passed using JDK 25.
- Existing TypeScript MCP tests passed: **13/13**.
- An isolated NeoForge dedicated server reached `Done`, registered all **28** server RPCs,
  and returned Minecraft 26.2 / the correct mod version / dedicated-server capabilities.
- Live bridge checks passed: health, HTTP 401 without authentication, three dimensions,
  main-thread block placement/readback/restoration, commands and cow death events.
- `command.run stop` saved the test world, completed the Gradle run successfully and closed
  the HTTP bridge. Local evidence: `local/neoforge-server/smoke-report.json` and `logs/latest.log`.

A separate client acceptance run joined the isolated Building Gadgets 2 / Curios server as
`TechPortTest`. Client RPC registration, screenshots and player interaction worked. Authenticated
Java scratch compiled actual imports of `net.minecraft.client.Minecraft` and
`com.google.gson.JsonObject`, returning the player name and NeoForge loader successfully.

That run found an existing main-hand `interact.useItem` bug: a block PASS skipped `Item.use`.
The handler now follows Minecraft's target-to-item fallthrough. Block SUCCESS or FAIL stops;
block PASS tries the held item. Unconsumed entity interaction also tries the held item. The return
payload includes `targetResult` and `itemUseFallback` for target readback. This keeps the existing
main-hand API; it does not automatically retry with the offhand.

Survival placement and third-party mod screen coverage still require scenario-specific acceptance.
Compilation and these runtime checks do not prove arbitrary machine or mod compatibility.
