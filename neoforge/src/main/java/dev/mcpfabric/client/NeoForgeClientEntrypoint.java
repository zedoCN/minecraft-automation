package dev.mcpfabric.client;

import dev.mcpfabric.McpFabric;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

@Mod(value = McpFabric.MOD_ID, dist = Dist.CLIENT)
public final class NeoForgeClientEntrypoint {
	public NeoForgeClientEntrypoint(IEventBus modBus) {
		modBus.addListener((FMLClientSetupEvent e) -> e.enqueueWork(McpFabricClient::initialize));
	}
}
