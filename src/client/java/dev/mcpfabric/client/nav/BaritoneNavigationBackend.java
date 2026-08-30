package dev.mcpfabric.client.nav;

import com.google.gson.JsonObject;
import dev.mcpfabric.McpFabric;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Optional reflection-only bridge to Baritone. MCPFabric does not bundle or require Baritone;
 * when a compatible standalone build is present this backend uses its public API. Optional
 * implementation-specific shutdown compatibility lives separately in BaritoneShutdown.
 */
public final class BaritoneNavigationBackend {
	private static final BaritoneNavigationBackend INSTANCE = new BaritoneNavigationBackend();

	public static BaritoneNavigationBackend get() {
		return INSTANCE;
	}

	private BlockPos target;
	private double reachRadius;
	private long deadline;
	private long startedAt;
	private NavigationSafety.Options safety = NavigationSafety.Options.safeDefaults();
	private float lastHealth = Float.NaN;
	private boolean tracked;
	private boolean sprint;
	private String state = "idle";
	private String lastError;
	private WorldActions worldActions = WorldActions.safeDefaults();

	public record WorldActions(boolean allowBreak, boolean allowPlace, boolean allowInventory,
	                           boolean allowParkourPlace, boolean allowParkour) {
		public static WorldActions safeDefaults() {
			return new WorldActions(false, false, false, false, true);
		}
	}

	private BaritoneNavigationBackend() {}

	public boolean available() {
		try {
			Class.forName("baritone.api.BaritoneAPI", false, getClass().getClassLoader());
			Class.forName("baritone.api.pathing.goals.GoalBlock", false, getClass().getClassLoader());
			return true;
		} catch (ClassNotFoundException | LinkageError ignored) {
			return false;
		}
	}

	public synchronized JsonObject availabilityJson() {
		JsonObject out = new JsonObject();
		out.addProperty("name", "baritone");
		out.addProperty("available", available());
		out.addProperty("optional", true);
		out.addProperty("integration", "public_api_reflection");
		out.addProperty("supportsLongDistance", true);
		out.addProperty("supportsMining", true);
		out.addProperty("mcpSafetyMonitor", true);
		if (lastError != null) out.addProperty("lastError", lastError);
		return out;
	}

	public synchronized void start(BlockPos target, double reachRadius, boolean sprint, long deadline,
	                               NavigationSafety.Options safety, WorldActions worldActions)
			throws ReflectiveOperationException {
		if (!available()) throw new ClassNotFoundException("A compatible Baritone API is not loaded.");
		stopInternal("superseded", false);
		WorldActions actions = worldActions == null ? WorldActions.safeDefaults() : worldActions;
		configure(sprint, safety, actions);

		ClassLoader loader = getClass().getClassLoader();
		Class<?> apiClass = Class.forName("baritone.api.BaritoneAPI", true, loader);
		Object provider = apiClass.getMethod("getProvider").invoke(null);
		Object baritone = provider.getClass().getMethod("getPrimaryBaritone").invoke(provider);
		Object process = baritone.getClass().getMethod("getCustomGoalProcess").invoke(baritone);
		Class<?> goalClass = Class.forName("baritone.api.pathing.goals.Goal", true, loader);
		Object goal;
		int integerReach = (int) Math.floor(reachRadius);
		if (integerReach >= 1) {
			Class<?> nearClass = Class.forName("baritone.api.pathing.goals.GoalNear", true, loader);
			goal = nearClass.getConstructor(BlockPos.class, int.class).newInstance(target, integerReach);
		} else {
			Class<?> blockClass = Class.forName("baritone.api.pathing.goals.GoalBlock", true, loader);
			goal = blockClass.getConstructor(BlockPos.class).newInstance(target);
		}
		Method setGoalAndPath = Class.forName("baritone.api.process.ICustomGoalProcess", true, loader)
			.getMethod("setGoalAndPath", goalClass);
		setGoalAndPath.invoke(process, goal);

		this.target = target.immutable();
		this.reachRadius = reachRadius;
		this.sprint = sprint;
		this.deadline = deadline;
		this.startedAt = System.currentTimeMillis();
		this.safety = safety == null ? NavigationSafety.Options.safeDefaults() : safety;
		this.worldActions = actions;
		LocalPlayer player = Minecraft.getInstance().player;
		this.lastHealth = player == null ? Float.NaN : player.getHealth();
		this.tracked = true;
		this.state = "calculating";
		this.lastError = null;
	}

