package PortalLimiter.mixin;

import PortalLimiter.Config;
import PortalLimiter.PortalRegistryState;
import net.minecraft.block.BlockState;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.WorldAccess;
import net.minecraft.world.dimension.NetherPortal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Enforces the server-wide open-portal cap right at the point a lit frame would turn into
 * portal blocks, so a rejected 11th portal never actually forms instead of flashing in and
 * getting undone afterwards.
 */
@Mixin(NetherPortal.class)
public abstract class PortalShapeMixin {

    @Shadow
    private WorldAccess world;
    @Shadow
    private Direction negativeDir;
    @Shadow
    private BlockPos lowerCorner;
    @Shadow
    private int height;
    @Shadow
    private int width;

    @Inject(method = "createPortal", at = @At("HEAD"), cancellable = true)
    private void portalLimiter$enforceLimit(CallbackInfo ci) {
        if (!(this.world instanceof ServerWorld serverWorld)) {
            return;
        }

        BlockPos farCorner = this.lowerCorner.offset(Direction.UP, this.height - 1).offset(this.negativeDir, this.width - 1);
        PortalRegistryState registry = PortalRegistryState.get(serverWorld.getServer(), PortalRegistryState.NETHER_ID);

        if (registry.atLimit(Config.get().maxOpenPortals)) {
            for (BlockPos pos : BlockPos.iterate(this.lowerCorner, farCorner)) {
                BlockState state = serverWorld.getBlockState(pos);
                if (state.isIn(BlockTags.FIRE)) {
                    serverWorld.removeBlock(pos.toImmutable(), false);
                }
            }
            ci.cancel();
            return;
        }

        registry.register(serverWorld.getRegistryKey(), this.lowerCorner.toImmutable(), farCorner.toImmutable());
    }
}
