package dev.mcpfabric.client.nav;

import dev.mcpfabric.McpFabric;
import dev.mcpfabric.platform.LoaderPlatform;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Optional Baritone 1.18 lifecycle compatibility; never used when merely leaving a world. */
public final class BaritoneShutdown {
	private static final AtomicBoolean PREPARED = new AtomicBoolean();
	private static final AtomicBoolean CLOSED = new AtomicBoolean();

	private BaritoneShutdown() {}

	/** Runs on the client thread while its world is still available. */
	public static void prepareClientExit() {
		if (!LoaderPlatform.isModLoaded("baritone") || !PREPARED.compareAndSet(false, true)) return;
		try {
			ClassLoader loader = BaritoneShutdown.class.getClassLoader();
			Class<?> api = Class.forName("baritone.api.BaritoneAPI", true, loader);
			Object provider = api.getMethod("getProvider").invoke(null);
			Object all = Class.forName("baritone.api.IBaritoneProvider", true, loader)
				.getMethod("getAllBaritones").invoke(provider);
			Class<?> baritoneApi = Class.forName("baritone.api.IBaritone", true, loader);
			for (Object baritone : (Iterable<?>) all) {
				try {
					Object behavior = baritoneApi.getMethod("getPathingBehavior").invoke(baritone);
					behavior.getClass().getMethod("cancelEverything").invoke(behavior);
				} catch (ReflectiveOperationException | RuntimeException error) {
					warn("cancel path", error);
				}
				try {
					Object world = baritoneApi.getMethod("getWorldProvider").invoke(baritone);
					// Compatibility method, not IWorldProvider API: onClose queues the final cache save.
					world.getClass().getMethod("closeWorld").invoke(world);
				} catch (ReflectiveOperationException | RuntimeException error) {
					warn("close cached world", error);
				}
			}
		} catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
			warn("prepare client exit", error);
		}
	}

	/** Called only after Minecraft.close returns, so later disconnect callbacks cannot submit work. */
	public static void afterClientClose() {
		if (!LoaderPlatform.isModLoaded("baritone") || !CLOSED.compareAndSet(false, true)) return;
		try {
			// Baritone exposes this implementation method publicly; keep it isolated from navigation API.
			Object executor = Class.forName("baritone.Baritone", true, BaritoneShutdown.class.getClassLoader())
				.getMethod("getExecutor").invoke(null);
			if (!(executor instanceof ExecutorService service)) {
				McpFabric.LOGGER.warn("[mcpfabric] Baritone executor cannot be shut down by this compatibility adapter");
				return;
			}
			Thread cleanup = new Thread(() -> shutdownExecutor(service), "mcpfabric-baritone-shutdown");
			cleanup.setDaemon(true);
			cleanup.start();
		} catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
			warn("resolve executor", error);
		}
	}

	private static void shutdownExecutor(ExecutorService executor) {
		try {
			executor.shutdown();
			// Let existing cache writes finish before interrupting permanent packer / autosave loops.
			if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
				McpFabric.LOGGER.info("[mcpfabric] stopping Baritone background workers after cache-save grace period");
				int pending = executor.shutdownNow().size();
				if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
					McpFabric.LOGGER.warn("[mcpfabric] Baritone workers did not terminate within 5 seconds; pending={}", pending);
					return;
				}
			}
			McpFabric.LOGGER.info("[mcpfabric] Baritone executor terminated");
		} catch (InterruptedException error) {
			executor.shutdownNow();
			Thread.currentThread().interrupt();
			warn("await executor shutdown", error);
		} catch (RuntimeException error) {
			warn("shutdown executor", error);
		}
	}

	private static void warn(String operation, Throwable error) {
		Throwable cause = error.getCause() == null ? error : error.getCause();
		McpFabric.LOGGER.warn("[mcpfabric] Baritone shutdown could not {}: {}", operation, cause.toString());
	}
}
