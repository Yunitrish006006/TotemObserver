package dev.totem.observer.runtime;

import dev.totem.core.api.v1.gamerule.TotemGameRuleCategories;
import dev.totem.core.api.v1.social.TotemFriendshipApi;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.gamerules.GameRuleType;

/** Real server authority and persistent return-state regressions, without optional modules. */
public final class ObserverAccessGameTest {
    @GameTest(maxTicks = 60)
    public void rulesAndFriendshipControlAdmission(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var rules = server.overworld().getGameRules();
        boolean enabled = rules.get(ObserverGameRules.ENABLED);
        boolean friends = rules.get(ObserverGameRules.ALLOW_FRIENDS);
        var observer = makePlayer(helper);
        var target = makePlayer(helper);
        try {
            require(helper, ObserverGameRules.ENABLED.defaultValue(), "Observer should default on");
            require(helper, !ObserverGameRules.ALLOW_FRIENDS.defaultValue(), "Friends should require opt-in");
            for (var rule : java.util.List.of(ObserverGameRules.ENABLED, ObserverGameRules.ALLOW_FRIENDS)) {
                require(helper, rule.category() == TotemGameRuleCategories.TOTEM, "Wrong gamerule category");
                require(helper, rule.gameRuleType() == GameRuleType.BOOL, "Gamerule is not boolean");
            }
            rules.set(ObserverGameRules.ENABLED, true, server);
            rules.set(ObserverGameRules.ALLOW_FRIENDS, true, server);
            require(helper, !ObserverAccessPolicy.allows(observer, target), "Stranger was authorized");
            TotemFriendshipApi.inviteOrAccept(server, observer.getUUID(), target.getUUID());
            require(helper, !ObserverAccessPolicy.allows(observer, target), "One-way invite was authorized");
            TotemFriendshipApi.inviteOrAccept(server, target.getUUID(), observer.getUUID());
            require(helper, ObserverAccessPolicy.allows(observer, target), "Mutual friend was denied");
            rules.set(ObserverGameRules.ALLOW_FRIENDS, false, server);
            require(helper, !ObserverAccessPolicy.allows(observer, target), "Friend toggle was ignored");
            rules.set(ObserverGameRules.ALLOW_FRIENDS, true, server);
            rules.set(ObserverGameRules.ENABLED, false, server);
            require(helper, !ObserverAccessPolicy.allows(observer, target), "Master toggle was ignored");
            rules.set(ObserverGameRules.ENABLED, true, server);
            TotemFriendshipApi.removeRelationship(server, observer.getUUID(), target.getUUID());
            require(helper, !ObserverAccessPolicy.allows(observer, target), "Revoked friend remains authorized");
        } finally {
            TotemFriendshipApi.removeRelationship(server, observer.getUUID(), target.getUUID());
            rules.set(ObserverGameRules.ENABLED, enabled, server);
            rules.set(ObserverGameRules.ALLOW_FRIENDS, friends, server);
            observer.discard(); target.discard();
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 60)
    public void savedReturnStateRestoresModesAndPosition(GameTestHelper helper) {
        var player = makePlayer(helper);
        try {
            for (var mode : GameType.values()) {
                player.setGameMode(mode);
                var origin = player.position();
                ObserverReturnState.capture(player);
                player.setGameMode(GameType.SPECTATOR);
                player.teleportTo(origin.x + 8, origin.y, origin.z + 8);
                var output = net.minecraft.world.level.storage.TagValueOutput.createWithContext(
                        net.minecraft.util.ProblemReporter.DISCARDING, helper.getLevel().registryAccess());
                player.saveWithoutId(output);
                player.removeAttached(ObserverReturnState.TYPE);
                player.load(net.minecraft.world.level.storage.TagValueInput.create(
                        net.minecraft.util.ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), output.buildResult()));
                require(helper, player.hasAttached(ObserverReturnState.TYPE), "Player save/load lost return attachment");
                ObserverSessionManager.stop(player, true);
                require(helper, player.gameMode.getGameModeForPlayer() == mode, "Original mode was not restored");
                require(helper, player.position().distanceToSqr(origin) < 0.001, "Original position was not restored");
                require(helper, !player.hasAttached(ObserverReturnState.TYPE), "Return state was not removed");
            }
        } finally { player.discard(); }
        helper.succeed();
    }

    @GameTest(maxTicks = 60)
    public void temporarySpectatorCannotUseVanillaTeleport(GameTestHelper helper) {
        var player = makePlayer(helper);
        var stranger = makePlayer(helper);
        try {
            player.setGameMode(GameType.SURVIVAL);
            var origin = player.position();
            stranger.teleportTo(origin.x + 32, origin.y + 4, origin.z + 32);
            ObserverReturnState.capture(player);
            player.setGameMode(GameType.SPECTATOR);
            player.connection.handleTeleportToEntityPacket(
                    new net.minecraft.network.protocol.game.ServerboundTeleportToEntityPacket(stranger.getUUID()));
            require(helper, player.position().distanceToSqr(origin) < 0.001, "Observer teleported to unrelated player");
            player.connection.handleSpectatorAction(new net.minecraft.network.protocol.game.ServerboundSpectatorActionPacket(
                    java.util.OptionalInt.of(stranger.getId())));
            require(helper, player.getCamera() == player, "Observer selected an unrelated camera");
            player.connection.handleMovePlayer(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Pos(
                    origin.x + 2, origin.y, origin.z, false, false));
            require(helper, player.position().distanceToSqr(origin) < 0.001, "Observer moved independently");
            ObserverSessionManager.stop(player, true);
            require(helper, player.gameMode.getGameModeForPlayer() == GameType.SURVIVAL, "Stop was blocked");
            player.setGameMode(GameType.SPECTATOR);
            player.connection.handleTeleportToEntityPacket(
                    new net.minecraft.network.protocol.game.ServerboundTeleportToEntityPacket(stranger.getUUID()));
            require(helper, player.position().distanceToSqr(stranger.position()) < 0.001,
                    "Unrelated vanilla spectator behavior was blocked");
        } finally { ObserverSessionManager.stop(player, true); player.discard(); stranger.discard(); }
        helper.succeed();
    }

