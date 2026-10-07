package dev.mcpfabric.client;

import com.google.gson.JsonObject;
import dev.mcpfabric.events.EventBus;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;

public final class ClientEvents {
	private ClientEvents() {}
	public static void register(EventBus events) {
		NeoForge.EVENT_BUS.addListener((ClientChatReceivedEvent.System e) -> {
			JsonObject d = new JsonObject();
			d.addProperty("text", e.getMessage().getString());
			d.addProperty("overlay", e.isOverlay());
			events.emit("system_message", d);
		});
		NeoForge.EVENT_BUS.addListener((ClientChatReceivedEvent.Player e) -> {
			JsonObject d = new JsonObject();
			d.addProperty("text", e.getMessage().getString());
			var connection = Minecraft.getInstance().getConnection();
			var sender = connection == null ? null : connection.getPlayerInfo(e.getSender());
			if (sender != null) d.addProperty("sender", sender.getProfile().name());
			events.emit("chat", d);
		});
	}
}
