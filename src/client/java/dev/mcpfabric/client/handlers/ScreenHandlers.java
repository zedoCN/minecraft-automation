package dev.mcpfabric.client.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.mcpfabric.McpFabric;
import dev.mcpfabric.bridge.Json;
import dev.mcpfabric.bridge.RpcException;
import dev.mcpfabric.bridge.RpcContext;
import dev.mcpfabric.bridge.RpcRouter;
import dev.mcpfabric.client.ClientMc;
import dev.mcpfabric.client.mixin.AbstractContainerScreenAccessor;
import dev.mcpfabric.mixin.AbstractContainerMenuAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
//? if >=26.2 {
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
//?}
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
//? if <26.1 {
import net.minecraft.world.inventory.ClickType;
//?} else
/*import net.minecraft.world.inventory.ContainerInput;*/
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Generic screen, widget, text, and container primitives for complete vanilla GUI operation. */
public final class ScreenHandlers {
	private ScreenHandlers() {}

	public static void register(RpcRouter router) {
		router.register("screen.getState", ctx -> ClientMc.call(() -> screenState(ctx.optBool("includeEmptySlots", true))));
		router.register("screen.findWidgets", ctx -> ClientMc.call(() -> findWidgets(ctx)));
		router.register("screen.waitState", ScreenHandlers::waitState);
		router.register("screen.probeSlot", ctx -> ClientMc.call(() -> probeSlot(ctx)));
		router.register("screen.containerTransaction", ScreenHandlers::containerTransaction);
		router.register("screen.moveItem", ScreenHandlers::moveItem);
		router.register("screen.transferItems", ScreenHandlers::transferItems);

		router.register("screen.containerClick", ctx -> ClientMc.call(() -> {
			requireControl();
			requireContainerScreen();
			LocalPlayer player = ClientMc.player();
			AbstractContainerMenu menu = player.containerMenu;
			int slot = ctx.getInt("slot");
			if (slot != AbstractContainerMenu.SLOT_CLICKED_OUTSIDE && !menu.isValidSlotIndex(slot)) {
				throw RpcException.badRequest("Container slot is not valid for the current menu: " + slot);
			}
			int button = ctx.optInt("button", 0);
			String input = ctx.optString("input", "PICKUP").toUpperCase();
			containerClick(ClientMc.gameMode(), menu.containerId, slot, button, input, player);
			JsonObject out = Json.ok("container input sent");
			out.addProperty("containerId", menu.containerId);
			out.addProperty("slot", slot);
			out.addProperty("button", button);
			out.addProperty("input", input);
			return out;
		}));

		router.register("screen.containerButton", ctx -> ClientMc.call(() -> {
			requireControl();
			requireContainerScreen();
			LocalPlayer player = ClientMc.player();
			int button = ctx.getInt("button");
			ClientMc.gameMode().handleInventoryButtonClick(player.containerMenu.containerId, button);
			return Json.ok("container button " + button + " sent");
		}));

		router.register("screen.setText", ctx -> ClientMc.call(() -> {
			requireControl();
			Screen screen = requireScreen();
			ResolvedWidget resolved = resolveWidget(screen, ctx);
			GuiEventListener child = resolved.widget;
			if (!(child instanceof EditBox edit)) {
				throw RpcException.badRequest("Widget " + resolved.path + " is not an EditBox; use screen.typeText for focused custom fields.");
			}
			String value = ctx.getString("value");
			if (ctx.optBool("append", false)) edit.insertText(value); else edit.setValue(value);
			JsonObject out = Json.ok("text updated");
			out.addProperty("widgetPath", resolved.path);
			out.addProperty("value", edit.getValue());
			return out;
		}));

		router.register("screen.typeText", ctx -> ClientMc.call(() -> {
			requireControl();
			Screen screen = requireScreen();
			String text = ctx.getString("text");
			int modifiers = ctx.optInt("modifiers", 0);
			int accepted = 0;
			//? if >=26.2 {
			for (int offset = 0; offset < text.length();) {
				int codePoint = text.codePointAt(offset);
				if (screen.charTyped(new CharacterEvent(codePoint))) accepted++;
				offset += Character.charCount(codePoint);
			}
			//?} else {
			/*for (int i = 0; i < text.length(); i++) {
				if (screen.charTyped(text.charAt(i), modifiers)) accepted++;
			}*/
			//?}
			JsonObject out = Json.ok("typed text");
			out.addProperty("codePoints", text.codePointCount(0, text.length()));
			out.addProperty("accepted", accepted);
			return out;
		}));

			router.register("screen.key", ctx -> ClientMc.call(() -> {
			requireControl();
			Screen screen = requireScreen();
			int key = ctx.getInt("key");
			int scanCode = ctx.optInt("scanCode", 0);
			int modifiers = ctx.optInt("modifiers", 0);
			String action = ctx.optString("action", "press").toLowerCase();
			//? if >=26.2 {
			KeyEvent event = new KeyEvent(key, scanCode, modifiers);
			boolean handled = switch (action) {
				case "press" -> screen.keyPressed(event);
				case "release" -> screen.keyReleased(event);
				default -> throw RpcException.badRequest("action must be press or release.");
			};
			//?} else
			/*boolean handled = switch (action) {
				case "press" -> screen.keyPressed(key, scanCode, modifiers);
				case "release" -> screen.keyReleased(key, scanCode, modifiers);
				default -> throw RpcException.badRequest("action must be press or release.");
			};*/
			JsonObject out = Json.ok("screen key sent");
			out.addProperty("action", action);
			out.addProperty("handled", handled);
			return out;
		}));

		router.register("screen.mouse", ctx -> ClientMc.call(() -> {
			requireControl();
			Screen screen = requireScreen();
			double x = ctx.getDouble("x");
			double y = ctx.getDouble("y");
			int button = ctx.optInt("button", 0);
			int modifiers = ctx.optInt("modifiers", 0);
			String action = ctx.optString("action", "click").toLowerCase();
			boolean handled;
			//? if >=26.2 {
			MouseButtonEvent event = new MouseButtonEvent(x, y, new MouseButtonInfo(button, modifiers));
			handled = switch (action) {
				case "move" -> {
					screen.mouseMoved(x, y);
					yield true;
				}
				case "scroll" -> screen.mouseScrolled(x, y,
						ctx.optDouble("scrollX", 0), ctx.getDouble("scrollY"));
				case "press" -> screen.mouseClicked(event, ctx.optBool("doubleClick", false));
				case "release" -> screen.mouseReleased(event);
				case "click" -> {
					boolean pressed = screen.mouseClicked(event, ctx.optBool("doubleClick", false));
					screen.mouseReleased(event);
					yield pressed;
				}
				case "drag" -> screen.mouseDragged(event, ctx.optDouble("dragX", 0), ctx.optDouble("dragY", 0));
				default -> throw RpcException.badRequest("action must be click, press, release, drag, move, or scroll.");
			};
			//?} else {
			/*handled = switch (action) {
				case "move" -> {
					screen.mouseMoved(x, y);
					yield true;
				}
				case "scroll" -> screen.mouseScrolled(x, y,
						ctx.optDouble("scrollX", 0), ctx.getDouble("scrollY"));
				case "press" -> screen.mouseClicked(x, y, button);
				case "release" -> screen.mouseReleased(x, y, button);
				case "click" -> {
					boolean pressed = screen.mouseClicked(x, y, button);
					screen.mouseReleased(x, y, button);
					yield pressed;
				}
				case "drag" -> screen.mouseDragged(x, y, button, ctx.optDouble("dragX", 0), ctx.optDouble("dragY", 0));
				default -> throw RpcException.badRequest("action must be click, press, release, drag, move, or scroll.");
			};*/
			//?}
			JsonObject out = Json.ok("screen mouse action sent");
			out.addProperty("handled", handled);
			return out;
		}));

		router.register("screen.clickWidget", ctx -> ClientMc.call(() -> {
			requireControl();
			Screen screen = requireScreen();
			ResolvedWidget resolved = resolveWidget(screen, ctx);
			GuiEventListener child = resolved.widget;
			if (!(child instanceof AbstractWidget widget)) {
				throw RpcException.badRequest("Widget " + resolved.path + " has no rectangular click target.");
			}
			if (ctx.optBool("requireVisible", true) && !widget.visible) {
				throw RpcException.badRequest("Widget " + resolved.path + " is not visible.");
			}
			if (ctx.optBool("requireActive", true) && !widget.active) {
				throw RpcException.badRequest("Widget " + resolved.path + " is not active.");
			}
			double relativeX = unitInterval(ctx, "relativeX", 0.5);
			double relativeY = unitInterval(ctx, "relativeY", 0.5);
			double x = widget.getX() + widget.getWidth() * relativeX;
			double y = widget.getY() + widget.getHeight() * relativeY;
			JsonObject before = screenState(false);
			int button = ctx.optInt("button", 0);
			//? if >=26.2 {
			MouseButtonEvent event = new MouseButtonEvent(x, y, new MouseButtonInfo(button, 0));
			boolean handled = screen.mouseClicked(event, ctx.optBool("doubleClick", false));
			screen.mouseReleased(event);
			//?} else {
			/*boolean handled = screen.mouseClicked(x, y, button);
			screen.mouseReleased(x, y, button);*/
			//?}
			JsonObject out = Json.ok("widget clicked");
			out.addProperty("widgetPath", resolved.path);
			out.add("widget", widgetJson(resolved));
			out.addProperty("x", x);
			out.addProperty("y", y);
			out.addProperty("handled", handled);
			JsonObject after = screenState(false);
			out.addProperty("screenChanged", !before.get("fingerprint").getAsString().equals(after.get("fingerprint").getAsString()));
			out.add("before", compactScreenIdentity(before));
			out.add("after", compactScreenIdentity(after));
			return out;
		}));

		router.register("screen.close", ctx -> ClientMc.call(() -> {
			requireControl();
			Screen screen = currentScreen(ClientMc.mc());
			if (screen == null) return Json.ok("no screen was open");
			screen.onClose();
			return Json.ok("screen closed");
		}));
	}

