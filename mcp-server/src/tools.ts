/**
 * The full mcpfabric tool catalogue.
 *
 * This table is the single source of truth for the RPC contract on the TypeScript side: every
 * entry maps an MCP tool (snake_case name) to a bridge RPC `method` (namespaced, dotted) plus a
 * zod input schema. `src/index.ts` registers each entry generically. Keep this in sync with the
 * Java handler registry in the mod (`dev.mcpfabric.handlers.*`).
 */
import { z } from "zod";

export interface ToolDef {
  /** MCP tool name exposed to the model. */
  name: string;
  /** Bridge RPC method this tool forwards to. */
  method: string;
  /** Short human title. */
  title: string;
  /** Description shown to the model — be precise about behaviour and side requirements. */
  description: string;
  /** zod raw shape describing the tool arguments. */
  inputSchema: z.ZodRawShape;
  /** Hints surfaced to MCP clients. */
  annotations?: {
    readOnlyHint?: boolean;
    destructiveHint?: boolean;
    idempotentHint?: boolean;
    openWorldHint?: boolean;
  };
  /** Rendering: "json" (default) returns text + structuredContent; "image" returns an image block. */
  kind?: "json" | "image";
  /** Delay before the next command in a batch can alter state needed by this command's server work. */
  batchBarrierMs?: number;
}

// ----- reusable schema fragments -------------------------------------------------------------

const vec3 = () => ({
  x: z.number().describe("X coordinate (east/west)."),
  y: z.number().describe("Y coordinate (height)."),
  z: z.number().describe("Z coordinate (north/south)."),
});

const dimensionOpt = {
  dimension: z
    .string()
    .optional()
    .describe('Dimension id, e.g. "minecraft:overworld", "minecraft:the_nether". Defaults to the current/overworld dimension.'),
};

const playerRef = {
  player: z
    .string()
    .describe('Target player by name or UUID. Use "@all" where broadcasting is meaningful.'),
};

const READ = { readOnlyHint: true } as const;
const WRITE = { destructiveHint: true } as const;

const containerSlotCondition = z.object({
  slot: z.number().int().min(0).describe("Exact menuSlot from get_screen_state."),
  empty: z.boolean().optional(),
  itemId: z.string().min(1).optional().describe('Namespaced item id; "minecraft:" is optional for vanilla items.'),
  componentFingerprint: z.string().regex(/^[0-9a-f]{64}$/).optional().describe("Exact data-component identity from an item snapshot."),
  count: z.number().int().min(0).optional(),
  minCount: z.number().int().min(0).optional(),
  maxCount: z.number().int().min(0).optional(),
});

const containerDataCondition = z.object({
  dataIndex: z.number().int().min(0).describe("Synchronized integer data index from get_screen_state."),
  value: z.number().int().optional(),
  minValue: z.number().int().optional(),
  maxValue: z.number().int().optional(),
});

const containerCondition = z.union([containerSlotCondition, containerDataCondition]);

const containerInputStep = z.object({
  slot: z.number().int().min(-999).describe("Exact menuSlot; -999 means outside the container."),
  button: z.number().int().optional().default(0),
  input: z.enum(["PICKUP", "QUICK_MOVE", "SWAP", "CLONE", "THROW", "QUICK_CRAFT", "PICKUP_ALL"]).optional().default("PICKUP"),
});

const widgetSelector = {
  widgetPath: z.string().regex(/^\d+(\/\d+)*$/).optional().describe("Exact recursive path from get_screen_state."),
  widgetIndex: z.number().int().min(0).optional().describe("Legacy top-level widget index."),
  classContains: z.string().min(1).optional().describe("Case-insensitive widget class-name fragment."),
  message: z.string().optional().describe("Exact visible widget message."),
  messageContains: z.string().min(1).optional().describe("Case-insensitive visible-message fragment."),
  currentValue: z.string().optional().describe("Exact current EditBox value."),
  currentValueContains: z.string().min(1).optional().describe("Case-insensitive current EditBox-value fragment."),
  focused: z.boolean().optional(),
  active: z.boolean().optional(),
  visible: z.boolean().optional(),
  occurrence: z.number().int().min(0).optional().describe("Zero-based match when a semantic selector intentionally matches more than one widget."),
};

const navigationSafetyOptions = () => ({
  safetyProfile: z.enum(["safe", "balanced", "risky"]).optional().default("safe")
    .describe("safe forbids all fluids and minimizes exposed edges; balanced permits non-lava fluids at high cost; risky retains hazard blocking but relaxes edge costs."),
  maxDropBlocks: z.number().int().min(0).max(3).optional()
    .describe("Maximum planned downward step. Defaults to 1/2/3 for safe/balanced/risky; this is geometric and does not assume armor or effects."),
  maxGapJumpBlocks: z.number().int().min(0).max(3).optional().default(1)
    .describe("Builtin: hard gap limit, supports 0-1 only. Baritone legacy hint, NOT a distance limit: 0 disables parkour, positive values enable native parkour, and 3 also permits sprinting. Prefer baritoneAllowParkour and sprint; Baritone chooses feasible jump distance."),
  avoidEntities: z.boolean().optional().default(true)
    .describe("Builtin: treat collidable entities as live obstacles and wait on exposed one-wide routes. Baritone: request native avoidance; this is not a guarantee of waiting at every neutral entity."),
  avoidHostiles: z.boolean().optional().default(true)
    .describe("Builtin: treat nearby hostile mobs as expanded dynamic obstacles. Baritone: request native avoidance; its costs and replanning semantics differ from builtin."),
  openDoors: z.boolean().optional().default(true)
    .describe("Plan through closed wooden doors and fence gates, then approach and open them with ordinary native hand interaction."),
  stopOnDamage: z.boolean().optional()
    .describe("Stop immediately if player health decreases during navigation. Defaults true in safe mode and false otherwise."),
  entityLookaheadNodes: z.number().int().min(1).max(8).optional().default(3)
    .describe("How many upcoming path nodes are scanned for collidable entities before advancing."),
  avoidBlockIds: z.array(z.string()).max(128).optional().default([])
    .describe('Additional exact vanilla or mod block IDs to forbid at feet, head, support, or immediate adjacency, e.g. ["mod:acid_block"].'),
  avoidFluidIds: z.array(z.string()).max(128).optional().default([])
    .describe('Additional exact vanilla or mod fluid IDs to forbid, e.g. ["mod:oil"]. Safe mode already forbids every non-empty fluid.'),
});

const rollingPlanOptions = () => ({
  ...navigationSafetyOptions(),
  segmentLength: z.number().int().min(8).max(48).optional().default(24)
    .describe("Maximum rolling A* segment length for distant targets. Shorter values load and revalidate terrain more frequently; 24 is the safe default."),
});

// ----- catalogue ------------------------------------------------------------------------------

