package dev.totem.observer.runtime;

import com.mojang.brigadier.CommandDispatcher;
import dev.totem.observer.network.ObserverPayloads;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

import java.util.UUID;

/** Server-authoritative observer lifecycle for protocol-native Observer View. */
public final class ObserverSessionManager {
    private ObserverSessionManager() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> registerCommand(dispatcher));
        ServerTickEvents.START_SERVER_TICK.register(ObserverSessionManager::cleanup);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> stop(handler.player, true));
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> ObserverReturnState.restore(newPlayer));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> ObserverReturnState.restore(handler.player));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) stop(player, true);
        });
    }

    private static void registerCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("observeui")
                .requires(source -> source.getEntity() instanceof ServerPlayer)
                .then(Commands.literal("stop")
                        .executes(context -> stop(context.getSource().getPlayerOrException(), true)))
                .then(Commands.argument("target", EntityArgument.player())
                        .executes(context -> start(
                                context.getSource(),
                                context.getSource().getPlayerOrException(),
                                EntityArgument.getPlayer(context, "target")
                        ))));
    }

    private static int start(CommandSourceStack source, ServerPlayer observer, ServerPlayer target) {
        if (!ObserverAccessPolicy.allows(observer, target)) {
            source.sendFailure(Component.translatable("message.totem-observer.access_denied"));
            return 0;
        }
        if (observer == target) {
            source.sendFailure(Component.literal("You cannot observe your own UI."));
            return 0;
        }
        if (!ObserverNativeSessionManager.supports(observer, target)) {
            source.sendFailure(Component.literal(
                    "Observer View now requires protocol-native TotemObserver clients on both players; framebuffer fallback was removed."
            ));
            return 0;
        }

        stop(observer, true);
        observer.closeContainer();
        ObserverReturnState.capture(observer);
        observer.setGameMode(GameType.SPECTATOR);
        observer.setCamera(target);
        if (!ObserverNativeSessionManager.start(observer, target)) {
            observer.setCamera(null);
            ObserverReturnState.restore(observer);
            source.sendFailure(Component.literal("Failed to negotiate protocol-native Observer View."));
            return 0;
        }

        source.sendSuccess(() -> Component.literal(
                "Observing natively for " + target.getGameProfile().name()
        ), false);
        return 1;
    }

    public static int stop(ServerPlayer observer, boolean resetCamera) {
        boolean hadReturnState = observer.hasAttached(ObserverReturnState.TYPE);
        boolean nativeSession = ObserverNativeSessionManager.stop(observer);
        if (resetCamera && (nativeSession || hadReturnState)) {
            observer.setCamera(null);
            ObserverReturnState.restore(observer);
        }
        return nativeSession || hadReturnState ? 1 : 0;
    }

    public static void acceptScreenState(ServerPlayer target, ObserverPayloads.ScreenState payload) {
        forEachObserver(target.level().getServer(), target.getUUID(), observer ->
                ServerPlayNetworking.send(observer, new ObserverPayloads.ScreenRelay(
                        target.getUUID(), payload.open(), payload.screenClass(), payload.title()
                )));
    }

    public static void acceptStop(ServerPlayer observer) {
        stop(observer, true);
    }

    private static void cleanup(MinecraftServer server) {
        for (ServerPlayer observer : server.getPlayerList().getPlayers()) {
            if (!observer.hasAttached(ObserverReturnState.TYPE)) continue;
            if (!(observer.getCamera() instanceof ServerPlayer target)
                    || !observer.isSpectator()
                    || !ObserverAccessPolicy.allows(observer, target)
                    || !ObserverNativeSessionManager.observerIdsForTarget(target.getUUID(), 0L)
                    .contains(observer.getUUID())) {
                stop(observer, true);
            }
        }
    }

    private static void forEachObserver(
            MinecraftServer server,
            UUID targetId,
            java.util.function.Consumer<ServerPlayer> action
    ) {
        ServerPlayer target = server.getPlayerList().getPlayer(targetId);
        if (target == null) return;
        for (UUID observerId : ObserverNativeSessionManager.observerIdsForTarget(targetId, 0L)) {
            ServerPlayer observer = server.getPlayerList().getPlayer(observerId);
            if (observer != null
                    && ObserverAccessPolicy.allows(observer, target)
                    && ServerPlayNetworking.canSend(observer, ObserverPayloads.ScreenRelay.TYPE)) {
                action.accept(observer);
            }
        }
    }
}
