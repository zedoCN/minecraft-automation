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
import dev.mcpfabric.client.nav.AStarPathfinder;
import dev.mcpfabric.handlers.support.Levels;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Read-only blueprint diffing and reach-aware work-site planning. */
public final class StructureEditHandlers {
	private static final int MAX_EXPLICIT_BLOCKS = 4_096;
	private static final int MAX_BOUNDS_VOLUME = 4_096;
	private static final int MAX_EDIT_DIFFERENCES = 64;
	private static final AtomicBoolean EDIT_ACTIVE = new AtomicBoolean(false);

	private StructureEditHandlers() {}

	public static void register(RpcRouter router) {
		router.register("interact.inspectStructure", StructureEditHandlers::inspectStructure);
		router.register("interact.editStructure", StructureEditHandlers::editStructure);
	}

	private static JsonObject inspectStructure(RpcContext ctx) throws RpcException {
		Blueprint blueprint = parseBlueprint(ctx);
		return inspectBlueprint(blueprint);
	}

	private static JsonObject inspectBlueprint(Blueprint blueprint) throws RpcException {
		ResourceKey<Level> dimension = ClientMc.call(() -> ClientMc.level().dimension());
		MinecraftServer server = ServerHolder.get();
		JsonObject inspection;
		if (server != null) {
			inspection = MainThread.call(server, McpFabric.config().callTimeoutMs, () -> {
				ServerLevel level = server.getLevel(dimension);
				if (level == null) throw RpcException.notFound("Integrated server dimension is unavailable during blueprint inspection.");
				return inspectOnLevel(level, blueprint, "integrated_server");
			});
		} else {
			inspection = ClientMc.call(() -> inspectOnLevel(ClientMc.level(), blueprint, "client_cache"));
		}
		return ClientMc.call(() -> addReachability(ClientMc.level(), ClientMc.player(), inspection));
	}

	private static JsonObject editStructure(RpcContext ctx) throws RpcException {
		if (!EDIT_ACTIVE.compareAndSet(false, true)) {
			throw RpcException.unavailable("Another structure edit is already active.");
		}
		try {
			return editStructureLocked(ctx);
		} finally {
			EDIT_ACTIVE.set(false);
		}
	}

