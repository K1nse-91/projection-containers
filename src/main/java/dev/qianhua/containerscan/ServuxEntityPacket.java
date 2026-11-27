package dev.qianhua.containerscan;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

import fi.dy.masa.malilib.util.data.tag.CompoundData;
import fi.dy.masa.malilib.util.data.tag.util.DataFileUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

record ServuxEntityPacket(int kind, BlockPos pos, CompoundTag metadata, byte[] inventory)
        implements CustomPacketPayload {
    static final Type<ServuxEntityPacket> TYPE = new Type<>(Identifier.fromNamespaceAndPath("servux", "entity_data"));
    static final StreamCodec<FriendlyByteBuf, ServuxEntityPacket> CODEC =
            StreamCodec.of((buffer, packet) -> packet.write(buffer), ServuxEntityPacket::read);
    static final int PROTOCOL = 2;
    static final int METADATA_REPLY = 1;
    static final int METADATA_REQUEST = 2;
    static final int BLOCK_REQUEST = 3;
    static final int BLOCK_REPLY = 5;
    private static final int MAX_METADATA_BYTES = 4 * 1024;
    private static final int MAX_WIRE_BYTES = 1024 * 1024;
    private static final int MAX_NBT_BYTES = 4 * 1024 * 1024;
    private static final ServuxEntityPacket IGNORED = new ServuxEntityPacket(0, null, null, null);

    static ServuxEntityPacket requestMetadata() {
        var metadata = new CompoundTag();
        metadata.putInt("version", PROTOCOL);
        return new ServuxEntityPacket(METADATA_REQUEST, null, metadata, null);
    }

    static ServuxEntityPacket requestBlock(BlockPos pos) {
        return new ServuxEntityPacket(BLOCK_REQUEST, pos, null, null);
    }

    private void write(FriendlyByteBuf buffer) {
        buffer.writeVarInt(kind);
        if (pos != null) {
            buffer.writeBlockPos(pos);
        }
        if (metadata != null) {
            buffer.writeNbt(metadata);
        }
        if (inventory != null) {
            buffer.writeInt(inventory.length);
            buffer.writeBytes(inventory);
        }
    }

    private static ServuxEntityPacket read(FriendlyByteBuf buffer) {
        try {
            int kind = buffer.readVarInt();
            if ((kind == METADATA_REPLY || kind == METADATA_REQUEST) && buffer.readableBytes() <= MAX_METADATA_BYTES) {
                return new ServuxEntityPacket(kind, null, buffer.readNbt(), null);
            }
            if (kind == BLOCK_REQUEST) {
                return requestBlock(buffer.readBlockPos());
            }
            if (kind == BLOCK_REPLY) {
                BlockPos pos = buffer.readBlockPos();
                int size = buffer.readInt();
                if (size > 0 && size <= MAX_WIRE_BYTES && size == buffer.readableBytes()) {
                    byte[] data = new byte[size];
                    buffer.readBytes(data);
                    return new ServuxEntityPacket(kind, pos, null, data);
                }
            }
        } catch (RuntimeException ignored) {
            // A malformed or truncated response is discarded.
        }
        buffer.skipBytes(buffer.readableBytes());
        return IGNORED;
    }

    CompoundData decodeInventory() {
        if (inventory == null) {
            return null;
        }
        try (var bytes = new ByteArrayInputStream(inventory);
             var input = new DataInputStream(decompress(bytes))) {
            return DataFileUtils.readFromNbtStream(input, MAX_NBT_BYTES) instanceof CompoundData result
                    ? result : null;
        } catch (IOException | RuntimeException error) {
            ScanSettings.LOGGER.debug("Discarding an invalid Servux inventory response", error);
            return null;
        }
    }

    private InputStream decompress(ByteArrayInputStream bytes) throws IOException {
        return inventory.length > 1 && inventory[0] == 0x1f && (inventory[1] & 0xff) == 0x8b
                ? new GZIPInputStream(bytes) : bytes;
    }

    @Override
    public Type<ServuxEntityPacket> type() {
        return TYPE;
    }
}
