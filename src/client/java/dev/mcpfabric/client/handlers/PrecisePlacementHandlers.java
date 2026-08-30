package dev.mcpfabric.client.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.mcpfabric.McpFabric;
import dev.mcpfabric.ServerHolder;
import dev.mcpfabric.bridge.MainThread;
import dev.mcpfabric.bridge.RpcContext;
import dev.mcpfabric.bridge.RpcException;
import dev.mcpfabric.bridge.RpcRouter;
import dev.mcpfabric.client.ClientMc;
import dev.mcpfabric.handlers.support.Levels;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Exact, state-aware placement plus ordered multi-block construction. */
public final class PrecisePlacementHandlers {
	private static final Direction[] SUPPORT_PREFERENCE = {
			Direction.DOWN, Direction.NORTH, Direction.SOUTH,
			Direction.WEST, Direction.EAST, Direction.UP
	};
	private static final float[] CARDINAL_YAWS = {-180.0F, -90.0F, 0.0F, 90.0F};
	private static final float[] SEARCH_PITCHES = {0.0F, -90.0F, 90.0F};

	private PrecisePlacementHandlers() {}

	public static void register(RpcRouter router) {
		router.register("interact.placeBlockAt", PrecisePlacementHandlers::placeBlockAt);
		router.register("interact.buildStructure", PrecisePlacementHandlers::buildStructure);
	}

	static JsonObject placeBlockAt(RpcContext ctx) throws RpcException {
		PlacementPreparation preparation = ClientMc.call(() -> preparePlacement(ctx));
		try {
			PoseSettlement poseSettlement = awaitPose(preparation);
			PlacementAttempt attempt = ClientMc.call(() -> performPlacement(preparation, poseSettlement));
			if (!ctx.optBool("confirm", true)) {
				if (attempt.selection.movedFromMainSlot >= 0) sleep(100);
				attempt.result.addProperty("confirmed", false);
				attempt.result.addProperty("confirmationStatus", "not_requested");
				return attempt.result;
			}
			int timeoutMs = Math.max(100, Math.min(10_000, ctx.optInt("confirmTimeoutMs", 2_000)));
			JsonObject result = confirmPlacement(attempt, timeoutMs);
			if (!result.get("confirmed").getAsBoolean()) {
				throw new RpcException("placement_unconfirmed",
						"Placement did not reach the requested server state at " + preparation.target.toShortString() + ".",
						result);
			}
			return result;
		} finally {
			boolean restoreSelected = ctx.optBool("restoreSelectedSlot", false) || preparation.selection.movedFromMainSlot >= 0;
			try {
				restoreSelection(preparation.selection, restoreSelected);
			} finally {
				restoreSneak(preparation);
			}
		}
	}

