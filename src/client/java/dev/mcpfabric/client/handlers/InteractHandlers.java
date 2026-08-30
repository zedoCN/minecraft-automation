package dev.mcpfabric.client.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mcpfabric.McpFabric;
import dev.mcpfabric.ServerHolder;
import dev.mcpfabric.bridge.Json;
import dev.mcpfabric.bridge.MainThread;
import dev.mcpfabric.bridge.RpcContext;
import dev.mcpfabric.bridge.RpcException;
import dev.mcpfabric.bridge.RpcRouter;
import dev.mcpfabric.client.BotController;
import dev.mcpfabric.client.ClientMc;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/** Interaction: break/place blocks, use items, attack/use entities, drop held item. */
public final class InteractHandlers {
	private InteractHandlers() {}

	public static void register(RpcRouter router) {
		router.register("interact.breakBlock", InteractHandlers::breakBlock);

		router.register("interact.placeBlock", ctx -> ClientMc.call(() -> {
			requireControl();
			MultiPlayerGameMode gm = ClientMc.gameMode();
			LocalPlayer p = ClientMc.player();
			BlockPos pos = BlockPos.containing(ctx.getDouble("x"), ctx.getDouble("y"), ctx.getDouble("z"));
			Direction face = parseFace(ctx.optString("face", "up"));
			float yaw = ctx.has("yaw") ? (float) ctx.getDouble("yaw") : p.getYRot();
			float pitch = ctx.has("pitch") ? (float) ctx.getDouble("pitch") : p.getXRot();
			p.setYRot(yaw);
			p.setXRot(pitch);
			p.setYHeadRot(yaw);
			p.setYBodyRot(yaw);
			ControlHandlers.syncCurrentLook(p);
			Vec3 hitLoc = new Vec3(
					pos.getX() + 0.5 + face.getStepX() * 0.5,
					pos.getY() + 0.5 + face.getStepY() * 0.5,
					pos.getZ() + 0.5 + face.getStepZ() * 0.5);
			BlockHitResult hit = new BlockHitResult(hitLoc, face, pos, false);
			String heldItem = itemId(p.getMainHandItem());
			InteractionResult result = gm.useItemOn(p, InteractionHand.MAIN_HAND, hit);
			p.swing(InteractionHand.MAIN_HAND);
			JsonObject o = new JsonObject();
			o.addProperty("result", String.valueOf(result));
			o.addProperty("heldItem", heldItem);
			o.add("clickedBlock", blockPos(pos));
			o.add("intendedPlacement", blockPos(pos.relative(face)));
			o.addProperty("face", face.getName());
			o.addProperty("yaw", p.getYRot());
			o.addProperty("pitch", p.getXRot());
			o.addProperty("serverRotationQueued", true);
			return o;
		}));

		// Match an ordinary right-click: use the block/entity under the crosshair, otherwise use in air.
		router.register("interact.useItem", ctx -> ClientMc.call(() -> {
			requireControl();
			Minecraft mc = ClientMc.mc();
			MultiPlayerGameMode gm = ClientMc.gameMode();
			LocalPlayer p = ClientMc.player();
			HitResult hit = mc.hitResult;
			JsonObject o = new JsonObject();
			InteractionResult result;
			if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
				result = gm.useItemOn(p, InteractionHand.MAIN_HAND, blockHit);
				o.addProperty("targetType", "block");
				o.add("targetPos", blockPos(blockHit.getBlockPos()));
				o.addProperty("face", blockHit.getDirection().getName());
			} else if (hit instanceof EntityHitResult entityHit) {
				Entity entity = entityHit.getEntity();
				//? if <26.1 {
				result = gm.interact(p, entity, InteractionHand.MAIN_HAND);
				//?} else
				/*result = gm.interact(p, entity, entityHit, InteractionHand.MAIN_HAND);*/
				o.addProperty("targetType", "entity");
				o.addProperty("targetUuid", entity.getUUID().toString());
				o.addProperty("targetEntityType", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
			} else {
				result = gm.useItem(p, InteractionHand.MAIN_HAND);
				o.addProperty("targetType", "air");
			}
			p.swing(InteractionHand.MAIN_HAND);
			o.addProperty("result", String.valueOf(result));
			o.addProperty("heldItem", BuiltInRegistries.ITEM.getKey(p.getMainHandItem().getItem()).toString());
			return o;
		}));

		router.register("interact.useItemInAir", ctx -> ClientMc.call(() -> {
			requireControl();
			MultiPlayerGameMode gm = ClientMc.gameMode();
			LocalPlayer p = ClientMc.player();
			InteractionResult result = gm.useItem(p, InteractionHand.MAIN_HAND);
			JsonObject o = new JsonObject();
			o.addProperty("targetType", "air");
			o.addProperty("result", String.valueOf(result));
			o.addProperty("heldItem", BuiltInRegistries.ITEM.getKey(p.getMainHandItem().getItem()).toString());
			return o;
		}));

		router.register("interact.useBlock", ctx -> ClientMc.call(() -> {
			requireControl();
			MultiPlayerGameMode gm = ClientMc.gameMode();
			LocalPlayer p = ClientMc.player();
			BlockPos pos = BlockPos.containing(ctx.getDouble("x"), ctx.getDouble("y"), ctx.getDouble("z"));
			Direction face = parseFace(ctx.optString("face", "up"));
			Vec3 hitLoc = new Vec3(
					pos.getX() + 0.5 + face.getStepX() * 0.5,
					pos.getY() + 0.5 + face.getStepY() * 0.5,
					pos.getZ() + 0.5 + face.getStepZ() * 0.5);
			BlockHitResult hit = new BlockHitResult(hitLoc, face, pos, false);
			InteractionResult result = gm.useItemOn(p, InteractionHand.MAIN_HAND, hit);
			p.swing(InteractionHand.MAIN_HAND);
			JsonObject o = new JsonObject();
			o.addProperty("targetType", "block");
			o.add("targetPos", blockPos(pos));
			o.addProperty("face", face.getName());
			o.addProperty("result", String.valueOf(result));
			o.addProperty("heldItem", BuiltInRegistries.ITEM.getKey(p.getMainHandItem().getItem()).toString());
			return o;
		}));

		router.register("interact.useBlockAt", InteractHandlers::useBlockAt);

		router.register("interact.attackEntity", ctx -> ClientMc.call(() -> {
			requireControl();
			MultiPlayerGameMode gm = ClientMc.gameMode();
			LocalPlayer p = ClientMc.player();
			Entity e = findEntity(ctx.getString("uuid"));
			gm.attack(p, e);
			p.swing(InteractionHand.MAIN_HAND);
			return Json.ok("attacked " + e.getName().getString());
		}));

		router.register("interact.useEntity", ctx -> ClientMc.call(() -> {
			requireControl();
			MultiPlayerGameMode gm = ClientMc.gameMode();
			LocalPlayer p = ClientMc.player();
			Entity e = findEntity(ctx.getString("uuid"));
			//? if <26.1 {
			InteractionResult result = gm.interact(p, e, InteractionHand.MAIN_HAND);
			//?} else
			/*InteractionResult result = gm.interact(p, e, new net.minecraft.world.phys.EntityHitResult(e), InteractionHand.MAIN_HAND);*/
			JsonObject o = new JsonObject();
			o.addProperty("result", String.valueOf(result));
			return o;
		}));

		router.register("interact.dropItem", ctx -> ClientMc.call(() -> {
			LocalPlayer p = ClientMc.player();
			boolean whole = ctx.optBool("wholeStack", false);
			p.drop(whole);
			return Json.ok(whole ? "dropped stack" : "dropped one");
		}));
	}

