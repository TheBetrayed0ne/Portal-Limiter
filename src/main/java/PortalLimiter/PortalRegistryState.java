package PortalLimiter;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.PersistentState;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * Server-wide registry of currently open portals of one kind (nether or end), used to enforce
 * the matching config limit. Nether and end portals are tracked as separate instances of this
 * state (different save ids), so the two caps never share a count. Stored in the overworld's
 * persistent data so the count survives restarts.
 */
public class PortalRegistryState extends PersistentState {
    public static final String NETHER_ID = "portallimiter_nether_portals";
    public static final String END_ID = "portallimiter_end_portals";

    private final List<Entry> portals = new ArrayList<>();

    public static PortalRegistryState get(MinecraftServer server, String id) {
        ServerWorld overworld = server.getWorld(World.OVERWORLD);
        return overworld.getPersistentStateManager().getOrCreate(PortalRegistryState::fromNbt, PortalRegistryState::new, id);
    }

    public int count() {
        return portals.size();
    }

    public boolean atLimit(int max) {
        return portals.size() >= max;
    }

    public void register(RegistryKey<World> dimension, BlockPos corner1, BlockPos corner2) {
        portals.add(new Entry(dimension, corner1, corner2));
        markDirty();
    }

    /**
     * If more portals are tracked than {@code max}, forcibly closes the excess ones by clearing
     * their blocks in the world (via {@code clearPortalBlock}, which the caller supplies so this
     * class doesn't need to know which block type each registry holds). Closing the oldest or
     * newest first is controlled by {@code closeOldestFirst}. The existing block-removal mixins
     * untrack each entry on their own once its blocks are gone, so this method never touches the
     * {@code portals} list directly. Returns how many portals were closed.
     */
    public int enforceLimit(MinecraftServer server, int max, boolean closeOldestFirst, BiConsumer<ServerWorld, BlockPos> clearPortalBlock) {
        if (portals.size() <= max) {
            return 0;
        }

        List<Entry> snapshot = new ArrayList<>(portals);
        int excessCount = snapshot.size() - max;
        List<Entry> toClose = closeOldestFirst
                ? snapshot.subList(0, excessCount)
                : snapshot.subList(max, snapshot.size());

        int closed = 0;
        for (Entry entry : toClose) {
            ServerWorld world = server.getWorld(entry.dimension);
            if (world == null) {
                continue;
            }
            for (BlockPos pos : BlockPos.iterate(entry.min, entry.max)) {
                clearPortalBlock.accept(world, pos.toImmutable());
            }
            closed++;
        }
        return closed;
    }

    /**
     * Called whenever a portal block of this registry's kind disappears. Only decrements the
     * registry once every block of the matching portal's bounding box has stopped being a portal
     * block, so breaking one corner of a still-standing portal doesn't under-count it.
     */
    public void unregisterIfGone(RegistryKey<World> dimension, BlockPos removedPos, Predicate<BlockPos> isStillPortal) {
        for (int i = 0; i < portals.size(); i++) {
            Entry entry = portals.get(i);
            if (entry.dimension != dimension || !entry.contains(removedPos)) {
                continue;
            }
            if (!entry.anyRemaining(isStillPortal)) {
                portals.remove(i);
                markDirty();
            }
            return;
        }
    }

    @Override
    public NbtCompound writeNbt(NbtCompound tag) {
        NbtList list = new NbtList();
        for (Entry entry : portals) {
            list.add(entry.toNbt());
        }
        tag.put("portals", list);
        return tag;
    }

    private static PortalRegistryState fromNbt(NbtCompound tag) {
        PortalRegistryState state = new PortalRegistryState();
        NbtList list = tag.getList("portals", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < list.size(); i++) {
            state.portals.add(Entry.fromNbt(list.getCompound(i)));
        }
        return state;
    }

    private static final class Entry {
        final RegistryKey<World> dimension;
        final BlockPos min;
        final BlockPos max;

        Entry(RegistryKey<World> dimension, BlockPos corner1, BlockPos corner2) {
            this.dimension = dimension;
            this.min = new BlockPos(
                    Math.min(corner1.getX(), corner2.getX()),
                    Math.min(corner1.getY(), corner2.getY()),
                    Math.min(corner1.getZ(), corner2.getZ()));
            this.max = new BlockPos(
                    Math.max(corner1.getX(), corner2.getX()),
                    Math.max(corner1.getY(), corner2.getY()),
                    Math.max(corner1.getZ(), corner2.getZ()));
        }

        boolean contains(BlockPos pos) {
            return pos.getX() >= min.getX() && pos.getX() <= max.getX()
                    && pos.getY() >= min.getY() && pos.getY() <= max.getY()
                    && pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ();
        }

        boolean anyRemaining(Predicate<BlockPos> isStillPortal) {
            for (BlockPos pos : BlockPos.iterate(min, max)) {
                if (isStillPortal.test(pos)) {
                    return true;
                }
            }
            return false;
        }

        NbtCompound toNbt() {
            NbtCompound tag = new NbtCompound();
            tag.putString("dimension", dimension.getValue().toString());
            tag.putInt("minX", min.getX());
            tag.putInt("minY", min.getY());
            tag.putInt("minZ", min.getZ());
            tag.putInt("maxX", max.getX());
            tag.putInt("maxY", max.getY());
            tag.putInt("maxZ", max.getZ());
            return tag;
        }

        static Entry fromNbt(NbtCompound tag) {
            RegistryKey<World> dimension = RegistryKey.of(RegistryKeys.WORLD, new Identifier(tag.getString("dimension")));
            BlockPos min = new BlockPos(tag.getInt("minX"), tag.getInt("minY"), tag.getInt("minZ"));
            BlockPos max = new BlockPos(tag.getInt("maxX"), tag.getInt("maxY"), tag.getInt("maxZ"));
            return new Entry(dimension, min, max);
        }
    }
}
