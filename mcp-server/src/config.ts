import { readFileSync } from "node:fs";

/**
 * Runtime configuration for the mcpfabric MCP server.
 *
 * All values come from environment variables so the server can be configured from an MCP client's
 * launch config (e.g. Claude Desktop `mcpServers` entry) without code changes.
 */
export interface ServerConfig {
  /** Base URL of the in-game HTTP bridge exposed by the Fabric mod. */
  bridgeUrl: string;
  /** Bearer token from the mod's config/mcpfabric.config.json file. */
  token: string | undefined;
  /** Per-request timeout for bridge calls, in milliseconds. */
  timeoutMs: number;
  /** Transport used to talk to the MCP client. */
  transport: "stdio" | "http";
  /** Port for the streamable-HTTP transport (only used when transport === "http"). */
  httpPort: number;
  /** Model-visible tool surface. Catalog keeps schemas lazy; hybrid/all expose raw tools too. */
  toolMode: "catalog" | "hybrid" | "all";
}

function int(value: string | undefined, fallback: number): number {
  const n = value === undefined ? NaN : Number.parseInt(value, 10);
  return Number.isFinite(n) ? n : fallback;
}

function tokenFromFile(path: string | undefined): string | undefined {
  if (!path) return undefined;
  try {
    const parsed = JSON.parse(readFileSync(path, "utf8")) as { token?: unknown };
    return typeof parsed.token === "string" && parsed.token.length > 0 ? parsed.token : undefined;
  } catch {
    return undefined;
  }
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): ServerConfig {
  const rawUrl = env.MCPFABRIC_URL ?? "http://127.0.0.1:25599";
  // Normalise: strip a trailing slash so we can append paths cleanly.
  const bridgeUrl = rawUrl.replace(/\/+$/, "");

  const transport = (env.MCPFABRIC_TRANSPORT ?? "stdio").toLowerCase();
  if (transport !== "stdio" && transport !== "http") {
    throw new Error(`MCPFABRIC_TRANSPORT must be "stdio" or "http", got "${transport}"`);
  }

  const toolMode = (env.MCPFABRIC_TOOL_MODE ?? "catalog").toLowerCase();
  if (toolMode !== "catalog" && toolMode !== "hybrid" && toolMode !== "all") {
    throw new Error(`MCPFABRIC_TOOL_MODE must be "catalog", "hybrid", or "all", got "${toolMode}"`);
  }

  return {
    bridgeUrl,
    token: env.MCPFABRIC_TOKEN || tokenFromFile(env.MCPFABRIC_TOKEN_FILE),
    timeoutMs: int(env.MCPFABRIC_TIMEOUT_MS, 15000),
    transport,
    httpPort: int(env.MCPFABRIC_HTTP_PORT, 25600),
    toolMode,
  };
}