	private static JsonObject editStructureLocked(RpcContext ctx) throws RpcException {
		Blueprint blueprint = parseBlueprint(ctx);
		JsonObject inspection = inspectBlueprint(blueprint);
		JsonArray differences = inspection.getAsJsonArray("differences");
		boolean allowBreak = ctx.optBool("allowBreak", false);
		boolean rollbackOnFailure = ctx.optBool("rollbackOnFailure", true);
		boolean preflightOnly = ctx.optBool("preflightOnly", false);
		int timeoutMs = Math.max(100, Math.min(10_000, ctx.optInt("confirmTimeoutMs", 2_000)));
		MinecraftServer server = ServerHolder.get();
		boolean creative = ClientMc.call(() -> ClientMc.player().isCreative());

		Map<BlockPos, ExpectedBlock> expectedByPos = new HashMap<>();
		for (ExpectedBlock expected : blueprint.blocks) expectedByPos.put(expected.pos, expected);
		JsonArray operations = new JsonArray();
		JsonArray issues = new JsonArray();
		if (differences.size() > MAX_EDIT_DIFFERENCES) {
			issues.add(issue("too_many_differences", "Edit is capped at " + MAX_EDIT_DIFFERENCES
					+ " differences; inspection found " + differences.size() + "."));
		}
		if (rollbackOnFailure && server == null) {
			issues.add(issue("rollback_unavailable", "Automatic exact rollback requires an integrated server."));
		}

		for (JsonElement element : differences) {
			JsonObject difference = element.getAsJsonObject();
			BlockPos pos = positionUnchecked(difference.getAsJsonObject("target"));
			ExpectedBlock expected = expectedByPos.get(pos);
			JsonObject actual = difference.getAsJsonObject("actualState");
			boolean actualAir = actual.has("air") && actual.get("air").getAsBoolean();
			boolean expectedAir = "minecraft:air".equals(expected.blockId);
			boolean requiresBreak = !actualAir;
			String action = expectedAir ? "remove" : (requiresBreak ? "replace" : "place");
			JsonObject operation = new JsonObject();
			if (expected.index >= 0) operation.addProperty("index", expected.index);
			if (expected.implicitAir) operation.addProperty("implicitAir", true);
			operation.add("target", blockPos(pos));
			operation.addProperty("action", action);
			operation.add("before", actual.deepCopy());
			operation.add("expectedState", difference.get("expectedState").deepCopy());
			operations.add(operation);

			if (!difference.get("currentlyReachable").getAsBoolean()) {
				JsonObject reachIssue = issue("movement_required", "Target is not currently reachable at " + pos.toShortString() + ".");
				reachIssue.add("target", blockPos(pos));
				if (difference.has("suggestedStandPosition")) {
					reachIssue.add("suggestedStandPosition", difference.get("suggestedStandPosition").deepCopy());
				}
				issues.add(reachIssue);
			}
			if (requiresBreak && !allowBreak) {
				issues.add(issue("break_required", "Set allowBreak=true to " + action + " at " + pos.toShortString() + "."));
			}
			if (requiresBreak && !creative) {
				issues.add(issue("creative_required", "Exact synchronous replacement/removal currently requires creative mode at "
						+ pos.toShortString() + "."));
			}
			if (requiresBreak && hasBlockEntity(pos)) {
				issues.add(issue("block_entity_unsupported", "Refusing to destroy a block entity at " + pos.toShortString()
						+ " because inventory/NBT rollback is not implemented."));
			}
		}
		JsonArray placementIssues = preflightPlacements(differences, expectedByPos, creative);
		for (JsonElement placementIssue : placementIssues) issues.add(placementIssue);

		JsonObject preflight = new JsonObject();
		preflight.addProperty("ok", issues.isEmpty());
		preflight.addProperty("differenceCount", differences.size());
		preflight.addProperty("operationCount", operations.size());
		preflight.addProperty("allowBreak", allowBreak);
		preflight.addProperty("rollbackOnFailure", rollbackOnFailure);
		preflight.add("issues", issues);
		preflight.add("operations", operations);
		JsonObject result = new JsonObject();
		result.add("inspection", inspection);
		result.add("preflight", preflight);
		if (differences.isEmpty()) {
			result.addProperty("ok", true);
			result.addProperty("changed", 0);
			result.addProperty("message", "Structure already matches the blueprint.");
			return result;
		}
		if (preflightOnly) {
			result.addProperty("ok", issues.isEmpty());
			result.addProperty("preflightOnly", true);
			return result;
		}
		if (!issues.isEmpty()) {
			throw new RpcException("edit_preflight_failed", "Structure edit preflight found blocking issues.", result);
		}

		ResourceKey<Level> dimension = ClientMc.call(() -> ClientMc.level().dimension());
		Map<BlockPos, BlockState> rollbackSnapshot = rollbackOnFailure
				? captureSnapshot(server, dimension, blueprint.blocks) : Map.of();
		JsonArray outcomes = new JsonArray();
		try {
			for (JsonElement element : differences) {
				JsonObject difference = element.getAsJsonObject();
				BlockPos pos = positionUnchecked(difference.getAsJsonObject("target"));
				ExpectedBlock expected = expectedByPos.get(pos);
				JsonObject current = readActual(dimension, pos);
				if (!current.toString().equals(difference.get("actualState").toString())) {
					JsonObject conflict = new JsonObject();
					conflict.add("target", blockPos(pos));
					conflict.add("inspectedState", difference.get("actualState").deepCopy());
					conflict.add("currentState", current);
					throw new RpcException("concurrent_change", "Target changed after inspection at " + pos.toShortString() + ".", conflict);
				}

				boolean actualAir = current.has("air") && current.get("air").getAsBoolean();
				boolean expectedAir = "minecraft:air".equals(expected.blockId);
				JsonObject outcome = new JsonObject();
				if (expected.index >= 0) outcome.addProperty("index", expected.index);
				outcome.add("target", blockPos(pos));
				outcome.add("before", current.deepCopy());
				if (!actualAir) {
					destroyBlock(pos, dimension, timeoutMs);
					outcome.addProperty("removed", true);
				}
				if (!expectedAir) {
					JsonObject params = new JsonObject();
					params.addProperty("x", pos.getX());
					params.addProperty("y", pos.getY());
					params.addProperty("z", pos.getZ());
					params.addProperty("itemId", expected.itemId);
					if (expected.supportDirection != null) params.addProperty("supportDirection", expected.supportDirection);
					if (!expected.properties.isEmpty()) params.add("expectedProperties", expected.properties.deepCopy());
					params.addProperty("confirm", true);
					params.addProperty("confirmTimeoutMs", timeoutMs);
					params.addProperty("restoreSelectedSlot", ctx.optBool("restoreSelectedSlot", true));
					outcome.add("placement", PrecisePlacementHandlers.placeBlockAt(
							new RpcContext("interact.placeBlockAt", params)));
				}
				outcome.addProperty("ok", true);
				outcomes.add(outcome);
			}

			JsonObject postflight = inspectBlueprint(blueprint);
			result.add("outcomes", outcomes);
			result.add("postflight", postflight);
			if (!postflight.get("ok").getAsBoolean()) {
				throw new RpcException("edit_postflight_failed", "Edited structure does not match the final blueprint.", postflight);
			}
			result.addProperty("ok", true);
			result.addProperty("changed", outcomes.size());
			result.addProperty("rolledBack", false);
			return result;
		} catch (RpcException error) {
			JsonObject failure = new JsonObject();
			failure.addProperty("code", error.code());
			failure.addProperty("message", error.getMessage());
			if (error.data() != null) failure.add("data", error.data());
			result.add("outcomes", outcomes);
			result.add("failure", failure);
			if (rollbackOnFailure) result.add("rollback", rollback(server, dimension, rollbackSnapshot));
			else result.addProperty("rolledBack", false);
			throw new RpcException("edit_failed", "Structure edit failed; inspect error data for outcome and rollback state.", result);
		}
	}