	private static JsonObject breakBlock(RpcContext ctx) throws RpcException {
		BreakStart start = ClientMc.call(() -> {
			requireControl();
			MultiPlayerGameMode gameMode = ClientMc.gameMode();
			LocalPlayer player = ClientMc.player();
			BlockPos pos = BlockPos.containing(ctx.getDouble("x"), ctx.getDouble("y"), ctx.getDouble("z"));
			BlockState initialState = ClientMc.level().getBlockState(pos);
			Direction face = faceToward(pos, player.getEyePosition());
			String mode = ctx.optString("mode", "survival");
			JsonObject result = new JsonObject();
			result.add("target", blockPos(pos));
			result.addProperty("initialBlock", BuiltInRegistries.BLOCK.getKey(initialState.getBlock()).toString());
			result.addProperty("mode", mode);
			if (initialState.isAir()) {
				result.addProperty("started", false);
				result.addProperty("confirmed", true);
				result.addProperty("alreadyAir", true);
				return new BreakStart(pos, initialState, mode, result, true);
			}
			boolean accepted = gameMode.startDestroyBlock(pos, face);
			player.swing(InteractionHand.MAIN_HAND);
			result.addProperty("started", accepted);
			if ("instant".equals(mode)) {
				result.addProperty("broke", accepted);
				return new BreakStart(pos, initialState, mode, result, true);
			}
			if (!"survival".equals(mode)) throw RpcException.badRequest("mode must be instant or survival.");
			BlockPos previous = BotController.get().startMining(pos, face);
			if (previous != null && !previous.equals(pos)) result.add("replacedMiningTarget", blockPos(previous));
			return new BreakStart(pos, initialState, mode, result, false);
		});

		if (start.complete || !ctx.optBool("confirm", true)) {
			if (!start.complete) {
				start.result.addProperty("confirmed", false);
				start.result.addProperty("note", "Mining continues each tick; call get_client_block or use confirm=true.");
			}
			return start.result;
		}

		int timeoutMs = Math.max(100, Math.min(60_000, ctx.optInt("timeoutMs", 10_000)));
		long startedAt = System.nanoTime();
		long deadline = startedAt + timeoutMs * 1_000_000L;
		while (System.nanoTime() <= deadline) {
			BreakObservation observation = ClientMc.call(() -> {
				BlockState current = ClientMc.level().getBlockState(start.pos);
				String currentId = BuiltInRegistries.BLOCK.getKey(current.getBlock()).toString();
				boolean changed = current.getBlock() != start.initialState.getBlock();
				return new BreakObservation(changed, currentId, BotController.get().isMining(start.pos));
			});
			if (observation.changed) {
				ClientMc.call(() -> BotController.get().stopMining(start.pos));
				start.result.addProperty("confirmed", true);
				start.result.addProperty("confirmationSource", "client_cache");
				start.result.addProperty("finalBlock", observation.currentBlock);
				start.result.addProperty("waitedMs", elapsedMs(startedAt));
				return start.result;
			}
			if (!observation.active) {
				JsonObject data = start.result.deepCopy();
				data.addProperty("confirmed", false);
				data.addProperty("currentBlock", observation.currentBlock);
				data.addProperty("waitedMs", elapsedMs(startedAt));
				throw new RpcException("mining_interrupted",
						"Survival mining stopped or was replaced before the target changed.", data);
			}
			sleep(50);
		}

		boolean stopped = ClientMc.call(() -> BotController.get().stopMining(start.pos));
		JsonObject data = start.result.deepCopy();
		data.addProperty("confirmed", false);
		data.addProperty("waitedMs", elapsedMs(startedAt));
		data.addProperty("stoppedOnTimeout", stopped);
		throw new RpcException("mining_timeout", "Survival mining did not finish before timeout.", data);
	}

