package portallimiter.mixin;

import portallimiter.PortalRegistryState;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.EndPortalBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tracks end portal blocks disappearing (vanilla has no normal way to do this in survival, but
 * explosions, creative, commands, or other mods still can) so the open-end-portal count stays
 * accurate.
 */
@Mixin(AbstractBlock.class)
public abstract class EndPortalRemovalMixin {

    @Inject(method = "onStateReplaced", at = @At("TAIL"))
    private void portalLimiter$trackEndRemoval(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved, CallbackInfo ci) {
        if (!(state.getBlock() instanceof EndPortalBlock) || !(world instanceof ServerWorld serverWorld)) {
            return;
        }

        PortalRegistryState registry = PortalRegistryState.get(serverWorld.getServer(), PortalRegistryState.END_ID);
        registry.unregisterIfGone(serverWorld.getRegistryKey(), pos.toImmutable(),
                candidate -> serverWorld.getBlockState(candidate).isOf(Blocks.END_PORTAL));
    }
}