	private static JsonObject findWidgets(RpcContext ctx) throws RpcException {
		Screen screen = requireScreen();
		List<ResolvedWidget> matches;
		if (ctx.has("widgetPath") || ctx.has("widgetIndex")) {
			String path = ctx.has("widgetPath") ? ctx.getString("widgetPath") : String.valueOf(ctx.getInt("widgetIndex"));
			matches = List.of(resolveWidgetPath(screen, path));
		} else {
			matches = matchingWidgets(screen, ctx);
		}
		JsonArray widgets = new JsonArray();
		for (ResolvedWidget match : matches) widgets.add(widgetJson(match));
		JsonObject out = new JsonObject();
		out.addProperty("screenClass", screen.getClass().getName());
		out.addProperty("title", screen.getTitle().getString());
		out.addProperty("count", matches.size());
		out.add("widgets", widgets);
		return out;
	}

	private static JsonObject waitState(RpcContext ctx) throws RpcException {
		int timeoutMs = Math.max(100, Math.min(30_000, ctx.optInt("timeoutMs", 3_000)));
		int requiredStableReads = Math.max(1, Math.min(10, ctx.optInt("stableReads", 2)));
		long started = System.nanoTime();
		long deadline = started + timeoutMs * 1_000_000L;
		String previousFingerprint = null;
		int stableReads = 0;
		JsonObject last = null;
		boolean completeSlots = ctx.has("containerConditions")
				&& ctx.params().get("containerConditions").isJsonArray()
				&& !ctx.params().getAsJsonArray("containerConditions").isEmpty();
		while (System.nanoTime() <= deadline) {
			last = ClientMc.call(() -> screenState(completeSlots));
			if (screenConditionMatches(ctx, last)) {
				String fingerprint = screenStabilitySignature(last, ctx.optString("stabilityScope", "full"));
				stableReads = fingerprint.equals(previousFingerprint) ? stableReads + 1 : 1;
				previousFingerprint = fingerprint;
				if (stableReads >= requiredStableReads) {
					JsonObject result = new JsonObject();
					result.addProperty("matched", true);
					result.addProperty("waitedMs", elapsedMs(started));
					result.addProperty("stableReads", stableReads);
					result.add("state", completeSlots
							? conditionSnapshot(last, ctx.params().getAsJsonArray("containerConditions")) : last);
					return result;
				}
			} else {
				stableReads = 0;
				previousFingerprint = null;
			}
			sleep(50);
		}
		JsonObject data = new JsonObject();
		data.addProperty("waitedMs", elapsedMs(started));
		if (last != null) data.add("lastState", completeSlots
				? conditionSnapshot(last, ctx.params().getAsJsonArray("containerConditions")) : last);
		throw new RpcException("screen_wait_timeout", "Screen did not reach the requested stable state.", data);
	}

	private static JsonObject conditionSnapshot(JsonObject state, JsonArray conditions) {
		JsonObject compact = state.deepCopy();
		compact.remove("widgets");
		compact.remove("widgetCount");
		if (!compact.has("container")) return compact;
		Set<Integer> wantedSlots = new HashSet<>();
		Set<Integer> wantedData = new HashSet<>();
		for (JsonElement element : conditions) {
			JsonObject condition = element.getAsJsonObject();
			if (condition.has("slot")) wantedSlots.add(condition.get("slot").getAsInt());
			if (condition.has("dataIndex")) wantedData.add(condition.get("dataIndex").getAsInt());
		}
		JsonObject container = compact.getAsJsonObject("container");
		JsonArray slots = new JsonArray();
		for (JsonElement element : container.getAsJsonArray("slots")) {
			if (wantedSlots.contains(element.getAsJsonObject().get("menuSlot").getAsInt())) slots.add(element);
		}
		container.add("slots", slots);
		JsonArray data = new JsonArray();
		if (container.has("data")) {
			for (JsonElement element : container.getAsJsonArray("data")) {
				if (wantedData.contains(element.getAsJsonObject().get("index").getAsInt())) data.add(element);
			}
		}
		container.add("data", data);
		compact.add("matchedConditions", conditions.deepCopy());
		return compact;
	}

	private static boolean screenConditionMatches(RpcContext ctx, JsonObject state) {
		if (ctx.has("open") && state.get("open").getAsBoolean() != ctx.optBool("open", true)) return false;
		if (ctx.has("screenClassContains")
				&& (!state.has("class") || !state.get("class").getAsString().contains(ctx.optString("screenClassContains", "")))) return false;
		if (ctx.has("titleContains")
				&& (!state.has("title") || !state.get("title").getAsString().contains(ctx.optString("titleContains", "")))) return false;
		if (ctx.has("containerClassContains")
				&& (!state.has("container") || !state.getAsJsonObject("container").get("class").getAsString()
				.contains(ctx.optString("containerClassContains", "")))) return false;
		if (ctx.has("menuType")
				&& (!state.has("container") || !state.getAsJsonObject("container").has("menuType")
				|| !state.getAsJsonObject("container").get("menuType").getAsString()
				.equals(ctx.optString("menuType", "")))) return false;
		if (ctx.has("containerConditions")) {
			if (!state.has("container") || !ctx.params().get("containerConditions").isJsonArray()) return false;
			if (!failedConditions(state.getAsJsonObject("container"),
					ctx.params().getAsJsonArray("containerConditions")).isEmpty()) return false;
		}
		return !ctx.has("differentFromFingerprint")
				|| !state.get("fingerprint").getAsString().equals(ctx.optString("differentFromFingerprint", ""));
	}

	private static String screenStabilitySignature(JsonObject state, String scope) {
		if (scope.equalsIgnoreCase("full")) return state.get("fingerprint").getAsString();
		JsonObject stable = state.deepCopy();
		if (scope.equalsIgnoreCase("identity")) {
			JsonObject identity = new JsonObject();
			identity.addProperty("open", state.get("open").getAsBoolean());
			if (state.has("class")) identity.add("class", state.get("class"));
			if (state.has("title")) identity.add("title", state.get("title"));
			if (state.has("container")) {
				JsonObject source = state.getAsJsonObject("container");
				JsonObject container = new JsonObject();
				container.add("id", source.get("id"));
				container.add("class", source.get("class"));
				if (source.has("menuType")) container.add("menuType", source.get("menuType"));
				identity.add("container", container);
			}
			return sha256(identity.toString());
		}
		if (scope.equalsIgnoreCase("slots")) {
			if (stable.has("container")) {
				stable.getAsJsonObject("container").remove("data");
				stable.getAsJsonObject("container").remove("stateId");
			}
			stable.remove("fingerprint");
			return sha256(stable.toString());
		}
		return state.get("fingerprint").getAsString();
	}

