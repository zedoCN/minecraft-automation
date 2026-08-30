package dev.mcpfabric.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mcpfabric.client.nav.NavigationSafety;
import dev.mcpfabric.client.nav.NavigationPlanner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Per-tick driver for the local player: holds desired movement input (applied through key
 * mappings so it integrates with the vanilla input pipeline), single-shot jumps, survival mining,
 * and navigation following. The single instance ticks from {@code ClientTickEvents.END_CLIENT_TICK}.
 */
public final class BotController {
	private static final BotController INSTANCE = new BotController();

	public static BotController get() {
		return INSTANCE;
	}

	private BotController() {}

	// desired movement
	private volatile boolean fwd, back, left, right, jumpHeld, sneak, sprint;
	private int jumpOnceTicks = 0;

	// survival mining
	private BlockPos miningPos;
	private Direction miningFace = Direction.UP;

	// navigation
	private List<BlockPos> path;
	private int pathIndex;
	private BlockPos navTarget;
	private BlockPos segmentGoal;
	private boolean finalSegment;
	private int segmentLength = 24;
	private int segmentsCompleted;
	private boolean awaitingSegment;
	private int segmentWaitTicks;
	private int segmentWaitLimitTicks = 200;
	private double reachRadius = 1.0;
	private boolean navSprint;
	private NavigationSafety.Options navSafety = NavigationSafety.Options.safeDefaults();
	private int navNodeBudget = 12000;
	private long navDeadline;
	private double lastDist = Double.MAX_VALUE;
	private int stuckTicks;
	private int entityWaitTicks;
	private int blockedEntityCount;
	private List<String> blockedEntityTypes = List.of();
	private int replans;
	private float lastHealth = Float.NaN;
	private int gapJumpTicks;
	private int gapJumpPrimeTicks;
	private boolean gapJumpLaunched;
	private int gapJumpsCompleted;
	private BlockPos gapJumpTarget;
	private BlockPos openingObstacle;
	private int openingObstacleTicks;
	private int openingCooldownTicks;
	private boolean edgeGuardActive;
	private boolean plannedDescent;
	private String safetyReason = "clear";
	private volatile String navState = "idle";
	private boolean drivingKeys;

	// --- public control surface (called from handlers, on the render thread) ----------------

	public synchronized void setMovement(Boolean f, Boolean b, Boolean l, Boolean r, Boolean jump, Boolean sn, Boolean sp) {
		if (f != null) fwd = f;
		if (b != null) back = b;
		if (l != null) left = l;
		if (r != null) right = r;
		if (jump != null) jumpHeld = jump;
		if (sn != null) sneak = sn;
		if (sp != null) sprint = sp;
	}

	public synchronized void stopAllMovement() {
		fwd = back = left = right = jumpHeld = sneak = sprint = false;
		jumpOnceTicks = 0;
	}

	public synchronized void jumpOnce() {
		jumpOnceTicks = Math.max(jumpOnceTicks, 2);
	}

	public synchronized BlockPos startMining(BlockPos pos, Direction face) {
		BlockPos previous = this.miningPos;
		this.miningPos = pos;
		this.miningFace = face;
		return previous;
	}

	public synchronized void stopMining() {
		this.miningPos = null;
	}

	public synchronized boolean stopMining(BlockPos expected) {
		if (this.miningPos == null || !this.miningPos.equals(expected)) return false;
		this.miningPos = null;
		return true;
	}

	public synchronized boolean isMining(BlockPos expected) {
		return this.miningPos != null && this.miningPos.equals(expected);
	}