	private static boolean hasBlockEntity(BlockPos pos) throws RpcException {
		MinecraftServer server = ServerHolder.get();
		ResourceKey<Level> dimension = ClientMc.call(() -> ClientMc.level().dimension());
		if (server != null) {
			return MainThread.call(server, McpFabric.config().callTimeoutMs, () -> {
				ServerLevel level = server.getLevel(dimension);
				return level != null && level.getBlockEntity(pos) != null;
			});
		}
		return ClientMc.call(() -> ClientMc.level().getBlockEntity(pos) != null);
	}

	private static Map<BlockPos, BlockState> captureSnapshot(MinecraftServer server,
			ResourceKey<Level> dimension, List<ExpectedBlock> blocks) throws RpcException {
		if (server == null) throw RpcException.unavailable("Integrated server is required for exact rollback snapshots.");
		return MainThread.call(server, McpFabric.config().callTimeoutMs, () -> {
			ServerLevel level = server.getLevel(dimension);
			if (level == null) throw RpcException.notFound("Integrated server dimension is unavailable during snapshot.");
			Map<BlockPos, BlockState> snapshot = new LinkedHashMap<>();
			for (ExpectedBlock block : blocks) snapshot.put(block.pos.immutable(), level.getBlockState(block.pos));
			return snapshot;
		});
	}

	private static JsonObject rollback(MinecraftServer server, ResourceKey<Level> dimension,
			Map<BlockPos, BlockState> snapshot) {
		JsonObject result = new JsonObject();
		result.addProperty("attempted", true);
		try {
			MainThread.call(server, McpFabric.config().callTimeoutMs, () -> {
				ServerLevel level = server.getLevel(dimension);
				if (level == null) throw RpcException.notFound("Integrated server dimension is unavailable during rollback.");
				for (Map.Entry<BlockPos, BlockState> entry : snapshot.entrySet()) {
					level.setBlockAndUpdate(entry.getKey(), entry.getValue());
				}
				return true;
			});
			JsonObject verification = verifySnapshot(server, dimension, snapshot);
			result.addProperty("succeeded", verification.get("ok").getAsBoolean());
			result.add("verification", verification);
		} catch (RpcException rollbackError) {
			result.addProperty("succeeded", false);
			result.addProperty("error", rollbackError.getMessage());
		}
		return result;
	}

