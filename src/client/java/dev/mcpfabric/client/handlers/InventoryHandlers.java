package dev.mcpfabric.client.handlers;

import com.google.gson.JsonObject;
import dev.mcpfabric.McpFabric;
import dev.mcpfabric.ServerHolder;
import dev.mcpfabric.bridge.Json;
import dev.mcpfabric.bridge.MainThread;
import dev.mcpfabric.bridge.RpcException;
import dev.mcpfabric.bridge.RpcContext;
import dev.mcpfabric.bridge.RpcRouter;
import dev.mcpfabric.client.ClientMc;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
//? if <26.1 {
import net.minecraft.world.inventory.ClickType;
//?}

import java.util.UUID;

/** Inventory manipulation: hotbar selection, dropping a slot, swapping two slots. */
public final class InventoryHandlers {
	private InventoryHandlers() {}

	public static void register(RpcRouter router) {
		router.register("inventory.selectHotbar", InventoryHandlers::selectHotbarConfirmed);

		router.register("inventory.dropSlot", ctx -> ClientMc.call(() -> {
			LocalPlayer p = ClientMc.player();
			MultiPlayerGameMode gm = ClientMc.gameMode();
			int menuSlot = toMenuSlot(ctx.getInt("slot"));
			boolean whole = ctx.optBool("wholeStack", true);
			containerClick(gm, p.inventoryMenu.containerId, menuSlot, whole ? 1 : 0, true, p);
			return Json.ok("dropped slot");
		}));

		router.register("inventory.swapSlots", ctx -> ClientMc.call(() -> {
			LocalPlayer p = ClientMc.player();
			MultiPlayerGameMode gm = ClientMc.gameMode();
			int a = toMenuSlot(ctx.getInt("slotA"));
			int b = toMenuSlot(ctx.getInt("slotB"));
			int id = p.inventoryMenu.containerId;
			containerClick(gm, id, a, 0, false, p);
			containerClick(gm, id, b, 0, false, p);
			containerClick(gm, id, a, 0, false, p);
			return Json.ok("swapped");
		}));
	}

	private static JsonObject selectHotbarConfirmed(RpcContext ctx) throws RpcException {
		int slot = ctx.getInt("slot");
		if (slot < 0 || slot > 8) throw RpcException.badRequest("Hotbar slot must be 0-8.");
		int timeoutMs = Math.max(100, Math.min(10_000, ctx.optInt("timeoutMs", 2_000)));
		int requiredStableReads = Math.max(1, Math.min(10, ctx.optInt("stableReads", 2)));
		SelectionRequest request = ClientMc.call(() -> {
			LocalPlayer player = ClientMc.player();
			int beforeSlot = selectedSlot(player.getInventory());
			selectHotbarSlot(player, slot);
			return new SelectionRequest(player.getUUID(), beforeSlot);
		});

		long started = System.nanoTime();
		MinecraftServer server = ServerHolder.get();
		if (server == null) {
			JsonObject result = Json.ok("selected slot " + slot);
			result.addProperty("beforeSlot", request.beforeSlot);
			result.addProperty("selectedSlot", slot);
			result.addProperty("confirmed", false);
			result.addProperty("confirmationSource", "client_cache");
			result.addProperty("waitedMs", elapsedMs(started));
			return result;
		}

		long deadline = started + timeoutMs * 1_000_000L;
		int stableReads = 0;
		Integer authoritativeSlot = null;
		while (System.nanoTime() <= deadline) {
			authoritativeSlot = MainThread.call(server, McpFabric.config().callTimeoutMs, () -> {
				ServerPlayer player = server.getPlayerList().getPlayer(request.playerId);
				return player == null ? null : selectedSlot(player.getInventory());
			});
			stableReads = authoritativeSlot != null && authoritativeSlot == slot ? stableReads + 1 : 0;
			if (stableReads >= requiredStableReads) {
				JsonObject result = Json.ok("selected slot " + slot);
				result.addProperty("beforeSlot", request.beforeSlot);
				result.addProperty("selectedSlot", slot);
				result.addProperty("confirmed", true);
				result.addProperty("confirmationSource", "integrated_server");
				result.addProperty("waitedMs", elapsedMs(started));
				result.addProperty("stableReads", stableReads);
				return result;
			}
			sleep(25);
		}

		JsonObject data = new JsonObject();
		data.addProperty("beforeSlot", request.beforeSlot);
		data.addProperty("requestedSlot", slot);
		if (authoritativeSlot != null) data.addProperty("authoritativeSlot", authoritativeSlot);
		data.addProperty("waitedMs", elapsedMs(started));
		data.addProperty("packetSent", true);
		data.addProperty("retrySafe", false);
		throw new RpcException("hotbar_confirmation_timeout",
				"Hotbar selection packet was sent, but the integrated server did not confirm it before timeout.", data);
	}

