package dev.totem.observer.runtime;

import dev.totem.core.api.v1.gamerule.TotemGameRuleCategories;
import net.fabricmc.fabric.api.gamerule.v1.GameRuleBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.gamerules.GameRule;

/** Persistent world-wide Observer policy, shared across dimensions. */
public final class ObserverGameRules {
    public static final GameRule<Boolean> ENABLED = GameRuleBuilder.forBoolean(true)
            .category(TotemGameRuleCategories.TOTEM)
            .buildAndRegister(Identifier.fromNamespaceAndPath("totem", "observer_enabled"));
    public static final GameRule<Boolean> ALLOW_FRIENDS = GameRuleBuilder.forBoolean(false)
            .category(TotemGameRuleCategories.TOTEM)
            .buildAndRegister(Identifier.fromNamespaceAndPath("totem", "observer_allow_friends"));

    private ObserverGameRules() { }

    public static void register() { }

    public static boolean enabled(MinecraftServer server) {
        return server.overworld().getGameRules().get(ENABLED);
    }

    public static boolean allowFriends(MinecraftServer server) {
        return server.overworld().getGameRules().get(ALLOW_FRIENDS);
    }
}