	private static JsonArray preflightPlacements(JsonArray differences,
			Map<BlockPos, ExpectedBlock> expectedByPos, boolean creative) throws RpcException {
		return ClientMc.call(() -> {
			ClientLevel level = ClientMc.level();
			LocalPlayer player = ClientMc.player();
			Map<BlockPos, Boolean> simulatedSolid = new HashMap<>();
			Map<String, Integer> required = new HashMap<>();
			JsonArray issues = new JsonArray();
			for (JsonElement element : differences) {
				JsonObject difference = element.getAsJsonObject();
				BlockPos pos = positionUnchecked(difference.getAsJsonObject("target"));
				ExpectedBlock expected = expectedByPos.get(pos);
				boolean expectedAir = "minecraft:air".equals(expected.blockId);
				if (!expectedAir) {
					required.merge(expected.itemId, 1, Integer::sum);
					List<Direction> candidates = expected.supportDirection == null
							? List.of(Direction.DOWN, Direction.NORTH, Direction.SOUTH,
									Direction.WEST, Direction.EAST, Direction.UP)
							: List.of(Direction.byName(expected.supportDirection));
					boolean hasSupport = false;
					for (Direction direction : candidates) {
						if (direction == null) continue;
						BlockPos support = pos.relative(direction);
						boolean solid = simulatedSolid.computeIfAbsent(support,
								key -> level.hasChunkAt(key) && !level.getBlockState(key).canBeReplaced());
						if (solid) {
							hasSupport = true;
							break;
						}
					}
					if (!hasSupport) {
						JsonObject supportIssue = issue("missing_support_in_order",
								"No support will exist when editing " + pos.toShortString() + ". Reorder the blueprint or set supportDirection.");
						supportIssue.add("target", blockPos(pos));
						issues.add(supportIssue);
					}
				}
				simulatedSolid.put(pos, !expectedAir);
			}

			Map<String, Integer> available = new HashMap<>();
			Inventory inventory = player.getInventory();
			//? if >=1.21.5 {
			var items = inventory.getNonEquipmentItems();
			//?} else
			/*var items = inventory.items;*/
			for (int slot = 0; slot < Math.min(36, items.size()); slot++) {
				ItemStack stack = items.get(slot);
				if (!stack.isEmpty()) {
					available.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
				}
			}
			for (Map.Entry<String, Integer> need : required.entrySet()) {
				int requiredCount = creative ? 1 : need.getValue();
				int availableCount = available.getOrDefault(need.getKey(), 0);
				if (availableCount < requiredCount) {
					issues.add(issue("insufficient_material", need.getKey() + " needs " + requiredCount
							+ ", inventory has " + availableCount + "."));
				}
			}
			return issues;
		});
	}

	private static JsonObject verifySnapshot(MinecraftServer server, ResourceKey<Level> dimension,
			Map<BlockPos, BlockState> snapshot) throws RpcException {
		return MainThread.call(server, McpFabric.config().callTimeoutMs, () -> {
			ServerLevel level = server.getLevel(dimension);
			if (level == null) throw RpcException.notFound("Integrated server dimension is unavailable during rollback verification.");
			JsonArray mismatches = new JsonArray();
			for (Map.Entry<BlockPos, BlockState> entry : snapshot.entrySet()) {
				BlockState actual = level.getBlockState(entry.getKey());
				if (actual.equals(entry.getValue())) continue;
				JsonObject mismatch = new JsonObject();
				mismatch.add("target", blockPos(entry.getKey()));
				mismatch.add("expectedState", Levels.describeBlock(level, entry.getKey(), entry.getValue()));
				mismatch.add("actualState", Levels.describeBlock(level, entry.getKey(), actual));
				mismatches.add(mismatch);
			}
			JsonObject result = new JsonObject();
			result.addProperty("ok", mismatches.isEmpty());
			result.addProperty("checked", snapshot.size());
			result.add("mismatches", mismatches);
			return result;
		});
	}

	private static void destroyBlock(BlockPos pos, ResourceKey<Level> dimension, int timeoutMs) throws RpcException {
		boolean destroyed = ClientMc.call(() -> {
			LocalPlayer player = ClientMc.player();
			boolean result = ClientMc.gameMode().startDestroyBlock(pos, Direction.UP);
			player.swing(InteractionHand.MAIN_HAND);
			return result;
		});
		if (!destroyed) throw RpcException.badRequest("Client refused to start destroying block at " + pos.toShortString() + ".");
		long started = System.nanoTime();
		long deadline = started + timeoutMs * 1_000_000L;
		while (System.nanoTime() <= deadline) {
			JsonObject actual = readActual(dimension, pos);
			if (actual.has("air") && actual.get("air").getAsBoolean()) return;
			sleep(25);
		}
		throw new RpcException("removal_unconfirmed", "Server did not confirm air after removing " + pos.toShortString() + ".");
	}

