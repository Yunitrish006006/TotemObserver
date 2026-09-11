package dev.totem.observer.runtime;

import dev.totem.core.api.v1.social.TotemFriendshipApi;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

/** Rechecked at admission, during sessions and before forwarding private state. */
public final class ObserverAccessPolicy {
    private ObserverAccessPolicy() { }

    public static boolean allows(ServerPlayer observer, ServerPlayer target) {
        if (observer == null || target == null
                || !observer.isAlive() || !target.isAlive()
                || observer.level().getServer() != target.level().getServer()) return false;
        var server = observer.level().getServer();
        if (!ObserverGameRules.enabled(server)) return false;
        if (Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(observer.createCommandSourceStack())) {
            return true;
        }
        return ObserverGameRules.allowFriends(server)
                && TotemFriendshipApi.areMutualFriends(server, observer.getUUID(), target.getUUID());
    }
}
