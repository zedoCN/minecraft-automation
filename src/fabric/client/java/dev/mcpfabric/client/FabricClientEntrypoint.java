package dev.mcpfabric.client;

public final class FabricClientEntrypoint implements net.fabricmc.api.ClientModInitializer {
	@Override public void onInitializeClient() { McpFabricClient.initialize(); }
}