	public synchronized void onClientTick(Minecraft mc) {
		if (!tracked) return;
		LocalPlayer player = mc.player;
		if (player == null) {
			stopInternal("no_client_player", true);
			return;
		}
		if (System.currentTimeMillis() > deadline) {
			stopInternal("timeout", true);
			return;
		}
		if (safety.stopOnDamage() && !Float.isNaN(lastHealth)
				&& player.getHealth() < lastHealth - 0.01F) {
			lastHealth = player.getHealth();
			stopInternal("damage_taken", true);
			return;
		}
		lastHealth = player.getHealth();
		if (safety.profile() == NavigationSafety.Profile.SAFE && player.getAirSupply() < 60) {
			stopInternal("low_air", true);
			return;
		}
		if (safety.profile() == NavigationSafety.Profile.SAFE && player.isOnFire()) {
			stopInternal("player_on_fire", true);
			return;
		}
		if (target != null && goalReached(player)) {
			stopInternal("reached", true);
			return;
		}
		try {
			Object baritone = primaryBaritone();
			Object behavior = baritone.getClass().getMethod("getPathingBehavior").invoke(baritone);
			boolean pathing = (boolean) behavior.getClass().getMethod("isPathing").invoke(behavior);
			Optional<?> inProgress = (Optional<?>) behavior.getClass().getMethod("getInProgress").invoke(behavior);
			Object process = baritone.getClass().getMethod("getCustomGoalProcess").invoke(baritone);
			boolean processActive = (boolean) process.getClass().getMethod("isActive").invoke(process);
			Object processGoal = process.getClass().getMethod("getGoal").invoke(process);
			if (!pathing && inProgress.isEmpty() && !processActive && processGoal == null
					&& System.currentTimeMillis() - startedAt > 1000L) {
				stopInternal("no_path", false);
				return;
			}
			state = pathing ? "navigating" : inProgress.isPresent() ? "calculating" : "waiting_for_path";
		} catch (ReflectiveOperationException | RuntimeException error) {
			lastError = compactError(error);
			stopInternal("backend_error", true);
		}
	}

	public synchronized void stop(String reason) {
		stopInternal(reason, true);
	}

	public synchronized boolean tracked() {
		return tracked;
	}

	public synchronized JsonObject statusJson() {
		JsonObject out = new JsonObject();
		out.addProperty("active", tracked);
		out.addProperty("state", state);
		out.addProperty("backend", "baritone");
		out.addProperty("backendAvailable", available());
		out.addProperty("sprint", sprint);
		out.addProperty("safetyProfile", safety.profile().name().toLowerCase());
		out.addProperty("maxDropBlocks", safety.maxDropBlocks());
		out.addProperty("maxGapJumpBlocks", safety.maxGapJumpBlocks());
		out.addProperty("parkourEnabled", worldActions.allowParkour());
		out.addProperty("parkourGapLimitEnforced", false);
		out.addProperty("sprintAllowed", sprint || safety.maxGapJumpBlocks() >= 3);
		out.addProperty("stopOnDamage", safety.stopOnDamage());
		out.addProperty("mcpSafetyMonitor", true);
		out.addProperty("baritoneAllowBreak", worldActions.allowBreak());
		out.addProperty("baritoneAllowPlace", worldActions.allowPlace());
		out.addProperty("baritoneAllowInventory", worldActions.allowInventory());
		out.addProperty("baritoneAllowParkourPlace", worldActions.allowParkourPlace());
		out.addProperty("elapsedMs", startedAt == 0 ? 0 : Math.max(0, System.currentTimeMillis() - startedAt));
		if (target != null) {
			JsonObject goal = new JsonObject();
			goal.addProperty("x", target.getX());
			goal.addProperty("y", target.getY());
			goal.addProperty("z", target.getZ());
			out.add("target", goal);
			LocalPlayer player = Minecraft.getInstance().player;
			if (player != null) out.addProperty("distance",
				player.position().distanceTo(net.minecraft.world.phys.Vec3.atBottomCenterOf(target)));
		}
		if (lastError != null) out.addProperty("lastError", lastError);
		return out;
	}

