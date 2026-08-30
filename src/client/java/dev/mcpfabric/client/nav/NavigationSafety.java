package dev.mcpfabric.client.nav;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.FluidState;

import java.util.Locale;
import java.util.Set;

/** Shared path-planning and live-driving safety rules. */
public final class NavigationSafety {
	private static final Set<String> CONTACT_HAZARDS = Set.of(
		"minecraft:fire", "minecraft:soul_fire", "minecraft:cactus",
		"minecraft:sweet_berry_bush", "minecraft:wither_rose",
		"minecraft:powder_snow", "minecraft:pointed_dripstone"
	);
	private static final Set<String> HOT_GROUND = Set.of(
		"minecraft:magma_block", "minecraft:campfire", "minecraft:soul_campfire"
	);

	public enum Profile {
		SAFE,
		BALANCED,
		RISKY;

		public static Profile parse(String value) {
			if (value == null || value.isBlank()) return SAFE;
			try {
				return valueOf(value.trim().toUpperCase(Locale.ROOT));
			} catch (IllegalArgumentException ignored) {
				return SAFE;
			}
		}
	}

	public record Options(Profile profile, int maxDropBlocks, int maxGapJumpBlocks, boolean avoidEntities,
	                      boolean avoidHostiles, boolean openDoors, boolean stopOnDamage,
	                      int entityLookaheadNodes,
	                      Set<String> avoidBlockIds, Set<String> avoidFluidIds) {
		public Options {
			if (profile == null) profile = Profile.SAFE;
			maxDropBlocks = Math.max(0, Math.min(3, maxDropBlocks));
			maxGapJumpBlocks = Math.max(0, Math.min(3, maxGapJumpBlocks));
			entityLookaheadNodes = Math.max(1, Math.min(8, entityLookaheadNodes));
			avoidBlockIds = avoidBlockIds == null ? Set.of() : Set.copyOf(avoidBlockIds);
			avoidFluidIds = avoidFluidIds == null ? Set.of() : Set.copyOf(avoidFluidIds);
		}

		public static Options safeDefaults() {
			return new Options(Profile.SAFE, 1, 1, true, true, true, true, 3, Set.of(), Set.of());
		}
	}

	public record Assessment(boolean standable, boolean hardHazard, boolean exposedEdge,
	                         int unsupportedSides, double extraCost, String reason) {}

	private final BlockGetter level;
	private final Options options;

	public NavigationSafety(BlockGetter level, Options options) {
		this.level = level;
		this.options = options == null ? Options.safeDefaults() : options;
	}

	public Options options() {
		return options;
	}

	public boolean bodyPassable(BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		// An opened door still reports its thin, rotated panel collision inside the block cell.
		// Treat supported doors/gates as traversable planning cells in either state; the closed
		// state is separately detected and opened by the live driver before it advances.
		return state.getCollisionShape(level, pos).isEmpty() || supportedOpenable(state);
	}

	/** Closed wooden doors and fence gates can be planned through, then opened natively at runtime. */
	public boolean closedOpenable(BlockPos pos) {
		if (!options.openDoors()) return false;
		BlockState state = level.getBlockState(pos);
		if (!state.hasProperty(BlockStateProperties.OPEN) || state.getValue(BlockStateProperties.OPEN)) return false;
		return supportedOpenable(state);
	}

	private boolean supportedOpenable(BlockState state) {
		return options.openDoors() && state.hasProperty(BlockStateProperties.OPEN)
			&& (state.is(BlockTags.WOODEN_DOORS) || state.getBlock() instanceof FenceGateBlock);
	}

	/** True when passing through this body cell would contact a configured block/fluid hazard. */
	public boolean dangerousOccupancy(BlockPos feet) {
		BlockState feetState = level.getBlockState(feet);
		BlockState headState = level.getBlockState(feet.above());
		String feetId = blockId(feetState);
		String headId = blockId(headState);
		FluidState feetFluid = feetState.getFluidState();
		FluidState headFluid = headState.getFluidState();
		boolean fluid = !feetFluid.isEmpty() || !headFluid.isEmpty();
		return CONTACT_HAZARDS.contains(feetId) || CONTACT_HAZARDS.contains(headId)
			|| options.avoidBlockIds().contains(feetId) || options.avoidBlockIds().contains(headId)
			|| isLava(feetFluid) || isLava(headFluid)
			|| options.avoidFluidIds().contains(fluidId(feetFluid))
			|| options.avoidFluidIds().contains(fluidId(headFluid))
			|| (fluid && options.profile() == Profile.SAFE);
	}

