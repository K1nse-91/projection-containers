package dev.qianhua.containerscan;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

final class ServuxPayloadRegistry {
    private ServuxPayloadRegistry() {}

    static void register() {
        PayloadTypeRegistry.clientboundPlay().register(ServuxEntityPacket.TYPE, ServuxEntityPacket.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ServuxEntityPacket.TYPE, ServuxEntityPacket.CODEC);
    }
}
