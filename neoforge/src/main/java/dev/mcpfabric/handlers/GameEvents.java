package dev.mcpfabric.handlers;

import com.google.gson.JsonObject;
import dev.mcpfabric.events.EventBus;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/** Observe uncancelled server events without changing game decisions. */
public final class GameEvents {
	private GameEvents() {}
	public static void register(EventBus events) {
		NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, (ServerChatEvent e) -> {
			JsonObject d = player(e.getPlayer());
			d.addProperty("text", e.getRawText());
			events.emit("chat", d);
		});
		NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent e) -> events.emit("player_join", player(e.getEntity())));
		NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> events.emit("player_leave", player(e.getEntity())));
		NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, (LivingDeathEvent e) -> {
			var entity = e.getEntity();
			if (entity.level().isClientSide()) return;
			JsonObject d = new JsonObject();
			d.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
			d.addProperty("uuid", entity.getUUID().toString());
			d.addProperty("name", entity.getName().getString());
			d.addProperty("isPlayer", entity instanceof Player);
			d.addProperty("cause", e.getSource().getMsgId());
			d.addProperty("x", entity.getX());
			d.addProperty("y", entity.getY());
			d.addProperty("z", entity.getZ());
			events.emit(entity instanceof Player ? "player_death" : "entity_death", d);
		});
		NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, (LivingIncomingDamageEvent e) -> {
			if (!(e.getEntity() instanceof Player p) || p.level().isClientSide()) return;
			JsonObject d = player(p);
			d.addProperty("amount", e.getOriginalAmount());
			d.addProperty("cause", e.getSource().getMsgId());
			d.addProperty("healthBefore", p.getHealth());
			events.emit("player_damage", d);
		});
	}
	private static JsonObject player(Player p) {
		JsonObject d = new JsonObject();
		d.addProperty("player", p.getName().getString());
		d.addProperty("uuid", p.getUUID().toString());
		return d;
	}
}