	private static JsonObject probeSlot(RpcContext ctx) throws RpcException {
		LocalPlayer player = ClientMc.player();
		AbstractContainerMenu menu = player.containerMenu;
		int slotIndex = ctx.getInt("slot");
		if (!menu.isValidSlotIndex(slotIndex)) throw RpcException.badRequest("Container slot is not valid: " + slotIndex);
		String wanted = normalizeItemId(ctx.getString("itemId"));
		Item item = null;
		for (Item candidate : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
			if (net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(candidate).toString().equals(wanted)) {
				item = candidate;
				break;
			}
		}
		if (item == null) throw RpcException.notFound("Unknown item id: " + wanted);
		ItemStack sample = new ItemStack(item, Math.max(1, Math.min(99, ctx.optInt("count", 1))));
		Slot slot = menu.slots.get(slotIndex);
		JsonObject result = new JsonObject();
		result.addProperty("containerId", menu.containerId);
		result.addProperty("stateId", menu.getStateId());
		result.addProperty("slot", slotIndex);
		result.addProperty("itemId", wanted);
		result.addProperty("mayPlace", slot.mayPlace(sample));
		result.addProperty("mayPickup", slot.mayPickup(player));
		result.addProperty("maxStackSize", slot.getMaxStackSize(sample));
		result.addProperty("slotClass", slot.getClass().getName());
		result.addProperty("containerClass", slot.container.getClass().getName());
		return result;
	}

	private static JsonObject containerTransaction(RpcContext ctx) throws RpcException {
		requireControl();
		JsonArray steps = ctx.params().has("steps") && ctx.params().get("steps").isJsonArray()
				? ctx.params().getAsJsonArray("steps") : null;
		if (steps == null || steps.isEmpty()) throw RpcException.badRequest("steps must be a non-empty array.");
		if (steps.size() > 64) throw RpcException.badRequest("Container transactions are capped at 64 native inputs.");
		JsonObject before = ClientMc.call(() -> screenState(true));
		JsonObject beforeContainer = before.getAsJsonObject("container");
		validateContainerIdentity(ctx, before);
		if (ctx.optBool("requireEmptyCarriedBefore", true)
				&& !beforeContainer.getAsJsonObject("carried").has("empty")) {
			throw new RpcException("carried_item_present", "Transaction requires an empty carried cursor stack.",
					transactionSnapshotSummary(before));
		}
		JsonArray preconditions = ctx.params().has("preconditions")
				? ctx.params().getAsJsonArray("preconditions") : new JsonArray();
		JsonArray failedPreconditions = failedConditions(beforeContainer, preconditions);
		if (!failedPreconditions.isEmpty()) {
			JsonObject data = new JsonObject();
			data.add("before", transactionSnapshotSummary(before));
			data.add("failedPreconditions", failedPreconditions);
			throw new RpcException("container_precondition_failed", "Container slot preconditions do not match.", data);
		}

		int containerId = beforeContainer.get("id").getAsInt();
		String containerClass = beforeContainer.get("class").getAsString();
		ClientMc.call(() -> {
			LocalPlayer player = ClientMc.player();
			AbstractContainerMenu menu = player.containerMenu;
			if (menu.containerId != containerId || !menu.getClass().getName().equals(containerClass)) {
				throw RpcException.badRequest("Container changed before transaction inputs were sent.");
			}
			for (JsonElement element : steps) {
				JsonObject step = element.getAsJsonObject();
				if (!step.has("slot")) throw RpcException.badRequest("Every transaction step requires slot.");
				int slot = step.get("slot").getAsInt();
				if (slot != AbstractContainerMenu.SLOT_CLICKED_OUTSIDE && !menu.isValidSlotIndex(slot)) {
					throw RpcException.badRequest("Invalid transaction slot: " + slot);
				}
				containerClick(ClientMc.gameMode(), containerId, slot,
						step.has("button") ? step.get("button").getAsInt() : 0,
						step.has("input") ? step.get("input").getAsString().toUpperCase() : "PICKUP", player);
			}
			return new JsonObject();
		});

		int timeoutMs = Math.max(100, Math.min(10_000, ctx.optInt("timeoutMs", 2_000)));
		int requiredStableReads = Math.max(1, Math.min(10, ctx.optInt("stableReads", 3)));
		JsonArray postconditions = ctx.params().has("postconditions")
				? ctx.params().getAsJsonArray("postconditions") : new JsonArray();
		boolean requireChange = ctx.optBool("requireChange", true);
		boolean requireEmptyAfter = ctx.optBool("requireEmptyCarriedAfter", true);
		long started = System.nanoTime();
		long deadline = started + timeoutMs * 1_000_000L;
		int stableReads = 0;
		JsonObject last = null;
		while (System.nanoTime() <= deadline) {
			last = ClientMc.call(() -> screenState(true));
			if (!sameContainer(last, containerId, containerClass)) {
				return transactionFailure(ctx, before, last, steps, "container_changed",
						"Container changed while awaiting confirmation.");
			}
			JsonObject currentContainer = last.getAsJsonObject("container");
			boolean changed = !currentContainer.toString().equals(beforeContainer.toString());
			boolean conditionsOk = failedConditions(currentContainer, postconditions).isEmpty();
			boolean carriedOk = !requireEmptyAfter || currentContainer.getAsJsonObject("carried").has("empty");
			boolean confirmationCandidate = (!requireChange || changed) && conditionsOk && carriedOk;
			stableReads = confirmationCandidate ? stableReads + 1 : 0;
			if (stableReads >= requiredStableReads) {
				JsonObject result = new JsonObject();
				result.addProperty("ok", true);
				result.addProperty("confirmed", true);
				result.addProperty("waitedMs", elapsedMs(started));
				result.addProperty("stableReads", stableReads);
				result.add("before", transactionSnapshotSummary(before));
				result.add("after", transactionSnapshotSummary(last));
				result.add("deltas", containerDeltas(beforeContainer, currentContainer));
				return result;
			}
			sleep(50);
		}
		return transactionFailure(ctx, before, last, steps, "confirmation_timeout",
				"Container transaction did not reach its requested stable postconditions.");
	}

	private static JsonObject moveItem(RpcContext ctx) throws RpcException {
		requireControl();
		int sourceIndex = ctx.getInt("sourceSlot");
		int targetIndex = ctx.getInt("targetSlot");
		if (sourceIndex == targetIndex) throw RpcException.badRequest("sourceSlot and targetSlot must differ.");
		JsonObject snapshot = ClientMc.call(() -> screenState(true));
		validateContainerIdentity(ctx, snapshot);
		MovePlan plan = ClientMc.call(() -> {
			LocalPlayer player = ClientMc.player();
			AbstractContainerMenu menu = player.containerMenu;
			if (!menu.isValidSlotIndex(sourceIndex) || !menu.isValidSlotIndex(targetIndex)) {
				throw RpcException.badRequest("sourceSlot or targetSlot is invalid for the active container.");
			}
			Slot source = menu.slots.get(sourceIndex);
			Slot target = menu.slots.get(targetIndex);
			ItemStack sourceStack = source.getItem();
			ItemStack targetStack = target.getItem();
			if (sourceStack.isEmpty()) throw RpcException.badRequest("sourceSlot is empty.");
			if (!source.mayPickup(player)) throw RpcException.badRequest("sourceSlot refuses player pickup.");
			if (!target.mayPlace(sourceStack)) throw RpcException.badRequest("targetSlot refuses the source item.");
			if (!targetStack.isEmpty() && !ItemStack.isSameItemSameComponents(sourceStack, targetStack)) {
				throw RpcException.badRequest("targetSlot contains a different item or component set.");
			}
			int targetCount = targetStack.isEmpty() ? 0 : targetStack.getCount();
			int capacity = Math.max(0, target.getMaxStackSize(sourceStack) - targetCount);
			int requested = ctx.has("count") ? ctx.getInt("count") : sourceStack.getCount();
			if (requested <= 0 || requested > sourceStack.getCount()) {
				throw RpcException.badRequest("count must be between 1 and the source stack count.");
			}
			if (requested > capacity) throw RpcException.badRequest("targetSlot lacks capacity for the requested count.");
			if (requested < sourceStack.getCount() && requested > 62) {
				throw RpcException.badRequest("A partial move is capped at 62 items per confirmed transaction.");
			}
			int remainder = sourceStack.getCount() - requested;
			int remainderSlot = sourceIndex;
			ItemStack remainderBefore = sourceStack.copy();
			if (remainder > 0 && (!source.mayPlace(sourceStack) || source.getMaxStackSize(sourceStack) < remainder)) {
				remainderSlot = findRemainderSlot(menu, player, sourceIndex, targetIndex, sourceStack, remainder);
				if (remainderSlot < 0) {
					throw RpcException.badRequest(
							"The source slot cannot accept its remainder and no ordinary player slot can safely hold it.");
				}
				remainderBefore = menu.slots.get(remainderSlot).getItem().copy();
			}
			return new MovePlan(sourceStack.copy(), targetStack.copy(), requested, remainderSlot, remainderBefore);
		});

		JsonObject sourceBefore = LocalPlayerHandlers.itemJson(plan.sourceBefore);
		JsonObject targetBefore = LocalPlayerHandlers.itemJson(plan.targetBefore);
		JsonObject params = new JsonObject();
		JsonArray steps = new JsonArray();
		steps.add(containerStep(sourceIndex, 0));
		if (plan.count == plan.sourceBefore.getCount()) {
			steps.add(containerStep(targetIndex, 0));
		} else {
			for (int i = 0; i < plan.count; i++) steps.add(containerStep(targetIndex, 1));
			steps.add(containerStep(plan.remainderSlot, 0));
		}
		params.add("steps", steps);
		JsonObject container = snapshot.getAsJsonObject("container");
		params.addProperty("expectedContainerId", container.get("id").getAsInt());
		params.addProperty("expectedContainerClass", container.get("class").getAsString());
		if (container.has("menuType")) params.addProperty("expectedMenuType", container.get("menuType").getAsString());
		params.addProperty("cleanupSlot", plan.remainderSlot);
		params.addProperty("timeoutMs", ctx.optInt("timeoutMs", 2_000));
		params.addProperty("stableReads", ctx.optInt("stableReads", 3));

		JsonArray preconditions = new JsonArray();
		preconditions.add(itemCondition(sourceIndex, sourceBefore, plan.sourceBefore.getCount()));
		preconditions.add(itemCondition(targetIndex, targetBefore, plan.targetBefore.getCount()));
		params.add("preconditions", preconditions);
		JsonArray postconditions = new JsonArray();
		int remainder = plan.sourceBefore.getCount() - plan.count;
		if (plan.remainderSlot == sourceIndex) {
			postconditions.add(itemCondition(sourceIndex, sourceBefore, remainder));
		} else {
			postconditions.add(itemCondition(sourceIndex, sourceBefore, 0));
			postconditions.add(itemCondition(plan.remainderSlot, sourceBefore,
					plan.remainderBefore.getCount() + remainder));
		}
		params.add("postconditions", postconditions);

		JsonObject result = containerTransaction(new RpcContext("screen.containerTransaction", params));
		JsonObject move = new JsonObject();
		move.addProperty("sourceSlot", sourceIndex);
		move.addProperty("targetSlot", targetIndex);
		move.addProperty("count", plan.count);
		if (plan.remainderSlot != sourceIndex) move.addProperty("remainderSlot", plan.remainderSlot);
		move.addProperty("itemId", sourceBefore.get("id").getAsString());
		if (sourceBefore.has("componentFingerprint")) {
			move.addProperty("componentFingerprint", sourceBefore.get("componentFingerprint").getAsString());
		}
		result.add("move", move);
		return result;
	}