	private static JsonObject readActual(ResourceKey<Level> dimension, BlockPos pos) throws RpcException {
		MinecraftServer server = ServerHolder.get();
		if (server != null) {
			return MainThread.call(server, McpFabric.config().callTimeoutMs, () -> {
				ServerLevel level = server.getLevel(dimension);
				if (level == null) throw RpcException.notFound("Integrated server dimension is unavailable during edit confirmation.");
				return Levels.describeBlock(level, pos, level.getBlockState(pos));
			});
		}
		return ClientMc.call(() -> Levels.describeBlock(ClientMc.level(), pos, ClientMc.level().getBlockState(pos)));
	}

	private static JsonObject issue(String code, String detail) {
		JsonObject issue = new JsonObject();
		issue.addProperty("code", code);
		issue.addProperty("detail", detail);
		return issue;
	}

	private static void sleep(long millis) throws RpcException {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new RpcException("interrupted", "Interrupted while waiting for structure edit confirmation.");
		}
	}

	private static Blueprint parseBlueprint(RpcContext ctx) throws RpcException {
		JsonArray blocks = ctx.params().has("blocks") && ctx.params().get("blocks").isJsonArray()
				? ctx.params().getAsJsonArray("blocks") : null;
		if (blocks == null || blocks.isEmpty()) throw RpcException.badRequest("blocks must be a non-empty blueprint array.");
		if (blocks.size() > MAX_EXPLICIT_BLOCKS) {
			throw RpcException.badRequest("Blueprint blocks are capped at " + MAX_EXPLICIT_BLOCKS + ".");
		}
		LinkedHashMap<BlockPos, ExpectedBlock> expected = new LinkedHashMap<>();
		for (int index = 0; index < blocks.size(); index++) {
			JsonElement element = blocks.get(index);
			if (!element.isJsonObject()) throw RpcException.badRequest("Blueprint block at index " + index + " must be an object.");
			JsonObject block = element.getAsJsonObject();
			for (String required : List.of("x", "y", "z", "blockId")) {
				if (!block.has(required)) throw RpcException.badRequest("Blueprint block at index " + index + " is missing " + required + ".");
			}
			BlockPos pos = BlockPos.containing(block.get("x").getAsDouble(), block.get("y").getAsDouble(), block.get("z").getAsDouble());
			if (expected.containsKey(pos)) throw RpcException.badRequest("Duplicate blueprint target at " + pos.toShortString() + ".");
			String blockId = normalizeBlockId(block.get("blockId").getAsString());
			JsonObject properties = block.has("expectedProperties")
					? block.getAsJsonObject("expectedProperties").deepCopy() : new JsonObject();
			String itemId = block.has("itemId") ? normalizeBlockId(block.get("itemId").getAsString()) : blockId;
			String supportDirection = block.has("supportDirection") ? block.get("supportDirection").getAsString() : null;
			if (supportDirection != null && Direction.byName(supportDirection) == null) {
				throw RpcException.badRequest("Invalid supportDirection at blueprint index " + index + ": " + supportDirection);
			}
			expected.put(pos, new ExpectedBlock(index, pos, blockId, properties, false, itemId, supportDirection));
		}

		boolean unspecifiedAsAir = ctx.optBool("unspecifiedAsAir", false);
		if (unspecifiedAsAir) {
			JsonObject bounds = ctx.optObject("bounds");
			if (bounds == null || !bounds.has("from") || !bounds.has("to")) {
				throw RpcException.badRequest("bounds.from and bounds.to are required when unspecifiedAsAir=true.");
			}
			BlockPos from = position(bounds.getAsJsonObject("from"));
			BlockPos to = position(bounds.getAsJsonObject("to"));
			int minX = Math.min(from.getX(), to.getX());
			int minY = Math.min(from.getY(), to.getY());
			int minZ = Math.min(from.getZ(), to.getZ());
			int maxX = Math.max(from.getX(), to.getX());
			int maxY = Math.max(from.getY(), to.getY());
			int maxZ = Math.max(from.getZ(), to.getZ());
			long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
			if (volume > MAX_BOUNDS_VOLUME) throw RpcException.badRequest("Blueprint bounds volume is capped at " + MAX_BOUNDS_VOLUME + ".");
			for (int y = minY; y <= maxY; y++) {
				for (int z = minZ; z <= maxZ; z++) {
					for (int x = minX; x <= maxX; x++) {
						BlockPos pos = new BlockPos(x, y, z);
						expected.putIfAbsent(pos, new ExpectedBlock(-1, pos, "minecraft:air", new JsonObject(), true,
								"minecraft:air", null));
					}
				}
			}
		}
		return new Blueprint(new ArrayList<>(expected.values()), ctx.optBool("includeMatches", false), unspecifiedAsAir);
	}

	private static JsonObject inspectOnLevel(Level level, Blueprint blueprint, String source) {
		JsonArray differences = new JsonArray();
		JsonArray matches = new JsonArray();
		int missing = 0;
		int unexpected = 0;
		int wrongBlock = 0;
		int wrongState = 0;
		for (ExpectedBlock expected : blueprint.blocks) {
			JsonObject actual = Levels.describeBlock(level, expected.pos, level.getBlockState(expected.pos));
			String code = differenceCode(expected, actual);
			JsonObject entry = comparison(expected, actual, code);
			if (code == null) {
				if (blueprint.includeMatches) matches.add(entry);
				continue;
			}
			differences.add(entry);
			switch (code) {
				case "missing_block" -> missing++;
				case "unexpected_block" -> unexpected++;
				case "wrong_block" -> wrongBlock++;
				case "wrong_state" -> wrongState++;
				default -> { }
			}
		}
		JsonObject summary = new JsonObject();
		summary.addProperty("expected", blueprint.blocks.size());
		summary.addProperty("matching", blueprint.blocks.size() - differences.size());
		summary.addProperty("different", differences.size());
		summary.addProperty("missing", missing);
		summary.addProperty("unexpected", unexpected);
		summary.addProperty("wrongBlock", wrongBlock);
		summary.addProperty("wrongState", wrongState);
		JsonObject result = new JsonObject();
		result.addProperty("ok", differences.isEmpty());
		result.addProperty("source", source);
		result.addProperty("unspecifiedAsAir", blueprint.unspecifiedAsAir);
		result.add("summary", summary);
		result.add("differences", differences);
		if (blueprint.includeMatches) result.add("matches", matches);
		return result;
	}

	private static JsonObject addReachability(ClientLevel level, LocalPlayer player, JsonObject inspection) {
		JsonArray differences = inspection.getAsJsonArray("differences");
		Map<BlockPos, JsonObject> sites = new LinkedHashMap<>();
		int reachable = 0;
		for (JsonElement element : differences) {
			JsonObject difference = element.getAsJsonObject();
			BlockPos target = positionUnchecked(difference.getAsJsonObject("target"));
			boolean inReach = player.isWithinBlockInteractionRange(target, 1.0D);
			difference.addProperty("currentlyReachable", inReach);
			if (inReach) {
				reachable++;
				continue;
			}
			BlockPos stand = findStandPosition(level, player, target);
			if (stand == null) continue;
			difference.add("suggestedStandPosition", blockPos(stand));
			JsonObject site = sites.computeIfAbsent(stand, ignored -> {
				JsonObject created = new JsonObject();
				created.add("standPosition", blockPos(stand));
				created.add("targets", new JsonArray());
				created.addProperty("pathValidated", false);
				return created;
			});
			site.getAsJsonArray("targets").add(difference.get("target").deepCopy());
		}
		JsonObject reachability = new JsonObject();
		reachability.addProperty("currentlyReachable", reachable);
		reachability.addProperty("requiresMovement", differences.size() - reachable);
		JsonArray workSites = new JsonArray();
		int validatedSites = 0;
		for (Map.Entry<BlockPos, JsonObject> entry : sites.entrySet()) {
			JsonObject site = entry.getValue();
			if (validatedSites < 32) {
				List<BlockPos> path = new AStarPathfinder(level, 4_000)
						.findPath(player.blockPosition(), entry.getKey(), 0.6D);
				boolean valid = path != null && !path.isEmpty();
				site.addProperty("pathValidated", valid);
				if (valid) site.addProperty("pathLength", path.size());
				validatedSites++;
			}
			workSites.add(site);
		}
		reachability.addProperty("pathValidationCapped", sites.size() > validatedSites);
		reachability.add("suggestedWorkSites", workSites);
		inspection.add("reachability", reachability);
		return inspection;
	}

	private static BlockPos findStandPosition(ClientLevel level, LocalPlayer player, BlockPos target) {
		double range = player.blockInteractionRange() + 1.0D;
		Set<BlockPos> candidates = new HashSet<>();
		for (int dy = -2; dy <= 2; dy++) {
			for (int dz = -5; dz <= 5; dz++) {
				for (int dx = -5; dx <= 5; dx++) {
					BlockPos feet = target.offset(dx, dy, dz);
					if (!level.hasChunkAt(feet) || !level.getBlockState(feet).canBeReplaced()) continue;
					if (!level.getBlockState(feet.above()).canBeReplaced()) continue;
					if (level.getBlockState(feet.below()).canBeReplaced()) continue;
					Vec3 eye = new Vec3(feet.getX() + 0.5D, feet.getY() + player.getEyeHeight(), feet.getZ() + 0.5D);
					if (new AABB(target).distanceToSqr(eye) >= range * range) continue;
					candidates.add(feet);
				}
			}
		}
		return candidates.stream().min(Comparator.comparingDouble(pos -> pos.distSqr(player.blockPosition()))).orElse(null);
	}

	private static String differenceCode(ExpectedBlock expected, JsonObject actual) {
		String actualId = actual.get("id").getAsString();
		if (!expected.blockId.equals(actualId)) {
			if ("minecraft:air".equals(expected.blockId)) return "unexpected_block";
			if (actual.has("air") && actual.get("air").getAsBoolean()) return "missing_block";
			return "wrong_block";
		}
		JsonObject actualProperties = actual.has("properties") ? actual.getAsJsonObject("properties") : new JsonObject();
		for (Map.Entry<String, JsonElement> property : expected.properties.entrySet()) {
			if (!actualProperties.has(property.getKey())
					|| !actualProperties.get(property.getKey()).getAsString().equals(property.getValue().getAsString())) {
				return "wrong_state";
			}
		}
		return null;
	}

	private static JsonObject comparison(ExpectedBlock expected, JsonObject actual, String code) {
		JsonObject result = new JsonObject();
		if (expected.index >= 0) result.addProperty("index", expected.index);
		if (expected.implicitAir) result.addProperty("implicitAir", true);
		result.add("target", blockPos(expected.pos));
		if (code != null) result.addProperty("code", code);
		JsonObject expectedState = new JsonObject();
		expectedState.addProperty("id", expected.blockId);
		if (!expected.properties.isEmpty()) expectedState.add("properties", expected.properties.deepCopy());
		result.add("expectedState", expectedState);
		result.add("actualState", actual);
		return result;
	}

	private static BlockPos position(JsonObject object) throws RpcException {
		if (object == null || !object.has("x") || !object.has("y") || !object.has("z")) {
			throw RpcException.badRequest("Position requires x, y, and z.");
		}
		return BlockPos.containing(object.get("x").getAsDouble(), object.get("y").getAsDouble(), object.get("z").getAsDouble());
	}

	private static BlockPos positionUnchecked(JsonObject object) {
		return new BlockPos(object.get("x").getAsInt(), object.get("y").getAsInt(), object.get("z").getAsInt());
	}

	private static String normalizeBlockId(String id) {
		return id.contains(":") ? id : "minecraft:" + id;
	}

	private static JsonObject blockPos(BlockPos pos) {
		JsonObject object = new JsonObject();
		object.addProperty("x", pos.getX());
		object.addProperty("y", pos.getY());
		object.addProperty("z", pos.getZ());
		return object;
	}

	private record ExpectedBlock(int index, BlockPos pos, String blockId,
			JsonObject properties, boolean implicitAir, String itemId, String supportDirection) {}

	private record Blueprint(List<ExpectedBlock> blocks, boolean includeMatches,
			boolean unspecifiedAsAir) {}
}
