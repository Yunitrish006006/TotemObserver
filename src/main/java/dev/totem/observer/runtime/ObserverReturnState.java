package dev.totem.observer.runtime;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

import java.util.Set;

/** Stored with the player's own data, never synchronized to other clients. */
public record ObserverReturnState(String dimension, double x, double y, double z,
                                  float yaw, float pitch, int mode, boolean flying) {
    public static final Codec<ObserverReturnState> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("dimension").forGetter(ObserverReturnState::dimension),
            Codec.DOUBLE.fieldOf("x").forGetter(ObserverReturnState::x),
            Codec.DOUBLE.fieldOf("y").forGetter(ObserverReturnState::y),
            Codec.DOUBLE.fieldOf("z").forGetter(ObserverReturnState::z),
            Codec.FLOAT.fieldOf("yaw").forGetter(ObserverReturnState::yaw),
            Codec.FLOAT.fieldOf("pitch").forGetter(ObserverReturnState::pitch),
            Codec.INT.fieldOf("mode").forGetter(ObserverReturnState::mode),
            Codec.BOOL.fieldOf("flying").forGetter(ObserverReturnState::flying)
    ).apply(i, ObserverReturnState::new));
    public static final AttachmentType<ObserverReturnState> TYPE = AttachmentRegistry.create(
            Identifier.fromNamespaceAndPath("totem-observer", "return_state"),
            builder -> builder.persistent(CODEC).copyOnDeath());

    public static void register() { }

    public static void capture(ServerPlayer player) {
        if (player.hasAttached(TYPE)) return;
        player.setAttached(TYPE, new ObserverReturnState(player.level().dimension().identifier().toString(),
                player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot(),
                player.gameMode.getGameModeForPlayer().getId(), player.getAbilities().flying));
    }

    public static boolean restore(ServerPlayer player) {
        var saved = player.getAttached(TYPE);
        if (saved == null) return false;
        var server = player.level().getServer();
        var level = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(saved.dimension)));
        double x = saved.x, y = saved.y, z = saved.z;
        if (level == null) {
            level = server.overworld();
            var spawn = level.getRespawnData().pos();
            x = spawn.getX() + 0.5; y = spawn.getY(); z = spawn.getZ() + 0.5;
        }
        player.setCamera(null);
        if (!player.teleportTo(level, x, y, z, Set.of(), saved.yaw, saved.pitch, false)) return false;
        player.setGameMode(GameType.byId(saved.mode));
        player.getAbilities().flying = saved.flying && player.getAbilities().mayfly;
        player.onUpdateAbilities();
        player.removeAttached(TYPE);
        return true;
    }
}
