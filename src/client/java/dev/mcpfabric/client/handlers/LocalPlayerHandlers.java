package dev.mcpfabric.client.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import dev.mcpfabric.McpFabric;
import dev.mcpfabric.ServerHolder;
import dev.mcpfabric.bridge.MainThread;
import dev.mcpfabric.bridge.RpcRouter;
import dev.mcpfabric.client.ClientMc;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** Read-only state of the local player: position/vitals, inventory, equipment, effects. */
public final class LocalPlayerHandlers {
	private LocalPlayerHandlers() {}

	public static void register(RpcRouter router) {
		router.register("player.getState", ctx -> ClientMc.call(() -> {
			LocalPlayer p = ClientMc.player();
			JsonObject o = new JsonObject();
			o.addProperty("x", p.getX());
			o.addProperty("y", p.getY());
			o.addProperty("z", p.getZ());
			o.addProperty("yaw", p.getYRot());
			o.addProperty("pitch", p.getXRot());
			JsonObject motion = new JsonObject();
			motion.addProperty("x", p.getDeltaMovement().x);
			motion.addProperty("y", p.getDeltaMovement().y);
			motion.addProperty("z", p.getDeltaMovement().z);
			o.add("motion", motion);
			o.addProperty("health", p.getHealth());
			o.addProperty("maxHealth", p.getMaxHealth());
			o.addProperty("food", p.getFoodData().getFoodLevel());
			o.addProperty("saturation", p.getFoodData().getSaturationLevel());
			o.addProperty("air", p.getAirSupply());
			o.addProperty("maxAir", p.getMaxAirSupply());
			o.addProperty("xpLevel", p.experienceLevel);
			o.addProperty("xpProgress", p.experienceProgress);
			o.addProperty("onGround", p.onGround());
			o.addProperty("inWater", p.isInWater());
			o.addProperty("sprinting", p.isSprinting());
			o.addProperty("sneaking", p.isShiftKeyDown());
			o.addProperty("usingItem", p.isUsingItem());
			o.addProperty("selectedSlot", selectedSlot(p.getInventory()));
			//? if <1.21.11 {
				o.addProperty("dimension", p.level().dimension().location().toString());
				//?} else
				/*o.addProperty("dimension", p.level().dimension().identifier().toString());*/
			var gm = ClientMc.mc().gameMode;
			o.addProperty("gameMode", gm != null ? gm.getPlayerMode().getName() : "unknown");
			return o;
		}));

		router.register("player.getInventory", ctx -> inventoryState());
		router.register("player.reconcileInventory", ctx -> reconcileInventory(ctx.optBool("requireScreenClosed", true)));

		router.register("player.getEquipment", ctx -> ClientMc.call(() -> {
			LocalPlayer p = ClientMc.player();
			JsonObject o = new JsonObject();
			o.add("mainHand", itemJson(p.getMainHandItem()));
			o.add("offHand", itemJson(p.getOffhandItem()));
			o.add("helmet", itemJson(p.getItemBySlot(EquipmentSlot.HEAD)));
			o.add("chest", itemJson(p.getItemBySlot(EquipmentSlot.CHEST)));
			o.add("legs", itemJson(p.getItemBySlot(EquipmentSlot.LEGS)));
			o.add("boots", itemJson(p.getItemBySlot(EquipmentSlot.FEET)));
			return o;
		}));

		router.register("player.getStatusEffects", ctx -> ClientMc.call(() -> {
			LocalPlayer p = ClientMc.player();
			JsonArray effects = new JsonArray();
			for (MobEffectInstance inst : p.getActiveEffects()) {
				JsonObject e = new JsonObject();
				e.addProperty("id", BuiltInRegistries.MOB_EFFECT.getKey(inst.getEffect().value()).toString());
				e.addProperty("amplifier", inst.getAmplifier());
				e.addProperty("durationTicks", inst.getDuration());
				e.addProperty("ambient", inst.isAmbient());
				e.addProperty("visible", inst.isVisible());
				effects.add(e);
			}
			JsonObject o = new JsonObject();
			o.add("effects", effects);
			return o;
		}));
	}

