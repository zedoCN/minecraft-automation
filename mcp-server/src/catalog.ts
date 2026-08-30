import { createHash } from "node:crypto";
import { z } from "zod";
import { zodToJsonSchema } from "zod-to-json-schema";

import { TOOLS, type ToolDef } from "./tools.js";

export const TOOL_BY_NAME = new Map(TOOLS.map((tool) => [tool.name, tool]));

if (TOOL_BY_NAME.size !== TOOLS.length) {
  throw new Error("Duplicate MCPFabric command name in TOOLS catalog.");
}

export interface CommandSummary {
  name: string;
  category: string;
  title: string;
  description: string;
  readOnly: boolean;
  destructive: boolean;
  idempotent: boolean | null;
  resultKind: "json" | "image";
  requires: string[];
  available?: boolean;
  unavailableReason?: string;
}

export interface CommandDescriptor extends CommandSummary {
  inputSchema: Record<string, unknown>;
  schemaHash: string;
  delivery: "synchronous" | "starts_async_operation";
  postcondition: "bridge_result" | "poll_navigation_status";
  batchSupport: boolean;
  batchBarrierMs: number;
  target: "local_minecraft_bridge";
}

export function commandCategory(tool: ToolDef): string {
  return (tool.method.split(".", 1)[0] ?? "other").toLowerCase();
}

export interface AvailabilityContext {
  capabilities: ReadonlySet<string>;
  clientPlayerPresent?: boolean;
  clientLevelPresent?: boolean;
}

const WORLD_WRITES = new Set(["world.setBlock", "world.fill", "world.setTime", "world.setWeather"]);
const ENTITY_WRITES = new Set(["entities.summon", "entities.remove"]);

export function requiredCapabilities(tool: ToolDef): string[] {
  const category = commandCategory(tool);
  if (category === "world") return [WORLD_WRITES.has(tool.method) ? "world_write" : "world_read"];
  if (category === "entities") return ENTITY_WRITES.has(tool.method) ? ["entities", "world_write"] : ["entities"];
  if (category === "clientworld" || category === "cliententities" || category === "client") return ["player_local"];
  if (category === "players") return ["players_admin"];
  if (category === "player") return ["player_local"];
  if (category === "nav") return ["navigation"];
  if (category === "input") return ["key_bindings"];
  if (category === "unsafe") return ["unsafe_java"];
  return [category];
}

function availability(tool: ToolDef, context: AvailabilityContext | undefined) {
  if (!context) return {};
  const requires = requiredCapabilities(tool);
  const missing = requires.filter((capability) => !context.capabilities.has(capability));
  if (missing.length > 0) {
    return { available: false, unavailableReason: `Missing live capability: ${missing.join(", ")}.` };
  }
  const category = commandCategory(tool);
  const needsPlayer = ["player", "control", "interact", "inventory", "nav", "vision", "clientworld", "cliententities"].includes(category);
  if (needsPlayer && context.clientPlayerPresent === false) {
    return { available: false, unavailableReason: "No local client player is currently in a world." };
  }
  const needsLevel = category === "clientworld" || category === "cliententities";
  if (needsLevel && context.clientLevelPresent === false) {
    return { available: false, unavailableReason: "No client level is currently loaded." };
  }
  return { available: true };
}

export function commandSummary(tool: ToolDef, context?: AvailabilityContext): CommandSummary {
  return {
    name: tool.name,
    category: commandCategory(tool),
    title: tool.title,
    description: tool.description,
    readOnly: tool.annotations?.readOnlyHint === true,
    destructive: tool.annotations?.destructiveHint === true,
    idempotent: tool.annotations?.idempotentHint ?? null,
    resultKind: tool.kind ?? "json",
    requires: requiredCapabilities(tool),
    ...availability(tool, context),
  };
}

export function inputJsonSchema(tool: ToolDef): Record<string, unknown> {
  return zodToJsonSchema(z.object(tool.inputSchema), {
    $refStrategy: "none",
    target: "jsonSchema7",
  }) as Record<string, unknown>;
}

export function describeCommand(name: string, context?: AvailabilityContext): CommandDescriptor | undefined {
  const tool = TOOL_BY_NAME.get(name);
  if (!tool) return undefined;
  const inputSchema = inputJsonSchema(tool);
  const schemaHash = createHash("sha256").update(JSON.stringify(inputSchema)).digest("hex");
  const startsNavigation = tool.name === "navigate_to";
  return {
    ...commandSummary(tool, context),
    inputSchema,
    schemaHash,
    delivery: startsNavigation ? "starts_async_operation" : "synchronous",
    postcondition: startsNavigation ? "poll_navigation_status" : "bridge_result",
    batchSupport: tool.kind !== "image",
    batchBarrierMs: tool.batchBarrierMs ?? 0,
    target: "local_minecraft_bridge",
  };
}

export interface SearchCatalogOptions {
  category?: string;
  query?: string;
  offset?: number;
  limit?: number;
  availableOnly?: boolean;
}

export function searchCatalog(options: SearchCatalogOptions = {}, context?: AvailabilityContext) {
  const category = options.category?.trim().toLowerCase();
  const query = options.query?.trim().toLowerCase();
  const offset = Math.max(0, options.offset ?? 0);
  const limit = Math.min(100, Math.max(1, options.limit ?? 25));
  const all = TOOLS.map((tool) => commandSummary(tool, context)).filter((command) => {
    if (category && command.category !== category) return false;
    if (options.availableOnly && command.available === false) return false;
    if (!query) return true;
    return `${command.name}\n${command.title}\n${command.description}`.toLowerCase().includes(query);
  });
  const commands = all.slice(offset, offset + limit);
  return {
    commands,
    totalMatched: all.length,
    offset,
    limit,
    hasMore: offset + commands.length < all.length,
    nextOffset: offset + commands.length < all.length ? offset + commands.length : null,
  };
}

export function catalogOverview(context?: AvailabilityContext) {
  const counts: Record<string, number> = {};
  const availableCounts: Record<string, number> = {};
  for (const tool of TOOLS) {
    const category = commandCategory(tool);
    counts[category] = (counts[category] ?? 0) + 1;
    if (commandSummary(tool, context).available !== false) {
      availableCounts[category] = (availableCounts[category] ?? 0) + 1;
    }
  }
  return {
    totalCommands: TOOLS.length,
    categories: Object.entries(counts)
      .sort(([a], [b]) => a.localeCompare(b))
      .map(([name, commandCount]) => ({ name, commandCount, ...(context ? { availableCommandCount: availableCounts[name] ?? 0 } : {}) })),
  };
}
