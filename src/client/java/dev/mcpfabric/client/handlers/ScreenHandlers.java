package dev.mcpfabric.client.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mcpfabric.McpFabric;
import dev.mcpfabric.bridge.Json;
import dev.mcpfabric.bridge.RpcException;
import dev.mcpfabric.bridge.RpcRouter;
import dev.mcpfabric.client.ClientMc;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
//? if >=26.2 {
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
//?}
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.inventory.AbstractContainerMenu;
//? if <26.1 {
import net.minecraft.world.inventory.ClickType;
//?} else
/*import net.minecraft.world.inventory.ContainerInput;*/
import net.minecraft.world.inventory.Slot;

/** Generic screen, widget, text, and container primitives for complete vanilla GUI operation. */
public final class ScreenHandlers {
	private ScreenHandlers() {}

	public static void register(RpcRouter router) {
		router.register("screen.getState", ctx -> ClientMc.call(() -> screenState(ctx.optBool("includeEmptySlots", true))));

		router.register("screen.containerClick", ctx -> ClientMc.call(() -> {
			requireControl();
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
			LocalPlayer player = ClientMc.player();
			int button = ctx.getInt("button");
			ClientMc.gameMode().handleInventoryButtonClick(player.containerMenu.containerId, button);
			return Json.ok("container button " + button + " sent");
		}));

		router.register("screen.setText", ctx -> ClientMc.call(() -> {
			requireControl();
			Screen screen = requireScreen();
			int index = ctx.getInt("widgetIndex");
			GuiEventListener child = child(screen, index);
			if (!(child instanceof EditBox edit)) {
				throw RpcException.badRequest("Widget " + index + " is not an EditBox; use screen.typeText for focused custom fields.");
			}
			String value = ctx.getString("value");
			if (ctx.optBool("append", false)) edit.insertText(value); else edit.setValue(value);
			JsonObject out = Json.ok("text updated");
			out.addProperty("widgetIndex", index);
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
			//? if >=26.2 {
			boolean handled = screen.keyPressed(new KeyEvent(key, scanCode, modifiers));
			//?} else
			/*boolean handled = screen.keyPressed(key, scanCode, modifiers);*/
			JsonObject out = Json.ok("screen key sent");
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
				case "press" -> screen.mouseClicked(event, false);
				case "release" -> screen.mouseReleased(event);
				case "click" -> {
					boolean pressed = screen.mouseClicked(event, false);
					screen.mouseReleased(event);
					yield pressed;
				}
				case "drag" -> screen.mouseDragged(event, ctx.optDouble("dragX", 0), ctx.optDouble("dragY", 0));
				default -> throw RpcException.badRequest("action must be click, press, release, or drag.");
			};
			//?} else {
			/*handled = switch (action) {
				case "press" -> screen.mouseClicked(x, y, button);
				case "release" -> screen.mouseReleased(x, y, button);
				case "click" -> {
					boolean pressed = screen.mouseClicked(x, y, button);
					screen.mouseReleased(x, y, button);
					yield pressed;
				}
				case "drag" -> screen.mouseDragged(x, y, button, ctx.optDouble("dragX", 0), ctx.optDouble("dragY", 0));
				default -> throw RpcException.badRequest("action must be click, press, release, or drag.");
			};*/
			//?}
			JsonObject out = Json.ok("screen mouse action sent");
			out.addProperty("handled", handled);
			return out;
		}));

		router.register("screen.clickWidget", ctx -> ClientMc.call(() -> {
			requireControl();
			Screen screen = requireScreen();
			int index = ctx.getInt("widgetIndex");
			GuiEventListener child = child(screen, index);
			if (!(child instanceof AbstractWidget widget)) {
				throw RpcException.badRequest("Widget " + index + " has no rectangular click target.");
			}
			double x = widget.getX() + widget.getWidth() / 2.0;
			double y = widget.getY() + widget.getHeight() / 2.0;
			int button = ctx.optInt("button", 0);
			//? if >=26.2 {
			MouseButtonEvent event = new MouseButtonEvent(x, y, new MouseButtonInfo(button, 0));
			boolean handled = screen.mouseClicked(event, false);
			screen.mouseReleased(event);
			//?} else {
			/*boolean handled = screen.mouseClicked(x, y, button);
			screen.mouseReleased(x, y, button);*/
			//?}
			JsonObject out = Json.ok("widget clicked");
			out.addProperty("widgetIndex", index);
			out.addProperty("handled", handled);
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

	private static JsonObject screenState(boolean includeEmptySlots) throws RpcException {
		Minecraft mc = ClientMc.mc();
		Screen screen = currentScreen(mc);
		JsonObject out = new JsonObject();
		out.addProperty("open", screen != null);
		if (screen != null) {
			out.addProperty("class", screen.getClass().getName());
			out.addProperty("title", screen.getTitle().getString());
			out.addProperty("width", screen.width);
			out.addProperty("height", screen.height);
			JsonArray widgets = new JsonArray();
			for (int i = 0; i < screen.children().size(); i++) {
				GuiEventListener child = screen.children().get(i);
				JsonObject widgetJson = new JsonObject();
				widgetJson.addProperty("index", i);
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
				widgets.add(widgetJson);
			}
			out.add("widgets", widgets);
		}

		LocalPlayer player = mc.player;
		if (player != null) {
			AbstractContainerMenu menu = player.containerMenu;
			JsonObject container = new JsonObject();
			container.addProperty("id", menu.containerId);
			container.addProperty("class", menu.getClass().getName());
			container.add("carried", LocalPlayerHandlers.itemJson(menu.getCarried()));
			JsonArray slots = new JsonArray();
			for (int i = 0; i < menu.slots.size(); i++) {
				Slot slot = menu.slots.get(i);
				if (!includeEmptySlots && !slot.hasItem()) continue;
				JsonObject slotJson = new JsonObject();
				slotJson.addProperty("menuSlot", i);
				slotJson.addProperty("containerSlot", slot.getContainerSlot());
				slotJson.addProperty("x", slot.x);
				slotJson.addProperty("y", slot.y);
				slotJson.addProperty("active", slot.isActive());
				slotJson.addProperty("mayPickup", slot.mayPickup(player));
				slotJson.add("item", LocalPlayerHandlers.itemJson(slot.getItem()));
				slots.add(slotJson);
			}
			container.add("slots", slots);
			out.add("container", container);
		}
		return out;
	}

	private static Screen requireScreen() throws RpcException {
		Screen screen = currentScreen(ClientMc.mc());
		if (screen == null) throw RpcException.unavailable("No GUI screen is currently open.");
		return screen;
	}

	private static GuiEventListener child(Screen screen, int index) throws RpcException {
		if (index < 0 || index >= screen.children().size()) {
			throw RpcException.badRequest("widgetIndex out of range: " + index);
		}
		return screen.children().get(index);
	}

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
}