	private static JsonObject reconcileInventory(boolean requireScreenClosed) throws dev.mcpfabric.bridge.RpcException {
		if (!McpFabric.config().enablePlayerControl) {
			throw dev.mcpfabric.bridge.RpcException.unavailable(
					"Player control is disabled in mcpfabric.config.json (enablePlayerControl=false).");
		}
		boolean screenOpen = ClientMc.call(() -> {
			//? if >=26.2 {
			return ClientMc.mc().gui.screen() != null;
			//?} else
			/*return ClientMc.mc().screen != null;*/
		});
		if (requireScreenClosed && screenOpen) {
			throw dev.mcpfabric.bridge.RpcException.unavailable(
					"Close the current GUI before reconciling the complete inventory.");
		}
		JsonObject before = inventoryState();
		if (!before.get("authoritativeAvailable").getAsBoolean()) {
			throw dev.mcpfabric.bridge.RpcException.unavailable(
					"Inventory reconciliation requires the integrated server; remote multiplayer remains read-only.");
		}
		UUID playerId = ClientMc.call(() -> ClientMc.player().getUUID());
		InventorySnapshot authoritative = authoritativeInventory(playerId);
		if (authoritative == null) throw dev.mcpfabric.bridge.RpcException.unavailable("Integrated-server player is unavailable.");
		ClientMc.call(() -> {
			LocalPlayer player = ClientMc.player();
			Inventory inventory = player.getInventory();
			//? if >=1.21.5 {
			var items = inventory.getNonEquipmentItems();
			//?} else
			/*var items = inventory.items;*/
			for (int slot = 0; slot < Math.min(items.size(), authoritative.items.size()); slot++) {
				items.set(slot, authoritative.items.get(slot).copy());
			}
			EquipmentSlot[] armorSlots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
			for (int index = 0; index < armorSlots.length; index++) {
				player.setItemSlot(armorSlots[index], authoritative.armor.get(index).copy());
			}
			player.setItemSlot(EquipmentSlot.OFFHAND, authoritative.offhand.copy());
			inventory.setSelectedSlot(authoritative.selectedSlot);
			return null;
		});
		MinecraftServer server = ServerHolder.get();
		MainThread.call(server, McpFabric.config().callTimeoutMs, () -> {
			ServerPlayer player = server.getPlayerList().getPlayer(playerId);
			if (player != null) {
				player.inventoryMenu.broadcastFullState();
				if (player.containerMenu != player.inventoryMenu) player.containerMenu.broadcastFullState();
			}
			return null;
		});
		JsonObject after = inventoryState();
		JsonObject result = new JsonObject();
		result.addProperty("reconciled", after.has("consistent") && after.get("consistent").getAsBoolean());
		result.addProperty("source", "integrated_server");
		result.addProperty("updatedSlotCount", before.has("mismatches") ? before.getAsJsonArray("mismatches").size() : 0);
		result.add("beforeMismatches", before.has("mismatches") ? before.getAsJsonArray("mismatches") : new JsonArray());
		result.add("afterMismatches", after.has("mismatches") ? after.getAsJsonArray("mismatches") : new JsonArray());
		return result;
	}

	private static JsonObject inventoryState() throws dev.mcpfabric.bridge.RpcException {
		UUID playerId = ClientMc.call(() -> ClientMc.player().getUUID());
		InventorySnapshot authoritative = authoritativeInventory(playerId);
		return ClientMc.call(() -> {
			InventorySnapshot client = snapshot(ClientMc.player());
			JsonObject result = inventoryJson(client);
			result.addProperty("source", "client_cache");
			result.addProperty("authoritativeAvailable", authoritative != null);
			if (authoritative != null) {
				result.addProperty("authoritativeSource", "integrated_server");
				result.add("authoritative", inventoryJson(authoritative));
				JsonArray mismatches = inventoryMismatches(client, authoritative);
				result.addProperty("consistent", mismatches.isEmpty());
				result.add("mismatches", mismatches);
			}
			return result;
		});
	}

	@Nullable
	private static InventorySnapshot authoritativeInventory(UUID playerId) throws dev.mcpfabric.bridge.RpcException {
		MinecraftServer server = ServerHolder.get();
		if (server == null) return null;
		return MainThread.call(server, McpFabric.config().callTimeoutMs, () -> {
			ServerPlayer player = server.getPlayerList().getPlayer(playerId);
			return player == null ? null : snapshot(player);
		});
	}

