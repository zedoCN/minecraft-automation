package dev.mcpfabric.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public final class ClientPlatform {
	private ClientPlatform() {}
	public static void registerLifecycle() {
		ClientTickEvents.END_CLIENT_TICK.register(McpFabricClient::tick);
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> McpFabricClient.stopping());
	}
}
