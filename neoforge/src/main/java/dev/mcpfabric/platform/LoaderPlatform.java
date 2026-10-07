package dev.mcpfabric.platform;

import dev.mcpfabric.McpFabric;
import dev.mcpfabric.ServerHolder;
import dev.mcpfabric.events.EventBus;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.nio.file.Path;
import java.util.List;

public final class LoaderPlatform {
	private LoaderPlatform() {}
	public static boolean isClient() { return FMLLoader.getCurrent().getDist() == Dist.CLIENT; }
	public static boolean isModLoaded(String id) { return ModList.get().isLoaded(id); }
	public static Path configDir() { return FMLPaths.CONFIGDIR.get(); }
	public static String modVersion(String id, String fallback) {
		return ModList.get().getMods().stream().filter(m -> m.getModId().equals(id))
				.findFirst().map(m -> m.getVersion().toString()).orElse(fallback);
	}
	public static List<Path> modPaths() {
		return ModList.get().getModFiles().stream().map(m -> m.getFile().getFilePath()).toList();
	}
	public static void registerServerLifecycle(EventBus events) {
		NeoForge.EVENT_BUS.addListener((ServerStartedEvent e) -> ServerHolder.set(e.getServer()));
		NeoForge.EVENT_BUS.addListener((ServerStoppedEvent e) -> ServerHolder.set(null));
		NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> events.setTick(e.getServer().overworld().getGameTime()));
		NeoForge.EVENT_BUS.addListener((ServerStoppingEvent e) -> { if (!isClient()) McpFabric.stopHttpBridge(); });
	}
}
