import assert from "node:assert/strict";
import http from "node:http";
import test from "node:test";

import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";

test("catalog MCP surface discovers and invokes exact bridge commands", async (t) => {
  const calls: Array<{ method: string; params: Record<string, unknown> }> = [];
  const bridge = http.createServer(async (req, res) => {
    if (req.url === "/info") {
      res.setHeader("content-type", "application/json");
      res.end(JSON.stringify({ ok: true }));
      return;
    }
    if (req.url !== "/rpc" || req.method !== "POST") {
      res.writeHead(404).end();
      return;
    }
    const chunks: Buffer[] = [];
    for await (const chunk of req) chunks.push(chunk as Buffer);
    const call = JSON.parse(Buffer.concat(chunks).toString("utf8")) as {
      method: string;
      params: Record<string, unknown>;
    };
    calls.push(call);
    res.setHeader("content-type", "application/json");
    if (call.method === "info.status") {
      res.end(JSON.stringify({
        ok: true,
        result: {
          side: "client",
          capabilities: ["info", "player_local", "interact", "inventory"],
          methods: ["info.status", "client.status", "player.getState", "interact.useBlock"],
        },
      }));
    } else if (call.method === "client.status") {
      res.end(JSON.stringify({
        ok: true,
        result: { clientPlayerPresent: true, clientLevelPresent: true, remoteMultiplayer: true, currentDimension: "minecraft:the_end" },
      }));
    } else {
      res.end(JSON.stringify({ ok: true, result: { echoedMethod: call.method, echoedParams: call.params } }));
    }
  });
  await new Promise<void>((resolve) => bridge.listen(0, "127.0.0.1", resolve));
  t.after(() => bridge.close());
  const address = bridge.address();
  assert.ok(address && typeof address === "object");

  const client = new Client({ name: "mcpfabric-integration-test", version: "1" });
  const transport = new StdioClientTransport({
    command: process.execPath,
    args: [new URL("./index.js", import.meta.url).pathname],
    env: {
      ...process.env,
      MCPFABRIC_TOOL_MODE: "catalog",
      MCPFABRIC_URL: `http://127.0.0.1:${address.port}`,
    },
    stderr: "pipe",
  });
  await client.connect(transport);
  t.after(() => client.close());

  const tools = await client.listTools();
  assert.deepEqual(
    tools.tools.map((tool) => tool.name),
    ["minecraft_status", "command_catalog", "command_describe", "command_describe_many", "command_invoke", "command_batch"],
  );

  const status = await client.callTool({ name: "minecraft_status", arguments: {} });
  assert.equal((status.structuredContent as { status: { remoteMultiplayer: boolean } }).status.remoteMultiplayer, true);

  const unavailableWorld = await client.callTool({
    name: "command_catalog",
    arguments: { category: "world", availableOnly: true },
  });
  assert.equal((unavailableWorld.structuredContent as { commands: unknown[] }).commands.length, 0);

  const described = await client.callTool({ name: "command_describe", arguments: { name: "get_self" } });
  assert.equal(described.isError, undefined);
  assert.equal((described.structuredContent as { command: { name: string } }).command.name, "get_self");

  const describedMany = await client.callTool({
    name: "command_describe_many",
    arguments: { names: ["get_self", "use_block"] },
  });
  assert.equal(describedMany.isError, undefined);
  assert.equal((describedMany.structuredContent as { commands: unknown[] }).commands.length, 2);

  const invoked = await client.callTool({ name: "command_invoke", arguments: { name: "get_self", arguments: {} } });
  assert.equal(invoked.isError, undefined);
  assert.equal(calls.at(-1)?.method, "player.getState");

  const beforeInvalid = calls.length;
  const invalid = await client.callTool({
    name: "command_invoke",
    arguments: { name: "navigate_to", arguments: { x: "east", y: 64, z: 0 } },
  });
  assert.equal(invalid.isError, true);
  assert.equal((invalid.structuredContent as { error: { code: string } }).error.code, "invalid_arguments");
  assert.equal(calls.length, beforeInvalid);

  const unknown = await client.callTool({ name: "command_invoke", arguments: { name: "walk_over_there", arguments: {} } });
  assert.equal(unknown.isError, true);
  assert.equal((unknown.structuredContent as { error: { code: string } }).error.code, "unknown_command");
  assert.equal(calls.some((call) => call.method === "walk_over_there"), false);
});