export const TOOLS: ToolDef[] = [
  // ===== info ================================================================================
  {
    name: "get_status",
    method: "info.status",
    title: "Server/client status",
    description:
      "Get the current state of the running game: mod & Minecraft version, which side this bridge runs on (client / dedicated_server), whether an integrated server is present, whether the player is in a world, current dimension, and the list of available capability groups. Call this first to learn what you can do right now.",
    inputSchema: {},
    annotations: READ,
  },
  {
    name: "list_capabilities",
    method: "info.capabilities",
    title: "List capabilities",
    description:
      "List every capability group and whether it is currently available on this side (e.g. control/interact/vision/nav are client-only; players/command admin need a server). Useful to decide which tools will work.",
    inputSchema: {},
    annotations: READ,
  },
  {
    name: "get_client_status",
    method: "client.status",
    title: "Get live client connection status",
    description:
      "Client-only. Report whether a local player and client level exist, whether the connection is remote multiplayer, the current dimension/game mode, and whether a GUI is open.",
    inputSchema: {},
    annotations: READ,
  },

  // ===== world (read) ========================================================================
  {
    name: "get_block",
    method: "world.getBlock",
    title: "Get block at position",
    description:
      "Read the block at an exact integer position: its block id, blockstate properties, whether it is air/fluid/solid, light levels, and hardness. Requires a loaded chunk.",
    inputSchema: { ...vec3(), ...dimensionOpt },
    annotations: READ,
  },
  {
    name: "get_blocks_region",
    method: "world.getBlocks",
    title: "Scan a cuboid region",
    description:
      "Scan all blocks in the cuboid between two corners (inclusive) and return their ids. Volume is capped (default 32768 blocks) to protect the server; air is omitted unless includeAir is true. Use for mapping a small area.",
    inputSchema: {
      from: z.object(vec3()).describe("One corner of the cuboid."),
      to: z.object(vec3()).describe("Opposite corner of the cuboid."),
      includeAir: z.boolean().optional().default(false).describe("Include air blocks in the result."),
      maxBlocks: z.number().int().min(1).max(200000).optional().describe("Override the per-call block cap."),
      ...dimensionOpt,
    },
    annotations: READ,
  },
  {
    name: "find_blocks",
    method: "world.findBlocks",
    title: "Find nearby blocks by id",
    description:
      "Search a spherical radius around a center point for blocks matching any of the given ids (e.g. minecraft:diamond_ore). Returns matches sorted by distance. Only searches loaded chunks.",
    inputSchema: {
      center: z.object(vec3()).describe("Center of the search sphere."),
      radius: z.number().int().min(1).max(128).describe("Search radius in blocks."),
      blockIds: z.array(z.string()).min(1).describe('Block ids to match, e.g. ["minecraft:diamond_ore","minecraft:ancient_debris"].'),
      maxResults: z.number().int().min(1).max(1024).optional().default(64).describe("Maximum matches to return."),
      ...dimensionOpt,
    },
    annotations: READ,
  },
  {
    name: "get_time_and_weather",
    method: "world.getTimeAndWeather",
    title: "Time & weather",
    description:
      "Get the current day-time (0-24000), total game-time, day count, and weather (raining/thundering) for a dimension.",
    inputSchema: { ...dimensionOpt },
    annotations: READ,
  },
  {
    name: "list_dimensions",
    method: "world.getDimensions",
    title: "List dimensions",
    description: "List all dimensions present on the server and which one the player is currently in.",
    inputSchema: {},
    annotations: READ,
  },
  {
    name: "raycast",
    method: "world.raycast",
    title: "Raycast from a point",
    description:
      "Cast a ray and report the first block and/or entity it hits. Provide either an explicit direction vector or yaw/pitch angles. Great for 'what am I looking at' and line-of-sight checks.",
    inputSchema: {
      origin: z.object(vec3()).describe("Ray start position (usually an eye position)."),
      direction: z.object(vec3()).optional().describe("Ray direction vector (need not be normalized). Use this OR yaw/pitch."),
      yaw: z.number().optional().describe("Yaw in degrees (Minecraft convention). Use with pitch instead of direction."),
      pitch: z.number().optional().describe("Pitch in degrees (-90 up .. 90 down)."),
      maxDistance: z.number().min(0.1).max(256).optional().default(32).describe("Maximum ray length in blocks."),
      includeFluids: z.boolean().optional().default(false).describe("Treat fluids as hittable."),
      includeEntities: z.boolean().optional().default(true).describe("Also test entities along the ray."),
      ...dimensionOpt,
    },
    annotations: READ,
  },

  // ===== client world cache (read-only multiplayer observation) ===============================
  {
    name: "get_client_block",
    method: "clientWorld.getBlock",
    title: "Get a block from the client cache",
    description:
      "Client-only and read-only. Inspect an exact block in the currently loaded client dimension, including on remote multiplayer servers. Results are limited to synchronized client chunks and report source=client_cache.",
    inputSchema: { ...vec3(), ...dimensionOpt },
    annotations: READ,
  },
  {
    name: "get_client_blocks_region",
    method: "clientWorld.getBlocks",
    title: "Scan loaded client blocks",
    description:
      "Client-only and read-only. Scan a cuboid in the current client dimension, including on remote multiplayer servers. Unloaded chunks are skipped and reported explicitly.",
    inputSchema: {
      from: z.object(vec3()).describe("One corner of the cuboid."),
      to: z.object(vec3()).describe("Opposite corner of the cuboid."),
      includeAir: z.boolean().optional().default(false),
      maxBlocks: z.number().int().min(1).max(200000).optional(),
      ...dimensionOpt,
    },
    annotations: READ,
  },
  {
    name: "find_client_blocks",
    method: "clientWorld.findBlocks",
    title: "Find blocks in loaded client chunks",
    description:
      "Client-only and read-only. Find matching blocks around a point in the current client dimension. Useful on remote servers where server-side world RPC is unavailable.",
    inputSchema: {
      center: z.object(vec3()),
      radius: z.number().int().min(1).max(128),
      blockIds: z.array(z.string()).min(1),
      maxResults: z.number().int().min(1).max(1024).optional().default(64),
      ...dimensionOpt,
    },
    annotations: READ,
  },

  // ===== world (write) =======================================================================
  {
    name: "set_block",
    method: "world.setBlock",
    title: "Set a block",
    description:
      "Place/replace the block at an exact position with the given block id (optionally with blockstate properties as a string like 'minecraft:oak_log[axis=y]'). Requires a server (integrated client or dedicated).",
    inputSchema: { ...vec3(), blockId: z.string().describe('Block id, optionally with state, e.g. "minecraft:stone" or "minecraft:oak_stairs[facing=east]".'), ...dimensionOpt },
    annotations: WRITE,
  },
  {
    name: "fill_blocks",
    method: "world.fill",
    title: "Fill a region with a block",
    description:
      "Fill the cuboid between two corners with one block id. Volume is capped for safety. Requires a server.",
    inputSchema: {
      from: z.object(vec3()),
      to: z.object(vec3()),
      blockId: z.string().describe("Block id to fill with."),
      ...dimensionOpt,
    },
    annotations: WRITE,
  },
  {
    name: "set_time",
    method: "world.setTime",
    title: "Set time of day",
    description: "Set the day-time (0-24000; 0=dawn, 6000=noon, 12000=dusk, 18000=midnight). Requires a server.",
    inputSchema: { time: z.number().int().min(0).max(24000).describe("Day-time in ticks (0-24000).") },
    annotations: WRITE,
  },
  {
    name: "set_weather",
    method: "world.setWeather",
    title: "Set weather",
    description: "Set the weather. Requires a server.",
    inputSchema: {
      weather: z.enum(["clear", "rain", "thunder"]).describe("Target weather."),
      durationSeconds: z.number().int().min(1).optional().describe("How long the weather should last."),
    },
    annotations: WRITE,
  },

  // ===== entities ============================================================================
  {
    name: "query_entities",
    method: "entities.query",
    title: "Query entities",
    description:
      "List entities, optionally filtered by a sphere (center+radius), entity type ids, living-only, and whether to include players. Returns position, type, name, health and key flags for each.",
    inputSchema: {
      center: z.object(vec3()).optional().describe("Center of the search sphere; omit to use the player's position."),
      radius: z.number().min(1).max(256).optional().default(32).describe("Search radius in blocks."),
      types: z.array(z.string()).optional().describe('Entity type ids to match, e.g. ["minecraft:zombie","minecraft:cow"].'),
      includePlayers: z.boolean().optional().default(true),
      onlyLiving: z.boolean().optional().default(false),
      maxResults: z.number().int().min(1).max(1000).optional().default(100),
      ...dimensionOpt,
    },
    annotations: READ,
  },
  {
    name: "query_client_entities",
    method: "clientEntities.query",
    title: "Query client-tracked entities",
    description:
      "Client-only and read-only. Query entities currently tracked by the client, including dropped item stack ids/counts on remote multiplayer servers. Results report source=client_cache and are limited to the tracking range.",
    inputSchema: {
      center: z.object(vec3()).optional().describe("Center; defaults to the local player."),
      radius: z.number().min(1).max(256).optional().default(32),
      types: z.array(z.string()).optional(),
      includePlayers: z.boolean().optional().default(true),
      onlyLiving: z.boolean().optional().default(false),
      maxResults: z.number().int().min(1).max(1000).optional().default(100),
      ...dimensionOpt,
    },
    annotations: READ,
  },
  {
    name: "get_entity",
    method: "entities.get",
    title: "Get entity details",
    description: "Get detailed info about a single entity by UUID: type, position, velocity, health, equipment, NBT-derived attributes.",
    inputSchema: { uuid: z.string().describe("Entity UUID.") },
    annotations: READ,
  },
  {
    name: "summon_entity",
    method: "entities.summon",
    title: "Summon entity",
    description: "Summon an entity of the given type at a position, optionally with SNBT data. Requires a server.",
    inputSchema: {
      type: z.string().describe('Entity type id, e.g. "minecraft:armor_stand".'),
      ...vec3(),
      nbt: z.string().optional().describe("Optional SNBT, e.g. '{NoGravity:1b}'."),
      ...dimensionOpt,
    },
    annotations: WRITE,
  },
  {
    name: "remove_entity",
    method: "entities.remove",
    title: "Remove entity",
    description: "Discard (remove) a non-player entity by UUID. Requires a server.",
    inputSchema: { uuid: z.string().describe("Entity UUID to remove.") },
    annotations: WRITE,
  },

  // ===== players (admin, server-side) ========================================================
  {
    name: "list_players",
    method: "players.list",
    title: "List online players",
    description: "List all online players with name, UUID, position, dimension, health, food, game mode and ping. Requires a server.",
    inputSchema: {},
    annotations: READ,
  },
  {
    name: "get_player",
    method: "players.get",
    title: "Get player details",
    description: "Get detailed state for one online player. Requires a server.",
    inputSchema: { ...playerRef },
    annotations: READ,
  },
  {
    name: "teleport_player",
    method: "players.teleport",
    title: "Teleport player",
    description: "Teleport a player to coordinates (and optionally another dimension / facing). Requires a server.",
    inputSchema: { ...playerRef, ...vec3(), yaw: z.number().optional(), pitch: z.number().optional(), ...dimensionOpt },
    annotations: WRITE,
  },
  {
    name: "set_gamemode",
    method: "players.setGameMode",
    title: "Set player game mode",
    description: "Set a player's game mode. Requires a server.",
    inputSchema: { ...playerRef, mode: z.enum(["survival", "creative", "adventure", "spectator"]) },
    annotations: WRITE,
  },
  {
    name: "give_item",
    method: "players.give",
    title: "Give item to player",
    description: "Give an item stack to a player. Requires a server.",
    inputSchema: {
      ...playerRef,
      itemId: z.string().describe('Item id, e.g. "minecraft:diamond".'),
      count: z.number().int().min(1).max(6400).optional().default(1),
      nbt: z.string().optional().describe("Optional SNBT components."),
    },
    annotations: WRITE,
  },
  {
    name: "apply_effect",
    method: "players.applyEffect",
    title: "Apply status effect",
    description: "Apply a potion/status effect to a player. Requires a server.",
    inputSchema: {
      ...playerRef,
      effectId: z.string().describe('Effect id, e.g. "minecraft:speed".'),
      durationSeconds: z.number().int().min(1).optional().default(30),
      amplifier: z.number().int().min(0).max(255).optional().default(0),
      showParticles: z.boolean().optional().default(true),
    },
    annotations: WRITE,
  },
  {
    name: "message_player",
    method: "players.message",
    title: "Send system message",
    description: 'Send a system/chat message to a player ("@all" to broadcast). Requires a server.',
    inputSchema: { ...playerRef, text: z.string() },
  },
  {
    name: "kick_player",
    method: "players.kick",
    title: "Kick player",
    description: "Kick a player from the server. Requires a dedicated server.",
    inputSchema: { ...playerRef, reason: z.string().optional() },
    annotations: WRITE,
  },

  // ===== command =============================================================================
  {
    name: "run_command",
    method: "command.run",
    title: "Run a server command",
    description:
      "Execute an arbitrary Minecraft command at operator permission level 4 (do NOT include the leading slash) and capture its feedback output. This is extremely powerful (/setblock, /summon, /give, /tp, /gamerule, /execute, datapacks, ...). Requires a server.",
    inputSchema: { command: z.string().describe('Command without the leading slash, e.g. "time set day".') },
    annotations: { destructiveHint: true, openWorldHint: true },
  },

  // ===== chat ================================================================================
  {
    name: "send_chat",
    method: "chat.send",
    title: "Send chat message",
    description:
      "Send a chat message. On a client this is sent as the local player (a leading '/' runs a command as that player); on a dedicated server it is broadcast.",
    inputSchema: { message: z.string() },
  },
  {
    name: "get_recent_chat",
    method: "chat.getRecent",
    title: "Get recent chat",
    description: "Return recently observed chat & system messages (most recent last).",
    inputSchema: { limit: z.number().int().min(1).max(500).optional().default(50) },
    annotations: READ,
  },

  // ===== player (client local player) ========================================================
  {
    name: "get_self",
    method: "player.getState",
    title: "Get local player state",
    description:
      "Client-only. Full state of YOUR player: position, yaw/pitch, motion, health, food, saturation, air, XP, game mode, on-ground, in-fluid, selected hotbar slot, dimension.",
    inputSchema: {},
    annotations: READ,
  },
  {
    name: "get_inventory",
    method: "player.getInventory",
    title: "Get inventory",
    description: "Client-only. Full component-aware inventory from the local client cache. In single-player it also returns the integrated server's authoritative snapshot, an overall consistency flag, and exact mismatch slots so stale GUI copies and prediction can be detected.",
    inputSchema: {},
    annotations: READ,
  },
  {
    name: "reconcile_inventory",
    method: "player.reconcileInventory",
    title: "Reconcile inventory from integrated server",
    description:
      "Client-only single-player recovery. Replace stale local inventory prediction with one integrated-server authoritative snapshot, then request a complete menu broadcast and report exact before/after mismatches. Use only after get_inventory reports consistent=false; unavailable on remote multiplayer.",
    inputSchema: {
      requireScreenClosed: z.boolean().optional().default(true).describe("Refuse unless all GUIs are closed so no carried or screen-owned slot can be overwritten."),
    },
    annotations: WRITE,
  },
  {
    name: "get_equipment",
    method: "player.getEquipment",
    title: "Get equipment",
    description: "Client-only. Currently equipped items: main hand, off hand, helmet, chestplate, leggings, boots.",
    inputSchema: {},
    annotations: READ,
  },
  {
    name: "get_status_effects",
    method: "player.getStatusEffects",
    title: "Get active effects",
    description: "Client-only. Active status effects on your player with amplifier and remaining duration.",
    inputSchema: {},
    annotations: READ,
  },

  // ===== control (client) ====================================================================
  {
    name: "set_movement",
    method: "control.setInput",
    title: "Set movement input",
    description:
      "Client-only. Set held movement inputs as booleans; they persist until changed (like holding keys). Any omitted field is left unchanged. Combine with look/look_at to walk somewhere. Use stop_movement to release everything.",
    inputSchema: {
      forward: z.boolean().optional(),
      back: z.boolean().optional(),
      left: z.boolean().optional(),
      right: z.boolean().optional(),
      jump: z.boolean().optional(),
      sneak: z.boolean().optional(),
      sprint: z.boolean().optional(),
    },
  },
  {
    name: "stop_movement",
    method: "control.stop",
    title: "Release all movement",
    description: "Client-only. Release all movement inputs (stop walking/jumping/sneaking/sprinting).",
    inputSchema: {},
  },
  {
    name: "look",
    method: "control.look",
    title: "Set/adjust look angles",
    description:
      "Client-only. Set absolute yaw/pitch, or apply relative deltas. The rotation is queued to the multiplayer server before this command returns, so the next command in a batch observes it. Yaw: 0=south,-90=east,90=west,180=north. Pitch: -90=up, 90=down.",
    inputSchema: {
      yaw: z.number().optional().describe("Absolute yaw in degrees."),
      pitch: z.number().optional().describe("Absolute pitch in degrees (-90..90)."),
      deltaYaw: z.number().optional().describe("Relative yaw change in degrees."),
      deltaPitch: z.number().optional().describe("Relative pitch change in degrees."),
    },
    batchBarrierMs: 100,
  },
  {
    name: "look_at",
    method: "control.lookAt",
    title: "Look at a point",
    description: "Client-only. Rotate the player to face a world coordinate and queue that rotation to the multiplayer server before returning.",
    inputSchema: { ...vec3() },
    batchBarrierMs: 100,
  },
  {
    name: "jump",
    method: "control.jumpOnce",
    title: "Jump once",
    description: "Client-only. Perform a single jump.",
    inputSchema: {},
  },
  {
    name: "start_using_item",
    method: "control.startUsing",
    title: "Start using held item",
    description: "Client-only. Begin using/holding the right-click action of the held item (eat, draw bow, block with shield, etc.).",
    inputSchema: {},
  },
  {
    name: "stop_using_item",
    method: "control.stopUsing",
    title: "Stop using held item",
    description: "Client-only. Release the right-click use action.",
    inputSchema: {},
  },

  // ===== interact (client) ===================================================================
  {
    name: "break_block",
    method: "interact.breakBlock",
    title: "Break a block",
    description:
      "Client-only. Break the block at a position. mode 'instant' sends one creative-style break; 'survival' performs realistic timed mining and by default waits until the synchronized client block changes. A concurrent mining request explicitly interrupts the earlier confirmed call instead of allowing both to appear successful.",
    inputSchema: {
      ...vec3(),
      mode: z.enum(["instant", "survival"]).optional().default("survival"),
      confirm: z.boolean().optional().default(true).describe("For survival mining, wait for the synchronized target block to change before returning."),
      timeoutMs: z.number().int().min(100).max(60_000).optional().default(10_000),
    },
    annotations: WRITE,
  },
  {
    name: "place_block",
    method: "interact.placeBlock",
    title: "Place held block",
    description:
      "Client-only. Place the currently held block against the given position/face (must be reachable). The current rotation is queued immediately before placement; optionally provide yaw/pitch to set it atomically for orientation-sensitive blocks. Equip the desired block first with select_hotbar_slot.",
    inputSchema: {
      ...vec3(),
      face: z.enum(["up", "down", "north", "south", "east", "west"]).optional().default("up"),
      yaw: z.number().optional().describe("Optional absolute player yaw to publish immediately before placement."),
      pitch: z.number().min(-90).max(90).optional().describe("Optional absolute player pitch to publish immediately before placement."),
    },
    batchBarrierMs: 100,
    annotations: WRITE,
  },
  {
    name: "place_block_at",
    method: "interact.placeBlockAt",
    title: "Place a block at an exact target",
    description:
      "Client-only. State-aware placement at the destination coordinate. Selects itemId from inventory slots 0-35, can atomically sneak against interactive supports, searches pose/support candidates for expectedProperties, rejects out-of-reach supports, places, then confirms authoritative integrated-server state or stable remote client state. supportDirection points from target toward the clicked support (for example 'down' means support below).",
    inputSchema: {
      ...vec3(),
      itemId: z.string().optional().describe("Optional item id to select from hotbar or temporarily swap from main inventory, with or without the minecraft: namespace."),
      supportDirection: z
        .enum(["up", "down", "north", "south", "east", "west"])
        .optional()
        .describe("Direction from target to support. Omit to choose a loaded non-replaceable neighbor automatically."),
      yaw: z.number().optional().describe("Optional absolute player yaw to publish immediately before placement."),
      pitch: z.number().min(-90).max(90).optional().describe("Optional absolute player pitch to publish immediately before placement."),
      sneak: z.boolean().optional().describe("Temporarily use sneaking for this placement, then restore the previous state. Use true when placing against an interactive block."),
      allowBlockTransformation: z.boolean().optional().default(false).describe("Accept a different non-air final block id when a mod transforms the placed block during multiblock formation; requested properties must still match."),
      expectedProperties: z
        .record(z.union([z.string(), z.number(), z.boolean()]))
        .optional()
        .describe('Requested block-state subset such as {"facing":"east"}. When pose is omitted, the client searches cardinal yaw/pitch/support candidates before acting.'),
      confirm: z.boolean().optional().default(true).describe("Wait for the placed block to match the predicted id and expectedProperties."),
      confirmTimeoutMs: z.number().int().min(100).max(10000).optional().default(2000),
      restoreSelectedSlot: z.boolean().optional().default(false).describe("Restore the originally selected hotbar slot after confirmation. Main-inventory swaps are always restored."),
    },
    batchBarrierMs: 100,
    annotations: WRITE,
  },
  {
    name: "build_structure",
    method: "interact.buildStructure",
    title: "Build an ordered precise structure",
    description:
      "Client-only. Preflight and build up to 64 exact placements in dependency order. Checks loaded/replaceable targets, earlier planned supports, interaction reach, duplicate targets, and inventory material counts; each block is confirmed, then an authoritative postflight verifies that later neighbor updates did not invalidate earlier placements. Stops at the exact failed index by default and never retries a mutation blindly.",
    inputSchema: {
      placements: z
        .array(
          z.object({
            ...vec3(),
            itemId: z.string(),
            supportDirection: z.enum(["up", "down", "north", "south", "east", "west"]).optional(),
            yaw: z.number().optional(),
            pitch: z.number().min(-90).max(90).optional(),
            sneak: z.boolean().optional(),
            allowBlockTransformation: z.boolean().optional(),
            expectedProperties: z.record(z.union([z.string(), z.number(), z.boolean()])).optional(),
            confirm: z.boolean().optional(),
            confirmTimeoutMs: z.number().int().min(100).max(10000).optional(),
            restoreSelectedSlot: z.boolean().optional(),
          }),
        )
        .min(1)
        .max(64),
      preflightOnly: z.boolean().optional().default(false),
      stopOnError: z.boolean().optional().default(true),
      confirmTimeoutMs: z.number().int().min(100).max(10000).optional().default(2000),
      restoreSelectedSlot: z.boolean().optional().default(true),
    },
    annotations: WRITE,
  },
  {
    name: "inspect_structure",
    method: "interact.inspectStructure",
    title: "Inspect a structure blueprint",
    description:
      "Client-only and read-only. Compare an exact block/state blueprint with authoritative integrated-server state or the synchronized remote client cache. Reports missing, unexpected, wrong-block, and wrong-state differences. Optionally treats every unspecified coordinate inside bounded volume as expected air, and suggests nearby stand positions for currently unreachable differences.",
    inputSchema: {
      blocks: z
        .array(
          z.object({
            ...vec3(),
            blockId: z.string().describe('Expected block id, including "minecraft:air" for explicit removals.'),
            itemId: z.string().optional().describe("Optional placement item when it differs from blockId."),
            supportDirection: z.enum(["up", "down", "north", "south", "east", "west"]).optional(),
            expectedProperties: z.record(z.union([z.string(), z.number(), z.boolean()])).optional(),
          }),
        )
        .min(1)
        .max(4096),
      bounds: z
        .object({ from: z.object(vec3()), to: z.object(vec3()) })
        .optional()
        .describe("Required with unspecifiedAsAir=true; inclusive scan bounds capped at 4096 blocks."),
      unspecifiedAsAir: z.boolean().optional().default(false).describe("Treat every coordinate inside bounds not listed in blocks as expected air."),
      includeMatches: z.boolean().optional().default(false).describe("Include matching coordinates as well as differences."),
    },
    annotations: READ,
  },
  {
    name: "edit_structure",
    method: "interact.editStructure",
    title: "Incrementally edit a structure",
    description:
      "Client-only. Inspect an exact blueprint, preflight only its differences, verify each target has not changed since inspection, then incrementally place/remove/replace up to 64 differences. Destruction requires allowBreak=true and currently requires creative mode; block entities are refused. rollbackOnFailure snapshots exact integrated-server block states and restores them if any action or final postflight fails. Unreachable edits are reported with suggested stand positions before mutation.",
    inputSchema: {
      blocks: z
        .array(
          z.object({
            ...vec3(),
            blockId: z.string().describe('Expected final block id, including "minecraft:air" for removal.'),
            itemId: z.string().optional().describe("Optional placement item when it differs from blockId."),
            supportDirection: z.enum(["up", "down", "north", "south", "east", "west"]).optional(),
            expectedProperties: z.record(z.union([z.string(), z.number(), z.boolean()])).optional(),
          }),
        )
        .min(1)
        .max(4096),
      bounds: z.object({ from: z.object(vec3()), to: z.object(vec3()) }).optional(),
      unspecifiedAsAir: z.boolean().optional().default(false),
      includeMatches: z.boolean().optional().default(false),
      preflightOnly: z.boolean().optional().default(false),
      allowBreak: z.boolean().optional().default(false),
      rollbackOnFailure: z.boolean().optional().default(true),
      confirmTimeoutMs: z.number().int().min(100).max(10000).optional().default(2000),
      restoreSelectedSlot: z.boolean().optional().default(true),
    },
    annotations: WRITE,
  },
  {
    name: "use_item",
    method: "interact.useItem",
    title: "Use item / right-click",
    description:
      "Client-only. Right-click with the main hand: interact with the block/entity under the crosshair, then try the held item when the target does not consume the action. Block FAIL stops item use. With no target, use the held item directly. Returns targetType, native result, and targetResult/itemUseFallback when a target is present.",
    inputSchema: {},
  },
  {
    name: "use_item_in_air",
    method: "interact.useItemInAir",
    title: "Use held item in air",
    description: "Client-only. Use the held item without interacting with the block/entity under the crosshair.",
    inputSchema: {},
  },
  {
    name: "use_block",
    method: "interact.useBlock",
    title: "Use an exact block",
    description:
      "Client-only. Right-click an exact reachable block face with the main hand. Prefer this deterministic command for containers, buttons, levers, doors, and other block interactions.",
    inputSchema: { ...vec3(), face: z.enum(["up", "down", "north", "south", "east", "west"]).optional().default("up") },
  },
  {
    name: "use_block_at",
    method: "interact.useBlockAt",
    title: "Use an exact point on a block face",
    description:
      "Client-only. Interact with a precise normalized point on an exact reachable block face, using ordinary main-then-offhand fallback by default or one explicitly chosen hand, plus an atomic temporary sneak state and server-settled yaw/pitch. Returns every attempted hand and the final native result. Face-axis coordinates are pinned to the selected surface; the other two coordinates address sub-controls, ports, covers, and side configuration on vanilla or mod blocks.",
    inputSchema: {
      ...vec3(),
      face: z.enum(["up", "down", "north", "south", "east", "west"]),
      hitX: z.number().min(0).max(1).optional().default(0.5).describe("Normalized local X within the block; ignored on east/west faces."),
      hitY: z.number().min(0).max(1).optional().default(0.5).describe("Normalized local Y within the block; ignored on up/down faces."),
      hitZ: z.number().min(0).max(1).optional().default(0.5).describe("Normalized local Z within the block; ignored on north/south faces."),
      hand: z.enum(["auto", "main", "off"]).optional().default("auto").describe("auto tries main hand first and only falls back to offhand when the native result is Pass."),
      sneak: z.boolean().optional().describe("Temporarily use this sneak state for only the interaction, then restore the prior state."),
      yaw: z.number().optional().describe("Optional absolute yaw published and server-confirmed before interaction."),
      pitch: z.number().min(-90).max(90).optional().describe("Optional absolute pitch published and server-confirmed before interaction."),
      inside: z.boolean().optional().default(false).describe("Mark the native hit as originating inside the block."),
      requireReach: z.boolean().optional().default(true),
    },
    batchBarrierMs: 100,
    annotations: WRITE,
  },
  {
    name: "attack_entity",
    method: "interact.attackEntity",
    title: "Attack entity",
    description: "Client-only. Attack (left-click) an entity by UUID. Must be in reach.",
    inputSchema: { uuid: z.string() },
    annotations: WRITE,
  },
  {
    name: "use_entity",
    method: "interact.useEntity",
    title: "Interact with entity",
    description: "Client-only. Right-click/interact with an entity by UUID (e.g. trade with a villager, mount a horse).",
    inputSchema: { uuid: z.string() },
  },
  {
    name: "drop_held_item",
    method: "interact.dropItem",
    title: "Drop held item",
    description: "Client-only. Drop the held item (one, or the whole stack).",
    inputSchema: { wholeStack: z.boolean().optional().default(false) },
  },

  // ===== inventory (client) ==================================================================
  {
    name: "select_hotbar_slot",
    method: "inventory.selectHotbar",
    title: "Select hotbar slot",
    description:
      "Client-only. Select a hotbar slot (0-8), publish it once, and wait for stable integrated-server confirmation before returning. Remote multiplayer reports client-cache application because authoritative server inventory is unavailable; a confirmation timeout explicitly reports that the packet was already sent and must not be retried blindly.",
    inputSchema: {
      slot: z.number().int().min(0).max(8),
      timeoutMs: z.number().int().min(100).max(10_000).optional().default(2_000),
      stableReads: z.number().int().min(1).max(10).optional().default(2),
    },
  },
  {
    name: "drop_slot",
    method: "inventory.dropSlot",
    title: "Drop a specific slot",
    description: "Client-only. Drop the contents of a specific inventory slot.",
    inputSchema: { slot: z.number().int().min(0).max(40).describe("Inventory slot index (0-8 hotbar, 9-35 main, 36-39 armor, 40 offhand)."), wholeStack: z.boolean().optional().default(true) },
  },
  {
    name: "swap_slots",
    method: "inventory.swapSlots",
    title: "Swap two inventory slots",
    description: "Client-only. Swap the items in two inventory slots via container clicks (player inventory must be the active screen-less context).",
    inputSchema: { slotA: z.number().int().min(0).max(45), slotB: z.number().int().min(0).max(45) },
  },

  // ===== exact key bindings (client, including mod keys) =====================================
  {
    name: "list_key_bindings",
    method: "input.listKeyBindings",
    title: "List every registered key binding",
    description:
      "Client-only. List exact translation-key names, bound physical keys, categories, and held state for every vanilla and mod-provided key binding. Use the exact name with key_action.",
    inputSchema: {},
    annotations: READ,
  },
  {
    name: "key_action",
    method: "input.keyAction",
    title: "Operate any exact key binding",
    description:
      "Client-only. Click, press/hold, or release any vanilla or mod key binding by its exact name from list_key_bindings. A held binding remains down until released.",
    inputSchema: {
      name: z.string().min(1).describe('Exact translation-key name, e.g. "key.inventory".'),
      action: z.enum(["click", "press", "release"]).optional().default("click"),
    },
    annotations: WRITE,
  },

  // ===== generic screens and containers (client) =============================================
  {
    name: "get_screen_state",
    method: "screen.getState",
    title: "Inspect the current GUI and container",
    description:
      "Client-only. Return the open screen class/title, a recursively flattened widget tree with stable paths, a canonical state fingerprint, and the complete self-describing container including menu type, synchronized integer data, GUI origin, absolute slot hitboxes, slot implementations, semantic player-inventory roles, carried stack, component-aware items, and exact menu-slot mapping. Works generically for vanilla and mod screens.",
    inputSchema: {
      includeEmptySlots: z.boolean().optional().default(true).describe("Include empty slots so their menu indexes remain discoverable."),
    },
    annotations: READ,
  },
  {
    name: "find_screen_widgets",
    method: "screen.findWidgets",
    title: "Find GUI widgets semantically",
    description:
      "Client-only and read-only. Filter the recursively flattened current GUI widget tree by class, visible message, EditBox value, focus, active state, or visibility. Returns exact stable paths and hitboxes. With no selector, returns every widget.",
    inputSchema: { ...widgetSelector },
    annotations: READ,
  },
  {
    name: "wait_screen",
    method: "screen.waitState",
    title: "Wait for a stable GUI state",
    description:
      "Client-only. Wait until the screen matches optional identity/title/menu plus generic slot/data conditions and remains stable at the requested full, slot, or identity scope. Use the narrower scopes for actively ticking vanilla or mod machines.",
    inputSchema: {
      open: z.boolean().optional(),
      screenClassContains: z.string().optional(),
      titleContains: z.string().optional(),
      containerClassContains: z.string().optional(),
      menuType: z.string().optional(),
      containerConditions: z.array(containerCondition).optional().default([]).describe("Slot or synchronized-data conditions that must remain satisfied."),
      differentFromFingerprint: z.string().optional().describe("Require a state fingerprint different from this earlier snapshot."),
      stabilityScope: z
        .enum(["full", "slots", "identity"])
        .optional()
        .default("full")
        .describe("What must remain unchanged across stable reads; use slots or identity for actively ticking machines."),
      timeoutMs: z.number().int().min(100).max(30_000).optional().default(3_000),
      stableReads: z.number().int().min(1).max(10).optional().default(2),
    },
    annotations: READ,
  },
  {
    name: "probe_container_slot",
    method: "screen.probeSlot",
    title: "Probe a container slot capability",
    description:
      "Client-only and read-only. Ask the active slot implementation whether it accepts a sample item, whether the player may take from it, and its item-specific stack limit. This calls the actual vanilla or mod slot logic instead of inferring from slot number or GUI class.",
    inputSchema: {
      slot: z.number().int().min(0),
      itemId: z.string().min(1),
      count: z.number().int().min(1).max(99).optional().default(1),
    },
    annotations: READ,
  },
  {
    name: "container_transaction",
    method: "screen.containerTransaction",
    title: "Run and confirm a container transaction",
    description:
      "Client-only. Guard an exact native container-input sequence with screen identity and slot preconditions, then require server-synchronized postconditions to remain satisfied across consecutive reads and return precise slot/carried/data deltas. Ticking machine data may continue changing. Refuses concurrent GUI changes and never guesses where to put a stranded carried item.",
    inputSchema: {
      steps: z.array(containerInputStep).min(1).max(64),
      preconditions: z.array(containerCondition).optional().default([]),
      postconditions: z.array(containerCondition).optional().default([]),
      expectedContainerId: z.number().int().optional(),
      expectedContainerClass: z.string().optional(),
      expectedMenuType: z.string().optional(),
      expectedFingerprint: z.string().optional(),
      requireScreenOpen: z.boolean().optional().default(true),
      requireContainerScreen: z.boolean().optional().default(true).describe("Refuse hidden InventoryMenu mutation behind a non-container mod GUI."),
      requireEmptyCarriedBefore: z.boolean().optional().default(true),
      requireEmptyCarriedAfter: z.boolean().optional().default(true),
      requireChange: z.boolean().optional().default(true),
      cleanupSlot: z.number().int().min(0).optional().describe("Explicit safe slot for cursor cleanup if confirmation fails; omitted means never guess."),
      timeoutMs: z.number().int().min(100).max(10_000).optional().default(2_000),
      stableReads: z.number().int().min(1).max(10).optional().default(3),
    },
    annotations: WRITE,
  },
  {
    name: "move_container_item",
    method: "screen.moveItem",
    title: "Move an exact item stack between slots",
    description:
      "Client-only. Plan, execute, and confirm a component-aware move between two exact menu slots. It validates pickup/placement rules and capacity, safely relocates a partial remainder when an output-only source refuses placement, permits the target machine to consume the item immediately, and returns compact synchronized deltas.",
    inputSchema: {
      sourceSlot: z.number().int().min(0),
      targetSlot: z.number().int().min(0),
      count: z.number().int().min(1).optional().describe("Items to move; defaults to the entire source stack."),
      expectedContainerId: z.number().int().optional(),
      expectedContainerClass: z.string().optional(),
      expectedMenuType: z.string().optional(),
      expectedFingerprint: z.string().optional(),
      requireScreenOpen: z.boolean().optional().default(true),
      requireContainerScreen: z.boolean().optional().default(true).describe("Refuse hidden InventoryMenu mutation behind a non-container mod GUI."),
      timeoutMs: z.number().int().min(100).max(10_000).optional().default(2_000),
      stableReads: z.number().int().min(1).max(10).optional().default(3),
    },
    annotations: WRITE,
  },
  {
    name: "transfer_container_items",
    method: "screen.transferItems",
    title: "Transfer matching items by goal",
    description:
      "Client-only. Discover compatible source and destination slots, preflight a bounded component-aware transfer plan, then execute each native move with server-synchronized confirmation and actual source-slot count accounting. Use player->menu to load machines or storage and menu->player to collect products without manually resolving menu-slot indexes. Exact partial moves are refused before mutation when a native output slot cannot accept its remainder. Equipment, armor, offhand, crafting, and output slots are protected by default; exact slot allowlists remain available for ambiguous mod GUIs.",
    inputSchema: {
      from: z.enum(["player", "menu"]).optional().default("player"),
      to: z.enum(["player", "menu"]).optional().default("menu"),
      itemId: z.string().min(1).optional().describe('Only transfer this item id; "minecraft:" is optional for vanilla items.'),
      componentFingerprint: z.string().regex(/^[0-9a-f]{64}$/).optional().describe("Optionally require an exact data-component identity."),
      count: z.number().int().min(1).max(6400).optional().describe("Exact total requested count; omitted transfers every matching item that fits within the operation limit."),
      requireExactCount: z.boolean().optional().default(true).describe("When count is set, refuse before mutation unless the entire count can be planned."),
      sourceSlots: z.array(z.number().int().min(0)).max(128).optional().describe("Optional exact source menu-slot allowlist."),
      targetSlots: z.array(z.number().int().min(0)).max(128).optional().describe("Optional exact destination menu-slot allowlist for ambiguous mod interfaces."),
      includeEquipment: z.boolean().optional().default(false).describe("Allow armor, offhand, and other special player inventory slots when their native slot accepts the item."),
      dryRun: z.boolean().optional().default(false).describe("Return the complete move plan without sending any input."),
      maxOperations: z.number().int().min(1).max(64).optional().default(32),
      expectedContainerId: z.number().int().optional(),
      expectedContainerClass: z.string().optional(),
      expectedMenuType: z.string().optional(),
      expectedFingerprint: z.string().optional(),
      requireScreenOpen: z.boolean().optional().default(true),
      requireContainerScreen: z.boolean().optional().default(true).describe("Refuse hidden InventoryMenu mutation behind a non-container mod GUI."),
      timeoutMs: z.number().int().min(100).max(10_000).optional().default(2_000),
      stableReads: z.number().int().min(1).max(10).optional().default(2),
    },
    annotations: WRITE,
  },
  {
    name: "container_click",
    method: "screen.containerClick",
    title: "Send an exact container slot input",
    description:
      "Client-only. Operate any slot in the currently active container using Minecraft's native container protocol. Supports pickup, shift-click, hotbar swap, creative clone, throw, quick-craft drag sequences, and pickup-all.",
    inputSchema: {
      slot: z.number().int().min(-999).describe("Exact menuSlot from get_screen_state; -999 means outside the container."),
      button: z.number().int().optional().default(0).describe("Native button value; meaning depends on input type."),
      input: z.enum(["PICKUP", "QUICK_MOVE", "SWAP", "CLONE", "THROW", "QUICK_CRAFT", "PICKUP_ALL"]).optional().default("PICKUP"),
    },
    annotations: WRITE,
  },
  {
    name: "container_button",
    method: "screen.containerButton",
    title: "Press a native container button",
    description:
      "Client-only. Send an exact native button id to the active container, covering interfaces such as enchanting, beacon, loom, stonecutter, and trading when they use menu buttons.",
    inputSchema: { button: z.number().int() },
    annotations: WRITE,
  },
  {
    name: "set_screen_text",
    method: "screen.setText",
    title: "Set an exact text widget",
    description: "Client-only. Replace or append text in an EditBox selected by recursive widgetPath, with legacy top-level widgetIndex support.",
    inputSchema: {
      ...widgetSelector,
      value: z.string(),
      append: z.boolean().optional().default(false),
    },
    annotations: WRITE,
  },
  {
    name: "type_screen_text",
    method: "screen.typeText",
    title: "Type text into the focused screen control",
    description:
      "Client-only. Dispatch Unicode character events to the current screen. Use for signs, books, command blocks, mod widgets, and other custom fields that are not exposed as ordinary EditBox widgets.",
    inputSchema: { text: z.string(), modifiers: z.number().int().optional().default(0) },
    annotations: WRITE,
  },
  {
    name: "screen_key",
    method: "screen.key",
    title: "Send a raw key event to the current screen",
    description:
      "Client-only. Press or release an exact GLFW key/scancode/modifier event on the current GUI (for example Enter=257, Escape=256, Tab=258). This is a low-level escape hatch for custom screens.",
    inputSchema: {
      key: z.number().int(),
      scanCode: z.number().int().optional().default(0),
      modifiers: z.number().int().optional().default(0),
      action: z.enum(["press", "release"]).optional().default("press"),
    },
    annotations: WRITE,
  },
  {
    name: "screen_mouse",
    method: "screen.mouse",
    title: "Send a raw mouse action to the current screen",
    description:
      "Client-only. Click, double-click, press, release, drag, hover-move, or scroll at exact GUI coordinates using native screen events. Use after get_screen_state or a screenshot establishes the target and dimensions.",
    inputSchema: {
      x: z.number(),
      y: z.number(),
      button: z.number().int().optional().default(0),
      modifiers: z.number().int().optional().default(0),
      action: z.enum(["click", "press", "release", "drag", "move", "scroll"]).optional().default("click"),
      dragX: z.number().optional().default(0),
      dragY: z.number().optional().default(0),
      scrollX: z.number().optional().default(0),
      scrollY: z.number().optional().describe("Required vertical wheel delta when action=scroll; positive scrolls up and negative scrolls down."),
      doubleClick: z.boolean().optional().default(false).describe("Mark click/press as a native double click."),
    },
    annotations: WRITE,
  },
  {
    name: "click_screen_widget",
    method: "screen.clickWidget",
    title: "Click an exact screen widget",
    description:
      "Client-only. Resolve a rectangular widget by exact path or semantic class/message/value filters, refuse ambiguous matches unless occurrence is explicit, click an exact relative point, and return before/after screen identity. Prefer this over coordinate guessing for vanilla or mod controls.",
    inputSchema: {
      ...widgetSelector,
      button: z.number().int().optional().default(0),
      doubleClick: z.boolean().optional().default(false),
      relativeX: z.number().min(0).max(1).optional().default(0.5),
      relativeY: z.number().min(0).max(1).optional().default(0.5),
      requireActive: z.boolean().optional().default(true),
      requireVisible: z.boolean().optional().default(true),
    },
    annotations: WRITE,
  },
  {
    name: "close_screen",
    method: "screen.close",
    title: "Close the current screen",
    description: "Client-only. Close the current GUI through its normal onClose path, including container close synchronization.",
    inputSchema: {},
    annotations: WRITE,
  },

  // ===== unrestricted Java scratch (client, authenticated) ====================================
  {
    name: "java_scratch",
    method: "unsafe.javaScratch",
    title: "Execute arbitrary Java inside Minecraft",
    description:
      "UNSAFE, client-only, and authenticated. Runtime-compile and execute an arbitrary Java method body inside the Minecraft JVM. The body receives ctx with minecraft/player/level/server object handles, RPC dispatch, reflection helpers, class inspection, and logging. It also has normal Java access to files, network, processes, and system APIs. Return an object. mainThread=true is required for Minecraft object mutation but a blocking/infinite body can freeze the game. Every run writes a hash-only audit record.",
    inputSchema: {
      body: z.string().min(1).describe('Java method body, e.g. `return ctx.rpc("player.getState", "{}");`.'),
      imports: z.array(z.string()).optional().default([]).describe('Optional imports without the `import` keyword, e.g. ["java.nio.file.Files"].'),
      mainThread: z.boolean().optional().default(true).describe("Run on the Minecraft render thread for safe game-object access; false runs on the bridge worker."),
    },
    annotations: { destructiveHint: true, openWorldHint: true },
  },

  // ===== vision (client) =====================================================================
  {
    name: "screenshot",
    method: "vision.screenshot",
    title: "Capture screenshot",
    description:
      "Client-only. Capture the current game framebuffer as a PNG image (at the game's current resolution) so a vision-capable model can literally see what the player sees.",
    inputSchema: {},
    annotations: READ,
    kind: "image",
  },
  {
    name: "describe_scene",
    method: "vision.describeScene",
    title: "Describe visible scene",
    description:
      "Client-only. Produce a structured, text description of what is visible: the block/entity directly under the crosshair, a grid of raycasts across the field of view, and nearby visible entities. A cheap alternative to a screenshot for non-vision models.",
    inputSchema: {
      maxDistance: z.number().min(1).max(128).optional().default(48),
      rayColumns: z.number().int().min(1).max(33).optional().default(9),
      rayRows: z.number().int().min(1).max(33).optional().default(5),
    },
    annotations: READ,
  },

  // ===== navigation (client, pluggable backend) ==============================================
  {
    name: "preview_navigation",
    method: "nav.preview",
    title: "Preview a safe navigation path",
    description:
      "Client-only and read-only. Plan the first rolling segment without moving. Returns bounded exact nodes annotated with exposed-edge, gap-jump, and closed door/gate actions plus aggregate counts and whether more rolling segments will be needed. Use before hazardous or unfamiliar vanilla/mod terrain.",
    inputSchema: {
      ...vec3(),
      reachRadius: z.number().min(0).max(16).optional().default(1),
      ...rollingPlanOptions(),
      maxNodesOutput: z.number().int().min(1).max(256).optional().default(128),
    },
    annotations: READ,
  },
  {
    name: "navigate_to",
    method: "nav.pathTo",
    title: "Navigate to a position",
    description:
      "Client-only. Asynchronously walk to a target through auto, builtin, or optional Baritone. Auto uses compatible Baritone by default and falls back to the precise built-in planner only when needed. Baritone can optionally break/place route obstacles and use native 2-3 block parkour when explicitly enabled by arguments. Returns immediately; poll navigation_status for selected backend and progress.",
    inputSchema: {
      ...vec3(),
      reachRadius: z.number().min(0).max(16).optional().default(1).describe("Stop when within this many blocks of the target."),
      sprint: z.boolean().optional().default(false),
      backend: z.enum(["auto", "builtin", "baritone"]).optional().default("auto")
        .describe("auto uses compatible Baritone by default and falls back to builtin only when unavailable, incompatible, or exact custom avoidance requires it; explicit baritone refuses instead of silently falling back."),
      baritoneAllowParkour: z.boolean().optional()
        .describe("Enable or disable native Baritone parkour, without a hard gap-distance limit. Overrides the legacy maxGapJumpBlocks on/off hint for Baritone only; omitted defaults to that hint. Set sprint=true to permit sprint-assisted jumps."),
      baritoneAllowBreak: z.boolean().optional().default(false)
        .describe("Allow Baritone to break route obstacles. This may modify the world and only applies when Baritone is selected."),
      baritoneAllowPlace: z.boolean().optional().default(false)
        .describe("Allow Baritone to place blocks for ordinary traversal. This may modify the world and only applies when Baritone is selected."),
      baritoneAllowInventory: z.boolean().optional().default(false)
        .describe("Allow Baritone to move inventory items into the hotbar for tools or traversal blocks."),
      baritoneAllowParkourPlace: z.boolean().optional().default(false)
        .describe("Allow Baritone to place a landing block during parkour; requires baritoneAllowPlace=true."),
      ...rollingPlanOptions(),
      segmentWaitSeconds: z.number().int().min(1).max(60).optional().default(10)
        .describe("How long to wait and retry at a rolling boundary when the next chunk or route is not ready before reporting segment_unreachable."),
      timeoutSeconds: z.number().int().min(1).max(600).optional().default(60),
    },
  },
  {
    name: "navigation_backends",
    method: "nav.backends",
    title: "List navigation backends",
    description:
      "Client-only and read-only. Report the built-in planner and optional Baritone adapter, live availability, current selection, and major capability differences.",
    inputSchema: {},
    annotations: READ,
  },
  {
    name: "navigation_status",
    method: "nav.status",
    title: "Navigation status",
    description: "Client-only. Report the requested and selected backend plus backend-specific progress, safety state, distance, and fallback reason.",
    inputSchema: {},
    annotations: READ,
  },
  {
    name: "stop_navigation",
    method: "nav.stop",
    title: "Stop navigation",
    description: "Client-only. Cancel any active navigation and release movement.",
    inputSchema: {},
  },

  // ===== events ==============================================================================
  {
    name: "poll_events",
    method: "events.getRecent",
    title: "Poll recent game events",
    description:
      "Return recently observed game events from the in-mod ring buffer (damage taken, entity spawn/death, block break/place, chat, dimension change, etc.). Filter by type and/or pass sinceId to get only events newer than one you've already seen.",
    inputSchema: {
      limit: z.number().int().min(1).max(500).optional().default(50),
      types: z.array(z.string()).optional().describe('Event type filter, e.g. ["chat","player_damage","entity_death"].'),
      sinceId: z.number().int().min(0).optional().describe("Only return events with id greater than this."),
    },
    annotations: READ,
  },
];