	private static JsonObject useBlockAt(RpcContext ctx) throws RpcException {
		PreciseUse preparation = ClientMc.call(() -> prepareUseBlock(ctx));
		try {
			PoseSettlement settlement = awaitUsePose(preparation);
			return ClientMc.call(() -> performUseBlock(preparation, settlement));
		} finally {
			if (preparation.manageSneak) {
				ClientMc.call(() -> {
					setSneak(ClientMc.player(), preparation.previousSneak);
					return new JsonObject();
				});
			}
		}
	}

	private static PreciseUse prepareUseBlock(RpcContext ctx) throws RpcException {
		requireControl();
		LocalPlayer player = ClientMc.player();
		BlockPos pos = BlockPos.containing(ctx.getDouble("x"), ctx.getDouble("y"), ctx.getDouble("z"));
		if (!ClientMc.level().hasChunkAt(pos)) throw RpcException.notFound("Client chunk is not loaded at " + pos.toShortString());
		Direction face = parseStrictFace(ctx.optString("face", "up"));
		double localX = unitInterval(ctx, "hitX", 0.5);
		double localY = unitInterval(ctx, "hitY", 0.5);
		double localZ = unitInterval(ctx, "hitZ", 0.5);
		switch (face) {
			case WEST -> localX = 0.0;
			case EAST -> localX = 1.0;
			case DOWN -> localY = 0.0;
			case UP -> localY = 1.0;
			case NORTH -> localZ = 0.0;
			case SOUTH -> localZ = 1.0;
		}
		Vec3 hit = new Vec3(pos.getX() + localX, pos.getY() + localY, pos.getZ() + localZ);
		if (ctx.optBool("requireReach", true) && !player.isWithinBlockInteractionRange(pos, 1.0D)) {
			throw RpcException.badRequest("Target block is outside server interaction range at " + pos.toShortString() + ".");
		}
		String handMode = ctx.optString("hand", "auto").toLowerCase();
		InteractionHand hand = parseHand(handMode);
		boolean manageSneak = ctx.has("sneak");
		boolean previousSneak = player.isShiftKeyDown();
		boolean requestedSneak = manageSneak ? ctx.optBool("sneak", false) : previousSneak;
		float previousYaw = player.getYRot();
		float previousPitch = player.getXRot();
		float yaw = ctx.has("yaw") ? (float) ctx.getDouble("yaw") : previousYaw;
		float pitch = ctx.has("pitch") ? (float) ctx.getDouble("pitch") : previousPitch;
		try {
			if (manageSneak) setSneak(player, requestedSneak);
			if (ctx.has("yaw") || ctx.has("pitch")) ControlHandlers.setAndSyncLook(player, yaw, pitch);
			return new PreciseUse(pos, ClientMc.level().dimension(), player.getUUID(), face, hit, handMode, hand,
					manageSneak, previousSneak, requestedSneak, yaw, pitch,
					ctx.has("yaw") || ctx.has("pitch"), ctx.optBool("inside", false));
		} catch (RuntimeException error) {
			if (manageSneak) setSneak(player, previousSneak);
			throw error;
		}
	}

