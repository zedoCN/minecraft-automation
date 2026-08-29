import assert from "node:assert/strict";
import test from "node:test";

import { catalogOverview, describeCommand, searchCatalog, TOOL_BY_NAME } from "./catalog.js";
import { TOOLS } from "./tools.js";

test("catalog has one stable entry per raw command", () => {
  assert.equal(TOOLS.length, 53);
  assert.equal(TOOL_BY_NAME.size, TOOLS.length);
  assert.equal(catalogOverview().totalCommands, TOOLS.length);
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
