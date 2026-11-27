package dev.qianhua.containerscan;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import fi.dy.masa.litematica.data.EntityDataManager;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.malilib.interfaces.IDataSyncer;
import fi.dy.masa.malilib.util.InventoryUtils;
import fi.dy.masa.malilib.util.data.tag.CompoundData;
import fi.dy.masa.malilib.util.data.tag.converter.DataConverterNbt;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import static dev.qianhua.containerscan.ContainerScanClient.text;

final class SelectionScan {
    private static final int BATCH_SIZE = 8;
    private static final long TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(6);
    private static final Snapshot WAITING = new Snapshot(Status.WAITING, List.of());
    private static final Snapshot UNAVAILABLE = new Snapshot(Status.UNAVAILABLE, List.of());
    private static final Snapshot LOOT = new Snapshot(Status.LOOT, List.of());
    private static final Comparator<BlockPos> POSITION_ORDER = Comparator.comparingInt((BlockPos pos) -> pos.getX())
            .thenComparingInt(pos -> pos.getY()).thenComparingInt(pos -> pos.getZ());
    private static final Comparator<ItemCount> ITEM_ORDER = Comparator.comparingLong(ItemCount::count).reversed()
            .thenComparing(item -> item.stack().getHoverName().getString());

    private final ClientLevel level;
    private final List<Entry> entries;
    private final Map<Identifier, Block> detectedTypes;
    private final Deque<ReadTarget> queued = new ArrayDeque<>();
    private final List<RemoteRead> pending = new ArrayList<>();
    private final boolean hasSelection;
    private final boolean unloadedChunks;
    private final ServuxInventory servux;
    private long syncStartedAt;
    private boolean attemptedSync;
    private Function<ReadTarget, Supplier<CompoundData>> remoteRequests;
    private CompletableFuture<List<Snapshot>> localRead;
    private List<ReadTarget> localBatch = List.of();
    private int revision;
    private int completed;
    private int unavailable;
    private Component notice;

    SelectionScan(Minecraft client, List<Bounds> boxes, Set<Identifier> types, ServuxInventory servux) {
        level = client.level;
        this.servux = servux;
        hasSelection = !boxes.isEmpty();
        Map<BlockPos, BlockEntity> found = new LinkedHashMap<>();
        Map<Identifier, Block> detected = new LinkedHashMap<>();
        boolean skipped = false;
        if (level != null && client.player != null) {
            var center = client.player.blockPosition();
            int radius = client.options.getEffectiveRenderDistance() + 3;
            for (Bounds box : boxes) {
                skipped |= collect(box, center.getX() >> 4, center.getZ() >> 4, radius, types, found, detected);
            }
        }
        unloadedChunks = skipped;
        detectedTypes = Map.copyOf(detected);
        entries = groupContainers(found, this::loadedEntity, this::hasLoadedChunk);
        for (Entry entry : entries) {
            queued.add(new ReadTarget(entry, entry.entity));
            if (entry.partner != null) {
                queued.add(new ReadTarget(entry, entry.partner));
            }
        }
    }

    static List<Bounds> selectionBounds(AreaSelection selection) {
        return selection == null ? List.of() : selection.getAllSubRegionBoxes().stream()
                .filter(box -> box.getPos1() != null && box.getPos2() != null)
                .map(Bounds::from).toList();
    }

