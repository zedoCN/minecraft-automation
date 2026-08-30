package dev.mcpfabric.client.handlers;

import com.google.gson.JsonObject;
import dev.mcpfabric.ServerHolder;
import dev.mcpfabric.bridge.RpcRouter;
import dev.mcpfabric.client.ClientMc;
import dev.mcpfabric.handlers.support.Levels;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** Live status that is only knowable from the client process. */
public final class ClientStatusHandlers {
	private ClientStatusHandlers() {}

	public static void register(RpcRouter router) {
		router.register("client.status", ctx -> ClientMc.call(() -> {
			Minecraft mc = ClientMc.mc();
			JsonObject o = new JsonObject();
			boolean playerPresent = mc.player != null;
			boolean levelPresent = mc.level != null;
			o.addProperty("clientPlayerPresent", playerPresent);
			o.addProperty("clientLevelPresent", levelPresent);
			o.addProperty("connectionPresent", mc.getConnection() != null);
			o.addProperty("remoteMultiplayer", levelPresent && ServerHolder.get() == null);
			Screen screen = currentScreen(mc);
			o.addProperty("screenOpen", screen != null);
			if (screen != null) o.addProperty("screenClass", screen.getClass().getName());
			if (levelPresent) o.addProperty("currentDimension", Levels.dimensionId(mc.level));
			if (playerPresent && mc.gameMode != null) {
				o.addProperty("gameMode", mc.gameMode.getPlayerMode().getName());
			}
			return o;
		}));
	}

	private static Screen currentScreen(Minecraft minecraft) {
		//? if >=26.2 {
		return minecraft.gui.screen();
		//?} else
		/*return minecraft.screen;*/
	}
}
