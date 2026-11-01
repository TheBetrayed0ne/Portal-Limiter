package PortalLimiter.mixin;

import PortalLimiter.Config;
import PortalLimiter.PortalRegistryState;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.EndPortalFrameBlock;
import net.minecraft.block.pattern.BlockPattern;
import net.minecraft.item.EnderEyeItem;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Enforces the server-wide open-end-portal cap right at the point the last eye of ender would
 * complete a frame ring, so the final fill-with-end-portal-blocks step never happens once the
 * limit is hit. The eye socket that vanilla had already filled and the item it had already
 * consumed are both reverted, so the rejected attempt is a no-op the player can retry later
 * instead of a ring permanently stuck showing complete with no way to fill it.
 */
@Mixin(EnderEyeItem.class)
public abstract class EnderEyeItemMixin {

    @Inject(
            method = "useOnBlock",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/block/pattern/BlockPattern;searchAround(Lnet/minecraft/world/WorldView;Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/block/pattern/BlockPattern$Result;",
                    shift = At.Shift.BY,
                    by = 2
            ),
            cancellable = true
    )
    private void portalLimiter$enforceEndLimit(ItemUsageContext context, CallbackInfoReturnable<ActionResult> cir, @Local BlockPattern.Result result) {
        if (result == null || !(context.getWorld() instanceof ServerWorld serverWorld)) {
            return;
        }

        BlockPos corner = result.getFrontTopLeft().add(-3, 0, -3);
        BlockPos farCorner = corner.add(2, 0, 2);
        PortalRegistryState registry = PortalRegistryState.get(serverWorld.getServer(), PortalRegistryState.END_ID);

        if (registry.atLimit(Config.get().maxOpenEndPortals)) {
            // The eye socket was already filled and the item already consumed earlier in
            // useOnBlock (before this searchAround call), and vanilla refuses to re-accept an
            // eye on a frame block that already has one. Left alone, a ring that completes
            // while at the cap would be stuck showing fully-filled forever. Undo both so the
            // attempt is a no-op and the player can retry once a slot frees up, mirroring how
            // PortalShapeMixin clears the fire it leaves behind on a rejected nether portal.
            BlockPos framePos = context.getBlockPos();
            BlockState frameState = serverWorld.getBlockState(framePos);
            if (frameState.isOf(Blocks.END_PORTAL_FRAME)) {
                serverWorld.setBlockState(framePos, frameState.with(EndPortalFrameBlock.EYE, false), 2);
                serverWorld.updateComparators(framePos, Blocks.END_PORTAL_FRAME);
            }
            context.getStack().increment(1);

            cir.setReturnValue(ActionResult.CONSUME);
            cir.cancel();
            return;
        }

        registry.register(serverWorld.getRegistryKey(), corner.toImmutable(), farCorner.toImmutable());
    }
}
