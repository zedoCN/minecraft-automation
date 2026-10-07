package dev.mcpfabric.platform;

import dev.mcpfabric.McpFabric;
import dev.mcpfabric.ServerHolder;
import dev.mcpfabric.events.EventBus;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Fabric's small loader boundary; game handlers are shared with NeoForge. */
public final class LoaderPlatform {
	private LoaderPlatform() {}
	public static boolean isClient() { return FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT; }
	public static boolean isModLoaded(String id) { return FabricLoader.getInstance().isModLoaded(id); }
	public static Path configDir() { return FabricLoader.getInstance().getConfigDir(); }
	public static String modVersion(String id, String fallback) {
		return FabricLoader.getInstance().getModContainer(id).map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse(fallback);
	}
	public static List<Path> modPaths() {
		List<Path> paths = new ArrayList<>();
		FabricLoader.getInstance().getAllMods().forEach(mod -> {
			try { paths.addAll(mod.getOrigin().getPaths()); }
			catch (UnsupportedOperationException ignored) { /* Nested mods have no filesystem paths. */ }
		});
		return paths;
	}
	public static void registerServerLifecycle(EventBus events) {
		ServerLifecycleEvents.SERVER_STARTED.register(ServerHolder::set);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> ServerHolder.set(null));
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			try { events.setTick(server.overworld().getGameTime()); }
			catch (Throwable ignored) { /* Match the existing Fabric startup/shutdown observation. */ }
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> { if (!isClient()) McpFabric.stopHttpBridge(); });
	}
}
