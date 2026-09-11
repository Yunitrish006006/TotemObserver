package dev.totem.observer.mixin;

import dev.totem.observer.runtime.ObserverReturnState;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Temporary spectator grants camera access only, never independent spectator actions. */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ObserverServerActionFirewallMixin {
    @Shadow public ServerPlayer player;

    @Inject(method = {
            "handleMovePlayer", "handleMoveVehicle", "handlePlayerInput", "handlePlayerAction",
            "handlePlayerCommand", "handleTeleportToEntityPacket", "handleSpectatorAction",
            "handleAttack", "handleInteract", "handleUseItem", "handleUseItemOn",
            "handlePlayerAbilities", "handleSetCarriedItem", "handleSetCreativeModeSlot",
            "handleContainerClick", "handleContainerClose", "handleContainerButtonClick",
            "handleContainerSlotStateChanged", "handleRenameItem", "handleSelectTrade",
            "handleSetBeaconPacket", "handlePlaceRecipe",
            "updateBookContents", "signBook", "updateSignText",
            "handleBundleItemSelectedPacket", "handlePickItemFromBlock", "handlePickItemFromEntity"
    }, at = @At("HEAD"), cancellable = true)
    private void totem$rejectObserverActions(CallbackInfo ci) {
        // Off-thread calls retain vanilla's scheduling; inspect attachments only on the server thread.
        if (player.level().getServer().isSameThread() && player.hasAttached(ObserverReturnState.TYPE)) {
            ci.cancel();
        }
    }

    @Inject(method = "handleEditBook", at = @At("HEAD"), cancellable = true)
    private void totem$guardBook(net.minecraft.network.protocol.game.ServerboundEditBookPacket packet, CallbackInfo ci) {
        net.minecraft.network.protocol.PacketUtils.ensureRunningOnSameThread(packet,
                (ServerGamePacketListenerImpl) (Object) this, player.level());
        if (player.hasAttached(ObserverReturnState.TYPE)) ci.cancel();
    }

    @Inject(method = "handleSignUpdate", at = @At("HEAD"), cancellable = true)
    private void totem$guardSign(net.minecraft.network.protocol.game.ServerboundSignUpdatePacket packet, CallbackInfo ci) {
        net.minecraft.network.protocol.PacketUtils.ensureRunningOnSameThread(packet,
                (ServerGamePacketListenerImpl) (Object) this, player.level());
        if (player.hasAttached(ObserverReturnState.TYPE)) ci.cancel();
    }
}