    private boolean collect(Bounds box, int centerX, int centerZ, int radius,
                            Set<Identifier> types, Map<BlockPos, BlockEntity> found,
                            Map<Identifier, Block> detected) {
        int minX = box.minX >> 4;
        int maxX = box.maxX >> 4;
        int minZ = box.minZ >> 4;
        int maxZ = box.maxZ >> 4;
        long expected = (maxX - minX + 1L) * (maxZ - minZ + 1L);
        long visited = 0;

        for (int x = Math.max(minX, centerX - radius); x <= Math.min(maxX, centerX + radius); x++) {
            for (int z = Math.max(minZ, centerZ - radius); z <= Math.min(maxZ, centerZ + radius); z++) {
                var chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
                if (chunk == null) {
                    continue;
                }
                visited++;
                for (var pair : chunk.getBlockEntities().entrySet()) {
                    BlockPos pos = pair.getKey();
                    BlockEntity entity = pair.getValue();
                    if (box.contains(pos) && !entity.isRemoved() && !found.containsKey(pos)) {
                        Block block = entity.getBlockState().getBlock();
                        var blockId = BuiltInRegistries.BLOCK.getKey(block);
                        var typeId = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType());
                        boolean enabled = types.contains(blockId) || types.contains(typeId);
                        if (entity instanceof Container || enabled) {
                            Identifier option = ScanSettings.defaultType(typeId) ? typeId : blockId;
                            detected.putIfAbsent(option, block);
                        }
                        if (enabled) {
                            found.put(pos.immutable(), entity);
                        }
                    }
                }
            }
        }
        return visited < expected;
    }

    private BlockEntity loadedEntity(BlockPos pos) {
        var chunk = level.getChunkSource().getChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FULL, false);
        return chunk == null ? null : chunk.getBlockEntities().get(pos);
    }

    private boolean hasLoadedChunk(BlockPos pos) {
        return level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4);
    }

    static List<Entry> groupContainers(Map<BlockPos, BlockEntity> found,
                                       Function<BlockPos, BlockEntity> loadedEntity,
                                       Predicate<BlockPos> loadedChunk) {
        List<Entry> groups = new ArrayList<>(found.size());
        Set<BlockPos> visited = new HashSet<>();
        for (var selected : found.entrySet()) {
            BlockPos pos = selected.getKey();
            if (!visited.add(pos)) {
                continue;
            }
            BlockEntity entity = selected.getValue();
            BlockState state = entity.getBlockState();
            BlockPos opposite = chestPartner(pos, state);
            BlockEntity partner = null;
            if (opposite != null) {
                partner = found.get(opposite);
                if (partner == null) {
                    partner = loadedEntity.apply(opposite);
                }
                if (partner == null ? loadedChunk.test(opposite)
                        : !formsDoubleChest(state, pos, partner)) {
                    opposite = null;
                    partner = null;
                }
            }
            if (opposite != null) {
                visited.add(opposite);
            }
            groups.add(new Entry(pos, entity, opposite, partner));
        }
        groups.sort(Comparator.comparing(Entry::pos, POSITION_ORDER));
        return List.copyOf(groups);
    }

    private static BlockPos chestPartner(BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof ChestBlock) || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) {
            return null;
        }
        return pos.relative(ChestBlock.getConnectedDirection(state));
    }

    private static boolean formsDoubleChest(BlockState state, BlockPos pos, BlockEntity partner) {
        BlockState opposite = partner.getBlockState();
        return !partner.isRemoved() && state.is(opposite.getBlock())
                && state.getValue(ChestBlock.FACING) == opposite.getValue(ChestBlock.FACING)
                && state.getValue(ChestBlock.TYPE).getOpposite() == opposite.getValue(ChestBlock.TYPE)
                && pos.equals(chestPartner(partner.getBlockPos(), opposite));
    }

    void tick(Minecraft client, EntityDataManager sync) {
        if (isFinished()) {
            return;
        }
        if (client.level != level || client.player == null) {
            stop(text("world_changed"));
            return;
        }
        var server = client.getSingleplayerServer();
        if (server != null) {
            tickLocal(server);
        } else {
            tickMultiplayer(client, sync);
        }
    }

    private void tickMultiplayer(Minecraft client, EntityDataManager litematica) {
        if (!attemptedSync) {
            attemptedSync = true;
            syncStartedAt = System.nanoTime();
            servux.beginScan(client);
        }
        if (remoteRequests == null) {
            if (servux.isReady()) {
                remoteRequests = target -> servux.request(target.pos());
            } else if (litematica.isEnabled() && litematica.hasServuxServer()) {
                remoteRequests = target -> requestCached(litematica, target);
            } else if (servux.rejected() || System.nanoTime() - syncStartedAt >= TIMEOUT_NANOS) {
                stop(text(servux.rejected() ? "sync_incompatible" : "no_servux"));
            }
        }
        if (remoteRequests != null) {
            tickRemote();
        }
    }

    private void tickLocal(IntegratedServer server) {
        if (localRead != null && !localRead.isDone()) {
            return;
        }
        if (localRead != null) {
            try {
                List<Snapshot> results = localRead.join();
                for (int index = 0; index < localBatch.size(); index++) {
                    finish(localBatch.get(index), results.get(index));
                }
            } catch (CompletionException error) {
                ScanSettings.LOGGER.warn("Could not read local container data", error.getCause());
                localBatch.forEach(entry -> finish(entry, UNAVAILABLE));
            }
            localRead = null;
            localBatch = List.of();
        }
        if (!queued.isEmpty()) {
            List<ReadTarget> batch = new ArrayList<>(BATCH_SIZE);
            while (!queued.isEmpty() && batch.size() < BATCH_SIZE) {
                batch.add(queued.removeFirst());
            }
            localBatch = batch;
            var dimension = level.dimension();
            localRead = server.submit(() -> {
                ServerLevel world = server.getLevel(dimension);
                return batch.stream().map(target -> readLocal(world, target)).toList();
            });
        }
    }

    private static Snapshot readLocal(ServerLevel world, ReadTarget target) {
        if (world == null || !world.hasChunk(target.pos().getX() >> 4, target.pos().getZ() >> 4)) {
            return UNAVAILABLE;
        }
        var entity = world.getBlockEntity(target.pos());
        if (entity == null || entity.getType() != target.entity().getType()) {
            return UNAVAILABLE;
        }
        var registry = world.registryAccess();
        try {
            return decode(DataConverterNbt.fromVanillaCompound(entity.saveWithFullMetadata(registry)),
                          slotCount(entity), registry);
        } catch (RuntimeException error) {
            ScanSettings.LOGGER.debug("Could not read container at {}", target.pos(), error);
            return UNAVAILABLE;
        }
    }

    private static Supplier<CompoundData> requestCached(IDataSyncer sync, ReadTarget target) {
        var previous = sync.getCache().getBlockEntityPairFromCache(target.pos());
        sync.getRequestTracker().schedulePendingBlockEntity(target.pos());
        return () -> {
            var cached = sync.getCache().getBlockEntityPairFromCache(target.pos());
            return cached != null && cached != previous && cached.be() == target.entity() ? cached.data() : null;
        };
    }

    private void tickRemote() {
        long now = System.nanoTime();
        for (var iterator = pending.iterator(); iterator.hasNext();) {
            var read = iterator.next();
            ReadTarget target = read.target();
            CompoundData data = read.response().get();
            if (!stillPresent(target)) {
                finish(target, UNAVAILABLE);
            } else if (data != null) {
                finish(target, decode(data, slotCount(target.entity()), level.registryAccess()));
            } else if (now - read.deadline() >= 0) {
                finish(target, UNAVAILABLE);
            } else {
                continue;
            }
            servux.forget(target.pos());
            iterator.remove();
        }
        while (!queued.isEmpty() && pending.size() < BATCH_SIZE) {
            ReadTarget target = queued.removeFirst();
            if (!stillPresent(target)) {
                finish(target, UNAVAILABLE);
                continue;
            }
            pending.add(new RemoteRead(target, remoteRequests.apply(target), now + TIMEOUT_NANOS));
        }
    }

    private boolean stillPresent(ReadTarget target) {
        return !target.entity().isRemoved() && loadedEntity(target.pos()) == target.entity();
    }

    private static int slotCount(BlockEntity entity) {
        return entity instanceof Container container ? container.getContainerSize() : -1;
    }

    static Snapshot decode(CompoundData data, int slots, RegistryAccess registry) {
        if (data == null || data.isEmpty()) {
            return UNAVAILABLE;
        }
        if (data.contains("LootTable", Tag.TAG_STRING) || data.contains("LootTable", Tag.TAG_COMPOUND)) {
            return LOOT;
        }
        try {
            List<ItemCount> items = new ArrayList<>();
            if (data.contains("Items", Tag.TAG_LIST)) {
                var stacks = data.getList("Items");
                for (int index = 0; index < stacks.size(); index++) {
                    ItemStack stack = InventoryUtils.fromDataOrEmpty(registry, stacks.getCompoundAt(index));
                    if (stack.isEmpty()) {
                        return UNAVAILABLE;
                    }
                    addItem(items, stack);
                }
            } else {
                Container inventory = InventoryUtils.getDataInventory(data, slots, registry);
                if (inventory == null) {
                    return UNAVAILABLE;
                }
                for (int index = 0; index < inventory.getContainerSize(); index++) {
                    addItem(items, inventory.getItem(index));
                }
            }
            items.sort(ITEM_ORDER);
            return new Snapshot(Status.READ, List.copyOf(items));
        } catch (RuntimeException error) {
            ScanSettings.LOGGER.debug("Could not decode a container inventory", error);
            return UNAVAILABLE;
        }
    }

    private static void addItem(List<ItemCount> items, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        addCount(items, new ItemCount(stack.copy(), stack.getCount()));
    }

    private static void addCount(List<ItemCount> items, ItemCount addition) {
        for (int index = 0; index < items.size(); index++) {
            ItemCount match = items.get(index);
            if (ItemStack.isSameItemSameComponents(match.stack(), addition.stack())) {
                items.set(index, new ItemCount(match.stack(), match.count() + addition.count()));
                return;
            }
        }
        items.add(addition);
    }

    private void stop(Component reason) {
        notice = reason;
        queued.clear();
        pending.clear();
        localRead = null;
        localBatch = List.of();
        entries.stream().filter(entry -> entry.snapshot.status() == Status.WAITING)
                .forEach(this::complete);
        revision++;
    }

    private void finish(ReadTarget target, Snapshot snapshot) {
        Entry entry = target.entry();
        entry.reads.add(snapshot);
        if (entry.reads.size() == (entry.partner == null ? 1 : 2)) {
            complete(entry);
        }
    }

    private void complete(Entry entry) {
        entry.snapshot = combine(entry.reads, entry.secondPos == null ? 1 : 2);
        if (entry.snapshot.status() == Status.UNAVAILABLE || entry.snapshot.status() == Status.PARTIAL) {
            unavailable++;
        }
        completed++;
        revision++;
    }

    static Snapshot combine(List<Snapshot> results, int expected) {
        if (expected == 1) {
            return results.isEmpty() ? UNAVAILABLE : results.getFirst();
        }
        List<ItemCount> items = new ArrayList<>();
        int readable = 0;
        int loot = 0;
        for (Snapshot result : results) {
            if (result.status() == Status.READ) {
                readable++;
                result.items().forEach(item -> addCount(items, item));
            } else if (result.status() == Status.LOOT) {
                loot++;
            }
        }
        if (readable == 0) {
            return loot == expected ? LOOT : UNAVAILABLE;
        }
        items.sort(ITEM_ORDER);
        return new Snapshot(readable == expected ? Status.READ : Status.PARTIAL, List.copyOf(items));
    }

    boolean isFinished() { return completed == entries.size(); }
    boolean isInLevel(ClientLevel current) { return current != null && level == current; }
    boolean hasSelection() { return hasSelection; }
    boolean unloadedChunks() { return unloadedChunks; }
    int revision() { return revision; }
    int completed() { return completed; }
    int unavailable() { return unavailable; }
    Component notice() { return notice; }
    List<Entry> entries() { return entries; }
    Map<Identifier, Block> detectedTypes() { return detectedTypes; }

    enum Status { WAITING, READ, PARTIAL, UNAVAILABLE, LOOT }
    record ItemCount(ItemStack stack, long count) {}
    record Snapshot(Status status, List<ItemCount> items) {}
    private record RemoteRead(ReadTarget target, Supplier<CompoundData> response, long deadline) {}
    private record ReadTarget(Entry entry, BlockEntity entity) {
        BlockPos pos() { return entity.getBlockPos(); }
    }

    static final class Entry {
        private final BlockPos pos;
        private final BlockEntity entity;
        private final BlockPos secondPos;
        private final BlockEntity partner;
        private final AABB outline;
        private final Vec3 anchor;
        private final List<Snapshot> reads = new ArrayList<>(2);
        private Snapshot snapshot = WAITING;

        Entry(BlockPos pos, BlockEntity entity, BlockPos secondPos, BlockEntity partner) {
            this.pos = secondPos == null || POSITION_ORDER.compare(pos, secondPos) <= 0 ? pos : secondPos;
            this.entity = entity;
            this.secondPos = secondPos;
            this.partner = partner;
            AABB bounds = new AABB(pos);
            outline = (secondPos == null ? bounds : bounds.minmax(new AABB(secondPos))).inflate(0.025);
            Vec3 center = outline.getCenter();
            anchor = new Vec3(center.x(), outline.maxY + 0.16, center.z());
        }

        BlockPos pos() { return pos; }
        Block block() { return entity.getBlockState().getBlock(); }
        Component name() {
            if (secondPos == null) {
                return block().getName();
            }
            return block() == Blocks.TRAPPED_CHEST ? text("double_trapped_chest") : text("double_chest");
        }
        boolean isPresent() { return !entity.isRemoved() && (partner == null || !partner.isRemoved()); }
        AABB outline() { return outline; }
        Vec3 anchor() { return anchor; }
        Snapshot snapshot() { return snapshot; }
    }

    record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        static Bounds from(Box box) {
            var first = box.getPos1();
            var second = box.getPos2();
            return new Bounds(Math.min(first.getX(), second.getX()), Math.min(first.getY(), second.getY()),
                    Math.min(first.getZ(), second.getZ()), Math.max(first.getX(), second.getX()),
                    Math.max(first.getY(), second.getY()), Math.max(first.getZ(), second.getZ()));
        }

        boolean contains(BlockPos pos) {
            return pos.getX() >= minX && pos.getX() <= maxX && pos.getY() >= minY
                    && pos.getY() <= maxY && pos.getZ() >= minZ && pos.getZ() <= maxZ;
        }
    }
}
