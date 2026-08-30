import assert from "node:assert/strict";
import test from "node:test";

import { catalogOverview, describeCommand, searchCatalog, TOOL_BY_NAME } from "./catalog.js";
import { TOOLS } from "./tools.js";

test("catalog has one stable entry per raw command", () => {
  assert.ok(TOOLS.length > 0);
  assert.equal(TOOL_BY_NAME.size, TOOLS.length);
  assert.equal(catalogOverview().totalCommands, TOOLS.length);
  assert.deepEqual([...TOOL_BY_NAME.keys()].sort(), TOOLS.map((tool) => tool.name).sort());
  for (const tool of TOOLS) {
    assert.equal(TOOL_BY_NAME.get(tool.name), tool);
    assert.equal(describeCommand(tool.name)?.name, tool.name);
  }
});

test("catalog exposes deterministic interaction and multiplayer client-cache reads", () => {
  assert.equal(describeCommand("use_block")?.category, "interact");
  assert.equal(describeCommand("use_item_in_air")?.category, "interact");
  assert.equal(describeCommand("get_client_blocks_region")?.category, "clientworld");
  assert.equal(describeCommand("query_client_entities")?.category, "cliententities");
});

test("orientation-sensitive placement accepts an atomic player pose", () => {
  const command = describeCommand("place_block");
  assert.ok(command);
  const properties = command.inputSchema.properties as Record<string, unknown>;
  assert.ok(properties.yaw);
  assert.ok(properties.pitch);

  const exact = describeCommand("place_block_at");
  assert.ok(exact);
  const exactProperties = exact.inputSchema.properties as Record<string, unknown>;
  assert.ok(exactProperties.itemId);
  assert.ok(exactProperties.supportDirection);
  assert.ok(exactProperties.expectedProperties);
  assert.ok(exactProperties.confirmTimeoutMs);
  assert.equal(exact.batchBarrierMs, 100);
  assert.equal(describeCommand("build_structure")?.category, "interact");
  assert.equal(describeCommand("inspect_structure")?.category, "interact");
  assert.equal(describeCommand("edit_structure")?.category, "interact");
});

test("live availability filters commands that require an absent server", () => {
  const context = {
    capabilities: new Set(["info", "player_local", "interact", "inventory", "vision"]),
    clientPlayerPresent: true,
    clientLevelPresent: true,
  };
  assert.equal(describeCommand("get_blocks_region", context)?.available, false);
  assert.equal(describeCommand("get_client_blocks_region", context)?.available, true);
  const world = searchCatalog({ category: "world", availableOnly: true, limit: 100 }, context);
  assert.equal(world.commands.length, 0);
  const clientWorld = searchCatalog({ category: "clientworld", availableOnly: true, limit: 100 }, context);
  assert.ok(clientWorld.commands.length > 0);
});

test("catalog exposes the generic UI and authenticated Java escape hatches", () => {
  assert.equal(describeCommand("get_screen_state")?.category, "screen");
  assert.equal(describeCommand("wait_screen")?.category, "screen");
  assert.equal(describeCommand("probe_container_slot")?.category, "screen");
  assert.equal(describeCommand("container_transaction")?.category, "screen");
  assert.equal(describeCommand("move_container_item")?.category, "screen");
  assert.equal(describeCommand("list_key_bindings")?.category, "input");
  assert.equal(describeCommand("java_scratch")?.category, "unsafe");
  assert.equal(describeCommand("java_scratch")?.destructive, true);
});

test("catalog discovery is bounded and reports continuation", () => {
  const first = searchCatalog({ limit: 5 });
  assert.equal(first.commands.length, 5);
  assert.equal(first.hasMore, true);
  assert.equal(first.nextOffset, 5);

  const playerCommands = searchCatalog({ category: "player", limit: 100 });
  assert.ok(playerCommands.commands.length > 0);
  assert.ok(playerCommands.commands.every((command) => command.category === "player"));
});

test("describe returns exact schema and execution metadata", () => {
  const command = describeCommand("navigate_to");
  assert.ok(command);
  assert.equal(command.delivery, "starts_async_operation");
  assert.equal(command.postcondition, "poll_navigation_status");
  assert.match(command.schemaHash, /^[0-9a-f]{64}$/);
  assert.equal(command.inputSchema.type, "object");
});

test("unknown command is never guessed", () => {
  assert.equal(describeCommand("please_walk_over_there"), undefined);
});
