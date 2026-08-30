package dev.mcpfabric.client.nav;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Plans either the final path or one bounded rolling segment toward a distant destination. */
public final class NavigationPlanner {
	private NavigationPlanner() {}

	public record SegmentPlan(List<BlockPos> path, BlockPos segmentGoal, boolean finalSegment) {}

	public static SegmentPlan plan(ClientLevel level, BlockPos start, BlockPos finalGoal,
	                               double finalReachRadius, NavigationSafety.Options safety,
	                               int maxNodes, int segmentLength, Set<Long> temporaryBlocked) {
		double distance = distance(start, finalGoal);
		if (distance <= segmentLength) {
			List<BlockPos> path = new AStarPathfinder(level, maxNodes, safety, temporaryBlocked)
				.findPath(start, finalGoal, finalReachRadius);
			if (path != null && !path.isEmpty()) return new SegmentPlan(path, path.getLast(), true);
			// A loaded but unreachable final target is a real refusal. If its chunk is not yet
			// synchronized, fall through and choose a shorter loaded rolling anchor instead.
			if (level.hasChunkAt(finalGoal)) return null;
		}

		int[] lengths = distinctLengths(segmentLength);
		for (int length : lengths) {
			double ratio = Math.min(1.0, length / distance);
			int x = (int) Math.round(start.getX() + (finalGoal.getX() - start.getX()) * ratio);
			int z = (int) Math.round(start.getZ() + (finalGoal.getZ() - start.getZ()) * ratio);
			int interpolatedY = (int) Math.round(start.getY() + (finalGoal.getY() - start.getY()) * ratio);
			int[] baseYs = interpolatedY == start.getY()
				? new int[]{start.getY()}
				: new int[]{start.getY(), interpolatedY};
			for (int baseY : baseYs) {
				for (int dy : new int[]{0, 1, -1, 2, -2, 4, -4, 8, -8}) {
					BlockPos anchor = new BlockPos(x, baseY + dy, z);
					if (!level.hasChunkAt(anchor)) continue;
					List<BlockPos> path = new AStarPathfinder(level, maxNodes, safety, temporaryBlocked)
						.findPath(start, anchor, 1.75);
					if (path != null && !path.isEmpty()) {
						return new SegmentPlan(path, path.getLast(), false);
					}
				}
			}
		}
		return null;
	}

	private static int[] distinctLengths(int requested) {
		Set<Integer> values = new HashSet<>();
		values.add(Math.max(8, requested));
		values.add(Math.max(8, requested * 3 / 4));
		values.add(Math.max(8, requested / 2));
		values.add(8);
		return values.stream().sorted(java.util.Comparator.reverseOrder()).mapToInt(Integer::intValue).toArray();
	}

	private static double distance(BlockPos a, BlockPos b) {
		double dx = a.getX() - b.getX();
		double dy = a.getY() - b.getY();
		double dz = a.getZ() - b.getZ();
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}
}
