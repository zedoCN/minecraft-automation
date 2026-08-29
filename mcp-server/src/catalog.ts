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
}

export interface CommandDescriptor extends CommandSummary {
  inputSchema: Record<string, unknown>;
  schemaHash: string;
  delivery: "synchronous" | "starts_async_operation";
  postcondition: "bridge_result" | "poll_navigation_status";
  batchSupport: boolean;
  target: "local_minecraft_bridge";
}

export function commandCategory(tool: ToolDef): string {
  return tool.method.split(".", 1)[0] ?? "other";
}

export function commandSummary(tool: ToolDef): CommandSummary {
  return {
    name: tool.name,
    category: commandCategory(tool),
    title: tool.title,
    description: tool.description,
    readOnly: tool.annotations?.readOnlyHint === true,
    destructive: tool.annotations?.destructiveHint === true,
    idempotent: tool.annotations?.idempotentHint ?? null,
    resultKind: tool.kind ?? "json",
  };
}

export function inputJsonSchema(tool: ToolDef): Record<string, unknown> {
  return zodToJsonSchema(z.object(tool.inputSchema), {
    $refStrategy: "none",
    target: "jsonSchema7",
  }) as Record<string, unknown>;
}

export function describeCommand(name: string): CommandDescriptor | undefined {
  const tool = TOOL_BY_NAME.get(name);
  if (!tool) return undefined;
  const inputSchema = inputJsonSchema(tool);
  const schemaHash = createHash("sha256").update(JSON.stringify(inputSchema)).digest("hex");
  const startsNavigation = tool.name === "navigate_to";
  return {
    ...commandSummary(tool),
    inputSchema,
    schemaHash,
    delivery: startsNavigation ? "starts_async_operation" : "synchronous",
    postcondition: startsNavigation ? "poll_navigation_status" : "bridge_result",
    batchSupport: tool.kind !== "image",
    target: "local_minecraft_bridge",
  };
}

export interface SearchCatalogOptions {
  category?: string;
  query?: string;
  offset?: number;
  limit?: number;
}

export function searchCatalog(options: SearchCatalogOptions = {}) {
  const category = options.category?.trim().toLowerCase();
  const query = options.query?.trim().toLowerCase();
  const offset = Math.max(0, options.offset ?? 0);
  const limit = Math.min(100, Math.max(1, options.limit ?? 25));
  const all = TOOLS.map(commandSummary).filter((command) => {
    if (category && command.category !== category) return false;
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

export function catalogOverview() {
  const counts: Record<string, number> = {};
  for (const tool of TOOLS) {
    const category = commandCategory(tool);
    counts[category] = (counts[category] ?? 0) + 1;
  }
  return {
    totalCommands: TOOLS.length,
    categories: Object.entries(counts)
      .sort(([a], [b]) => a.localeCompare(b))
      .map(([name, commandCount]) => ({ name, commandCount })),
  };
}
