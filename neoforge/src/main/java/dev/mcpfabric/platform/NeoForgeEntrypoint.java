package dev.mcpfabric.platform;

import dev.mcpfabric.McpFabric;
import net.neoforged.fml.common.Mod;

@Mod(McpFabric.MOD_ID)
public final class NeoForgeEntrypoint {
	public NeoForgeEntrypoint() { McpFabric.initialize(); }
}