	/**
	 * Click a container slot. {@code handleInventoryMouseClick(..., ClickType, ...)} became
	 * {@code handleContainerInput(..., ContainerInput, ...)} in 26.1 (same constant names).
	 */
	private static void containerClick(MultiPlayerGameMode gm, int containerId, int slot, int button, boolean throwItem, LocalPlayer p) {
		//? if <26.1 {
		gm.handleInventoryMouseClick(containerId, slot, button, throwItem ? ClickType.THROW : ClickType.PICKUP, p);
		//?} else
		/*gm.handleContainerInput(containerId, slot, button, throwItem ? net.minecraft.world.inventory.ContainerInput.THROW : net.minecraft.world.inventory.ContainerInput.PICKUP, p);*/
	}

	/** Select and immediately publish one hotbar slot. */
	public static void selectHotbarSlot(LocalPlayer player, int slot) throws RpcException {
		if (slot < 0 || slot > 8) throw RpcException.badRequest("Hotbar slot must be 0-8.");
		//? if >=1.21.5 {
		player.getInventory().setSelectedSlot(slot);
		//?} else
		/*player.getInventory().selected = slot;*/
		player.connection.send(new ServerboundSetCarriedItemPacket(slot));
	}

	private static int selectedSlot(Inventory inventory) {
		//? if >=1.21.5 {
		return inventory.getSelectedSlot();
		//?} else
		/*return inventory.selected;*/
	}

	private static long elapsedMs(long started) {
		return (System.nanoTime() - started) / 1_000_000L;
	}

	private static void sleep(long millis) throws RpcException {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw RpcException.unavailable("Interrupted while awaiting hotbar selection confirmation.");
		}
	}

	private record SelectionRequest(UUID playerId, int beforeSlot) {}

	/** Native SWAP input between a player inventory slot and a hotbar slot. */
	public static void swapWithHotbar(MultiPlayerGameMode gameMode, LocalPlayer player, int inventorySlot, int hotbarSlot) throws RpcException {
		if (inventorySlot < 9 || inventorySlot > 35) throw RpcException.badRequest("Main inventory slot must be 9-35.");
		if (hotbarSlot < 0 || hotbarSlot > 8) throw RpcException.badRequest("Hotbar slot must be 0-8.");
		int menuSlot = toMenuSlot(inventorySlot);
		//? if <26.1 {
		gameMode.handleInventoryMouseClick(player.inventoryMenu.containerId, menuSlot, hotbarSlot, ClickType.SWAP, player);
		//?} else
		/*gameMode.handleContainerInput(player.inventoryMenu.containerId, menuSlot, hotbarSlot, net.minecraft.world.inventory.ContainerInput.SWAP, player);*/
	}

	/**
	 * Map a player-inventory index to the slot index inside the player's {@code InventoryMenu}.
	 * Convention: 0-8 hotbar, 9-35 main, 36-39 armor (helmet..boots), 40 offhand.
	 */
	private static int toMenuSlot(int inv) throws RpcException {
		if (inv >= 0 && inv <= 8) return 36 + inv;       // hotbar -> menu 36-44
		if (inv >= 9 && inv <= 35) return inv;            // main -> menu 9-35
		if (inv >= 36 && inv <= 39) return 5 + (inv - 36); // armor -> menu 5-8 (helmet..boots)
		if (inv == 40) return 45;                         // offhand -> menu 45
		throw RpcException.badRequest("Inventory slot out of range (0-40): " + inv);
	}
}
