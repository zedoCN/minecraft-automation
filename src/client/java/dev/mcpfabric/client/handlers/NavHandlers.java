package dev.mcpfabric.client.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mcpfabric.McpFabric;
import dev.mcpfabric.bridge.Json;
import dev.mcpfabric.bridge.RpcException;
import dev.mcpfabric.bridge.RpcRouter;
import dev.mcpfabric.client.BotController;
import dev.mcpfabric.client.ClientMc;
import dev.mcpfabric.client.nav.BaritoneNavigationBackend;
import dev.mcpfabric.client.nav.NavigationPlanner;
import dev.mcpfabric.client.nav.NavigationSafety;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

/** A* navigation: start walking to a target, query status, stop. */
public final class NavHandlers {
	private static final int NODE_BUDGET = 12000;
	private static volatile String selectedBackend = "builtin";
	private static volatile String requestedBackend = "auto";
	private static volatile String fallbackReason;

	private NavHandlers() {}

	public static void register(RpcRouter router) {
		router.register("nav.preview", ctx -> ClientMc.call(() -> {
			LocalPlayer p = ClientMc.player();
			ClientLevel level = ClientMc.level();
			BlockPos start = p.blockPosition();
			BlockPos goal = BlockPos.containing(ctx.getDouble("x"), ctx.getDouble("y"), ctx.getDouble("z"));
			double reach = ctx.optDouble("reachRadius", 1.0);
			int segmentLength = Math.max(8, Math.min(48, ctx.optInt("segmentLength", 24)));
			NavigationSafety.Options safetyOptions = safetyOptions(ctx);
			if (safetyOptions.maxGapJumpBlocks() > 1) {
				throw RpcException.badRequest("preview_navigation uses the built-in backend, which supports maxGapJumpBlocks <= 1.");
			}
			NavigationPlanner.SegmentPlan plan = NavigationPlanner.plan(
				level, start, goal, reach, safetyOptions, NODE_BUDGET, segmentLength, java.util.Set.of());
			if (plan == null || plan.path().isEmpty()) {
				throw RpcException.notFound("No safe first segment found within the search budget.");
			}

			NavigationSafety safety = new NavigationSafety(level, safetyOptions);
			int exposedNodes = 0;
			int gapJumps = 0;
			int openableObstacles = 0;
			BlockPos previous = start;
			JsonArray nodes = new JsonArray();
			int outputLimit = Math.max(1, Math.min(256, ctx.optInt("maxNodesOutput", 128)));
			for (int i = 0; i < plan.path().size(); i++) {
				BlockPos node = plan.path().get(i);
				NavigationSafety.Assessment assessment = safety.assess(node);
				if (assessment.exposedEdge()) exposedNodes++;
				int horizontal = Math.abs(node.getX() - previous.getX()) + Math.abs(node.getZ() - previous.getZ());
				boolean gapJump = node.getY() == previous.getY() && horizontal > 1;
				if (gapJump) gapJumps++;
				boolean openable = safety.closedOpenable(node) || safety.closedOpenable(node.above());
				if (openable) openableObstacles++;
				if (i < outputLimit) {
					JsonObject n = blockPos(node);
					n.addProperty("exposedEdge", assessment.exposedEdge());
					n.addProperty("gapJump", gapJump);
					n.addProperty("openableObstacle", openable);
					nodes.add(n);
				}
				previous = node;
			}

			JsonObject out = new JsonObject();
			out.addProperty("backend", "builtin");
			out.addProperty("safePathFound", true);
			out.addProperty("rolling", !plan.finalSegment());
			out.addProperty("finalSegment", plan.finalSegment());
			out.addProperty("segmentLength", segmentLength);
			out.addProperty("pathLength", plan.path().size());
			out.addProperty("exposedNodes", exposedNodes);
			out.addProperty("gapJumps", gapJumps);
			out.addProperty("openableObstacles", openableObstacles);
			out.addProperty("nodesTruncated", plan.path().size() > outputLimit);
			out.add("start", blockPos(start));
			out.add("goal", blockPos(goal));
			out.add("segmentGoal", blockPos(plan.segmentGoal()));
			out.add("nodes", nodes);
			return out;
		}));

		router.register("nav.pathTo", ctx -> ClientMc.call(() -> {
			if (!McpFabric.config().enablePlayerControl) {
				throw RpcException.unavailable("Player control is disabled (enablePlayerControl=false).");
			}
			LocalPlayer p = ClientMc.player();
			ClientLevel level = ClientMc.level();
			BlockPos start = p.blockPosition();
			BlockPos goal = BlockPos.containing(ctx.getDouble("x"), ctx.getDouble("y"), ctx.getDouble("z"));
			double reach = ctx.optDouble("reachRadius", 1.0);
			boolean sprint = ctx.optBool("sprint", false);
			int timeout = ctx.optInt("timeoutSeconds", 60);
			int segmentLength = Math.max(8, Math.min(48, ctx.optInt("segmentLength", 24)));
			int segmentWaitSeconds = Math.max(1, Math.min(60, ctx.optInt("segmentWaitSeconds", 10)));
			NavigationSafety.Options safety = safetyOptions(ctx);
			NavigationSafety.Profile profile = safety.profile();
			String backend = parseBackend(ctx.optString("backend", "auto"));
			requestedBackend = backend;
			fallbackReason = null;
			BaritoneNavigationBackend baritone = BaritoneNavigationBackend.get();
			boolean baritoneEligible = safety.avoidBlockIds().isEmpty() && safety.avoidFluidIds().isEmpty();
			boolean useBaritone = backend.equals("baritone")
				|| (backend.equals("auto") && baritone.available() && baritoneEligible);
			if (backend.equals("baritone") && !baritone.available()) {
				throw RpcException.unavailable("backend=baritone requested, but no compatible Baritone API is loaded.");
			}
			if (backend.equals("baritone") && !baritoneEligible) {
				throw RpcException.badRequest("backend=baritone cannot guarantee custom avoidBlockIds/avoidFluidIds; use builtin or auto.");
			}
			long deadline = System.currentTimeMillis() + timeout * 1000L;
			BaritoneNavigationBackend.WorldActions worldActions = new BaritoneNavigationBackend.WorldActions(
				ctx.optBool("baritoneAllowBreak", false),
				ctx.optBool("baritoneAllowPlace", false),
				ctx.optBool("baritoneAllowInventory", false),
				ctx.optBool("baritoneAllowParkourPlace", false),
				ctx.optBool("baritoneAllowParkour", safety.maxGapJumpBlocks() > 0)
			);
			if (useBaritone) {
				BotController.get().stopNavigation("superseded");
				BotController.get().stopAllMovement();
				try {
					baritone.start(goal, reach, sprint, deadline, safety, worldActions);
				} catch (ReflectiveOperationException | RuntimeException error) {
					if (backend.equals("baritone")) {
						throw RpcException.unavailable("Baritone API could not start: " + error.getClass().getSimpleName()
							+ (error.getMessage() == null ? "" : ": " + error.getMessage()));
					}
					fallbackReason = "baritone_start_failed";
					useBaritone = false;
				}
				if (useBaritone) {
					selectedBackend = "baritone";
					JsonObject out = baritone.statusJson();
					out.addProperty("started", true);
					out.addProperty("requestedBackend", backend);
					out.addProperty("selectedBackend", "baritone");
					out.addProperty("safetyNote", "Baritone plans movement; MCPFabric enforces timeout, damage, air, fire, fall, parkour, sprint, break, place, and inventory guard settings.");
					return out;
				}
			}
			if (backend.equals("auto")) {
				if (!baritone.available()) fallbackReason = "baritone_not_loaded";
				else if (!baritoneEligible) fallbackReason = "custom_avoidance_requires_builtin";
			}
			if (safety.maxGapJumpBlocks() > 1) {
				String reason = backend.equals("builtin")
					? "backend=builtin supports maxGapJumpBlocks <= 1."
					: "Baritone is required for maxGapJumpBlocks 2 or 3, but it was unavailable or could not be selected.";
				throw RpcException.unavailable(reason);
			}

			NavigationPlanner.SegmentPlan plan = NavigationPlanner.plan(
				level, start, goal, reach, safety, NODE_BUDGET, segmentLength, java.util.Set.of());
			if (plan == null || plan.path().isEmpty()) {
				throw RpcException.notFound("No path found to target within the search budget (try a closer/standable target).");
			}
			baritone.stop("superseded");
			BotController.get().startNavigation(plan, goal, reach, sprint, deadline, safety, NODE_BUDGET,
				segmentLength, segmentWaitSeconds);
			selectedBackend = "builtin";

			JsonObject o = new JsonObject();
			o.addProperty("started", true);
			o.addProperty("requestedBackend", backend);
			o.addProperty("selectedBackend", "builtin");
			if (fallbackReason != null) o.addProperty("fallbackReason", fallbackReason);
			o.addProperty("pathLength", plan.path().size());
			o.addProperty("rolling", !plan.finalSegment());
			o.addProperty("finalSegment", plan.finalSegment());
			o.addProperty("segmentLength", segmentLength);
			o.addProperty("segmentWaitSeconds", segmentWaitSeconds);
			JsonObject segment = new JsonObject();
			segment.addProperty("x", plan.segmentGoal().getX());
			segment.addProperty("y", plan.segmentGoal().getY());
			segment.addProperty("z", plan.segmentGoal().getZ());
			o.add("segmentGoal", segment);
			o.addProperty("safetyProfile", profile.name().toLowerCase());
			o.addProperty("maxDropBlocks", safety.maxDropBlocks());
			o.addProperty("maxGapJumpBlocks", safety.maxGapJumpBlocks());
			o.addProperty("avoidEntities", safety.avoidEntities());
			o.addProperty("avoidHostiles", safety.avoidHostiles());
			o.addProperty("openDoors", safety.openDoors());
			o.addProperty("stopOnDamage", safety.stopOnDamage());
			o.addProperty("entityLookaheadNodes", safety.entityLookaheadNodes());
			o.addProperty("customAvoidBlockCount", safety.avoidBlockIds().size());
			o.addProperty("customAvoidFluidCount", safety.avoidFluidIds().size());
			JsonObject g = new JsonObject();
			g.addProperty("x", goal.getX());
			g.addProperty("y", goal.getY());
			g.addProperty("z", goal.getZ());
			o.add("goal", g);
			return o;
		}));

		router.register("nav.status", ctx -> ClientMc.call(() -> {
			JsonObject out = selectedBackend.equals("baritone")
				? BaritoneNavigationBackend.get().statusJson() : BotController.get().statusJson();
			out.addProperty("backend", selectedBackend);
			out.addProperty("requestedBackend", requestedBackend);
			out.addProperty("baritoneAvailable", BaritoneNavigationBackend.get().available());
			if (fallbackReason != null) out.addProperty("fallbackReason", fallbackReason);
			return out;
		}));

		router.register("nav.backends", ctx -> ClientMc.call(() -> {
			JsonObject out = new JsonObject();
			out.addProperty("default", "auto");
			out.addProperty("active", selectedBackend);
			JsonArray backends = new JsonArray();
			JsonObject builtin = new JsonObject();
			builtin.addProperty("name", "builtin");
			builtin.addProperty("available", true);
			builtin.addProperty("preciseSafetySemantics", true);
			builtin.addProperty("supportsCustomAvoidance", true);
			backends.add(builtin);
			backends.add(BaritoneNavigationBackend.get().availabilityJson());
			out.add("backends", backends);
			return out;
		}));

		router.register("nav.stop", ctx -> {
			BotController.get().stopNavigation("stopped");
			BotController.get().stopAllMovement();
			BaritoneNavigationBackend.get().stop("stopped");
			return Json.ok("navigation stopped");
		});
	}