	public synchronized void startNavigation(NavigationPlanner.SegmentPlan plan, BlockPos target,
	                                         double reachRadius, boolean sprint, long deadlineMillis,
	                                         NavigationSafety.Options safety, int nodeBudget, int segmentLength,
	                                         int segmentWaitSeconds) {
		this.path = plan.path();
		this.pathIndex = 0;
		this.navTarget = target;
		this.segmentGoal = plan.segmentGoal();
		this.finalSegment = plan.finalSegment();
		this.segmentLength = segmentLength;
		this.segmentsCompleted = 0;
		this.awaitingSegment = false;
		this.segmentWaitTicks = 0;
		this.segmentWaitLimitTicks = Math.max(20, segmentWaitSeconds * 20);
		this.reachRadius = reachRadius;
		this.navSprint = sprint;
		this.navSafety = safety == null ? NavigationSafety.Options.safeDefaults() : safety;
		this.navNodeBudget = nodeBudget;
		this.navDeadline = deadlineMillis;
		this.lastDist = Double.MAX_VALUE;
		this.stuckTicks = 0;
		this.entityWaitTicks = 0;
		this.blockedEntityCount = 0;
		this.blockedEntityTypes = List.of();
		this.replans = 0;
		LocalPlayer player = Minecraft.getInstance().player;
		this.lastHealth = player == null ? Float.NaN : player.getHealth();
		this.gapJumpTicks = 0;
		this.gapJumpPrimeTicks = 0;
		this.gapJumpLaunched = false;
		this.gapJumpsCompleted = 0;
		this.gapJumpTarget = null;
		this.jumpHeld = false;
		this.jumpOnceTicks = 0;
		this.openingObstacle = null;
		this.openingObstacleTicks = 0;
		this.openingCooldownTicks = 0;
		this.edgeGuardActive = false;
		this.plannedDescent = false;
		this.safetyReason = "clear";
		this.navState = "navigating";
	}

	public synchronized void stopNavigation(String reason) {
		stopNavigationInternal(reason);
	}

	public synchronized JsonObject statusJson() {
		JsonObject o = new JsonObject();
		boolean active = path != null;
		o.addProperty("active", active);
		o.addProperty("state", navState);
		if (navTarget != null) {
			JsonObject t = new JsonObject();
			t.addProperty("x", navTarget.getX());
			t.addProperty("y", navTarget.getY());
			t.addProperty("z", navTarget.getZ());
			o.add("target", t);
		}
		if (active) {
			o.addProperty("remainingNodes", Math.max(0, path.size() - pathIndex));
		}
		if (segmentGoal != null) {
			JsonObject segment = new JsonObject();
			segment.addProperty("x", segmentGoal.getX());
			segment.addProperty("y", segmentGoal.getY());
			segment.addProperty("z", segmentGoal.getZ());
			o.add("segmentGoal", segment);
		}
		o.addProperty("rolling", !finalSegment || segmentsCompleted > 0);
		o.addProperty("finalSegment", finalSegment);
		o.addProperty("segmentLength", segmentLength);
		o.addProperty("segmentsCompleted", segmentsCompleted);
		o.addProperty("segmentWaitTicks", segmentWaitTicks);
		o.addProperty("segmentWaitLimitTicks", segmentWaitLimitTicks);
		o.addProperty("awaitingSegment", awaitingSegment);
		o.addProperty("safetyProfile", navSafety.profile().name().toLowerCase());
		o.addProperty("maxDropBlocks", navSafety.maxDropBlocks());
		o.addProperty("maxGapJumpBlocks", navSafety.maxGapJumpBlocks());
		o.addProperty("avoidEntities", navSafety.avoidEntities());
		o.addProperty("avoidHostiles", navSafety.avoidHostiles());
		o.addProperty("openDoors", navSafety.openDoors());
		o.addProperty("stopOnDamage", navSafety.stopOnDamage());
		o.addProperty("entityLookaheadNodes", navSafety.entityLookaheadNodes());
		o.addProperty("gapJumpActive", gapJumpTicks > 0);
		o.addProperty("gapJumpPhase", gapJumpTicks <= 0 ? "idle"
			: gapJumpLaunched ? "airborne" : "priming");
		o.addProperty("gapJumpsCompleted", gapJumpsCompleted);
		o.addProperty("edgeGuard", edgeGuardActive);
		o.addProperty("plannedDescent", plannedDescent);
		o.addProperty("safetyReason", safetyReason);
		o.addProperty("blockedEntities", blockedEntityCount);
		JsonArray blockerTypes = new JsonArray();
		blockedEntityTypes.forEach(blockerTypes::add);
		o.add("blockedEntityTypes", blockerTypes);
		o.addProperty("entityWaitTicks", entityWaitTicks);
		o.addProperty("replans", replans);
		if (openingObstacle != null) {
			o.add("openingObstacle", blockPosJson(openingObstacle));
			o.addProperty("openingObstacleTicks", openingObstacleTicks);
		}
		JsonArray upcoming = new JsonArray();
		if (active) {
			for (int i = pathIndex; i < Math.min(path.size(), pathIndex + 5); i++) {
				BlockPos node = path.get(i);
				JsonObject n = new JsonObject();
				n.addProperty("x", node.getX());
				n.addProperty("y", node.getY());
				n.addProperty("z", node.getZ());
				upcoming.add(n);
			}
		}
		o.add("nextNodes", upcoming);
		LocalPlayer p = Minecraft.getInstance().player;
		if (p != null && navTarget != null) {
			o.addProperty("distance", p.position().distanceTo(Vec3.atBottomCenterOf(navTarget)));
		}
		return o;
	}

