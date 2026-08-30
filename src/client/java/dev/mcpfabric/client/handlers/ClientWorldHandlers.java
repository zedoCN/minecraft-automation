package dev.mcpfabric.client.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mcpfabric.bridge.RpcException;
import dev.mcpfabric.bridge.RpcRouter;
import dev.mcpfabric.client.ClientMc;
import dev.mcpfabric.handlers.support.Levels;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Read-only inspection of the chunks currently synchronized to a multiplayer client. */
public final class ClientWorldHandlers {
	private static final int DEFAULT_REGION_CAP = 32768;
	private static final int SCAN_BUDGET = 250_000;

	private ClientWorldHandlers() {}

	public static void register(RpcRouter router) {
		router.register("clientWorld.getBlock", ctx -> ClientMc.call(() -> {
			ClientLevel level = currentLevel(ctx.optString("dimension", null));
			BlockPos pos = BlockPos.containing(ctx.getDouble("x"), ctx.getDouble("y"), ctx.getDouble("z"));
			if (!level.hasChunkAt(pos)) throw RpcException.notFound("Client chunk is not loaded at " + pos.toShortString());
			BlockState state = level.getBlockState(pos);
			JsonObject o = Levels.describeBlock(level, pos, state);
			addClientMetadata(o, level);
			o.addProperty("blockLight", level.getBrightness(LightLayer.BLOCK, pos));
			o.addProperty("skyLight", level.getBrightness(LightLayer.SKY, pos));
			o.addProperty("hardness", state.getDestroySpeed(level, pos));
			return o;
		}));

		router.register("clientWorld.getBlocks", ctx -> ClientMc.call(() -> {
			ClientLevel level = currentLevel(ctx.optString("dimension", null));
			JsonObject from = ctx.getObject("from");
			JsonObject to = ctx.getObject("to");
			int x1 = from.get("x").getAsInt(), y1 = from.get("y").getAsInt(), z1 = from.get("z").getAsInt();
			int x2 = to.get("x").getAsInt(), y2 = to.get("y").getAsInt(), z2 = to.get("z").getAsInt();
			int minX = Math.min(x1, x2), minY = Math.min(y1, y2), minZ = Math.min(z1, z2);
			int maxX = Math.max(x1, x2), maxY = Math.max(y1, y2), maxZ = Math.max(z1, z2);
			boolean includeAir = ctx.optBool("includeAir", false);
			int cap = ctx.optInt("maxBlocks", DEFAULT_REGION_CAP);
			long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
			if (volume > cap) {
				throw RpcException.badRequest("Region volume " + volume + " exceeds maxBlocks " + cap + ". Narrow the region or raise maxBlocks explicitly.");
			}
			JsonArray blocks = new JsonArray();
			BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
			long scanned = 0;
			long skippedUnloaded = 0;
			for (int y = minY; y <= maxY; y++) {
				for (int x = minX; x <= maxX; x++) {
					for (int z = minZ; z <= maxZ; z++) {
						pos.set(x, y, z);
						if (!level.hasChunkAt(pos)) { skippedUnloaded++; continue; }
						scanned++;
						BlockState state = level.getBlockState(pos);
						if (!includeAir && state.isAir()) continue;
						JsonObject b = new JsonObject();
						b.addProperty("x", x);
						b.addProperty("y", y);
						b.addProperty("z", z);
						b.addProperty("id", Levels.blockId(state));
						blocks.add(b);
					}
				}
			}
			JsonObject o = new JsonObject();
			addClientMetadata(o, level);
			o.addProperty("volume", volume);
			o.addProperty("scanned", scanned);
			o.addProperty("skippedUnloaded", skippedUnloaded);
			o.addProperty("count", blocks.size());
			o.addProperty("truncated", false);
			o.add("blocks", blocks);
			return o;
		}));

		router.register("clientWorld.findBlocks", ctx -> ClientMc.call(() -> {
			ClientLevel level = currentLevel(ctx.optString("dimension", null));
			JsonObject center = ctx.getObject("center");
			int cx = center.get("x").getAsInt(), cy = center.get("y").getAsInt(), cz = center.get("z").getAsInt();
			int radius = ctx.getInt("radius");
			int maxResults = ctx.optInt("maxResults", 64);
			Set<String> wanted = new HashSet<>(ctx.getStringList("blockIds"));
			if (wanted.isEmpty()) throw RpcException.badRequest("blockIds must not be empty.");
			List<JsonObject> found = new ArrayList<>();
			BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
			long scanned = 0;
			long skippedUnloaded = 0;
			boolean truncated = false;
			int r2 = radius * radius;
			outer:
			for (int dx = -radius; dx <= radius; dx++) {
				for (int dy = -radius; dy <= radius; dy++) {
					for (int dz = -radius; dz <= radius; dz++) {
						if (++scanned > SCAN_BUDGET) { truncated = true; break outer; }
						int dist2 = dx * dx + dy * dy + dz * dz;
						if (dist2 > r2) continue;
						pos.set(cx + dx, cy + dy, cz + dz);
						if (!level.hasChunkAt(pos)) { skippedUnloaded++; continue; }
						String id = Levels.blockId(level.getBlockState(pos));
						if (!wanted.contains(id)) continue;
						JsonObject b = new JsonObject();
						b.addProperty("x", pos.getX());
						b.addProperty("y", pos.getY());
						b.addProperty("z", pos.getZ());
						b.addProperty("id", id);
						b.addProperty("distance", Math.sqrt(dist2));
						found.add(b);
					}
				}
			}
			found.sort((a, b) -> Double.compare(a.get("distance").getAsDouble(), b.get("distance").getAsDouble()));
			JsonArray matches = new JsonArray();
			for (int i = 0; i < Math.min(maxResults, found.size()); i++) matches.add(found.get(i));
			JsonObject o = new JsonObject();
			addClientMetadata(o, level);
			o.addProperty("totalFound", found.size());
			o.addProperty("returned", matches.size());
			o.addProperty("scanned", Math.min(scanned, SCAN_BUDGET));
			o.addProperty("skippedUnloaded", skippedUnloaded);
			o.addProperty("truncated", truncated || found.size() > maxResults);
			o.add("matches", matches);
			return o;
		}));
	}

	private static ClientLevel currentLevel(String requestedDimension) throws RpcException {
		ClientLevel level = ClientMc.level();
		String current = Levels.dimensionId(level);
		if (requestedDimension != null && !requestedDimension.isBlank() && !requestedDimension.equals(current)) {
			throw RpcException.unavailable("The client cache only contains the current dimension " + current + ".");
		}
		return level;
	}

	private static void addClientMetadata(JsonObject o, ClientLevel level) {
		o.addProperty("dimension", Levels.dimensionId(level));
		o.addProperty("source", "client_cache");
		o.addProperty("loadedChunksOnly", true);
	}
}