	private static int findRemainderSlot(AbstractContainerMenu menu, LocalPlayer player, int sourceIndex,
			int targetIndex, ItemStack sourceStack, int remainder) {
		int emptyCandidate = -1;
		for (int index = 0; index < menu.slots.size(); index++) {
			if (index == sourceIndex || index == targetIndex) continue;
			Slot slot = menu.slots.get(index);
			if (slot.container != player.getInventory()
					|| slot.getContainerSlot() < 0 || slot.getContainerSlot() > 35
					|| !slot.mayPlace(sourceStack)) continue;
			ItemStack existing = slot.getItem();
			if (!existing.isEmpty() && !ItemStack.isSameItemSameComponents(sourceStack, existing)) continue;
			int existingCount = existing.isEmpty() ? 0 : existing.getCount();
			if (slot.getMaxStackSize(sourceStack) - existingCount < remainder) continue;
			if (!existing.isEmpty()) return index;
			if (emptyCandidate < 0) emptyCandidate = index;
		}
		return emptyCandidate;
	}

	private static JsonObject transferItems(RpcContext ctx) throws RpcException {
		requireControl();
		String from = ctx.optString("from", "player").toLowerCase();
		String to = ctx.optString("to", "menu").toLowerCase();
		if ((!from.equals("player") && !from.equals("menu"))
				|| (!to.equals("player") && !to.equals("menu")) || from.equals(to)) {
			throw RpcException.badRequest("from and to must be different values chosen from player or menu.");
		}
		int maxOperations = Math.max(1, Math.min(64, ctx.optInt("maxOperations", 32)));
		Integer requestedCount = ctx.has("count") ? ctx.getInt("count") : null;
		if (requestedCount != null && requestedCount <= 0) throw RpcException.badRequest("count must be positive.");
		boolean requireExactCount = ctx.optBool("requireExactCount", true);
		JsonObject snapshot = ClientMc.call(() -> screenState(true));
		validateContainerIdentity(ctx, snapshot);
		TransferPlan plan = ClientMc.call(() -> planTransfer(ctx, from, to, requestedCount, maxOperations));
		if (requestedCount != null && requireExactCount && plan.plannedCount < requestedCount) {
			JsonObject data = plan.toJson();
			data.addProperty("requestedCount", requestedCount);
			String code = plan.partialOutputBlocked ? "exact_count_unrepresentable" : "insufficient_transfer_capacity";
			String message = plan.partialOutputBlocked
					? "The requested exact count would require removing an output-slot remainder that native clicks cannot return. No mutation was sent."
					: "The complete requested count cannot be transferred without guessing or overflowing a target slot.";
			throw new RpcException(code, message, data);
		}
		if (plan.moves.isEmpty()) {
			throw new RpcException("no_transfer_path", "No matching source items can be accepted by the requested target scope.",
					plan.toJson());
		}
		if (ctx.optBool("dryRun", false)) {
			JsonObject result = plan.toJson();
			result.addProperty("dryRun", true);
			result.addProperty("completed", false);
			return result;
		}

		JsonArray completedMoves = new JsonArray();
		int moved = 0;
		for (PlannedMove move : plan.moves) {
			JsonObject params = new JsonObject();
			params.addProperty("sourceSlot", move.sourceSlot);
			params.addProperty("targetSlot", move.targetSlot);
			params.addProperty("count", move.count);
			JsonObject container = snapshot.getAsJsonObject("container");
			params.addProperty("expectedContainerId", container.get("id").getAsInt());
			params.addProperty("expectedContainerClass", container.get("class").getAsString());
			if (container.has("menuType")) params.addProperty("expectedMenuType", container.get("menuType").getAsString());
			params.addProperty("timeoutMs", ctx.optInt("timeoutMs", 2_000));
			params.addProperty("stableReads", ctx.optInt("stableReads", 2));
			try {
				JsonObject confirmation = moveItem(new RpcContext("screen.moveItem", params));
				int actualCount = actualSourceRemoval(confirmation.getAsJsonArray("deltas"), move);
				JsonObject completed = move.toJson();
				completed.addProperty("confirmed", confirmation.get("confirmed").getAsBoolean());
				completed.addProperty("actualCount", actualCount);
				completed.add("deltas", confirmation.getAsJsonArray("deltas").deepCopy());
				completedMoves.add(completed);
				moved += actualCount;
				if (actualCount != move.count) {
					JsonObject result = transferResult(plan, completedMoves, moved, false);
					JsonObject error = new JsonObject();
					error.addProperty("code", "transfer_count_mismatch");
					error.addProperty("message", "The synchronized source-slot delta differs from the planned move count.");
					error.addProperty("plannedCount", move.count);
					error.addProperty("actualCount", actualCount);
					result.add("failure", error);
					return result;
				}
			} catch (RpcException failure) {
				JsonObject result = transferResult(plan, completedMoves, moved, false);
				JsonObject error = new JsonObject();
				error.addProperty("code", failure.code());
				error.addProperty("message", failure.getMessage());
				if (failure.data() != null) error.add("data", failure.data().deepCopy());
				result.add("failure", error);
				return result;
			}
		}
		boolean fullyCompleted = plan.requestedCount != null
				? moved >= plan.requestedCount : !plan.operationLimitReached;
		return transferResult(plan, completedMoves, moved, fullyCompleted);
	}