	private static JsonObject blockPosJson(BlockPos pos) {
		JsonObject out = new JsonObject();
		out.addProperty("x", pos.getX());
		out.addProperty("y", pos.getY());
		out.addProperty("z", pos.getZ());
		return out;
	}

	// --- tick --------------------------------------------------------------------------------

	public void onClientTick(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (p == null) {
			return;
		}

		synchronized (this) {
			if (path != null) {
				steer(mc, p);
			}
			boolean driving = fwd || back || left || right || jumpHeld || sneak || sprint || jumpOnceTicks > 0 || path != null;
			if (driving) {
				applyKeys(mc.options);
				drivingKeys = true;
			} else if (drivingKeys) {
				// Release any keys the bot forced down instead of continuing to stomp on them
				// every tick — without this, real keyboard input can never move the player
				// again once the bot has issued any movement command.
				releaseKeys(mc.options);
				drivingKeys = false;
			}
			if (jumpOnceTicks > 0) jumpOnceTicks--;
			if (gapJumpTicks > 0) gapJumpTicks--;
			if (gapJumpPrimeTicks > 0) gapJumpPrimeTicks--;
			tickMining(mc);
		}
	}

	private void applyKeys(Options o) {
		o.keyUp.setDown(fwd);
		o.keyDown.setDown(back);
		o.keyLeft.setDown(left);
		o.keyRight.setDown(right);
		o.keyShift.setDown(sneak);
		o.keySprint.setDown(sprint);
		o.keyJump.setDown(jumpHeld || jumpOnceTicks > 0);
	}

	private void releaseKeys(Options o) {
		o.keyUp.setDown(false);
		o.keyDown.setDown(false);
		o.keyLeft.setDown(false);
		o.keyRight.setDown(false);
		o.keyShift.setDown(false);
		o.keySprint.setDown(false);
		o.keyJump.setDown(false);
	}

	private void tickMining(Minecraft mc) {
		if (miningPos == null) return;
		MultiPlayerGameMode gm = mc.gameMode;
		if (gm == null || mc.level == null) {
			miningPos = null;
			return;
		}
		if (mc.level.getBlockState(miningPos).isAir()) {
			gm.stopDestroyBlock();
			miningPos = null;
			return;
		}
		gm.continueDestroyBlock(miningPos, miningFace);
	}

