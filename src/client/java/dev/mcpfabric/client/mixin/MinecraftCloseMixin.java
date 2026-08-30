package dev.mcpfabric.client.mixin;

import dev.mcpfabric.client.nav.BaritoneShutdown;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftCloseMixin {
	@Inject(method = "close", at = @At("RETURN"))
	private void mcpfabric$afterClientClose(CallbackInfo ci) {
		BaritoneShutdown.afterClientClose();
	}
}
