package dev.totem.observer.runtime;

import dev.totem.core.api.v1.client.observer.ObserverScreenSnapshot;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;
import java.util.function.BooleanSupplier;

/** Optional server authority adapter. Only vanilla map packets leave the owning module. */
final class ObserverNexusTerrainRelay {
    private ObserverNexusTerrainRelay() { }
    static void enqueue(ServerPlayer target,ServerPlayer observer,ObserverScreenSnapshot snapshot) {
        if(!snapshot.familyId().equals("nexus") || snapshot.protocolVersion()!=5 || !snapshot.variant().equals("map")
                || !FabricLoader.getInstance().isModLoaded("totem-nexus")) return;
        try {
            int id=Integer.parseInt(snapshot.metadata().getOrDefault("terrain_map","-1"));
            int x=Integer.parseInt(snapshot.metadata().getOrDefault("terrain_x","0"));
            int z=Integer.parseInt(snapshot.metadata().getOrDefault("terrain_z","0"));
            int radius=Integer.parseInt(snapshot.metadata().getOrDefault("terrain_radius","0"));
            BooleanSupplier valid=()->ObserverOwnedScreenRelayManager.matchesOpen(target.getUUID(),"nexus","map",5)
                    && ObserverAccessPolicy.allows(observer,target)
                    && ObserverNativeSessionManager.ownedProviderAdvertises(observer,"nexus",5)
                    && ObserverNativeSessionManager.observerIdsForTarget(target.getUUID(),
                        dev.totem.observer.network.ObserverOwnedScreenCapability.CAPABILITY).contains(observer.getUUID());
            Owner.ENQUEUE.invoke(null,target,observer,id,x,z,radius,valid);
        } catch(ReflectiveOperationException | IllegalArgumentException | LinkageError ignored) {
            // Missing/older owner does not get a substitute terrain renderer or relaxed authority.
        }
    }
    private static final class Owner {
        static final java.lang.reflect.Method ENQUEUE=find();
        static java.lang.reflect.Method find() {
            try {
                return Class.forName("dev.totem.nexus.space.NexusMapDetailNetworking").getMethod("enqueueObserved",
                        ServerPlayer.class,ServerPlayer.class,int.class,int.class,int.class,int.class,BooleanSupplier.class);
            } catch(ReflectiveOperationException e) { throw new ExceptionInInitializerError(e); }
        }
    }
}