	private static PlacementPreparation preparePlacement(RpcContext ctx) throws RpcException {
		requireControl();
		ClientLevel level = ClientMc.level();
		MultiPlayerGameMode gameMode = ClientMc.gameMode();
		LocalPlayer player = ClientMc.player();
		BlockPos target = BlockPos.containing(ctx.getDouble("x"), ctx.getDouble("y"), ctx.getDouble("z"));
		if (!level.hasChunkAt(target)) {
			throw RpcException.notFound("Client chunk is not loaded at " + target.toShortString());
		}
		if (!level.getBlockState(target).canBeReplaced()) {
			throw RpcException.badRequest("Target block is not replaceable at " + target.toShortString() + ".");
		}
		String originalBlockId = Levels.blockId(level.getBlockState(target));

		ItemSelection selection = ctx.has("itemId")
				? selectInventoryItem(player, gameMode, ctx.getString("itemId"))
				: ItemSelection.current(player);
		boolean previousSneak = player.isShiftKeyDown();
		boolean manageSneak = ctx.has("sneak");
		try {
			if (manageSneak) setAndSyncSneak(player, ctx.optBool("sneak", false));
			ItemStack held = player.getMainHandItem();
			String heldItemId = itemId(held);
			if (!(held.getItem() instanceof BlockItem blockItem)) {
				throw RpcException.badRequest("Held item is not a placeable block item: " + heldItemId + ".");
			}

			JsonObject expectedProperties = ctx.optObject("expectedProperties");
			List<Direction> supports = availableSupports(level, target, ctx);
			PlacementChoice choice = choosePlacement(player, held, blockItem, target, supports, ctx, expectedProperties);
			if (!player.isWithinBlockInteractionRange(choice.hit.getBlockPos(), 1.0D)) {
				throw RpcException.badRequest("Clicked support is outside server interaction range at "
						+ choice.hit.getBlockPos().toShortString() + ". Move closer before placing at "
						+ target.toShortString() + ".");
			}
			float previousYaw = player.getYRot();
			float previousPitch = player.getXRot();
			ControlHandlers.setAndSyncLook(player, choice.yaw, choice.pitch);
			String expectedBlockId = Levels.blockId(choice.predictedState);
			boolean poseChanged = angleDistance(previousYaw, choice.yaw) > 0.01F
					|| Math.abs(previousPitch - choice.pitch) > 0.01F;
			boolean requestedSneak = manageSneak ? ctx.optBool("sneak", false) : previousSneak;
			return new PlacementPreparation(target, level.dimension(), player.getUUID(), originalBlockId, expectedBlockId,
					expectedProperties == null ? new JsonObject() : expectedProperties.deepCopy(), choice,
					selection, heldItemId, poseChanged, manageSneak, previousSneak, requestedSneak,
					ctx.optBool("allowBlockTransformation", false));
		} catch (RpcException | RuntimeException error) {
			if (manageSneak) setAndSyncSneak(player, previousSneak);
			restoreSelectionOnClient(player, gameMode, selection, true);
			throw error;
		}
	}

	private static PlacementAttempt performPlacement(PlacementPreparation preparation,
			PoseSettlement poseSettlement) throws RpcException {
		ClientLevel level = ClientMc.level();
		MultiPlayerGameMode gameMode = ClientMc.gameMode();
		LocalPlayer player = ClientMc.player();
		if (!level.dimension().equals(preparation.dimension)) {
			throw RpcException.badRequest("Player changed dimension before the prepared placement could execute.");
		}
		if (!level.getBlockState(preparation.target).canBeReplaced()) {
			throw RpcException.badRequest("Target changed before placement at " + preparation.target.toShortString() + ".");
		}
		if (!itemId(player.getMainHandItem()).equals(preparation.heldItemId)) {
			throw RpcException.badRequest("Selected item changed before placement; refusing to place a different block.");
		}
		BlockPos support = preparation.target.relative(preparation.choice.supportDirection);
		if (!level.hasChunkAt(support) || level.getBlockState(support).canBeReplaced()) {
			throw RpcException.badRequest("Support changed before placement at " + support.toShortString() + ".");
		}

		InteractionResult interaction = gameMode.useItemOn(player, InteractionHand.MAIN_HAND, preparation.choice.hit);
		player.swing(InteractionHand.MAIN_HAND);

			JsonObject result = new JsonObject();
			result.addProperty("result", String.valueOf(interaction));
			result.addProperty("heldItem", preparation.heldItemId);
			result.addProperty("selectedSlot", preparation.selection.selectedSlot);
			result.addProperty("materialSource", preparation.selection.movedFromMainSlot >= 0 ? "main_inventory" : "hotbar");
			if (preparation.selection.movedFromMainSlot >= 0) result.addProperty("movedFromSlot", preparation.selection.movedFromMainSlot);
			result.add("target", blockPos(preparation.target));
			result.add("clickedBlock", blockPos(preparation.choice.hit.getBlockPos()));
			result.addProperty("clickedFace", preparation.choice.hit.getDirection().getName());
			result.addProperty("supportDirection", preparation.choice.supportDirection.getName());
			result.addProperty("yaw", preparation.choice.yaw);
			result.addProperty("pitch", preparation.choice.pitch);
			result.addProperty("poseResolvedFromProperties", preparation.choice.semanticPose);
			result.addProperty("serverRotationQueued", true);
			result.addProperty("poseSettled", poseSettlement.confirmed);
			result.addProperty("poseSettlementSource", poseSettlement.source);
			result.addProperty("poseWaitedMs", poseSettlement.waitedMs);
			result.addProperty("sneak", player.isShiftKeyDown());
			result.addProperty("allowBlockTransformation", preparation.allowBlockTransformation);
			result.add("predictedState", Levels.describeBlock(level, preparation.target, preparation.choice.predictedState));
			JsonObject expected = new JsonObject();
			expected.addProperty("id", preparation.expectedBlockId);
			if (!preparation.expectedProperties.isEmpty()) expected.add("properties", preparation.expectedProperties.deepCopy());
			result.add("expectedState", expected);

		return new PlacementAttempt(preparation.target, preparation.dimension, preparation.originalBlockId,
				preparation.expectedBlockId,
				preparation.expectedProperties, preparation.allowBlockTransformation, result, preparation.selection);
	}