	private static TransferPlan planTransfer(RpcContext ctx, String from, String to,
			Integer requestedCount, int maxOperations) throws RpcException {
		LocalPlayer player = ClientMc.player();
		AbstractContainerMenu menu = player.containerMenu;
		Set<Integer> sourceFilter = slotFilter(ctx, "sourceSlots");
		Set<Integer> targetFilter = slotFilter(ctx, "targetSlots");
		String itemId = ctx.has("itemId") ? normalizeItemId(ctx.getString("itemId")) : null;
		String componentFingerprint = ctx.has("componentFingerprint") ? ctx.getString("componentFingerprint") : null;
		boolean includeEquipment = ctx.optBool("includeEquipment", false);
		List<ItemStack> simulated = new ArrayList<>();
		for (Slot slot : menu.slots) simulated.add(slot.getItem().copy());
		List<Integer> sources = new ArrayList<>();
		List<Integer> targets = new ArrayList<>();
		for (int index = 0; index < menu.slots.size(); index++) {
			Slot slot = menu.slots.get(index);
			boolean playerSlot = slot.container == player.getInventory();
			boolean ordinaryPlayerSlot = playerSlot && slot.getContainerSlot() >= 0 && slot.getContainerSlot() <= 35;
			if ((from.equals("player") ? playerSlot : !playerSlot)
					&& (includeEquipment || !playerSlot || ordinaryPlayerSlot)
					&& (sourceFilter.isEmpty() || sourceFilter.contains(index))) sources.add(index);
			if ((to.equals("player") ? playerSlot : !playerSlot)
					&& (includeEquipment || !playerSlot || ordinaryPlayerSlot)
					&& (targetFilter.isEmpty() || targetFilter.contains(index))) targets.add(index);
		}
		List<PlannedMove> moves = new ArrayList<>();
		boolean partialOutputBlocked = false;
		int remaining = requestedCount == null ? Integer.MAX_VALUE : requestedCount;
		for (int sourceIndex : sources) {
			if (remaining <= 0 || moves.size() >= maxOperations) break;
			Slot sourceSlot = menu.slots.get(sourceIndex);
			ItemStack sourceStack = simulated.get(sourceIndex);
			if (sourceStack.isEmpty() || !sourceSlot.mayPickup(player)) continue;
			String sourceId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(sourceStack.getItem()).toString();
			if (itemId != null && !itemId.equals(sourceId)) continue;
			JsonObject sourceJson = LocalPlayerHandlers.itemJson(sourceStack);
			if (componentFingerprint != null && (!sourceJson.has("componentFingerprint")
					|| !componentFingerprint.equals(sourceJson.get("componentFingerprint").getAsString()))) continue;
			List<Integer> orderedTargets = new ArrayList<>(targets);
			orderedTargets.sort((left, right) -> Boolean.compare(simulated.get(left).isEmpty(), simulated.get(right).isEmpty()));
			for (int targetIndex : orderedTargets) {
				if (remaining <= 0 || sourceStack.isEmpty() || moves.size() >= maxOperations) break;
				if (targetIndex == sourceIndex) continue;
				Slot targetSlot = menu.slots.get(targetIndex);
				ItemStack targetStack = simulated.get(targetIndex);
				if (!targetSlot.isActive() || !targetSlot.mayPlace(sourceStack)) continue;
				if (!targetStack.isEmpty() && !ItemStack.isSameItemSameComponents(sourceStack, targetStack)) continue;
				int capacity = Math.max(0, targetSlot.getMaxStackSize(sourceStack) - targetStack.getCount());
				if (capacity <= 0) continue;
				int count = Math.min(Math.min(sourceStack.getCount(), capacity), remaining);
				if (count < sourceStack.getCount() && count > 62) count = 62;
				if (count <= 0) continue;
				int remainder = sourceStack.getCount() - count;
				if (remainder > 0
						&& (!sourceSlot.mayPlace(sourceStack) || sourceSlot.getMaxStackSize(sourceStack) < remainder)) {
					partialOutputBlocked = true;
					continue;
				}
				moves.add(new PlannedMove(sourceIndex, targetIndex, count, sourceId,
						sourceJson.has("componentFingerprint") ? sourceJson.get("componentFingerprint").getAsString() : null));
				if (targetStack.isEmpty()) {
					ItemStack placed = sourceStack.copy();
					placed.setCount(count);
					simulated.set(targetIndex, placed);
				} else {
					targetStack.grow(count);
				}
				sourceStack.shrink(count);
				remaining -= count;
			}
		}
		int plannedCount = moves.stream().mapToInt(move -> move.count).sum();
		boolean operationLimitReached = moves.size() >= maxOperations
				&& (requestedCount == null || plannedCount < requestedCount);
		return new TransferPlan(from, to, requestedCount, plannedCount, maxOperations,
				operationLimitReached, partialOutputBlocked, moves);
	}

	private static int actualSourceRemoval(JsonArray deltas, PlannedMove move) {
		for (JsonElement element : deltas) {
			JsonObject delta = element.getAsJsonObject();
			if (!delta.has("kind") || !"slot".equals(delta.get("kind").getAsString())
					|| !delta.has("slot") || delta.get("slot").getAsInt() != move.sourceSlot) continue;
			JsonObject before = delta.getAsJsonObject("before");
			JsonObject after = delta.getAsJsonObject("after");
			int beforeCount = matchingItemCount(before, move.itemId, move.componentFingerprint);
			int afterCount = matchingItemCount(after, move.itemId, move.componentFingerprint);
			return Math.max(0, beforeCount - afterCount);
		}
		return 0;
	}

	private static int matchingItemCount(JsonObject item, String itemId, String componentFingerprint) {
		if (item == null || item.has("empty") || !item.has("id") || !itemId.equals(item.get("id").getAsString())) return 0;
		if (componentFingerprint != null && (!item.has("componentFingerprint")
				|| !componentFingerprint.equals(item.get("componentFingerprint").getAsString()))) return 0;
		return item.has("count") ? item.get("count").getAsInt() : 0;
	}

	private static Set<Integer> slotFilter(RpcContext ctx, String name) throws RpcException {
		Set<Integer> slots = new HashSet<>();
		if (!ctx.has(name)) return slots;
		JsonElement value = ctx.params().get(name);
		if (!value.isJsonArray()) throw RpcException.badRequest(name + " must be an array of menu slot indexes.");
		for (JsonElement element : value.getAsJsonArray()) slots.add(element.getAsInt());
		return slots;
	}

	private static JsonObject transferResult(TransferPlan plan, JsonArray completedMoves, int moved, boolean completed) {
		JsonObject result = new JsonObject();
		result.addProperty("completed", completed);
		result.addProperty("from", plan.from);
		result.addProperty("to", plan.to);
		if (plan.requestedCount != null) result.addProperty("requestedCount", plan.requestedCount);
		result.addProperty("plannedCount", plan.plannedCount);
		result.addProperty("movedCount", moved);
		result.addProperty("operationLimitReached", plan.operationLimitReached);
		result.addProperty("operationCount", completedMoves.size());
		result.add("moves", completedMoves);
		return result;
	}

	private record PlannedMove(int sourceSlot, int targetSlot, int count, String itemId,
			String componentFingerprint) {
		JsonObject toJson() {
			JsonObject result = new JsonObject();
			result.addProperty("sourceSlot", sourceSlot);
			result.addProperty("targetSlot", targetSlot);
			result.addProperty("count", count);
			result.addProperty("itemId", itemId);
			if (componentFingerprint != null) result.addProperty("componentFingerprint", componentFingerprint);
			return result;
		}
	}

	private record TransferPlan(String from, String to, Integer requestedCount, int plannedCount,
			int maxOperations, boolean operationLimitReached, boolean partialOutputBlocked, List<PlannedMove> moves) {
		JsonObject toJson() {
			JsonObject result = new JsonObject();
			result.addProperty("from", from);
			result.addProperty("to", to);
			if (requestedCount != null) result.addProperty("requestedCount", requestedCount);
			result.addProperty("plannedCount", plannedCount);
			result.addProperty("maxOperations", maxOperations);
			result.addProperty("operationLimitReached", operationLimitReached);
			result.addProperty("partialOutputBlocked", partialOutputBlocked);
			JsonArray plannedMoves = new JsonArray();
			for (PlannedMove move : moves) plannedMoves.add(move.toJson());
			result.add("moves", plannedMoves);
			return result;
		}
	}

	private static JsonObject containerStep(int slot, int button) {
		JsonObject step = new JsonObject();
		step.addProperty("slot", slot);
		step.addProperty("button", button);
		step.addProperty("input", "PICKUP");
		return step;
	}

	private static JsonObject itemCondition(int slot, JsonObject item, int count) {
		JsonObject condition = new JsonObject();
		condition.addProperty("slot", slot);
		if (count <= 0) {
			condition.addProperty("empty", true);
			return condition;
		}
		condition.addProperty("itemId", item.get("id").getAsString());
		condition.addProperty("count", count);
		if (item.has("componentFingerprint")) {
			condition.addProperty("componentFingerprint", item.get("componentFingerprint").getAsString());
		}
		return condition;
	}

