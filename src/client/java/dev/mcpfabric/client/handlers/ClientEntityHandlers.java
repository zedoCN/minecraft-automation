package dev.mcpfabric.client.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mcpfabric.bridge.RpcException;
import dev.mcpfabric.bridge.RpcRouter;
import dev.mcpfabric.client.ClientMc;
import dev.mcpfabric.handlers.support.Levels;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Query entities that are currently tracked by a multiplayer client. */
public final class ClientEntityHandlers {
	private ClientEntityHandlers() {}

	public static void register(RpcRouter router) {
		router.register("clientEntities.query", ctx -> ClientMc.call(() -> {
			ClientLevel level = ClientMc.level();
			String currentDimension = Levels.dimensionId(level);
			String requestedDimension = ctx.optString("dimension", null);
			if (requestedDimension != null && !requestedDimension.isBlank() && !requestedDimension.equals(currentDimension)) {
				throw RpcException.unavailable("The client cache only tracks the current dimension " + currentDimension + ".");
			}

			JsonObject centerJson = ctx.optObject("center");
			Vec3 center = centerJson == null
					? ClientMc.player().position()
					: new Vec3(centerJson.get("x").getAsDouble(), centerJson.get("y").getAsDouble(), centerJson.get("z").getAsDouble());
			double radius = ctx.optDouble("radius", 32.0);
			boolean includePlayers = ctx.optBool("includePlayers", true);
			boolean onlyLiving = ctx.optBool("onlyLiving", false);
			int maxResults = ctx.optInt("maxResults", 100);
			Set<String> types = new HashSet<>(ctx.getStringList("types"));

			List<Entity> matches = new ArrayList<>();
			for (Entity entity : level.entitiesForRendering()) {
				if (!includePlayers && entity instanceof Player) continue;
				if (onlyLiving && !(entity instanceof LivingEntity)) continue;
				if (!types.isEmpty() && !types.contains(typeId(entity))) continue;
				if (entity.position().distanceTo(center) > radius) continue;
				matches.add(entity);
			}
			matches.sort((a, b) -> Double.compare(a.position().distanceTo(center), b.position().distanceTo(center)));

			JsonArray entities = new JsonArray();
			for (int i = 0; i < Math.min(maxResults, matches.size()); i++) {
				entities.add(describe(matches.get(i), center));
			}
			JsonObject o = new JsonObject();
			o.addProperty("dimension", currentDimension);
			o.addProperty("source", "client_cache");
			o.addProperty("trackedEntitiesOnly", true);
			o.addProperty("total", matches.size());
			o.addProperty("returned", entities.size());
			o.addProperty("truncated", matches.size() > maxResults);
			o.add("entities", entities);
			return o;
		}));
	}

	private static String typeId(Entity entity) {
		return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
	}

	private static JsonObject describe(Entity entity, Vec3 from) {
		JsonObject o = new JsonObject();
		o.addProperty("uuid", entity.getUUID().toString());
		o.addProperty("type", typeId(entity));
		o.addProperty("name", entity.getName().getString());
		o.addProperty("isPlayer", entity instanceof Player);
		o.addProperty("x", entity.getX());
		o.addProperty("y", entity.getY());
		o.addProperty("z", entity.getZ());
		o.addProperty("distance", entity.position().distanceTo(from));
		o.addProperty("onGround", entity.onGround());
		if (entity instanceof LivingEntity living) {
			o.addProperty("health", living.getHealth());
			o.addProperty("maxHealth", living.getMaxHealth());
		}
		if (entity instanceof ItemEntity item) {
			o.add("item", LocalPlayerHandlers.itemJson(item.getItem()));
			o.addProperty("age", item.getAge());
		}
		return o;
	}
}
