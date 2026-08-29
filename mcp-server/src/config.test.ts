import assert from "node:assert/strict";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";

import { loadConfig } from "./config.js";

test("catalog mode is the low-context default", () => {
  assert.equal(loadConfig({}).toolMode, "catalog");
});

test("raw and hybrid modes remain explicitly available", () => {
  assert.equal(loadConfig({ MCPFABRIC_TOOL_MODE: "all" }).toolMode, "all");
  assert.equal(loadConfig({ MCPFABRIC_TOOL_MODE: "HYBRID" }).toolMode, "hybrid");
});

test("invalid tool mode fails closed", () => {
  assert.throws(() => loadConfig({ MCPFABRIC_TOOL_MODE: "everything" }), /MCPFABRIC_TOOL_MODE/);
});

test("token can be loaded from the mod config without copying it into Codex config", () => {
  const directory = mkdtempSync(join(tmpdir(), "mcpfabric-config-"));
  const file = join(directory, "mcpfabric.config.json");
  try {
    writeFileSync(file, JSON.stringify({ token: "local-secret" }));
    assert.equal(loadConfig({ MCPFABRIC_TOKEN_FILE: file }).token, "local-secret");
    assert.equal(loadConfig({ MCPFABRIC_TOKEN: "explicit", MCPFABRIC_TOKEN_FILE: file }).token, "explicit");
  } finally {
    rmSync(directory, { recursive: true });
  }
});