	private void steer(Minecraft mc, LocalPlayer p) {
		if (System.currentTimeMillis() > navDeadline) {
			stopNavigationInternal("timeout");
			return;
		}
		if (navSafety.stopOnDamage()
				&& !Float.isNaN(lastHealth) && p.getHealth() < lastHealth - 0.01F) {
			lastHealth = p.getHealth();
			safetyReason = "player_damage";
			stopNavigationInternal("damage_taken");
			return;
		}
		lastHealth = p.getHealth();
		if (navSafety.profile() == NavigationSafety.Profile.SAFE && p.getAirSupply() < 60) {
			safetyReason = "low_air";
			stopNavigationInternal("low_air");
			return;
		}
		if (navSafety.profile() == NavigationSafety.Profile.SAFE && p.isOnFire()) {
			safetyReason = "player_on_fire";
			stopNavigationInternal("player_on_fire");
			return;
		}
		Vec3 tgt = Vec3.atBottomCenterOf(navTarget);
		if (p.position().distanceTo(tgt) <= Math.max(reachRadius, 0.6)) {
			stopNavigationInternal("reached");
			return;
		}
		if (pathIndex >= path.size()) {
			if (finalSegment) {
				stopNavigationInternal("reached");
			} else {
				if (!awaitingSegment) {
					segmentsCompleted++;
					awaitingSegment = true;
					segmentWaitTicks = 0;
				}
				if (segmentWaitTicks % 10 == 0 && planNextSegment(mc, p, Set.of())) return;
				fwd = back = left = right = false;
				sprint = false;
				sneak = edgeGuardActive;
				navState = "waiting_for_segment";
				safetyReason = "chunk_or_route_not_ready";
				if (++segmentWaitTicks > segmentWaitLimitTicks) stopNavigationInternal("segment_unreachable");
			}
			return;
		}

		BlockPos node = path.get(pathIndex);
		NavigationSafety safety = new NavigationSafety(mc.level, navSafety);
		NavigationSafety.Assessment currentSafety = safety.assess(p.blockPosition());
		NavigationSafety.Assessment nodeSafety = safety.assess(node);
		if (!nodeSafety.standable() || nodeSafety.hardHazard()) {
			safetyReason = nodeSafety.reason();
			if (!replan(mc, p, Set.of())) {
				stopNavigationInternal("unsafe_path:" + nodeSafety.reason());
			}
			return;
		}

		plannedDescent = node.getY() < p.blockPosition().getY();
		// Vanilla sneak prevents stepping off a ledge, including a planner-approved one-block
		// descent. Release edge guard only for that exact downward transition; same-height
		// exposed paths such as one-wide bridges retain center steering and sneak protection.
		edgeGuardActive = !plannedDescent && (currentSafety.exposedEdge() || nodeSafety.exposedEdge());
		safetyReason = edgeGuardActive ? "exposed_edge" : nodeSafety.reason();

		double cx = node.getX() + 0.5;
		double cz = node.getZ() + 0.5;
		double dx = cx - p.getX();
		double dz = cz - p.getZ();
		double horiz = Math.sqrt(dx * dx + dz * dz);
		double vertical = Math.abs(node.getY() - p.getY());

		float yaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
		p.setYRot(yaw);
		p.setYHeadRot(yaw);
		p.setYBodyRot(yaw);

		BlockPos openable = safety.closedOpenable(node) ? node
			: safety.closedOpenable(node.above()) ? node.above() : null;
		if (openable != null) {
			handleOpenableObstacle(mc, p, openable, edgeGuardActive);
			return;
		}
		openingObstacle = null;
		openingObstacleTicks = 0;
		openingCooldownTicks = 0;

		List<Entity> blockers = (navSafety.avoidEntities() || navSafety.avoidHostiles())
			? blockingEntities(mc, p)
			: List.of();
		blockedEntityCount = blockers.size();
		blockedEntityTypes = blockers.stream()
			.map(entity -> BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString())
			.distinct().sorted().toList();
		if (!blockers.isEmpty()) {
			fwd = back = left = right = false;
			sprint = false;
			sneak = edgeGuardActive;
			entityWaitTicks++;
			navState = "waiting_for_entities";
			safetyReason = edgeGuardActive ? "entity_on_exposed_path" : "entity_obstacle";
			if (!edgeGuardActive && entityWaitTicks >= 20 && entityWaitTicks % 40 == 20) {
				replan(mc, p, blockedPositions(blockers, node.getY()));
			}
			return;
		}
		entityWaitTicks = 0;
		blockedEntityCount = 0;
		blockedEntityTypes = List.of();
		navState = "navigating";

		boolean startingGapJump = isGapJumpTransition(p, node);
		if (startingGapJump && p.onGround()) {
			gapJumpTicks = 20;
			gapJumpPrimeTicks = 1;
			gapJumpLaunched = false;
			gapJumpTarget = node.immutable();
			safetyReason = "gap_jump";
		}
		if (gapJumpTicks > 0 && !gapJumpLaunched && gapJumpPrimeTicks <= 0 && p.onGround()) {
			gapJumpLaunched = true;
			jumpOnceTicks = Math.max(jumpOnceTicks, 6);
		}
		if (gapJumpTicks > 0 && gapJumpTarget != null && p.getY() < gapJumpTarget.getY() - 0.75) {
			safetyReason = "gap_jump_failed";
			stopNavigationInternal("gap_jump_failed");
			return;
		}

		fwd = true;
		back = left = right = false;
		sneak = edgeGuardActive && gapJumpTicks <= 0;
		sprint = gapJumpTicks > 0 || (navSprint && !edgeGuardActive);

		if (node.getY() > p.getY() + 0.4) {
			jumpOnceTicks = Math.max(jumpOnceTicks, 1);
		}
		if (horiz < (edgeGuardActive ? 0.18 : 0.55) && vertical < 0.55) {
			if (gapJumpTarget != null && gapJumpTarget.equals(node)) {
				gapJumpsCompleted++;
				gapJumpTarget = null;
				gapJumpTicks = 0;
				gapJumpPrimeTicks = 0;
				gapJumpLaunched = false;
			}
			pathIndex++;
			plannedDescent = false;
			lastDist = Double.MAX_VALUE;
			stuckTicks = 0;
			return;
		}

		// Only horizontal progress proves that we are advancing into the next path cell. A jump in
		// place changes vertical distance and previously reset this watchdog forever under low
		// ceilings. Replan around the exact blocked node instead of blindly jumping again.
		if (horiz < lastDist - 0.01) {
			stuckTicks = 0;
			lastDist = horiz;
		} else if (++stuckTicks > 60) {
			stuckTicks = 0;
			if (edgeGuardActive) {
				stopNavigationInternal("stuck_on_exposed_path");
			} else if (!replan(mc, p, Set.of(node.asLong()))) {
				stopNavigationInternal("stuck");
			}
		}
	}

