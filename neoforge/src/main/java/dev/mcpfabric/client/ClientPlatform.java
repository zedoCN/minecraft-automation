package dev.mcpfabric.client;

import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.lifecycle.ClientStoppingEvent;

public final class ClientPlatform {
	private ClientPlatform() {}
	public static void registerLifecycle() {
		NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> McpFabricClient.tick(Minecraft.getInstance()));
		NeoForge.EVENT_BUS.addListener((ClientStoppingEvent e) -> McpFabricClient.stopping());
	}
}