    @GameTest(maxTicks = 60)
    @SuppressWarnings("unchecked")
    public void activeRevocationRestoresPlayer(GameTestHelper helper) throws ReflectiveOperationException {
        var server = helper.getLevel().getServer();
        var rules = server.overworld().getGameRules();
        boolean enabled = rules.get(ObserverGameRules.ENABLED);
        boolean friends = rules.get(ObserverGameRules.ALLOW_FRIENDS);
        var player = makePlayer(helper);
        var target = makePlayer(helper);
        var field = ObserverSessionManager.class.getDeclaredField("TARGET_BY_OBSERVER");
        field.setAccessible(true);
        var sessions = (java.util.Map<java.util.UUID, java.util.UUID>) field.get(null);
        var cleanup = ObserverSessionManager.class.getDeclaredMethod("cleanup", net.minecraft.server.MinecraftServer.class);
        cleanup.setAccessible(true);
        try {
            for (int reason = 0; reason < 3; reason++) {
                rules.set(ObserverGameRules.ENABLED, true, server);
                rules.set(ObserverGameRules.ALLOW_FRIENDS, true, server);
                TotemFriendshipApi.inviteOrAccept(server, player.getUUID(), target.getUUID());
                TotemFriendshipApi.inviteOrAccept(server, target.getUUID(), player.getUUID());
                player.setGameMode(GameType.CREATIVE);
                var origin = player.position();
                ObserverReturnState.capture(player);
                player.setGameMode(GameType.SPECTATOR);
                player.setCamera(target);
                sessions.put(player.getUUID(), target.getUUID());
                if (reason == 0) rules.set(ObserverGameRules.ENABLED, false, server);
                if (reason == 1) rules.set(ObserverGameRules.ALLOW_FRIENDS, false, server);
                if (reason == 2) TotemFriendshipApi.removeRelationship(server, player.getUUID(), target.getUUID());
                cleanup.invoke(null, server);
                require(helper, !sessions.containsKey(player.getUUID()), "Revocation left an active session");
                require(helper, player.gameMode.getGameModeForPlayer() == GameType.CREATIVE, "Revocation lost original mode");
                require(helper, player.position().distanceToSqr(origin) < 0.001, "Revocation lost original position");
                TotemFriendshipApi.removeRelationship(server, player.getUUID(), target.getUUID());
            }
        } finally {
            ObserverSessionManager.stop(player, true);
            TotemFriendshipApi.removeRelationship(server, player.getUUID(), target.getUUID());
            rules.set(ObserverGameRules.ENABLED, enabled, server);
            rules.set(ObserverGameRules.ALLOW_FRIENDS, friends, server);
            player.discard(); target.discard();
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 100)
    public void asyncBookAndSignPacketsAreReadOnly(GameTestHelper helper) {
        var player = makePlayer(helper);
        var book = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.WRITABLE_BOOK);
        player.getInventory().setItem(0, book);
        var pos = helper.absolutePos(new net.minecraft.core.BlockPos(1, 2, 1));
        helper.getLevel().setBlockAndUpdate(pos, net.minecraft.world.level.block.Blocks.OAK_SIGN.defaultBlockState());
        var sign = (net.minecraft.world.level.block.entity.SignBlockEntity) helper.getLevel().getBlockEntity(pos);
        sign.setAllowedPlayerEditor(player.getUUID());
        player.teleportTo(pos.getX(), pos.getY(), pos.getZ());
        ObserverReturnState.capture(player);
        player.setGameMode(GameType.SPECTATOR);
        var incoming = java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                player.connection.handleEditBook(new net.minecraft.network.protocol.game.ServerboundEditBookPacket(
                        0, java.util.List.of("injected"), java.util.Optional.empty()));
            } catch (net.minecraft.server.RunningOnDifferentThreadException scheduled) { }
            try {
                player.connection.handleSignUpdate(new net.minecraft.network.protocol.game.ServerboundSignUpdatePacket(
                        pos, true, "injected", "", "", ""));
            } catch (net.minecraft.server.RunningOnDifferentThreadException scheduled) { }
        });
        helper.startSequence().thenWaitUntil(() -> {
            require(helper, incoming.isDone(), "Incoming packets have not been scheduled");
            incoming.join();
        }).thenIdle(5).thenExecute(() -> {
            try {
                require(helper, book.get(net.minecraft.core.component.DataComponents.WRITABLE_BOOK_CONTENT).pages().isEmpty(),
                        "Async book packet changed inventory");
                require(helper, sign.getFrontText().getMessage(0, false).getString().isEmpty(),
                        "Async sign packet changed world");
            } finally { ObserverSessionManager.stop(player, true); player.discard(); }
        }).thenSucceed();
    }

    private static net.minecraft.server.level.ServerPlayer makePlayer(GameTestHelper helper) {
        // Vanilla's mock overrides gameMode() to CREATIVE, so it cannot test spectator authority.
        var server = helper.getLevel().getServer();
        var profile = new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "observer-test");
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(profile, false);
        var player = new net.minecraft.server.level.ServerPlayer(server, helper.getLevel(), profile, cookie.clientInformation());
        var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        return player;
    }

    private static void require(GameTestHelper helper, boolean value, String message) {
        if (!value) helper.fail(message);
    }
}