	private void configure(boolean sprint, NavigationSafety.Options safety, WorldActions actions)
			throws ReflectiveOperationException {
		Class<?> apiClass = Class.forName("baritone.api.BaritoneAPI", true, getClass().getClassLoader());
		Object settings = apiClass.getMethod("getSettings").invoke(null);
		setSetting(settings, "allowBreak", actions.allowBreak());
		setSetting(settings, "allowPlace", actions.allowPlace());
		setSetting(settings, "allowInventory", actions.allowInventory());
		setSetting(settings, "allowSprint", sprint || safety.maxGapJumpBlocks() >= 3);
		setSetting(settings, "allowParkour", actions.allowParkour());
		setSetting(settings, "allowParkourPlace", actions.allowParkourPlace() && actions.allowPlace());
		setSetting(settings, "maxFallHeightNoWater", safety.maxDropBlocks());
		setSetting(settings, "maxFallHeightBucket", safety.maxDropBlocks());
		setSetting(settings, "allowOvershootDiagonalDescend", safety.profile() != NavigationSafety.Profile.SAFE);
		setSetting(settings, "avoidance", safety.avoidEntities() || safety.avoidHostiles());
	}

	private static void setSetting(Object settings, String name, Object value)
			throws ReflectiveOperationException {
		Field settingField = settings.getClass().getField(name);
		Object setting = settingField.get(settings);
		Field valueField = setting.getClass().getField("value");
		valueField.set(setting, value);
	}

	private Object pathingBehavior() throws ReflectiveOperationException {
		Object baritone = primaryBaritone();
		return baritone.getClass().getMethod("getPathingBehavior").invoke(baritone);
	}

	private Object primaryBaritone() throws ReflectiveOperationException {
		Class<?> apiClass = Class.forName("baritone.api.BaritoneAPI", true, getClass().getClassLoader());
		Object provider = apiClass.getMethod("getProvider").invoke(null);
		return provider.getClass().getMethod("getPrimaryBaritone").invoke(provider);
	}

	private boolean goalReached(LocalPlayer player) {
		BlockPos feet = player.blockPosition();
		int dx = feet.getX() - target.getX();
		int dy = feet.getY() - target.getY();
		int dz = feet.getZ() - target.getZ();
		int range = Math.max(0, (int) Math.floor(reachRadius));
		return dx * dx + dy * dy + dz * dz <= range * range;
	}

	private void stopInternal(String reason, boolean cancel) {
		if (cancel && available()) {
			try {
				Object behavior = pathingBehavior();
				behavior.getClass().getMethod("cancelEverything").invoke(behavior);
			} catch (ReflectiveOperationException | RuntimeException error) {
				lastError = compactError(error);
				McpFabric.LOGGER.warn("[mcpfabric] failed to stop Baritone cleanly: {}", lastError);
			}
		}
		tracked = false;
		state = reason;
	}

	private static String compactError(Throwable error) {
		Throwable cause = error.getCause() == null ? error : error.getCause();
		String message = cause.getMessage();
		return cause.getClass().getSimpleName() + (message == null ? "" : ": " + message);
	}
}
