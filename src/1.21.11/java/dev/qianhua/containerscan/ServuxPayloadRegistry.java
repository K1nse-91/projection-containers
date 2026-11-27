package dev.qianhua.containerscan;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

final class ServuxPayloadRegistry {
    private ServuxPayloadRegistry() {}

    static void register() {
        PayloadTypeRegistry.playS2C().register(ServuxEntityPacket.TYPE, ServuxEntityPacket.CODEC);
        PayloadTypeRegistry.playC2S().register(ServuxEntityPacket.TYPE, ServuxEntityPacket.CODEC);
    }
}