	private static PoseSettlement awaitPose(PlacementPreparation preparation) throws RpcException {
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
				ServerPlayer player = server.getPlayerList().getPlayer(preparation.playerId);
				return player != null
						&& angleDistance(player.getYRot(), preparation.choice.yaw) <= 0.01F
						&& Math.abs(player.getXRot() - preparation.choice.pitch) <= 0.01F
						&& (!preparation.manageSneak || player.isShiftKeyDown() == preparation.requestedSneak);
			});
			if (matches) return new PoseSettlement(true, "integrated_server", elapsedMs(started));
			sleep(10);
		}
		throw new RpcException("pose_sync_timeout", "Integrated server did not observe the prepared placement pose before mutation.");
	}

	private static float angleDistance(float a, float b) {
		return Math.abs(Mth.wrapDegrees(a - b));
	}

	private static PlacementChoice choosePlacement(LocalPlayer player, ItemStack held, BlockItem blockItem,
			BlockPos target, List<Direction> supports, RpcContext ctx, JsonObject expectedProperties) throws RpcException {
		float originalYaw = player.getYRot();
		float originalPitch = player.getXRot();
		List<Float> yaws = candidateValues(ctx, "yaw", originalYaw, CARDINAL_YAWS);
		List<Float> pitches = candidateValues(ctx, "pitch", originalPitch, SEARCH_PITCHES);
		PlacementChoice fallback = null;
		try {
			for (Direction support : supports) {
				BlockHitResult hit = hitFor(target, support);
				for (float yaw : yaws) {
					for (float pitch : pitches) {
						setLocalLook(player, yaw, pitch);
						BlockPlaceContext placeContext = new BlockPlaceContext(player, InteractionHand.MAIN_HAND, held, hit);
						BlockState predicted = blockItem.getBlock().getStateForPlacement(placeContext);
						if (predicted == null) continue;
						PlacementChoice candidate = new PlacementChoice(support, hit, yaw, pitch, predicted,
								expectedProperties != null && expectedProperties.size() > 0);
						if (fallback == null) fallback = candidate;
						if (expectedProperties == null || expectedProperties.size() == 0 || propertiesMatch(predicted, expectedProperties)) {
							return candidate;
						}
					}
				}
			}
		} finally {
			setLocalLook(player, originalYaw, originalPitch);
		}
		if (fallback == null) throw RpcException.badRequest("Held block cannot be placed at " + target.toShortString() + ".");
		throw RpcException.badRequest("No reachable pose/support combination predicts the requested properties at " + target.toShortString() + ".");
	}

	private static JsonObject confirmPlacement(PlacementAttempt attempt, int timeoutMs) throws RpcException {
		long started = System.nanoTime();
		long deadline = started + timeoutMs * 1_000_000L;
		MinecraftServer server = ServerHolder.get();
		JsonObject actual = null;
		String lastSignature = null;
		int stableReads = 0;
		while (System.nanoTime() <= deadline) {
			actual = server != null ? readIntegratedServer(server, attempt) : readClient(attempt);
			boolean exactMatch = stateDescriptionMatches(actual, attempt.expectedBlockId, attempt.expectedProperties);
			boolean transformedMatch = attempt.allowBlockTransformation
					&& transformedStateMatches(actual, attempt.originalBlockId, attempt.expectedBlockId,
							attempt.expectedProperties);
			boolean matches = exactMatch || transformedMatch;
			if (server != null && matches) return confirmed(attempt, actual, started, "integrated_server", transformedMatch);
			if (server == null) {
				String signature = actual.toString();
				stableReads = signature.equals(lastSignature) ? stableReads + 1 : 1;
				lastSignature = signature;
				if (matches && stableReads >= 3 && elapsedMs(started) >= 100) {
					return confirmed(attempt, actual, started, "client_cache_stable", transformedMatch);
				}
			}
			sleep(50);
		}
		attempt.result.addProperty("confirmed", false);
		attempt.result.addProperty("confirmationStatus", "timeout_or_mismatch");
		attempt.result.addProperty("confirmationSource", server != null ? "integrated_server" : "client_cache_stable");
		attempt.result.addProperty("waitedMs", elapsedMs(started));
		if (actual != null) attempt.result.add("actualState", actual);
		return attempt.result;
	}

	private static JsonObject confirmed(PlacementAttempt attempt, JsonObject actual, long started, String source,
			boolean transformed) {
		attempt.result.addProperty("confirmed", true);
		attempt.result.addProperty("confirmationStatus", transformed ? "confirmed_transformed" : "confirmed");
		attempt.result.addProperty("confirmationSource", source);
		attempt.result.addProperty("waitedMs", elapsedMs(started));
		attempt.result.addProperty("transformed", transformed);
		attempt.result.add("actualState", actual);
		return attempt.result;
	}

	private static JsonObject readIntegratedServer(MinecraftServer server, PlacementAttempt attempt) throws RpcException {
		return MainThread.call(server, McpFabric.config().callTimeoutMs, () -> {
			ServerLevel level = server.getLevel(attempt.dimension);
			if (level == null) throw RpcException.notFound("Integrated server dimension disappeared during confirmation.");
			return Levels.describeBlock(level, attempt.target, level.getBlockState(attempt.target));
		});
	}

	private static JsonObject readClient(PlacementAttempt attempt) throws RpcException {
		return ClientMc.call(() -> {
			ClientLevel level = ClientMc.level();
			return Levels.describeBlock(level, attempt.target, level.getBlockState(attempt.target));
		});
	}

	private static JsonObject buildStructure(RpcContext ctx) throws RpcException {
		JsonArray placements = ctx.params().has("placements") && ctx.params().get("placements").isJsonArray()
				? ctx.params().getAsJsonArray("placements") : null;
		if (placements == null || placements.isEmpty()) throw RpcException.badRequest("placements must be a non-empty array.");
		if (placements.size() > 64) throw RpcException.badRequest("placements is capped at 64 blocks.");
		JsonObject preflight = ClientMc.call(() -> preflight(placements));
		JsonObject result = new JsonObject();
		result.add("preflight", preflight);
		if (!preflight.get("ok").getAsBoolean() || ctx.optBool("preflightOnly", false)) {
			result.addProperty("ok", preflight.get("ok").getAsBoolean());
			result.addProperty("preflightOnly", true);
			return result;
		}

		boolean stopOnError = ctx.optBool("stopOnError", true);
		boolean restoreSelected = ctx.optBool("restoreSelectedSlot", true);
		int defaultTimeout = Math.max(100, Math.min(10_000, ctx.optInt("confirmTimeoutMs", 2_000)));
		JsonArray outcomes = new JsonArray();
		int confirmedCount = 0;
		for (int index = 0; index < placements.size(); index++) {
			JsonObject params = placements.get(index).getAsJsonObject().deepCopy();
			if (!params.has("confirm")) params.addProperty("confirm", true);
			if (!params.has("confirmTimeoutMs")) params.addProperty("confirmTimeoutMs", defaultTimeout);
			if (!params.has("restoreSelectedSlot")) params.addProperty("restoreSelectedSlot", restoreSelected);
			JsonObject outcome = new JsonObject();
			outcome.addProperty("index", index);
			try {
				JsonObject placement = placeBlockAt(new RpcContext("interact.placeBlockAt", params));
				outcome.add("placement", placement);
				boolean confirmed = placement.has("confirmed") && placement.get("confirmed").getAsBoolean();
				outcome.addProperty("ok", confirmed);
				if (confirmed) confirmedCount++;
				outcomes.add(outcome);
				if (!confirmed && stopOnError) break;
			} catch (RpcException error) {
				outcome.addProperty("ok", false);
				JsonObject errorJson = new JsonObject();
				errorJson.addProperty("code", error.code());
				errorJson.addProperty("message", error.getMessage());
				if (error.data() != null) errorJson.add("data", error.data());
				outcome.add("error", errorJson);
				outcomes.add(outcome);
				if (stopOnError) break;
			}
		}
		JsonObject postflight = verifyFinalStructure(outcomes);
		boolean complete = confirmedCount == placements.size();
		boolean finalStateMatches = postflight.get("ok").getAsBoolean();
		result.addProperty("ok", complete && finalStateMatches);
		result.addProperty("requested", placements.size());
		result.addProperty("completed", outcomes.size());
		result.addProperty("confirmed", confirmedCount);
		result.addProperty("stoppedEarly", outcomes.size() < placements.size());
		result.add("outcomes", outcomes);
		result.add("postflight", postflight);
		if (!complete || !finalStateMatches) {
			throw new RpcException("structure_unconfirmed",
					"Structure did not preserve every requested final block state.", result);
		}
		return result;
	}

	private static JsonObject verifyFinalStructure(JsonArray outcomes) throws RpcException {
		sleep(100);
		MinecraftServer server = ServerHolder.get();
		ResourceKey<Level> dimension = ClientMc.call(() -> ClientMc.level().dimension());
		if (server != null) {
			return MainThread.call(server, McpFabric.config().callTimeoutMs, () -> {
				ServerLevel level = server.getLevel(dimension);
				if (level == null) throw RpcException.notFound("Integrated server dimension disappeared during structure postflight.");
				return verifyFinalStructureOnLevel(level, outcomes, "integrated_server");
			});
		}
		return ClientMc.call(() -> verifyFinalStructureOnLevel(ClientMc.level(), outcomes, "client_cache"));
	}

	private static JsonObject verifyFinalStructureOnLevel(Level level, JsonArray outcomes, String source) {
		JsonArray issues = new JsonArray();
		int checked = 0;
		int matched = 0;
		for (JsonElement element : outcomes) {
			JsonObject outcome = element.getAsJsonObject();
			if (!outcome.has("placement")) continue;
			JsonObject placement = outcome.getAsJsonObject("placement");
			JsonObject target = placement.getAsJsonObject("target");
			JsonObject expected = placement.getAsJsonObject("expectedState");
			BlockPos pos = new BlockPos(target.get("x").getAsInt(), target.get("y").getAsInt(), target.get("z").getAsInt());
			JsonObject expectedProperties = expected.has("properties")
					? expected.getAsJsonObject("properties") : new JsonObject();
			JsonObject actual = Levels.describeBlock(level, pos, level.getBlockState(pos));
			checked++;
			String expectedId = placement.has("transformed") && placement.get("transformed").getAsBoolean()
					&& placement.has("actualState")
					? placement.getAsJsonObject("actualState").get("id").getAsString()
					: expected.get("id").getAsString();
			if (stateDescriptionMatches(actual, expectedId, expectedProperties)) {
				matched++;
				continue;
			}
			JsonObject issue = new JsonObject();
			issue.addProperty("index", outcome.get("index").getAsInt());
			issue.addProperty("code", "final_state_mismatch");
			issue.add("target", target.deepCopy());
			issue.add("expectedState", expected.deepCopy());
			issue.add("actualState", actual);
			issues.add(issue);
		}
		JsonObject result = new JsonObject();
		result.addProperty("ok", issues.isEmpty());
		result.addProperty("source", source);
		result.addProperty("checked", checked);
		result.addProperty("matched", matched);
		result.add("issues", issues);
		return result;
	}

	private static JsonObject preflight(JsonArray placements) throws RpcException {
		ClientLevel level = ClientMc.level();
		LocalPlayer player = ClientMc.player();
		Map<String, Integer> required = new HashMap<>();
		Set<BlockPos> planned = new HashSet<>();
		JsonArray issues = new JsonArray();
		for (int index = 0; index < placements.size(); index++) {
			JsonElement element = placements.get(index);
			if (!element.isJsonObject()) {
				issues.add(issue(index, "invalid_placement", "Placement must be an object."));
				continue;
			}
			JsonObject p = element.getAsJsonObject();
			if (!p.has("x") || !p.has("y") || !p.has("z") || !p.has("itemId")) {
				issues.add(issue(index, "missing_fields", "x, y, z, and itemId are required for structure builds."));
				continue;
			}
			BlockPos target = BlockPos.containing(p.get("x").getAsDouble(), p.get("y").getAsDouble(), p.get("z").getAsDouble());
			if (!planned.add(target)) issues.add(issue(index, "duplicate_target", target.toShortString()));
			if (!level.hasChunkAt(target)) issues.add(issue(index, "chunk_not_loaded", target.toShortString()));
			else if (!level.getBlockState(target).canBeReplaced()) issues.add(issue(index, "target_occupied", target.toShortString()));
			String item = normalizeItemId(p.get("itemId").getAsString());
			required.merge(item, 1, Integer::sum);
			boolean hasSupport = false;
			boolean hasReachableSupport = false;
			if (p.has("supportDirection")) {
				Direction direction = parseDirection(p.get("supportDirection").getAsString());
				BlockPos support = target.relative(direction);
				hasSupport = planned.contains(support) || (level.hasChunkAt(support) && !level.getBlockState(support).canBeReplaced());
				hasReachableSupport = hasSupport && player.isWithinBlockInteractionRange(support, 1.0D);
			} else {
				for (Direction direction : SUPPORT_PREFERENCE) {
					BlockPos support = target.relative(direction);
					if (planned.contains(support) || (level.hasChunkAt(support) && !level.getBlockState(support).canBeReplaced())) {
						hasSupport = true;
						if (player.isWithinBlockInteractionRange(support, 1.0D)) hasReachableSupport = true;
					}
				}
			}
			if (!hasSupport) issues.add(issue(index, "missing_support", target.toShortString()));
			else if (!hasReachableSupport) issues.add(issue(index, "support_out_of_reach", target.toShortString()));
		}

		Map<String, Integer> availableItems = new HashMap<>();
		Inventory inventory = player.getInventory();
		//? if >=1.21.5 {
		var items = inventory.getNonEquipmentItems();
		//?} else
		/*var items = inventory.items;*/
		for (int slot = 0; slot < Math.min(36, items.size()); slot++) {
			ItemStack stack = items.get(slot);
			if (!stack.isEmpty()) availableItems.merge(itemId(stack), stack.getCount(), Integer::sum);
		}
		for (Map.Entry<String, Integer> need : required.entrySet()) {
			int available = availableItems.getOrDefault(need.getKey(), 0);
			if (available < need.getValue()) {
				issues.add(issue(-1, "insufficient_material", need.getKey() + " needs " + need.getValue() + ", has " + available));
			}
		}
		JsonObject result = new JsonObject();
		result.addProperty("ok", issues.isEmpty());
		result.addProperty("placementCount", placements.size());
		result.add("issues", issues);
		return result;
	}

	private static List<Direction> availableSupports(ClientLevel level, BlockPos target, RpcContext ctx) throws RpcException {
		List<Direction> supports = new ArrayList<>();
		if (ctx.has("supportDirection")) {
			Direction direction = parseDirection(ctx.getString("supportDirection"));
			BlockPos support = target.relative(direction);
			if (!level.hasChunkAt(support) || level.getBlockState(support).canBeReplaced()) {
				throw RpcException.badRequest("Requested support block is replaceable or unloaded at " + support.toShortString() + ".");
			}
			supports.add(direction);
			return supports;
		}
		for (Direction direction : SUPPORT_PREFERENCE) {
			BlockPos support = target.relative(direction);
			if (level.hasChunkAt(support) && !level.getBlockState(support).canBeReplaced()) supports.add(direction);
		}
		if (supports.isEmpty()) throw RpcException.badRequest("No adjacent support block found for " + target.toShortString() + ".");
		return supports;
	}

	private static List<Float> candidateValues(RpcContext ctx, String key, float current, float[] defaults) throws RpcException {
		if (ctx.has(key)) return List.of((float) ctx.getDouble(key));
		Set<Float> values = new LinkedHashSet<>();
		values.add(current);
		for (float value : defaults) values.add(value);
		return new ArrayList<>(values);
	}

	private static BlockHitResult hitFor(BlockPos target, Direction supportDirection) {
		BlockPos clicked = target.relative(supportDirection);
		Direction face = supportDirection.getOpposite();
		Vec3 location = new Vec3(
				clicked.getX() + 0.5 + face.getStepX() * 0.5,
				clicked.getY() + 0.5 + face.getStepY() * 0.5,
				clicked.getZ() + 0.5 + face.getStepZ() * 0.5);
		return new BlockHitResult(location, face, clicked, false);
	}

	private static boolean propertiesMatch(BlockState state, JsonObject expected) {
		JsonObject described = Levels.describeBlock(ClientMc.mc().level, BlockPos.ZERO, state);
		JsonObject actual = described.has("properties") ? described.getAsJsonObject("properties") : new JsonObject();
		for (Map.Entry<String, JsonElement> entry : expected.entrySet()) {
			if (!actual.has(entry.getKey()) || !actual.get(entry.getKey()).getAsString().equals(entry.getValue().getAsString())) return false;
		}
		return true;
	}

	private static boolean stateDescriptionMatches(JsonObject actual, String expectedId, JsonObject expectedProperties) {
		if (!actual.has("id") || !expectedId.equals(actual.get("id").getAsString())) return false;
		return statePropertiesMatch(actual, expectedProperties);
	}

	private static boolean transformedStateMatches(JsonObject actual, String originalId, String expectedId,
			JsonObject expectedProperties) {
		if (!actual.has("id")) return false;
		String actualId = actual.get("id").getAsString();
		if (expectedId.equals(actualId) || originalId.equals(actualId)) return false;
		if (actual.has("air") && actual.get("air").getAsBoolean()) return false;
		return statePropertiesMatch(actual, expectedProperties);
	}

	private static boolean statePropertiesMatch(JsonObject actual, JsonObject expectedProperties) {
		JsonObject properties = actual.has("properties") ? actual.getAsJsonObject("properties") : new JsonObject();
		for (Map.Entry<String, JsonElement> entry : expectedProperties.entrySet()) {
			if (!properties.has(entry.getKey()) || !properties.get(entry.getKey()).getAsString().equals(entry.getValue().getAsString())) return false;
		}
		return true;
	}

	private static ItemSelection selectInventoryItem(LocalPlayer player, MultiPlayerGameMode gameMode, String requestedId) throws RpcException {
		String wanted = normalizeItemId(requestedId);
		Inventory inventory = player.getInventory();
		//? if >=1.21.5 {
		var items = inventory.getNonEquipmentItems();
		//?} else
		/*var items = inventory.items;*/
		int previous = selectedSlot(inventory);
		for (int slot = 0; slot < 9 && slot < items.size(); slot++) {
			ItemStack stack = items.get(slot);
			if (!stack.isEmpty() && itemId(stack).equals(wanted)) {
				InventoryHandlers.selectHotbarSlot(player, slot);
				return new ItemSelection(previous, slot, -1);
			}
		}
		for (int slot = 9; slot < Math.min(36, items.size()); slot++) {
			ItemStack stack = items.get(slot);
			if (!stack.isEmpty() && itemId(stack).equals(wanted)) {
				InventoryHandlers.swapWithHotbar(gameMode, player, slot, previous);
				InventoryHandlers.selectHotbarSlot(player, previous);
				return new ItemSelection(previous, previous, slot);
			}
		}
		throw RpcException.notFound("No " + wanted + " found in player inventory slots 0-35.");
	}

	private static void restoreSelection(ItemSelection selection, boolean restoreSelected) throws RpcException {
		if (selection.movedFromMainSlot < 0 && !restoreSelected) return;
		ClientMc.call(() -> {
			restoreSelectionOnClient(ClientMc.player(), ClientMc.gameMode(), selection, restoreSelected);
			return new JsonObject();
		});
	}

	private static void restoreSelectionOnClient(LocalPlayer player, MultiPlayerGameMode gameMode,
			ItemSelection selection, boolean restoreSelected) throws RpcException {
		if (selection.movedFromMainSlot >= 0) {
			InventoryHandlers.swapWithHotbar(gameMode, player, selection.movedFromMainSlot, selection.selectedSlot);
		}
		if (restoreSelected) InventoryHandlers.selectHotbarSlot(player, selection.previousSelectedSlot);
	}

	private static int selectedSlot(Inventory inventory) {
		//? if >=1.21.5 {
		return inventory.getSelectedSlot();
		//?} else
		/*return inventory.selected;*/
	}

	private static void requireControl() throws RpcException {
		if (!McpFabric.config().enablePlayerControl) {
			throw RpcException.unavailable("Player control is disabled in mcpfabric.config.json.");
		}
	}

	private static void setLocalLook(LocalPlayer player, float yaw, float pitch) {
		player.setYRot(yaw);
		player.setXRot(pitch);
		player.setYHeadRot(yaw);
		player.setYBodyRot(yaw);
	}

	private static void setAndSyncSneak(LocalPlayer player, boolean sneak) {
		ClientMc.mc().options.keyShift.setDown(sneak);
		player.setShiftKeyDown(sneak);
	}

	private static void restoreSneak(PlacementPreparation preparation) throws RpcException {
		if (!preparation.manageSneak) return;
		ClientMc.call(() -> {
			setAndSyncSneak(ClientMc.player(), preparation.previousSneak);
			return new JsonObject();
		});
	}

	private static Direction parseDirection(String name) throws RpcException {
		Direction direction = Direction.byName(name.toLowerCase());
		if (direction == null) throw RpcException.badRequest("Invalid direction: " + name);
		return direction;
	}

	private static String normalizeItemId(String id) {
		return id.contains(":") ? id : "minecraft:" + id;
	}

	private static String itemId(ItemStack stack) {
		return stack.isEmpty() ? "minecraft:air" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
	}

	private static JsonObject blockPos(BlockPos pos) {
		JsonObject object = new JsonObject();
		object.addProperty("x", pos.getX());
		object.addProperty("y", pos.getY());
		object.addProperty("z", pos.getZ());
		return object;
	}

	private static JsonObject issue(int index, String code, String detail) {
		JsonObject issue = new JsonObject();
		if (index >= 0) issue.addProperty("index", index);
		issue.addProperty("code", code);
		issue.addProperty("detail", detail);
		return issue;
	}

	private static void sleep(long millis) throws RpcException {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new RpcException("interrupted", "Interrupted while waiting for placement confirmation.");
		}
	}

	private static long elapsedMs(long started) {
		return (System.nanoTime() - started) / 1_000_000L;
	}

	private record PlacementChoice(Direction supportDirection, BlockHitResult hit, float yaw, float pitch,
			BlockState predictedState, boolean semanticPose) {}

	private record PlacementAttempt(BlockPos target, ResourceKey<Level> dimension, String originalBlockId,
			String expectedBlockId, JsonObject expectedProperties, boolean allowBlockTransformation, JsonObject result,
			ItemSelection selection) {}

	private record PlacementPreparation(BlockPos target, ResourceKey<Level> dimension, UUID playerId,
			String originalBlockId, String expectedBlockId, JsonObject expectedProperties, PlacementChoice choice,
			ItemSelection selection, String heldItemId, boolean poseChanged, boolean manageSneak,
			boolean previousSneak, boolean requestedSneak, boolean allowBlockTransformation) {}

	private record PoseSettlement(boolean confirmed, String source, long waitedMs) {}

	private record ItemSelection(int previousSelectedSlot, int selectedSlot, int movedFromMainSlot) {
		private static ItemSelection current(LocalPlayer player) {
			int selected = PrecisePlacementHandlers.selectedSlot(player.getInventory());
			return new ItemSelection(selected, selected, -1);
		}
	}
}
