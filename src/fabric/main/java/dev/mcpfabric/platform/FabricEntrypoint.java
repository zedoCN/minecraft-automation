package dev.mcpfabric.platform;

public final class FabricEntrypoint implements net.fabricmc.api.ModInitializer {
	@Override public void onInitialize() { dev.mcpfabric.McpFabric.initialize(); }
}