	private static JsonObject performUseBlock(PreciseUse preparation, PoseSettlement settlement) throws RpcException {
		LocalPlayer player = ClientMc.player();
		if (!ClientMc.level().dimension().equals(preparation.dimension)) {
			throw RpcException.badRequest("Player changed dimension before the prepared block interaction.");
		}
		BlockHitResult hit = new BlockHitResult(preparation.hit, preparation.face, preparation.pos, preparation.inside);
		InteractionHand[] hands = preparation.hand == null
				? new InteractionHand[]{InteractionHand.MAIN_HAND, InteractionHand.OFF_HAND}
				: new InteractionHand[]{preparation.hand};
		JsonArray attempts = new JsonArray();
		InteractionResult interaction = InteractionResult.PASS;
		InteractionHand usedHand = hands[0];
		String heldItemId = "minecraft:air";
		for (int i = 0; i < hands.length; i++) {
			InteractionHand candidate = hands[i];
			ItemStack held = player.getItemInHand(candidate);
			String candidateItemId = itemId(held);
			InteractionResult candidateResult = ClientMc.gameMode().useItemOn(player, candidate, hit);
			JsonObject attempt = new JsonObject();
			attempt.addProperty("hand", handName(candidate));
			attempt.addProperty("heldItem", candidateItemId);
			attempt.addProperty("result", String.valueOf(candidateResult));
			attempts.add(attempt);
			interaction = candidateResult;
			usedHand = candidate;
			heldItemId = candidateItemId;
			if (candidateResult != InteractionResult.PASS || i + 1 >= hands.length) break;
		}
		if (interaction.consumesAction()) player.swing(usedHand);
		JsonObject out = new JsonObject();
		out.addProperty("result", String.valueOf(interaction));
		out.addProperty("requestedHand", preparation.handMode);
		out.addProperty("hand", handName(usedHand));
		out.addProperty("fallbackUsed", attempts.size() > 1);
		out.add("attempts", attempts);
		out.addProperty("heldItem", heldItemId);
		out.add("targetPos", blockPos(preparation.pos));
		out.addProperty("face", preparation.face.getName());
		JsonObject localHit = new JsonObject();
		localHit.addProperty("x", preparation.hit.x - preparation.pos.getX());
		localHit.addProperty("y", preparation.hit.y - preparation.pos.getY());
		localHit.addProperty("z", preparation.hit.z - preparation.pos.getZ());
		out.add("localHit", localHit);
		out.addProperty("inside", preparation.inside);
		out.addProperty("sneak", player.isShiftKeyDown());
		out.addProperty("yaw", player.getYRot());
		out.addProperty("pitch", player.getXRot());
		out.addProperty("poseSettled", settlement.confirmed);
		out.addProperty("poseSettlementSource", settlement.source);
		out.addProperty("poseWaitedMs", settlement.waitedMs);
		return out;
	}

