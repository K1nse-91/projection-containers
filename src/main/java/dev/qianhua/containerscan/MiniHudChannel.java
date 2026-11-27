package dev.qianhua.containerscan;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.function.Consumer;

import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.network.IClientPayloadData;
import fi.dy.masa.malilib.util.data.tag.CompoundData;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

record MiniHudChannel(CustomPacketPayload.Type<CustomPacketPayload> id, Constructor<?> payload,
                      Method metadataRequest, Method blockRequest, Method unwrap,
                      Method pos, Method compound, ConfigBoolean syncSetting) {
    @SuppressWarnings("unchecked")
    static MiniHudChannel connect() {
        try {
            var packet = Class.forName("fi.dy.masa.minihud.network.ServuxEntitiesPacket");
            var payload = Class.forName(packet.getName() + "$Payload");
            var id = (CustomPacketPayload.Type<CustomPacketPayload>) payload.getField("ID").get(null);
            var config = Class.forName("fi.dy.masa.minihud.config.Configs$Generic");
            var syncSetting = (ConfigBoolean) config.getField("ENTITY_DATA_SYNC").get(null);
            return new MiniHudChannel(id, payload.getConstructor(packet),
                    packet.getMethod("MetadataRequest", CompoundData.class),
                    packet.getMethod("BlockEntityRequest", BlockPos.class), payload.getMethod("data"),
                    packet.getMethod("getPos"), packet.getMethod("getCompound"), syncSetting);
        } catch (ReflectiveOperationException | LinkageError | ClassCastException error) {
            ScanSettings.LOGGER.warn("Could not attach to MiniHUD's Servux packet codec", error);
            return null;
        }
    }

    boolean ensureReceiver(Consumer<IClientPayloadData> receive) {
        if (ClientPlayNetworking.getReceived().contains(id.id())) {
            return true;
        }
        try {
            return ClientPlayNetworking.registerReceiver(id, (packet, context) -> {
                try {
                    receive.accept((IClientPayloadData) unwrap.invoke(packet));
                } catch (ReflectiveOperationException error) {
                    ScanSettings.LOGGER.debug("Could not unwrap a MiniHUD Servux packet", error);
                }
            });
        } catch (IllegalArgumentException error) {
            // MiniHUD has not registered its codec yet.
            return false;
        }
    }

    boolean requestMetadata() {
        var metadata = new CompoundData();
        metadata.putInt("version", ServuxEntityPacket.PROTOCOL);
        return send(metadataRequest, metadata);
    }

    boolean requestBlock(BlockPos pos) {
        return send(blockRequest, pos);
    }

    private boolean send(Method factory, Object argument) {
        try {
            ClientPlayNetworking.send((CustomPacketPayload) payload.newInstance(factory.invoke(null, argument)));
            return true;
        } catch (ReflectiveOperationException error) {
            ScanSettings.LOGGER.debug("Could not send through MiniHUD's Servux codec", error);
            return false;
        }
    }

    BlockPos blockPosition(IClientPayloadData packet) throws ReflectiveOperationException {
        return (BlockPos) pos.invoke(packet);
    }

    CompoundData data(IClientPayloadData packet) throws ReflectiveOperationException {
        return (CompoundData) compound.invoke(packet);
    }
}
