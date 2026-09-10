package dev.totem.observer.mixin;

import dev.totem.observer.network.ObserverAdvancementsScreenPayloads;
import dev.totem.observer.network.ObserverBeaconScreenPayloads;
import dev.totem.observer.network.ObserverBrewingScreenPayloads;
import dev.totem.observer.network.ObserverCartographyScreenPayloads;
import dev.totem.observer.network.ObserverCrafterScreenPayloads;
import dev.totem.observer.network.ObserverGrindstoneScreenPayloads;
import dev.totem.observer.network.ObserverHorseScreenPayloads;
import dev.totem.observer.network.ObserverLocksmithManagementPayloads;
import dev.totem.observer.network.ObserverLoomScreenPayloads;
import dev.totem.observer.network.ObserverNativeScreenPayloads;
import dev.totem.observer.network.ObserverNexusDeathNodeAdminPayloads;
import dev.totem.observer.network.ObserverOwnedScreenCapability;
import dev.totem.observer.network.ObserverRemoteCursorPayloads;
import dev.totem.observer.network.ObserverSignScreenPayloads;
import dev.totem.observer.network.ObserverSmithingScreenPayloads;
import dev.totem.observer.network.ObserverStatsScreenPayloads;
import dev.totem.observer.network.ObserverStonecutterScreenPayloads;
import dev.totem.observer.network.ObserverVillagersWoodcutterPayloads;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Extends the built-in semantic capability sanitizer with post-v2 semantic families. */
@Mixin(value = ObserverNativeScreenPayloads.class, remap = false)
public abstract class ObserverNativeScreenPayloadsExtensionCapabilityMixin {
    @Inject(method = "sanitizeCapabilities", at = @At("HEAD"), cancellable = true)
    private static void totem$includeExtensionCapabilities(long capabilities, CallbackInfoReturnable<Long> cir) {
        cir.setReturnValue(capabilities & (ObserverNativeScreenPayloads.KNOWN_CAPABILITIES
                | ObserverVillagersWoodcutterPayloads.CAPABILITY
                | ObserverBrewingScreenPayloads.CAPABILITY
                | ObserverSmithingScreenPayloads.CAPABILITY
                | ObserverStonecutterScreenPayloads.CAPABILITY
                | ObserverGrindstoneScreenPayloads.CAPABILITY
                | ObserverLoomScreenPayloads.CAPABILITY
                | ObserverCartographyScreenPayloads.CAPABILITY
                | ObserverBeaconScreenPayloads.CAPABILITY
                | ObserverSignScreenPayloads.CAPABILITY
                | ObserverCrafterScreenPayloads.CAPABILITY
                | ObserverNexusDeathNodeAdminPayloads.CAPABILITY
                | ObserverLocksmithManagementPayloads.CAPABILITY
                | ObserverAdvancementsScreenPayloads.CAPABILITY
                | ObserverStatsScreenPayloads.CAPABILITY
                | ObserverRemoteCursorPayloads.CAPABILITY
                | ObserverHorseScreenPayloads.CAPABILITY
                | ObserverOwnedScreenCapability.CAPABILITY));
    }
}