	private static JsonObject screenState(boolean includeEmptySlots) throws RpcException {
		Minecraft mc = ClientMc.mc();
		Screen screen = currentScreen(mc);
		JsonObject out = new JsonObject();
		JsonArray canonicalSlots = null;
		out.addProperty("open", screen != null);
		out.addProperty("containerScreen", screen instanceof AbstractContainerScreen<?>);
		if (screen != null) {
			out.addProperty("class", screen.getClass().getName());
			out.addProperty("title", screen.getTitle().getString());
			out.addProperty("width", screen.width);
			out.addProperty("height", screen.height);
			JsonArray widgets = new JsonArray();
			IdentityHashMap<GuiEventListener, Boolean> visited = new IdentityHashMap<>();
			for (int i = 0; i < screen.children().size(); i++) {
				appendWidget(widgets, screen.children().get(i), String.valueOf(i), null, 0, visited);
			}
			out.add("widgets", widgets);
			out.addProperty("widgetCount", widgets.size());
		}

		LocalPlayer player = mc.player;
		if (player != null) {
			AbstractContainerMenu menu = player.containerMenu;
			JsonObject container = new JsonObject();
			container.addProperty("id", menu.containerId);
			container.addProperty("class", menu.getClass().getName());
			String menuType = safeMenuType(menu);
			if (menuType != null) container.addProperty("menuType", menuType);
			container.addProperty("stateId", menu.getStateId());
			JsonArray dataValues = new JsonArray();
			List<DataSlot> dataSlots = ((AbstractContainerMenuAccessor) menu).mcpfabric$getDataSlots();
			for (int i = 0; i < dataSlots.size(); i++) {
				JsonObject data = new JsonObject();
				data.addProperty("index", i);
				data.addProperty("value", dataSlots.get(i).get());
				dataValues.add(data);
			}
			container.add("data", dataValues);
			container.add("carried", LocalPlayerHandlers.itemJson(menu.getCarried()));
			Integer guiLeft = null;
			Integer guiTop = null;
			if (screen instanceof AbstractContainerScreen<?> containerScreen) {
				AbstractContainerScreenAccessor accessor = (AbstractContainerScreenAccessor) containerScreen;
				guiLeft = accessor.mcpfabric$getLeftPos();
				guiTop = accessor.mcpfabric$getTopPos();
				JsonObject origin = new JsonObject();
				origin.addProperty("x", guiLeft);
				origin.addProperty("y", guiTop);
				container.add("screenOrigin", origin);
			}
			JsonArray slots = new JsonArray();
			canonicalSlots = new JsonArray();
			for (int i = 0; i < menu.slots.size(); i++) {
				Slot slot = menu.slots.get(i);
				JsonObject slotJson = new JsonObject();
				slotJson.addProperty("menuSlot", i);
				slotJson.addProperty("containerSlot", slot.getContainerSlot());
				slotJson.addProperty("slotClass", slot.getClass().getName());
				slotJson.addProperty("containerClass", slot.container.getClass().getName());
				slotJson.addProperty("x", slot.x);
				slotJson.addProperty("y", slot.y);
				if (guiLeft != null && guiTop != null) {
					slotJson.addProperty("screenX", guiLeft + slot.x);
					slotJson.addProperty("screenY", guiTop + slot.y);
					slotJson.addProperty("screenWidth", 16);
					slotJson.addProperty("screenHeight", 16);
				}
				slotJson.addProperty("active", slot.isActive());
				slotJson.addProperty("mayPickup", slot.mayPickup(player));
				slotJson.addProperty("maxStackSize", slot.getMaxStackSize());
				boolean playerInventory = slot.container == player.getInventory();
				slotJson.addProperty("playerInventory", playerInventory);
				slotJson.addProperty("role", playerInventory
						? playerInventoryRole(slot.getContainerSlot()) : "menu");
				slotJson.add("item", LocalPlayerHandlers.itemJson(slot.getItem()));
				canonicalSlots.add(slotJson.deepCopy());
				if (includeEmptySlots || slot.hasItem()) slots.add(slotJson);
			}
			container.add("slots", slots);
			out.add("container", container);
		}
		JsonObject canonicalState = out.deepCopy();
		if (canonicalSlots != null) canonicalState.getAsJsonObject("container").add("slots", canonicalSlots);
		out.addProperty("fingerprint", sha256(canonicalState.toString()));
		return out;
	}

	private static void appendWidget(JsonArray output, GuiEventListener child, String path,
			String parentPath, int depth, IdentityHashMap<GuiEventListener, Boolean> visited) {
		if (visited.put(child, Boolean.TRUE) != null) return;
		JsonObject widgetJson = new JsonObject();
		widgetJson.addProperty("path", path);
		if (parentPath != null) widgetJson.addProperty("parentPath", parentPath);
		widgetJson.addProperty("depth", depth);
		if (depth == 0) widgetJson.addProperty("index", Integer.parseInt(path));
		widgetJson.addProperty("class", child.getClass().getName());
		widgetJson.addProperty("focused", child.isFocused());
		if (child instanceof AbstractWidget widget) {
			widgetJson.addProperty("message", widget.getMessage().getString());
			widgetJson.addProperty("x", widget.getX());
			widgetJson.addProperty("y", widget.getY());
			widgetJson.addProperty("width", widget.getWidth());
			widgetJson.addProperty("height", widget.getHeight());
			widgetJson.addProperty("active", widget.active);
			widgetJson.addProperty("visible", widget.visible);
		}
		if (child instanceof EditBox edit) widgetJson.addProperty("value", edit.getValue());
		output.add(widgetJson);
		if (child instanceof ContainerEventHandler container) {
			List<? extends GuiEventListener> children = container.children();
			for (int i = 0; i < children.size(); i++) {
				appendWidget(output, children.get(i), path + "/" + i, path, depth + 1, visited);
			}
		}
	}

	private static String playerInventoryRole(int slot) {
		if (slot >= 0 && slot <= 8) return "player_hotbar";
		if (slot >= 9 && slot <= 35) return "player_main";
		if (slot >= 36 && slot <= 39) return "player_armor";
		if (slot == 40) return "player_offhand";
		return "player_other";
	}

	private static String safeMenuType(AbstractContainerMenu menu) {
		try {
			return net.minecraft.core.registries.BuiltInRegistries.MENU.getKey(menu.getType()).toString();
		} catch (UnsupportedOperationException unsupported) {
			return null;
		}
	}

	private static void validateContainerIdentity(RpcContext ctx, JsonObject state) throws RpcException {
		if (ctx.optBool("requireScreenOpen", true) && !state.get("open").getAsBoolean()) {
			throw RpcException.unavailable("No GUI screen is open for the requested container transaction.");
		}
		if (state.get("open").getAsBoolean() && ctx.optBool("requireContainerScreen", true)
				&& (!state.has("containerScreen") || !state.get("containerScreen").getAsBoolean())) {
			throw RpcException.unavailable(
					"The visible GUI is not bound to a container; refusing to mutate its hidden player InventoryMenu.");
		}
		JsonObject container = state.getAsJsonObject("container");
		if (container == null) throw RpcException.unavailable("No local container menu is available.");
		if (ctx.has("expectedContainerId")
				&& container.get("id").getAsInt() != ctx.optInt("expectedContainerId", -1)) {
			throw new RpcException("container_identity_mismatch", "Container id does not match the caller snapshot.", transactionSnapshotSummary(state));
		}
		if (ctx.has("expectedContainerClass")
				&& !container.get("class").getAsString().equals(ctx.optString("expectedContainerClass", ""))) {
			throw new RpcException("container_identity_mismatch", "Container class does not match the caller snapshot.", transactionSnapshotSummary(state));
		}
		if (ctx.has("expectedMenuType")
				&& (!container.has("menuType")
				|| !container.get("menuType").getAsString().equals(ctx.optString("expectedMenuType", "")))) {
			throw new RpcException("container_identity_mismatch", "Menu type does not match the caller snapshot.", transactionSnapshotSummary(state));
		}
		if (ctx.has("expectedFingerprint")
				&& !state.get("fingerprint").getAsString().equals(ctx.optString("expectedFingerprint", ""))) {
			throw new RpcException("screen_concurrent_change", "Screen fingerprint changed before the transaction.", transactionSnapshotSummary(state));
		}
	}

	private static JsonObject transactionSnapshotSummary(JsonObject state) {
		JsonObject summary = new JsonObject();
		summary.addProperty("open", state.get("open").getAsBoolean());
		if (state.has("class")) summary.addProperty("screenClass", state.get("class").getAsString());
		if (state.has("title")) summary.addProperty("title", state.get("title").getAsString());
		if (state.has("fingerprint")) summary.addProperty("fingerprint", state.get("fingerprint").getAsString());
		if (state.has("container")) {
			JsonObject container = state.getAsJsonObject("container");
			JsonObject compact = new JsonObject();
			compact.addProperty("id", container.get("id").getAsInt());
			compact.addProperty("class", container.get("class").getAsString());
			if (container.has("menuType")) compact.addProperty("menuType", container.get("menuType").getAsString());
			compact.addProperty("stateId", container.get("stateId").getAsInt());
			compact.add("carried", container.getAsJsonObject("carried").deepCopy());
			summary.add("container", compact);
		}
		return summary;
	}