	public Assessment assess(BlockPos feet) {
		if (!bodyPassable(feet) || !bodyPassable(feet.above())) {
			return new Assessment(false, false, false, 0, 0.0, "collision");
		}
		if (bodyPassable(feet.below())) {
			return new Assessment(false, false, true, 4, 0.0, "no_ground");
		}

		BlockState feetState = level.getBlockState(feet);
		BlockState headState = level.getBlockState(feet.above());
		BlockState groundState = level.getBlockState(feet.below());
		FluidState feetFluid = feetState.getFluidState();
		FluidState headFluid = headState.getFluidState();

		String feetId = blockId(feetState);
		String headId = blockId(headState);
		String groundId = blockId(groundState);
		boolean contactHazard = CONTACT_HAZARDS.contains(feetId) || CONTACT_HAZARDS.contains(headId)
			|| HOT_GROUND.contains(groundId) || options.avoidBlockIds().contains(feetId)
			|| options.avoidBlockIds().contains(headId) || options.avoidBlockIds().contains(groundId);
		boolean lava = isLava(feetFluid) || isLava(headFluid);
		boolean fluid = !feetFluid.isEmpty() || !headFluid.isEmpty();
		boolean configuredFluid = options.avoidFluidIds().contains(fluidId(feetFluid))
			|| options.avoidFluidIds().contains(fluidId(headFluid));
		boolean hardHazard = contactHazard || lava || configuredFluid
			|| (fluid && options.profile() == Profile.SAFE);
		if (hardHazard) {
			String reason = lava ? "lava" : contactHazard ? "dangerous_block"
				: configuredFluid ? "configured_fluid_hazard" : "fluid";
			return new Assessment(true, true, false, 0, Double.POSITIVE_INFINITY, reason);
		}

		int unsupported = 0;
		int adjacentHazards = 0;
		for (Direction direction : Direction.Plane.HORIZONTAL) {
			BlockPos neighborFeet = feet.relative(direction);
			if (!hasSupportWithinAllowedDrop(neighborFeet)) unsupported++;
			if (isImmediateHazard(neighborFeet) || isImmediateHazard(neighborFeet.below())) adjacentHazards++;
		}
		boolean exposed = unsupported > 0;
		double cost = unsupported * switch (options.profile()) {
			case SAFE -> 10.0;
			case BALANCED -> 4.0;
			case RISKY -> 0.75;
		};
		cost += adjacentHazards * switch (options.profile()) {
			case SAFE -> 6.0;
			case BALANCED -> 2.5;
			case RISKY -> 0.5;
		};
		if (fluid) cost += options.profile() == Profile.BALANCED ? 6.0 : 2.0;
		return new Assessment(true, false, exposed, unsupported, cost,
			exposed ? "exposed_edge" : fluid ? "fluid" : "clear");
	}

	private boolean hasSupportWithinAllowedDrop(BlockPos neighborFeet) {
		// A wall or low overhang is containment, not an exposed edge.
		if (!bodyPassable(neighborFeet) || !bodyPassable(neighborFeet.above())) return true;
		for (int drop = 0; drop <= options.maxDropBlocks(); drop++) {
			BlockPos candidateFeet = neighborFeet.below(drop);
			if (bodyPassable(candidateFeet) && bodyPassable(candidateFeet.above())
					&& !bodyPassable(candidateFeet.below())) return true;
		}
		return false;
	}

	private boolean isImmediateHazard(BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		return CONTACT_HAZARDS.contains(blockId(state)) || HOT_GROUND.contains(blockId(state))
			|| options.avoidBlockIds().contains(blockId(state)) || isLava(state.getFluidState())
			|| options.avoidFluidIds().contains(fluidId(state.getFluidState()));
	}

	private static String blockId(BlockState state) {
		return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
	}

	private static boolean isLava(FluidState state) {
		if (state.isEmpty()) return false;
		String id = fluidId(state);
		return id.equals("minecraft:lava") || id.equals("minecraft:flowing_lava");
	}

	private static String fluidId(FluidState state) {
		return state.isEmpty() ? "minecraft:empty" : BuiltInRegistries.FLUID.getKey(state.getType()).toString();
	}
}