	private boolean isGapJumpTransition(LocalPlayer p, BlockPos node) {
		if (gapJumpTicks > 0 || navSafety.maxGapJumpBlocks() <= 0) return false;
		BlockPos current = p.blockPosition();
		int dx = Math.abs(node.getX() - current.getX());
		int dz = Math.abs(node.getZ() - current.getZ());
		return node.getY() == current.getY() && dx + dz == navSafety.maxGapJumpBlocks() + 1
			&& (dx == 0 || dz == 0);
	}

	private List<Entity> blockingEntities(Minecraft mc, LocalPlayer p) {
		if (mc.level == null) return List.of();
		int lookaheadIndex = Math.min(path.size() - 1, pathIndex + navSafety.entityLookaheadNodes());
		double minX = p.getX();
		double maxX = p.getX();
		double minZ = p.getZ();
		double maxZ = p.getZ();
		double minY = p.getY();
		double maxY = p.getY() + 1.8;
		for (int i = pathIndex; i <= lookaheadIndex; i++) {
			BlockPos upcoming = path.get(i);
			double x = upcoming.getX() + 0.5;
			double z = upcoming.getZ() + 0.5;
			minX = Math.min(minX, x);
			maxX = Math.max(maxX, x);
			minZ = Math.min(minZ, z);
			maxZ = Math.max(maxZ, z);
			minY = Math.min(minY, upcoming.getY());
			maxY = Math.max(maxY, upcoming.getY() + 1.8);
		}
		minX -= 0.42;
		maxX += 0.42;
		minZ -= 0.42;
		maxZ += 0.42;
		AABB corridor = new AABB(minX, minY, minZ, maxX, maxY, maxZ);
		LinkedHashSet<Entity> blockers = navSafety.avoidEntities()
			? new LinkedHashSet<>(mc.level.getEntities(p, corridor,
				entity -> entity.isAlive() && entity.isPickable()
					&& entityIntersectsExactRoute(entity, lookaheadIndex)))
			: new LinkedHashSet<>();
		if (navSafety.avoidHostiles()) {
			// The bounding box around a bent lookahead path contains large diagonal corners that
			// are not actually part of the route. Search broadly, then keep only hostiles close
			// to the player or to an exact upcoming node so a successful detour can resume.
			AABB threatSearch = corridor.inflate(3.0, 1.0, 3.0);
			blockers.addAll(mc.level.getEntities(p, threatSearch,
				entity -> entity.isAlive() && entity instanceof Enemy
					&& hostileNearRoute(entity, p, lookaheadIndex)));
		}
		return new ArrayList<>(blockers);
	}

	private boolean entityIntersectsExactRoute(Entity entity, int lookaheadIndex) {
		AABB entityBox = entity.getBoundingBox().inflate(0.1);
		for (int i = pathIndex; i <= lookaheadIndex; i++) {
			BlockPos node = path.get(i);
			AABB lane = new AABB(node.getX() + 0.05, node.getY(), node.getZ() + 0.05,
				node.getX() + 0.95, node.getY() + 1.8, node.getZ() + 0.95);
			if (entityBox.intersects(lane)) return true;
		}
		return false;
	}

	private boolean hostileNearRoute(Entity entity, LocalPlayer player, int lookaheadIndex) {
		final double radiusSq = 2.75 * 2.75;
		AABB box = entity.getBoundingBox();
		if (horizontalDistanceSq(box, player.getX(), player.getZ()) <= radiusSq) return true;
		for (int i = pathIndex; i <= lookaheadIndex; i++) {
			BlockPos node = path.get(i);
			if (horizontalDistanceSq(box, node.getX() + 0.5, node.getZ() + 0.5) <= radiusSq) return true;
		}
		return false;
	}

