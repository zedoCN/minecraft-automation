package dev.mcpfabric.client;

import dev.mcpfabric.McpFabric;
import dev.mcpfabric.bridge.RpcRouter;
import dev.mcpfabric.client.handlers.ClientChatHandlers;
import dev.mcpfabric.client.handlers.ClientEntityHandlers;
import dev.mcpfabric.client.handlers.ClientStatusHandlers;
import dev.mcpfabric.client.handlers.ClientWorldHandlers;
import dev.mcpfabric.client.handlers.ControlHandlers;
import dev.mcpfabric.client.handlers.InteractHandlers;
import dev.mcpfabric.client.handlers.InventoryHandlers;
import dev.mcpfabric.client.handlers.KeyBindingHandlers;
import dev.mcpfabric.client.handlers.LocalPlayerHandlers;
import dev.mcpfabric.client.handlers.NavHandlers;
import dev.mcpfabric.client.handlers.PrecisePlacementHandlers;
import dev.mcpfabric.client.handlers.ScreenHandlers;
import dev.mcpfabric.client.handlers.StructureEditHandlers;
import dev.mcpfabric.client.handlers.UnsafeJavaHandlers;
import dev.mcpfabric.client.handlers.VisionHandlers;
import dev.mcpfabric.client.nav.BaritoneNavigationBackend;
import dev.mcpfabric.client.nav.BaritoneShutdown;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/**
 * Client entrypoint. Registers all client-only handlers into the shared router started by
 * {@link McpFabric} and drives the {@link BotController} once per client tick.
 */
public class McpFabricClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		RpcRouter router = McpFabric.router();
		if (router == null) {
			McpFabric.LOGGER.error("[mcpfabric] router not initialized; client handlers unavailable");
			return;
		}

		LocalPlayerHandlers.register(router);
		ClientStatusHandlers.register(router);
		ClientWorldHandlers.register(router);
		ClientEntityHandlers.register(router);
		ControlHandlers.register(router);
		InteractHandlers.register(router);
		PrecisePlacementHandlers.register(router);
		StructureEditHandlers.register(router);
		InventoryHandlers.register(router);
		KeyBindingHandlers.register(router);
		ScreenHandlers.register(router);
		UnsafeJavaHandlers.register(router);
		VisionHandlers.register(router);
		NavHandlers.register(router);
		ClientChatHandlers.register(router); // client variant of chat.send (speaks as local player)
		ClientEvents.register(McpFabric.events());

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			BotController.get().onClientTick(client);
			BaritoneNavigationBackend.get().onClientTick(client);
		});
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			BotController.get().stopNavigation("client_stopping");
			BotController.get().stopAllMovement();
			BaritoneShutdown.prepareClientExit();
			McpFabric.LOGGER.info("[mcpfabric] stopping HTTP bridge with Minecraft client");
			McpFabric.stopHttpBridge();
		});

		McpFabric.LOGGER.info("[mcpfabric] client handlers registered");
	}
}
