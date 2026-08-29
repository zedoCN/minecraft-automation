package dev.mcpfabric.client.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import dev.mcpfabric.McpFabric;
import dev.mcpfabric.bridge.Json;
import dev.mcpfabric.bridge.RpcException;
import dev.mcpfabric.bridge.RpcRouter;
import dev.mcpfabric.client.ClientMc;
import net.minecraft.client.KeyMapping;

/** Exact access to every vanilla or mod-provided key mapping registered in the client options. */
public final class KeyBindingHandlers {
	private KeyBindingHandlers() {}

	public static void register(RpcRouter router) {
		router.register("input.listKeyBindings", ctx -> ClientMc.call(() -> {
			JsonArray bindings = new JsonArray();
			for (KeyMapping mapping : ClientMc.mc().options.keyMappings) {
				JsonObject o = new JsonObject();
				o.addProperty("name", mapping.getName());
				o.addProperty("key", mapping.saveString());
				o.addProperty("displayName", mapping.getTranslatedKeyMessage().getString());
				o.addProperty("category", mapping.getCategory().toString());
				o.addProperty("down", mapping.isDown());
				o.addProperty("unbound", mapping.isUnbound());
				bindings.add(o);
			}
			JsonObject out = new JsonObject();
			out.add("bindings", bindings);
			return out;
		}));

		router.register("input.keyAction", ctx -> ClientMc.call(() -> {
			requireControl();
			String name = ctx.getString("name");
			String action = ctx.optString("action", "click").toLowerCase();
			KeyMapping mapping = find(name);
			switch (action) {
				case "press" -> mapping.setDown(true);
				case "release" -> mapping.setDown(false);
				case "click" -> KeyMapping.click(InputConstants.getKey(mapping.saveString()));
				default -> throw RpcException.badRequest("action must be click, press, or release.");
			}
			JsonObject out = Json.ok(action + " " + name);
			out.addProperty("name", name);
			out.addProperty("key", mapping.saveString());
			out.addProperty("down", mapping.isDown());
			return out;
		}));
	}

	private static KeyMapping find(String exactName) throws RpcException {
		for (KeyMapping mapping : ClientMc.mc().options.keyMappings) {
			if (mapping.getName().equals(exactName)) return mapping;
		}
		throw RpcException.notFound("No key binding with exact name '" + exactName + "'. Call input.listKeyBindings first.");
	}

	private static void requireControl() throws RpcException {
		if (!McpFabric.config().enablePlayerControl) {
			throw RpcException.unavailable("Player control is disabled in mcpfabric.config.json (enablePlayerControl=false).");
		}
	}
}
