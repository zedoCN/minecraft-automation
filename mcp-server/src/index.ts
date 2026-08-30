#!/usr/bin/env node
/**
 * mcpfabric MCP server entrypoint.
 *
 * Registers every tool from the catalogue (`tools.ts`) as a thin forwarder to the in-game HTTP
 * bridge, then serves them over stdio (default) or streamable HTTP. All diagnostic logging goes to
 * stderr so it never corrupts the stdio JSON-RPC stream.
 */
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js";
import type { CallToolResult } from "@modelcontextprotocol/sdk/types.js";
import http from "node:http";
import { randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";
import { z } from "zod";

import { loadConfig, type ServerConfig } from "./config.js";
import { BridgeClient, BridgeError, BridgeUnreachableError } from "./bridge.js";
import { TOOLS, type ToolDef } from "./tools.js";
import { TOOL_BY_NAME, catalogOverview, describeCommand, searchCatalog, type AvailabilityContext } from "./catalog.js";

const PKG_VERSION: string = JSON.parse(
  readFileSync(new URL("../package.json", import.meta.url), "utf8"),
).version;

class CommandInvocationError extends Error {
  constructor(
    readonly code: string,
    message: string,
    readonly data?: unknown,
  ) {
    super(message);
    this.name = "CommandInvocationError";
  }
}

function log(...args: unknown[]): void {
  // stderr only — stdout is reserved for the stdio transport.
  console.error("[mcpfabric]", ...args);
}

function errorResult(err: unknown, context: Record<string, unknown> = {}): CallToolResult {
  let code = "internal_error";
  let message: string;
  let data: unknown;
  if (err instanceof CommandInvocationError) {
    code = err.code;
    message = err.message;
    data = err.data;
  } else if (err instanceof z.ZodError) {
    code = "invalid_arguments";
    message = "Command arguments did not match the catalog schema.";
    data = err.issues;
  } else if (err instanceof BridgeUnreachableError) {
    code = "bridge_unreachable";
    message = err.message;
  } else if (err instanceof BridgeError) {
    code = err.code;
    message = err.message;
    data = err.data;
  } else if (err instanceof Error) {
    message = err.message;
  } else {
    message = String(err);
  }
  const payload = {
    ok: false,
    error: { code, message, ...(data === undefined ? {} : { data }) },
    ...context,
  };
  return {
    isError: true,
    content: [{ type: "text", text: JSON.stringify(payload, null, 2) }],
    structuredContent: payload,
  };
}

function jsonResult(result: unknown): CallToolResult {
  if (result === undefined || result === null) {
    return { content: [{ type: "text", text: "ok" }] };
  }
  const text = typeof result === "string" ? result : JSON.stringify(result, null, 2);
  const out: CallToolResult = { content: [{ type: "text", text }] };
  if (typeof result === "object") {
    out.structuredContent = result as Record<string, unknown>;
  }
  return out;
}

function commandResultSucceeded(result: unknown): boolean {
  if (!result || typeof result !== "object") return true;
  const envelope = result as { ok?: unknown; result?: unknown };
  if (envelope.ok === false) return false;
  if (!envelope.result || typeof envelope.result !== "object") return true;
  const commandResult = envelope.result as { completed?: unknown; dryRun?: unknown; ok?: unknown };
  if (commandResult.ok === false) return false;
  return commandResult.completed !== false || commandResult.dryRun === true;
}

interface ScreenshotResult {
  format?: string;
  base64: string;
  width?: number;
  height?: number;
}

function imageResult(result: unknown): CallToolResult {
  const r = result as ScreenshotResult;
  if (!r || typeof r.base64 !== "string") {
    return errorResult(new Error("Bridge did not return image data."));
  }
  const mimeType = r.format === "jpeg" || r.format === "jpg" ? "image/jpeg" : "image/png";
  const meta = `Screenshot ${r.width ?? "?"}x${r.height ?? "?"} (${mimeType})`;
  return {
    content: [
      { type: "image", data: r.base64, mimeType },
      { type: "text", text: meta },
    ],
  };
}

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

interface BridgeStatus extends Record<string, unknown> {
  side?: string;
  capabilities?: string[];
  methods?: string[];
}

interface ClientStatus extends Record<string, unknown> {
  clientPlayerPresent?: boolean;
  clientLevelPresent?: boolean;
}

async function readLiveContext(bridge: BridgeClient): Promise<{
  status: BridgeStatus;
  clientStatus?: ClientStatus;
  availability: AvailabilityContext;
}> {
  const status = await bridge.call<BridgeStatus>("info.status", {});
  let clientStatus: ClientStatus | undefined;
  if (status.side === "client" && status.methods?.includes("client.status")) {
    try {
      clientStatus = await bridge.call<ClientStatus>("client.status", {});
    } catch {
      // A menu/loading transition can temporarily make client status unavailable. Capability
      // filtering still remains useful from info.status alone.
    }
  }
  return {
    status,
    clientStatus,
    availability: {
      capabilities: new Set(status.capabilities ?? []),
      clientPlayerPresent: clientStatus?.clientPlayerPresent,
      clientLevelPresent: clientStatus?.clientLevelPresent,
    },
  };
}

async function optionalLiveContext(bridge: BridgeClient): Promise<AvailabilityContext | undefined> {
  try {
    return (await readLiveContext(bridge)).availability;
  } catch {
    return undefined;
  }
}

async function invokeCommand(
  bridge: BridgeClient,
  name: string,
  args: Record<string, unknown>,
): Promise<CallToolResult> {
  const requestId = randomUUID();
  const startedAt = new Date().toISOString();
  const started = performance.now();
  const def = TOOL_BY_NAME.get(name);
  if (!def) {
    return errorResult(new CommandInvocationError("unknown_command", `Unknown command "${name}". Use command_catalog to discover exact command names.`), {
      command: name,
      requestId,
    });
  }

  try {
    const parsedArgs = z.object(def.inputSchema).parse(args ?? {});
    const result = await bridge.call(def.method, parsedArgs);
    const durationMs = Math.round(performance.now() - started);
    if (def.kind === "image") {
      const rendered = imageResult(result);
      rendered.structuredContent = rendered.isError
        ? { ...(rendered.structuredContent ?? {}), command: name, requestId, startedAt, durationMs }
        : { ok: true, command: name, requestId, startedAt, durationMs };
      return rendered;
    }
    return jsonResult({ ok: true, command: name, requestId, startedAt, durationMs, result });
  } catch (err) {
    return errorResult(err, {
      command: name,
      requestId,
      startedAt,
      durationMs: Math.round(performance.now() - started),
    });
  }
}

function registerCatalogTools(server: McpServer, bridge: BridgeClient): void {
  server.registerTool(
    "minecraft_status",
    {
      title: "Minecraft bridge and catalog status",
      description:
        "Call first. Returns live Minecraft/Fabric bridge status plus command categories. This compact catalog grants the same capabilities as the raw tool surface without loading every command schema up front.",
      inputSchema: {},
      annotations: { readOnlyHint: true },
    },
    async () => {
      try {
        const live = await readLiveContext(bridge);
        return jsonResult({
          ok: true,
          status: { ...live.status, ...(live.clientStatus ?? {}) },
          catalog: catalogOverview(live.availability),
        });
      } catch (err) {
        return errorResult(err);
      }
    },
  );

  server.registerTool(
    "command_catalog",
    {
      title: "Search Minecraft command catalog",
      description:
        "Discover commands by category or text. This only filters the catalog; execution always requires an exact command name. Results are summaries without large input schemas.",
      inputSchema: {
        category: z.string().optional().describe('Exact category such as "player", "control", "interact", "world", or "command".'),
        query: z.string().optional().describe("Optional case-insensitive catalog filter over command name, title, and description."),
        availableOnly: z.boolean().optional().default(true).describe("Only return commands available in the live game context. Set false to inspect the complete static catalog."),
        offset: z.number().int().min(0).optional().default(0),
        limit: z.number().int().min(1).max(100).optional().default(25),
      },
      annotations: { readOnlyHint: true },
    },
    async (args) => {
      const availability = await optionalLiveContext(bridge);
      return jsonResult({ ok: true, liveAvailability: availability !== undefined, ...searchCatalog(args, availability) });
    },
  );

  server.registerTool(
    "command_describe",
    {
      title: "Describe one Minecraft command",
      description:
        "Return the exact JSON input schema, behavior metadata, schema hash, delivery mode, and postcondition for one exact catalog command name.",
      inputSchema: { name: z.string().min(1).describe("Exact name returned by command_catalog.") },
      annotations: { readOnlyHint: true },
    },
    async ({ name }) => {
      const descriptor = describeCommand(name, await optionalLiveContext(bridge));
      if (!descriptor) return errorResult(new CommandInvocationError("unknown_command", `Unknown command "${name}".`), { command: name });
      return jsonResult({ ok: true, command: descriptor });
    },
  );

  server.registerTool(
    "command_describe_many",
    {
      title: "Describe several Minecraft commands",
      description: "Return exact schemas and live availability for up to 32 exact command names in one round-trip.",
      inputSchema: { names: z.array(z.string().min(1)).min(1).max(32) },
      annotations: { readOnlyHint: true },
    },
    async ({ names }) => {
      const availability = await optionalLiveContext(bridge);
      const commands = names.map((name) => describeCommand(name, availability));
      const missing = names.filter((_, index) => commands[index] === undefined);
      if (missing.length > 0) {
        return errorResult(new CommandInvocationError("unknown_command", `Unknown command(s): ${missing.join(", ")}.`));
      }
      return jsonResult({ ok: true, commands });
    },
  );

  server.registerTool(
    "command_invoke",
    {
      title: "Invoke one Minecraft command",
      description:
        "Invoke one exact catalog command with structured arguments. Arguments are validated against that command's schema before the local game bridge is called. Mutations are never retried automatically.",
      inputSchema: {
        name: z.string().min(1).describe("Exact command name."),
        arguments: z.record(z.unknown()).optional().default({}).describe("Arguments matching command_describe.inputSchema."),
      },
    },
    async ({ name, arguments: args }) => invokeCommand(bridge, name, args),
  );

  server.registerTool(
    "command_batch",
    {
      title: "Invoke an ordered Minecraft command batch",
      description:
        "Run up to 64 exact commands sequentially. Stops on the first error by default. Orientation-sensitive commands apply a bounded server-settle barrier before the next command can change player pose. No command is automatically retried; screenshots are excluded because image results do not belong in JSON batches.",
      inputSchema: {
        commands: z
          .array(
            z.object({
              name: z.string().min(1),
              arguments: z.record(z.unknown()).optional().default({}),
            }),
          )
          .min(1)
          .max(64),
        stopOnError: z.boolean().optional().default(true),
      },
    },
    async ({ commands, stopOnError }) => {
      const batchId = randomUUID();
      const results: unknown[] = [];
      let settledMs = 0;
      for (let index = 0; index < commands.length; index += 1) {
        const item = commands[index];
        const def = TOOL_BY_NAME.get(item.name);
        if (def?.kind === "image") {
          const failure = { ok: false, command: item.name, error: { code: "image_not_batchable", message: "Use command_invoke for image commands." } };
          results.push(failure);
          if (stopOnError) break;
          continue;
        }
        const response = await invokeCommand(bridge, item.name, item.arguments);
        results.push(response.structuredContent ?? { ok: !response.isError });
        const accepted = !response.isError && commandResultSucceeded(response.structuredContent);
        if (!accepted && stopOnError) break;
        if (accepted && index + 1 < commands.length && (def?.batchBarrierMs ?? 0) > 0) {
          await delay(def!.batchBarrierMs!);
          settledMs += def!.batchBarrierMs!;
        }
      }
      const succeeded = results.filter(commandResultSucceeded).length;
      return jsonResult({
        ok: succeeded === commands.length,
        batchId,
        requested: commands.length,
        completed: results.length,
        succeeded,
        settledMs,
        stoppedEarly: results.length < commands.length,
        results,
      });
    },
  );
}

function registerTools(server: McpServer, bridge: BridgeClient): void {
  for (const def of TOOLS as ToolDef[]) {
    const config = {
      title: def.title,
      description: def.description,
      inputSchema: def.inputSchema,
      ...(def.annotations ? { annotations: def.annotations } : {}),
    };

    const handler = async (args: Record<string, unknown>): Promise<CallToolResult> => {
      try {
        const result = await bridge.call(def.method, args ?? {});
        return def.kind === "image" ? imageResult(result) : jsonResult(result);
      } catch (err) {
        return errorResult(err);
      }
    };

    server.registerTool(def.name, config, handler);
  }
}

function buildServer(bridge: BridgeClient, toolMode: ServerConfig["toolMode"]): McpServer {
  const workflow =
    toolMode === "all"
      ? "Raw mode exposes every game command as a direct MCP tool. Call get_status first."
      : "Call minecraft_status, then command_catalog -> command_describe -> command_invoke using exact names and structured arguments.";
  const server = new McpServer(
    { name: "mcpfabric", version: PKG_VERSION },
    {
      instructions:
        `Control and observe the local Minecraft game through MCPFabric. ${workflow} ` +
        "Never blindly retry a mutation whose result is unknown. Navigation is asynchronous: poll navigation_status. " +
        "Capabilities depend on the live client/server connection; respect available and unavailableReason from the catalog. " +
        "Use java_scratch only when the structured commands cannot express the operation; it executes with the Minecraft process's OS-user authority.",
    },
  );
  if (toolMode !== "all") registerCatalogTools(server, bridge);
  if (toolMode !== "catalog") registerTools(server, bridge);
  return server;
}

async function runStdio(bridge: BridgeClient, cfg: ServerConfig): Promise<void> {
  const server = buildServer(bridge, cfg.toolMode);
  const transport = new StdioServerTransport();
  await server.connect(transport);
  log(`stdio transport ready (bridge: ${cfg.bridgeUrl}, tools: ${cfg.toolMode})`);
}

async function runHttp(bridge: BridgeClient, cfg: ServerConfig): Promise<void> {
  // Stateful streamable-HTTP: one transport+server per session id.
  const sessions = new Map<string, { server: McpServer; transport: StreamableHTTPServerTransport }>();

  async function readBody(req: http.IncomingMessage): Promise<unknown> {
    const chunks: Buffer[] = [];
    for await (const chunk of req) chunks.push(chunk as Buffer);
    if (chunks.length === 0) return undefined;
    try {
      return JSON.parse(Buffer.concat(chunks).toString("utf8"));
    } catch {
      return undefined;
    }
  }

  const httpServer = http.createServer(async (req, res) => {
    if (!req.url || !req.url.startsWith("/mcp")) {
      res.writeHead(404).end("Not found");
      return;
    }
    const sessionId = req.headers["mcp-session-id"];
    const sid = Array.isArray(sessionId) ? sessionId[0] : sessionId;
    const body = req.method === "POST" ? await readBody(req) : undefined;

    let entry = sid ? sessions.get(sid) : undefined;
    if (!entry) {
      const transport = new StreamableHTTPServerTransport({
        sessionIdGenerator: () => randomUUID(),
        onsessioninitialized: (id: string) => {
          sessions.set(id, entry!);
        },
      });
      transport.onclose = () => {
        if (transport.sessionId) sessions.delete(transport.sessionId);
      };
      const server = buildServer(bridge, cfg.toolMode);
      await server.connect(transport);
      entry = { server, transport };
    }
    await entry.transport.handleRequest(req, res, body);
  });

  httpServer.listen(cfg.httpPort, "127.0.0.1", () => {
    log(`streamable-HTTP transport ready on http://127.0.0.1:${cfg.httpPort}/mcp`);
  });
}

async function main(): Promise<void> {
  const cfg = loadConfig();
  const bridge = new BridgeClient(cfg.bridgeUrl, cfg.token, cfg.timeoutMs);

  // Best-effort connectivity hint (does not block startup; the mod may launch later).
  bridge
    .info()
    .then((info) => log("connected to bridge:", JSON.stringify(info)))
    .catch((err) => log("bridge not reachable yet:", (err as Error).message));

  if (cfg.transport === "http") {
    await runHttp(bridge, cfg);
  } else {
    await runStdio(bridge, cfg);
  }
}

main().catch((err) => {
  log("fatal:", err);
  process.exit(1);
});
