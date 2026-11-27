package dev.qianhua.containerscan;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import fi.dy.masa.malilib.network.IClientPayloadData;
import fi.dy.masa.malilib.util.data.tag.CompoundData;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

final class ServuxInventory {
    private final Map<BlockPos, Response> pending = new HashMap<>();
    // A Fabric payload identifier has one codec. Share MiniHUD's codec when installed.
    private final boolean miniHudInstalled = FabricLoader.getInstance().isModLoaded("minihud");
    private ClientPacketListener connection;
    private MiniHudChannel miniHud;
    private boolean miniHudLookedUp;
    private boolean metadataRequested;
    private boolean compatible;
    private boolean rejected;
    private boolean miniHudWasEnabled;

    void register() {
        if (!miniHudInstalled) {
            ServuxPayloadRegistry.register();
            ClientPlayNetworking.registerGlobalReceiver(ServuxEntityPacket.TYPE, this::receive);
        }
    }

    void beginScan(Minecraft client) {
        pending.clear();
        connection = client.getConnection();
        metadataRequested = false;
        compatible = false;
        rejected = false;
        miniHudWasEnabled = false;
        if (miniHudInstalled && !miniHudLookedUp) {
            miniHud = MiniHudChannel.connect();
            miniHudLookedUp = true;
        }
    }

    boolean isReady() {
        if (connection == null || (miniHudInstalled && miniHud == null)) {
            return false;
        }
        if (miniHud != null && miniHudWasEnabled && !miniHud.syncSetting().getBooleanValue()) {
            // MiniHUD may have unregistered itself after rejecting a cross-version server.
            // Start a fresh read-only handshake after its setting changes.
            metadataRequested = false;
            compatible = false;
            miniHudWasEnabled = false;
        }
        if (!metadataRequested && ClientPlayNetworking.canSend(ServuxEntityPacket.TYPE)) {
            if (miniHud == null) {
                metadataRequested = true;
                ClientPlayNetworking.send(ServuxEntityPacket.requestMetadata());
            } else if (miniHud.ensureReceiver(this::receiveMiniHud)) {
                miniHudWasEnabled = miniHud.syncSetting().getBooleanValue();
                metadataRequested = miniHud.requestMetadata();
            }
        }
        return compatible;
    }

    boolean rejected() {
        return rejected;
    }

    Supplier<CompoundData> request(BlockPos pos) {
        var response = new Response();
        if (compatible && ClientPlayNetworking.canSend(ServuxEntityPacket.TYPE)) {
            pending.put(pos, response);
            if (miniHud == null) {
                ClientPlayNetworking.send(ServuxEntityPacket.requestBlock(pos));
            } else if (miniHud.ensureReceiver(this::receiveMiniHud)) {
                miniHud.requestBlock(pos);
            }
        }
        return () -> response.data;
    }

    void forget(BlockPos pos) {
        pending.remove(pos);
    }

    void clearRequests() {
        pending.clear();
    }

    private void receive(ServuxEntityPacket packet, ClientPlayNetworking.Context context) {
        if (connection != context.client().getConnection() || !metadataRequested) {
            return;
        }
        if (packet.kind() == ServuxEntityPacket.METADATA_REPLY) {
            acceptMetadata(supportsMetadata(packet.metadata()));
        } else if (compatible && packet.kind() == ServuxEntityPacket.BLOCK_REPLY) {
            var response = pending.remove(packet.pos());
            if (response != null) {
                response.data = packet.decodeInventory();
            }
        }
    }

    void receiveMiniHud(IClientPayloadData packet) {
        if (miniHud == null || connection != Minecraft.getInstance().getConnection() || !metadataRequested) {
            return;
        }
        try {
            if (packet.getPacketType() == ServuxEntityPacket.METADATA_REPLY) {
                var metadata = miniHud.data(packet);
                acceptMetadata(supportsMetadata(metadata.getIntOrDefault("version", -1),
                                                metadata.getStringOrDefault("servux", "")));
            } else if (compatible && packet.getPacketType() == ServuxEntityPacket.BLOCK_REPLY) {
                var response = pending.remove(miniHud.blockPosition(packet));
                if (response != null) {
                    response.data = miniHud.data(packet);
                }
            }
        } catch (ReflectiveOperationException error) {
            ScanSettings.LOGGER.debug("Could not read a Servux packet from MiniHUD's codec", error);
        }
    }

    private void acceptMetadata(boolean supported) {
        if (!compatible && !rejected) {
            compatible = supported;
            rejected = !supported;
        }
    }

    static boolean supportsMetadata(CompoundTag metadata) {
        return metadata != null && supportsMetadata(metadata.getIntOr("version", -1),
                                                    metadata.getStringOr("servux", ""));
    }

    private static boolean supportsMetadata(int protocol, String server) {
        return protocol == ServuxEntityPacket.PROTOCOL && server.startsWith("servux-fabric-");
    }

    private static final class Response {
        private CompoundData data;
    }
}