	private static JsonArray failedConditions(JsonObject container, JsonArray conditions) {
		Map<Integer, JsonObject> slots = slotMap(container);
		Map<Integer, Integer> dataValues = dataMap(container);
		JsonArray failures = new JsonArray();
		for (JsonElement element : conditions) {
			JsonObject condition = element.getAsJsonObject();
			if (condition.has("dataIndex")) {
				int dataIndex = condition.get("dataIndex").getAsInt();
				Integer actual = dataValues.get(dataIndex);
				boolean matches = actual != null;
				if (matches && condition.has("value")) matches = actual == condition.get("value").getAsInt();
				if (matches && condition.has("minValue")) matches = actual >= condition.get("minValue").getAsInt();
				if (matches && condition.has("maxValue")) matches = actual <= condition.get("maxValue").getAsInt();
				if (!matches) {
					JsonObject failure = new JsonObject();
					failure.add("condition", condition.deepCopy());
					if (actual != null) failure.addProperty("actual", actual);
					failure.addProperty("reason", actual == null ? "data_index_not_found" : "condition_mismatch");
					failures.add(failure);
				}
				continue;
			}
			int slotIndex = condition.has("slot") ? condition.get("slot").getAsInt() : Integer.MIN_VALUE;
			JsonObject slot = slots.get(slotIndex);
			if (slot == null) {
				JsonObject failure = new JsonObject();
				failure.add("condition", condition.deepCopy());
				failure.addProperty("reason", "slot_not_found");
				failures.add(failure);
				continue;
			}
			JsonObject item = slot.getAsJsonObject("item");
			boolean empty = item.has("empty") && item.get("empty").getAsBoolean();
			int count = empty ? 0 : item.get("count").getAsInt();
			boolean matches = true;
			if (condition.has("empty")) matches &= empty == condition.get("empty").getAsBoolean();
			if (condition.has("itemId")) {
				matches &= !empty && item.get("id").getAsString().equals(normalizeItemId(condition.get("itemId").getAsString()));
			}
			if (condition.has("componentFingerprint")) {
				matches &= !empty && item.has("componentFingerprint")
						&& item.get("componentFingerprint").getAsString()
						.equals(condition.get("componentFingerprint").getAsString());
			}
			if (condition.has("count")) matches &= count == condition.get("count").getAsInt();
			if (condition.has("minCount")) matches &= count >= condition.get("minCount").getAsInt();
			if (condition.has("maxCount")) matches &= count <= condition.get("maxCount").getAsInt();
			if (!matches) {
				JsonObject failure = new JsonObject();
				failure.add("condition", condition.deepCopy());
				failure.add("actual", slot.deepCopy());
				failure.addProperty("reason", "condition_mismatch");
				failures.add(failure);
			}
		}
		return failures;
	}

	private static JsonArray containerDeltas(JsonObject before, JsonObject after) {
		Map<Integer, JsonObject> beforeSlots = slotMap(before);
		Map<Integer, JsonObject> afterSlots = slotMap(after);
		JsonArray deltas = new JsonArray();
		for (Map.Entry<Integer, JsonObject> entry : beforeSlots.entrySet()) {
			JsonObject afterSlot = afterSlots.get(entry.getKey());
			if (afterSlot == null) continue;
			JsonObject beforeItem = entry.getValue().getAsJsonObject("item");
			JsonObject afterItem = afterSlot.getAsJsonObject("item");
			if (beforeItem.toString().equals(afterItem.toString())) continue;
			JsonObject delta = new JsonObject();
			delta.addProperty("kind", "slot");
			delta.addProperty("slot", entry.getKey());
			delta.add("before", beforeItem.deepCopy());
			delta.add("after", afterItem.deepCopy());
			deltas.add(delta);
		}
		JsonObject beforeCarried = before.getAsJsonObject("carried");
		JsonObject afterCarried = after.getAsJsonObject("carried");
		if (!beforeCarried.toString().equals(afterCarried.toString())) {
			JsonObject delta = new JsonObject();
			delta.addProperty("kind", "carried");
			delta.addProperty("slot", "carried");
			delta.add("before", beforeCarried.deepCopy());
			delta.add("after", afterCarried.deepCopy());
			deltas.add(delta);
		}
		Map<Integer, Integer> beforeData = dataMap(before);
		Map<Integer, Integer> afterData = dataMap(after);
		for (Map.Entry<Integer, Integer> entry : beforeData.entrySet()) {
			Integer afterValue = afterData.get(entry.getKey());
			if (afterValue == null || afterValue.equals(entry.getValue())) continue;
			JsonObject delta = new JsonObject();
			delta.addProperty("kind", "data");
			delta.addProperty("dataIndex", entry.getKey());
			delta.addProperty("before", entry.getValue());
			delta.addProperty("after", afterValue);
			deltas.add(delta);
		}
		return deltas;
	}

	private static Map<Integer, JsonObject> slotMap(JsonObject container) {
		Map<Integer, JsonObject> result = new LinkedHashMap<>();
		for (JsonElement element : container.getAsJsonArray("slots")) {
			JsonObject slot = element.getAsJsonObject();
			result.put(slot.get("menuSlot").getAsInt(), slot);
		}
		return result;
	}

	private static Map<Integer, Integer> dataMap(JsonObject container) {
		Map<Integer, Integer> result = new LinkedHashMap<>();
		if (!container.has("data")) return result;
		for (JsonElement element : container.getAsJsonArray("data")) {
			JsonObject value = element.getAsJsonObject();
			result.put(value.get("index").getAsInt(), value.get("value").getAsInt());
		}
		return result;
	}

	private static boolean sameContainer(JsonObject state, int id, String className) {
		if (state == null || !state.has("container")) return false;
		JsonObject container = state.getAsJsonObject("container");
		return container.get("id").getAsInt() == id && container.get("class").getAsString().equals(className);
	}

	private static JsonObject transactionFailure(RpcContext ctx, JsonObject before, JsonObject last,
			JsonArray steps, String code, String message) throws RpcException {
		JsonObject data = new JsonObject();
		data.add("before", transactionSnapshotSummary(before));
		if (last != null) {
			data.add("last", transactionSnapshotSummary(last));
			if (last.has("container") && before.has("container")) {
				data.add("deltas", containerDeltas(before.getAsJsonObject("container"), last.getAsJsonObject("container")));
			}
		}
		data.add("steps", steps.deepCopy());
		data.add("cleanup", cleanupCarried(ctx, before));
		throw new RpcException("container_transaction_unconfirmed", code + ": " + message, data);
	}

	private static JsonObject cleanupCarried(RpcContext ctx, JsonObject before) {
		JsonObject result = new JsonObject();
		result.addProperty("attempted", false);
		try {
			JsonObject state = ClientMc.call(() -> screenState(true));
			JsonObject original = before.getAsJsonObject("container");
			if (!sameContainer(state, original.get("id").getAsInt(), original.get("class").getAsString())) {
				result.addProperty("needed", false);
				result.addProperty("succeeded", false);
				result.addProperty("reason", "container_changed_no_cleanup");
				return result;
			}
			if (!state.has("container") || state.getAsJsonObject("container").getAsJsonObject("carried").has("empty")) {
				result.addProperty("needed", false);
				result.addProperty("succeeded", true);
				return result;
			}
			result.addProperty("needed", true);
			if (!ctx.has("cleanupSlot")) {
				result.addProperty("succeeded", false);
				result.addProperty("reason", "carried_item_requires_explicit_cleanup_slot");
				return result;
			}
			int cleanupSlot = ctx.optInt("cleanupSlot", -1);
			ClientMc.call(() -> {
				LocalPlayer player = ClientMc.player();
				if (!player.containerMenu.isValidSlotIndex(cleanupSlot)) {
					throw RpcException.badRequest("cleanupSlot is invalid for the active container: " + cleanupSlot);
				}
				containerClick(ClientMc.gameMode(), player.containerMenu.containerId, cleanupSlot, 0, "PICKUP", player);
				return new JsonObject();
			});
			result.addProperty("attempted", true);
			long deadline = System.nanoTime() + 750_000_000L;
			while (System.nanoTime() <= deadline) {
				JsonObject current = ClientMc.call(() -> screenState(false));
				if (current.getAsJsonObject("container").getAsJsonObject("carried").has("empty")) {
					result.addProperty("succeeded", true);
					result.addProperty("cleanupSlot", cleanupSlot);
					return result;
				}
				sleep(25);
			}
			result.addProperty("succeeded", false);
			result.addProperty("reason", "cleanup_not_confirmed");
		} catch (RpcException error) {
			result.addProperty("succeeded", false);
			result.addProperty("reason", error.getMessage());
		}
		return result;
	}