	private static PoseSettlement awaitUsePose(PreciseUse preparation) throws RpcException {
		if (!preparation.poseChanged && !preparation.manageSneak) return new PoseSettlement(true, "unchanged", 0);
		long started = System.nanoTime();
		MinecraftServer server = ServerHolder.get();
		if (server == null) {
			sleep(100);
			return new PoseSettlement(false, "remote_network_delay", elapsedMs(started));
		}
		long deadline = started + 500_000_000L;
		while (System.nanoTime() <= deadline) {
			boolean matches = MainThread.call(server, McpFabric.config().callTimeoutMs, () -> {
				ServerPlayer serverPlayer = server.getPlayerList().getPlayer(preparation.playerId);
				return serverPlayer != null
						&& (!preparation.poseChanged || (Math.abs(Mth.wrapDegrees(serverPlayer.getYRot() - preparation.yaw)) <= 0.01F
						&& Math.abs(serverPlayer.getXRot() - preparation.pitch) <= 0.01F))
						&& (!preparation.manageSneak || serverPlayer.isShiftKeyDown() == preparation.requestedSneak);
			});
			if (matches) return new PoseSettlement(true, "integrated_server", elapsedMs(started));
			sleep(10);
		}
		throw new RpcException("pose_sync_timeout", "Integrated server did not observe the prepared block-interaction pose.");
	}

	private static Direction parseStrictFace(String name) throws RpcException {
		Direction direction = Direction.byName(name.toLowerCase());
		if (direction == null) throw RpcException.badRequest("Invalid block face: " + name);
		return direction;
	}

	private static InteractionHand parseHand(String name) throws RpcException {
		return switch (name.toLowerCase()) {
			case "auto" -> null;
			case "main", "main_hand" -> InteractionHand.MAIN_HAND;
			case "off", "off_hand" -> InteractionHand.OFF_HAND;
			default -> throw RpcException.badRequest("hand must be auto, main, or off.");
		};
	}

	private static String handName(InteractionHand hand) {
		return hand == InteractionHand.MAIN_HAND ? "main" : "off";
	}

	private static double unitInterval(RpcContext ctx, String key, double fallback) throws RpcException {
		double value = ctx.has(key) ? ctx.getDouble(key) : fallback;
		if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
			throw RpcException.badRequest(key + " must be between 0 and 1.");
		}
		return value;
	}

	private static void setSneak(LocalPlayer player, boolean sneak) {
		ClientMc.mc().options.keyShift.setDown(sneak);
		player.setShiftKeyDown(sneak);
	}

	private static void sleep(long millis) throws RpcException {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw RpcException.unavailable("Interrupted while waiting for block-interaction pose confirmation.");
		}
	}

	private static long elapsedMs(long started) {
		return (System.nanoTime() - started) / 1_000_000L;
	}

	private static void requireControl() throws RpcException {
		if (!McpFabric.config().enablePlayerControl) {
			throw RpcException.unavailable("Player control is disabled in mcpfabric.config.json (enablePlayerControl=false).");
		}
	}

	private static Entity findEntity(String uuidStr) throws RpcException {
		UUID uuid;
		try {
			uuid = UUID.fromString(uuidStr);
		} catch (IllegalArgumentException e) {
			throw RpcException.badRequest("Invalid UUID: " + uuidStr);
		}
		for (Entity e : ClientMc.level().entitiesForRendering()) {
			if (e.getUUID().equals(uuid)) return e;
		}
		throw RpcException.notFound("No visible entity with uuid " + uuid);
	}

	private static Direction parseFace(String name) {
		Direction d = Direction.byName(name.toLowerCase());
		return d == null ? Direction.UP : d;
	}

	private static String itemId(ItemStack stack) {
		return stack.isEmpty() ? "minecraft:air" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
	}

	private static Direction faceToward(BlockPos pos, Vec3 eye) {
		double dx = eye.x - (pos.getX() + 0.5);
		double dy = eye.y - (pos.getY() + 0.5);
		double dz = eye.z - (pos.getZ() + 0.5);
		double ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
		if (ax >= ay && ax >= az) return dx > 0 ? Direction.EAST : Direction.WEST;
		if (az >= ax && az >= ay) return dz > 0 ? Direction.SOUTH : Direction.NORTH;
		return dy > 0 ? Direction.UP : Direction.DOWN;
	}

	private static JsonObject blockPos(BlockPos pos) {
		JsonObject o = new JsonObject();
		o.addProperty("x", pos.getX());
		o.addProperty("y", pos.getY());
		o.addProperty("z", pos.getZ());
		return o;
	}

	private record PreciseUse(BlockPos pos, ResourceKey<Level> dimension, UUID playerId,
			Direction face, Vec3 hit, String handMode, InteractionHand hand, boolean manageSneak, boolean previousSneak,
			boolean requestedSneak, float yaw, float pitch, boolean poseChanged, boolean inside) {}

	private record PoseSettlement(boolean confirmed, String source, long waitedMs) {}
	private record BreakStart(BlockPos pos, BlockState initialState, String mode, JsonObject result, boolean complete) {}
	private record BreakObservation(boolean changed, String currentBlock, boolean active) {}
}
