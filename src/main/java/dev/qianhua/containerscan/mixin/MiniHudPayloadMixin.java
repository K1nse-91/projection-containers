package dev.qianhua.containerscan.mixin;

import dev.qianhua.containerscan.ContainerScanClient;
import fi.dy.masa.malilib.network.IClientPayloadData;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "fi.dy.masa.minihud.network.ServuxEntitiesHandler", remap = false)
abstract class MiniHudPayloadMixin {
    @Inject(method = "decodeClientData", at = @At("HEAD"))
    private void containerScan$observeEntityData(Identifier channel, IClientPayloadData packet, CallbackInfo callback) {
        ContainerScanClient.observeMiniHudPacket(channel, packet);
    }
}