	private static String parseBackend(String value) throws RpcException {
		String backend = value == null ? "auto" : value.trim().toLowerCase(java.util.Locale.ROOT);
		if (!backend.equals("auto") && !backend.equals("builtin") && !backend.equals("baritone")) {
			throw RpcException.badRequest("backend must be one of: auto, builtin, baritone.");
		}
		return backend;
	}

	private static NavigationSafety.Options safetyOptions(dev.mcpfabric.bridge.RpcContext ctx) {
		NavigationSafety.Profile profile = NavigationSafety.Profile.parse(ctx.optString("safetyProfile", "safe"));
		int defaultDrop = switch (profile) {
			case SAFE -> 1;
			case BALANCED -> 2;
			case RISKY -> 3;
		};
		return new NavigationSafety.Options(
			profile,
			ctx.optInt("maxDropBlocks", defaultDrop),
			ctx.optInt("maxGapJumpBlocks", 1),
			ctx.optBool("avoidEntities", true),
			ctx.optBool("avoidHostiles", true),
			ctx.optBool("openDoors", true),
			ctx.optBool("stopOnDamage", profile == NavigationSafety.Profile.SAFE),
			ctx.optInt("entityLookaheadNodes", 3),
			java.util.Set.copyOf(ctx.getStringList("avoidBlockIds")),
			java.util.Set.copyOf(ctx.getStringList("avoidFluidIds"))
		);
	}

	private static JsonObject blockPos(BlockPos pos) {
		JsonObject out = new JsonObject();
		out.addProperty("x", pos.getX());
		out.addProperty("y", pos.getY());
		out.addProperty("z", pos.getZ());
		return out;
	}
}
