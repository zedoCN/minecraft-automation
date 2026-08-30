package dev.mcpfabric.client.nav;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * A small A* pathfinder over block positions for walking bots. Considers same-level walking,
 * a one-block step-up (jump), configurable bounded drops, hazard costs, and temporary obstacles.
 * Walkability is derived from block collision shapes, so it works on any {@link BlockGetter}
 * (client or server level).
 */
public final class AStarPathfinder {
	private final BlockGetter level;
	private final int maxNodes;
	private final NavigationSafety safety;
	private final java.util.Set<Long> temporaryBlocked;

	public AStarPathfinder(BlockGetter level, int maxNodes) {
		this(level, maxNodes, NavigationSafety.Options.safeDefaults(), java.util.Set.of());
	}

	public AStarPathfinder(BlockGetter level, int maxNodes, NavigationSafety.Options options) {
		this(level, maxNodes, options, java.util.Set.of());
	}

	public AStarPathfinder(BlockGetter level, int maxNodes, NavigationSafety.Options options,
	                       java.util.Set<Long> temporaryBlocked) {
		this.level = level;
		this.maxNodes = maxNodes;
		this.safety = new NavigationSafety(level, options);
		this.temporaryBlocked = temporaryBlocked == null ? java.util.Set.of() : temporaryBlocked;
	}

	private boolean passable(BlockPos pos) {
		return safety.bodyPassable(pos);
	}

	private NavigationSafety.Assessment assess(BlockPos feet) {
		if (temporaryBlocked.contains(feet.asLong())) {
			return new NavigationSafety.Assessment(false, false, false, 0, 0.0, "temporary_obstacle");
		}
		return safety.assess(feet);
	}

	/** @return a path of block positions (excluding start, ending at/near goal), or null if none. */
	public List<BlockPos> findPath(BlockPos start, BlockPos goal, double reachRadius) {
		Node startNode = new Node(start, 0, heuristic(start, goal), null);
		PriorityQueue<Node> open = new PriorityQueue<>();
		Map<Long, Double> best = new HashMap<>();
		open.add(startNode);
		best.put(start.asLong(), 0.0);

		int expanded = 0;
		while (!open.isEmpty() && expanded < maxNodes) {
			Node current = open.poll();
			expanded++;

			if (withinReach(current.pos, goal, reachRadius)) {
				return reconstruct(current);
			}

			for (Direction dir : Direction.Plane.HORIZONTAL) {
				BlockPos h = current.pos.relative(dir);
				BlockPos next = null;
				double moveCost = 1.0;

				NavigationSafety.Assessment nextAssessment = assess(h);
				if (nextAssessment.standable() && !nextAssessment.hardHazard()) {
					next = h;
				} else if ((nextAssessment = assess(h.above())).standable()
						&& !nextAssessment.hardHazard() && passable(current.pos.above().above())) {
					next = h.above(); // step / jump up
					moveCost = 1.5;
				} else {
					for (int d = 1; d <= safety.options().maxDropBlocks(); d++) {
						nextAssessment = assess(h.below(d));
						if (nextAssessment.standable() && !nextAssessment.hardHazard()
								&& descentTransitionClear(current.pos, h.below(d))) {
							next = h.below(d);
							moveCost = 1.0 + 0.3 * d;
							break;
						}
					}
					if (next == null && safety.options().maxGapJumpBlocks() > 0) {
						for (int gap = 1; gap <= safety.options().maxGapJumpBlocks(); gap++) {
							boolean clearFlight = passable(current.pos.above().above());
							for (int skipped = 1; skipped <= gap && clearFlight; skipped++) {
								BlockPos air = current.pos.relative(dir, skipped);
								clearFlight = passable(air) && passable(air.above())
									&& passable(air.above(2)) && !safety.dangerousOccupancy(air);
							}
							BlockPos landing = current.pos.relative(dir, gap + 1);
							nextAssessment = assess(landing);
							if (clearFlight && nextAssessment.standable() && !nextAssessment.hardHazard()
									&& passable(landing.above(2))) {
								next = landing;
								moveCost = 2.2 + 0.8 * gap;
								break;
							}
						}
					}
				}
				if (next == null) continue;
				moveCost += nextAssessment.extraCost();

				double tentativeG = current.g + moveCost;
				long key = next.asLong();
				Double prev = best.get(key);
				if (prev != null && tentativeG >= prev) continue;
				best.put(key, tentativeG);
				open.add(new Node(next.immutable(), tentativeG, heuristic(next, goal), current));
			}
		}
		return null;
	}

	/**
	 * A lower destination can have enough room to stand while still being impossible to enter
	 * from the higher source: the player's head crosses the destination column before their feet
	 * fall. Require the complete vertical transition column through source head height to be clear.
	 */
	private boolean descentTransitionClear(BlockPos sourceFeet, BlockPos destinationFeet) {
		for (int y = destinationFeet.getY(); y <= sourceFeet.getY() + 1; y++) {
			if (!passable(new BlockPos(destinationFeet.getX(), y, destinationFeet.getZ()))) return false;
		}
		return true;
	}

	private static boolean withinReach(BlockPos a, BlockPos goal, double reach) {
		double dx = a.getX() - goal.getX();
		double dy = a.getY() - goal.getY();
		double dz = a.getZ() - goal.getZ();
		return Math.sqrt(dx * dx + dy * dy + dz * dz) <= Math.max(0.75, reach);
	}

	private static double heuristic(BlockPos a, BlockPos b) {
		double dx = a.getX() - b.getX();
		double dy = a.getY() - b.getY();
		double dz = a.getZ() - b.getZ();
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	private static List<BlockPos> reconstruct(Node end) {
		List<BlockPos> path = new ArrayList<>();
		for (Node n = end; n != null && n.parent != null; n = n.parent) {
			path.add(n.pos);
		}
		Collections.reverse(path);
		return path;
	}

	private static final class Node implements Comparable<Node> {
		final BlockPos pos;
		final double g;
		final double f;
		final Node parent;

		Node(BlockPos pos, double g, double h, Node parent) {
			this.pos = pos;
			this.g = g;
			this.f = g + h;
			this.parent = parent;
		}

		@Override
		public int compareTo(Node o) {
			return Double.compare(this.f, o.f);
		}
	}
}
