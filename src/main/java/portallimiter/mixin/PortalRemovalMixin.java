package portallimiter.mixin;

import portallimiter.PortalRegistryState;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.NetherPortalBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tracks every cause of a nether portal block disappearing (breaking, explosions, the vanilla
 * self-pruning that runs when a neighbor block changes) so the open-portal count stays accurate.
 */
@Mixin(AbstractBlock.class)
public abstract class PortalRemovalMixin {

    @Inject(method = "onStateReplaced", at = @At("TAIL"))
    private void portalLimiter$trackRemoval(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved, CallbackInfo ci) {
        if (!(state.getBlock() instanceof NetherPortalBlock) || !(world instanceof ServerWorld serverWorld)) {
            return;
        }

        PortalRegistryState registry = PortalRegistryState.get(serverWorld.getServer(), PortalRegistryState.NETHER_ID);
        registry.unregisterIfGone(serverWorld.getRegistryKey(), pos.toImmutable(),
                candidate -> serverWorld.getBlockState(candidate).isOf(Blocks.NETHER_PORTAL));
    }
}