	private static InventorySnapshot snapshot(net.minecraft.world.entity.player.Player player) {
		Inventory inventory = player.getInventory();
		//? if >=1.21.5 {
		var source = inventory.getNonEquipmentItems();
		//?} else
		/*var source = inventory.items;*/
		List<ItemStack> items = new ArrayList<>();
		for (int slot = 0; slot < Math.min(36, source.size()); slot++) items.add(source.get(slot).copy());
		List<ItemStack> armor = new ArrayList<>();
		for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
			armor.add(player.getItemBySlot(slot).copy());
		}
		return new InventorySnapshot(selectedSlot(inventory), items, armor, player.getOffhandItem().copy());
	}

	private static JsonObject inventoryJson(InventorySnapshot snapshot) {
		JsonObject result = new JsonObject();
		result.addProperty("selectedSlot", snapshot.selectedSlot);
		JsonArray hotbar = new JsonArray();
		for (int slot = 0; slot <= 8 && slot < snapshot.items.size(); slot++) addItem(hotbar, slot, snapshot.items.get(slot));
		result.add("hotbar", hotbar);
		JsonArray main = new JsonArray();
		for (int slot = 9; slot <= 35 && slot < snapshot.items.size(); slot++) addItem(main, slot, snapshot.items.get(slot));
		result.add("main", main);
		JsonArray armor = new JsonArray();
		EquipmentSlot[] armorSlots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
		for (int index = 0; index < armorSlots.length; index++) {
			ItemStack stack = snapshot.armor.get(index);
			if (stack.isEmpty()) continue;
			JsonObject item = itemJson(stack);
			item.addProperty("slot", armorSlots[index].getName());
			armor.add(item);
		}
		result.add("armor", armor);
		result.add("offhand", itemJson(snapshot.offhand));
		return result;
	}

	private static JsonArray inventoryMismatches(InventorySnapshot client, InventorySnapshot server) {
		JsonArray mismatches = new JsonArray();
		int slots = Math.max(client.items.size(), server.items.size());
		for (int slot = 0; slot < slots; slot++) {
			ItemStack clientStack = slot < client.items.size() ? client.items.get(slot) : ItemStack.EMPTY;
			ItemStack serverStack = slot < server.items.size() ? server.items.get(slot) : ItemStack.EMPTY;
			addMismatch(mismatches, Integer.toString(slot), clientStack, serverStack);
		}
		EquipmentSlot[] armorSlots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
		for (int index = 0; index < armorSlots.length; index++) {
			addMismatch(mismatches, armorSlots[index].getName(), client.armor.get(index), server.armor.get(index));
		}
		addMismatch(mismatches, "offhand", client.offhand, server.offhand);
		if (client.selectedSlot != server.selectedSlot) {
			JsonObject mismatch = new JsonObject();
			mismatch.addProperty("slot", "selected");
			mismatch.addProperty("client", client.selectedSlot);
			mismatch.addProperty("server", server.selectedSlot);
			mismatches.add(mismatch);
		}
		return mismatches;
	}

	private static void addMismatch(JsonArray mismatches, String slot, ItemStack client, ItemStack server) {
		JsonObject clientJson = itemJson(client);
		JsonObject serverJson = itemJson(server);
		if (clientJson.equals(serverJson)) return;
		JsonObject mismatch = new JsonObject();
		mismatch.addProperty("slot", slot);
		mismatch.add("client", clientJson);
		mismatch.add("server", serverJson);
		mismatches.add(mismatch);
	}

	private record InventorySnapshot(int selectedSlot, List<ItemStack> items, List<ItemStack> armor, ItemStack offhand) {}

	/** The selected hotbar slot. The accessor replaced the public {@code selected} field in 1.21.5. */
	private static int selectedSlot(Inventory inv) {
		//? if >=1.21.5 {
		return inv.getSelectedSlot();
		//?} else
		/*return inv.selected;*/
	}

	private static void addItem(JsonArray arr, int slot, ItemStack stack) {
		if (stack.isEmpty()) return;
		JsonObject o = itemJson(stack);
		o.addProperty("slot", slot);
		arr.add(o);
	}

	@Nullable
	static JsonObject itemJson(ItemStack stack) {
		JsonObject o = new JsonObject();
		if (stack.isEmpty()) {
			o.addProperty("empty", true);
			return o;
		}
		o.addProperty("id", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
		o.addProperty("count", stack.getCount());
		o.addProperty("name", stack.getHoverName().getString());
		DataComponentPatch patch = stack.getComponentsPatch();
		o.addProperty("componentCount", patch.size());
		JsonElement components = encodeComponents(patch);
		if (components != null) {
			String canonical = canonicalJson(components);
			o.addProperty("componentFingerprint", sha256(canonical));
			if (canonical.length() <= 16_384) {
				o.add("components", components);
			} else {
				o.addProperty("componentsTruncated", true);
				o.addProperty("componentsEncodedChars", canonical.length());
			}
		}
		if (stack.isDamageableItem()) {
			o.addProperty("damage", stack.getDamageValue());
			o.addProperty("maxDamage", stack.getMaxDamage());
		}
		return o;
	}

	private static JsonElement encodeComponents(DataComponentPatch patch) {
		if (ClientMc.mc().level == null) return null;
		var ops = ClientMc.mc().level.registryAccess().createSerializationContext(JsonOps.INSTANCE);
		return DataComponentPatch.CODEC.encodeStart(ops, patch).result().orElse(null);
	}

	private static String canonicalJson(JsonElement element) {
		if (element == null || element.isJsonNull()) return "null";
		if (element.isJsonPrimitive()) {
			JsonPrimitive primitive = element.getAsJsonPrimitive();
			return primitive.toString();
		}
		if (element.isJsonArray()) {
			List<String> values = new ArrayList<>();
			for (JsonElement child : element.getAsJsonArray()) values.add(canonicalJson(child));
			return "[" + String.join(",", values) + "]";
		}
		List<String> keys = new ArrayList<>(element.getAsJsonObject().keySet());
		keys.sort(Comparator.naturalOrder());
		List<String> entries = new ArrayList<>();
		for (String key : keys) {
			entries.add(new JsonPrimitive(key) + ":" + canonicalJson(element.getAsJsonObject().get(key)));
		}
		return "{" + String.join(",", entries) + "}";
	}

	private static String sha256(String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (java.security.NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("SHA-256 is unavailable", impossible);
		}
	}
}