	private static Screen requireScreen() throws RpcException {
		Screen screen = currentScreen(ClientMc.mc());
		if (screen == null) throw RpcException.unavailable("No GUI screen is currently open.");
		return screen;
	}

	private static ResolvedWidget resolveWidget(Screen screen, RpcContext ctx) throws RpcException {
		String path = ctx.has("widgetPath") ? ctx.getString("widgetPath")
				: (ctx.has("widgetIndex") ? String.valueOf(ctx.getInt("widgetIndex")) : null);
		if (path == null || path.isBlank()) {
			List<ResolvedWidget> matches = matchingWidgets(screen, ctx);
			if (matches.isEmpty()) throw RpcException.notFound("No screen widget matches the semantic selector.");
			if (ctx.has("occurrence")) {
				int occurrence = ctx.getInt("occurrence");
				if (occurrence < 0 || occurrence >= matches.size()) {
					throw RpcException.badRequest("occurrence is outside the " + matches.size() + " matching widgets.");
				}
				return matches.get(occurrence);
			}
			if (matches.size() != 1) {
				throw RpcException.badRequest("Semantic selector matched " + matches.size()
						+ " widgets; add occurrence or a narrower selector.");
			}
			return matches.get(0);
		}
		return resolveWidgetPath(screen, path);
	}

	private static ResolvedWidget resolveWidgetPath(Screen screen, String path) throws RpcException {
		String[] parts = path.split("/");
		List<? extends GuiEventListener> children = screen.children();
		GuiEventListener current = null;
		for (int depth = 0; depth < parts.length; depth++) {
			int index;
			try {
				index = Integer.parseInt(parts[depth]);
			} catch (NumberFormatException invalid) {
				throw RpcException.badRequest("Invalid widgetPath: " + path);
			}
			if (index < 0 || index >= children.size()) throw RpcException.badRequest("widgetPath out of range: " + path);
			current = children.get(index);
			if (depth + 1 < parts.length) {
				if (!(current instanceof ContainerEventHandler container)) {
					throw RpcException.badRequest("widgetPath traverses a non-container control: " + path);
				}
				children = container.children();
			}
		}
		return new ResolvedWidget(path, current);
	}

	private static List<ResolvedWidget> matchingWidgets(Screen screen, RpcContext ctx) {
		List<ResolvedWidget> all = new ArrayList<>();
		IdentityHashMap<GuiEventListener, Boolean> visited = new IdentityHashMap<>();
		for (int i = 0; i < screen.children().size(); i++) {
			collectWidgets(all, screen.children().get(i), String.valueOf(i), visited);
		}
		String classContains = ctx.has("classContains") ? ctx.optString("classContains", "").toLowerCase() : null;
		String message = ctx.has("message") ? ctx.optString("message", "") : null;
		String messageContains = ctx.has("messageContains") ? ctx.optString("messageContains", "").toLowerCase() : null;
		String value = ctx.has("currentValue") ? ctx.optString("currentValue", "") : null;
		String valueContains = ctx.has("currentValueContains") ? ctx.optString("currentValueContains", "").toLowerCase() : null;
		List<ResolvedWidget> matches = new ArrayList<>();
		for (ResolvedWidget resolved : all) {
			GuiEventListener child = resolved.widget;
			if (classContains != null && !child.getClass().getName().toLowerCase().contains(classContains)) continue;
			if (ctx.has("focused") && child.isFocused() != ctx.optBool("focused", false)) continue;
			if (message != null || messageContains != null || ctx.has("active") || ctx.has("visible")) {
				if (!(child instanceof AbstractWidget widget)) continue;
				String actualMessage = widget.getMessage().getString();
				if (message != null && !actualMessage.equals(message)) continue;
				if (messageContains != null && !actualMessage.toLowerCase().contains(messageContains)) continue;
				if (ctx.has("active") && widget.active != ctx.optBool("active", false)) continue;
				if (ctx.has("visible") && widget.visible != ctx.optBool("visible", false)) continue;
			}
			if (value != null || valueContains != null) {
				if (!(child instanceof EditBox edit)) continue;
				String actualValue = edit.getValue();
				if (value != null && !actualValue.equals(value)) continue;
				if (valueContains != null && !actualValue.toLowerCase().contains(valueContains)) continue;
			}
			matches.add(resolved);
		}
		return matches;
	}

	private static void collectWidgets(List<ResolvedWidget> output, GuiEventListener child, String path,
			IdentityHashMap<GuiEventListener, Boolean> visited) {
		if (visited.put(child, Boolean.TRUE) != null) return;
		output.add(new ResolvedWidget(path, child));
		if (child instanceof ContainerEventHandler container) {
			List<? extends GuiEventListener> children = container.children();
			for (int i = 0; i < children.size(); i++) {
				collectWidgets(output, children.get(i), path + "/" + i, visited);
			}
		}
	}

	private static JsonObject widgetJson(ResolvedWidget resolved) {
		JsonObject out = new JsonObject();
		GuiEventListener child = resolved.widget;
		out.addProperty("path", resolved.path);
		out.addProperty("class", child.getClass().getName());
		out.addProperty("focused", child.isFocused());
		if (child instanceof AbstractWidget widget) {
			out.addProperty("message", widget.getMessage().getString());
			out.addProperty("x", widget.getX());
			out.addProperty("y", widget.getY());
			out.addProperty("width", widget.getWidth());
			out.addProperty("height", widget.getHeight());
			out.addProperty("active", widget.active);
			out.addProperty("visible", widget.visible);
		}
		if (child instanceof EditBox edit) out.addProperty("value", edit.getValue());
		return out;
	}

	private static JsonObject compactScreenIdentity(JsonObject state) {
		JsonObject out = new JsonObject();
		out.addProperty("open", state.get("open").getAsBoolean());
		if (state.has("class")) out.addProperty("class", state.get("class").getAsString());
		if (state.has("title")) out.addProperty("title", state.get("title").getAsString());
		out.addProperty("fingerprint", state.get("fingerprint").getAsString());
		if (state.has("container")) {
			JsonObject container = state.getAsJsonObject("container");
			JsonObject compact = new JsonObject();
			compact.addProperty("id", container.get("id").getAsInt());
			compact.addProperty("class", container.get("class").getAsString());
			if (container.has("menuType")) compact.addProperty("menuType", container.get("menuType").getAsString());
			compact.addProperty("stateId", container.get("stateId").getAsInt());
			out.add("container", compact);
		}
		return out;
	}

	private static double unitInterval(RpcContext ctx, String key, double fallback) throws RpcException {
		double value = ctx.has(key) ? ctx.getDouble(key) : fallback;
		if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
			throw RpcException.badRequest(key + " must be between 0 and 1.");
		}
		return value;
	}

	private static String normalizeItemId(String id) {
		String normalized = id.trim().toLowerCase();
		return normalized.contains(":") ? normalized : "minecraft:" + normalized;
	}

	private static long elapsedMs(long startedNanos) {
		return (System.nanoTime() - startedNanos) / 1_000_000L;
	}

	private static void sleep(long millis) throws RpcException {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw RpcException.unavailable("Interrupted while waiting for client state confirmation.");
		}
	}

	private static String sha256(String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (java.security.NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("SHA-256 is unavailable", impossible);
		}
	}

	private record ResolvedWidget(String path, GuiEventListener widget) {}

	private record MovePlan(ItemStack sourceBefore, ItemStack targetBefore, int count,
			int remainderSlot, ItemStack remainderBefore) {}

	private static Screen currentScreen(Minecraft minecraft) {
		//? if >=26.2 {
		return minecraft.gui.screen();
		//?} else
		/*return minecraft.screen;*/
	}

	private static void containerClick(MultiPlayerGameMode gameMode, int containerId, int slot, int button, String input, LocalPlayer player) throws RpcException {
		try {
			//? if <26.1 {
			gameMode.handleInventoryMouseClick(containerId, slot, button, ClickType.valueOf(input), player);
			//?} else
			/*gameMode.handleContainerInput(containerId, slot, button, ContainerInput.valueOf(input), player);*/
		} catch (IllegalArgumentException e) {
			throw RpcException.badRequest("Unknown container input '" + input + "'.");
		}
	}

	private static void requireControl() throws RpcException {
		if (!McpFabric.config().enablePlayerControl) {
			throw RpcException.unavailable("Player control is disabled in mcpfabric.config.json (enablePlayerControl=false).");
		}
	}

	private static void requireContainerScreen() throws RpcException {
		if (!(currentScreen(ClientMc.mc()) instanceof AbstractContainerScreen<?>)) {
			throw RpcException.unavailable(
					"The visible GUI is not a container screen; hidden menu mutation is refused.");
		}
	}
}