	private static double horizontalDistanceSq(AABB box, double x, double z) {
		double dx = x < box.minX ? box.minX - x : x > box.maxX ? x - box.maxX : 0.0;
		double dz = z < box.minZ ? box.minZ - z : z > box.maxZ ? z - box.maxZ : 0.0;
		return dx * dx + dz * dz;
	}

	private Set<Long> blockedPositions(List<Entity> blockers, int pathY) {
		Set<Long> blocked = new HashSet<>();
		for (Entity entity : blockers) {
			double margin = entity instanceof Enemy ? 2.5 : 0.35;
			AABB box = entity.getBoundingBox().inflate(margin);
			int minX = Mth.floor(box.minX);
			int maxX = Mth.floor(box.maxX);
			int minZ = Mth.floor(box.minZ);
			int maxZ = Mth.floor(box.maxZ);
			for (int x = minX; x <= maxX; x++) {
				for (int z = minZ; z <= maxZ; z++) {
					blocked.add(new BlockPos(x, pathY, z).asLong());
					blocked.add(new BlockPos(x, pathY + 1, z).asLong());
					blocked.add(new BlockPos(x, pathY - 1, z).asLong());
				}
			}
		}
		return blocked;
	}

	private void handleOpenableObstacle(Minecraft mc, LocalPlayer p, BlockPos obstacle, boolean edgeGuard) {
		fwd = back = left = right = false;
		sprint = false;
		sneak = false;
		navState = "opening_obstacle";
		safetyReason = "closed_door_or_gate";
		if (!obstacle.equals(openingObstacle)) {
			openingObstacle = obstacle.immutable();
			openingObstacleTicks = 0;
			openingCooldownTicks = 0;
		}
		openingObstacleTicks++;
		if (!p.isWithinBlockInteractionRange(obstacle, 1.0D)) {
			sneak = edgeGuard;
			fwd = true;
			return;
		}
		if (openingCooldownTicks > 0) {
			openingCooldownTicks--;
		} else if (mc.gameMode != null) {
			BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(obstacle), Direction.UP, obstacle, false);
			InteractionHand usedHand = InteractionHand.MAIN_HAND;
			InteractionResult result = mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, hit);
			if (result == InteractionResult.PASS) {
				usedHand = InteractionHand.OFF_HAND;
				result = mc.gameMode.useItemOn(p, InteractionHand.OFF_HAND, hit);
			}
			if (result.consumesAction()) p.swing(usedHand);
			openingCooldownTicks = 8;
		}
		if (openingObstacleTicks > 60) stopNavigationInternal("openable_obstacle_failed");
	}

	private boolean replan(Minecraft mc, LocalPlayer p, Set<Long> temporaryBlocked) {
		if (!planNextSegment(mc, p, temporaryBlocked)) return false;
		replans++;
		return true;
	}

	private boolean planNextSegment(Minecraft mc, LocalPlayer p, Set<Long> temporaryBlocked) {
		if (mc.level == null || navTarget == null) return false;
		NavigationPlanner.SegmentPlan replacement = NavigationPlanner.plan(
			mc.level, p.blockPosition(), navTarget, reachRadius, navSafety,
			navNodeBudget, segmentLength, temporaryBlocked);
		if (replacement == null || replacement.path().isEmpty()) return false;
		path = replacement.path();
		pathIndex = 0;
		segmentGoal = replacement.segmentGoal();
		finalSegment = replacement.finalSegment();
		awaitingSegment = false;
		segmentWaitTicks = 0;
		gapJumpTicks = 0;
		gapJumpPrimeTicks = 0;
		gapJumpLaunched = false;
		gapJumpTarget = null;
		openingObstacle = null;
		openingObstacleTicks = 0;
		openingCooldownTicks = 0;
		plannedDescent = false;
		lastDist = Double.MAX_VALUE;
		stuckTicks = 0;
		navState = "navigating";
		return true;
	}

	private void stopNavigationInternal(String reason) {
		path = null;
		navState = reason;
		fwd = back = left = right = false;
		sneak = false;
		sprint = false;
		jumpOnceTicks = 0;
		edgeGuardActive = false;
		gapJumpTicks = 0;
		gapJumpPrimeTicks = 0;
		gapJumpLaunched = false;
		gapJumpTarget = null;
		openingObstacle = null;
		openingObstacleTicks = 0;
		openingCooldownTicks = 0;
		plannedDescent = false;
	}
}
