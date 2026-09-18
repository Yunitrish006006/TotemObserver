package dev.totem.observer.gametest;

import dev.totem.observer.client.ObserverNativeClient;
import dev.totem.observer.client.ObserverSignScreenClient;
import dev.totem.observer.network.ObserverNativePayloads;
import dev.totem.observer.network.ObserverSignScreenPayloads;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;

/** Client runtime proof for Sign semantic reconstruction. */
public final class ObserverSignClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.waitTicks(2);
            singleplayer.getConnection().waitForChunksRender();
            UUID targetId = UUID.randomUUID();
            context.runOnClient(minecraft -> {
                applySession(true, targetId, ObserverSignScreenPayloads.CAPABILITY);
                accept(ObserverSignScreenPayloads.relay(targetId, openState(1L)));
            });
            context.waitFor(minecraft -> minecraft.gui.screen() != null
                    && minecraft.gui.screen().getClass().getName().contains("ObserverHangingSignScreen"), 100);
            context.waitFor(minecraft -> getLong("extractedFrames") > 0L, 100);
            context.runOnClient(minecraft -> assertProductionSign(minecraft, false, "black", true, 2));
            if (!"hanging_sign".equals(getString("remoteVariant")) || getBoolean("remoteFrontText")
                    || getInt("remoteCurrentLine") != 2 || !"black".equals(getString("remoteColor"))
                    || !getBoolean("remoteGlowing") || getListSize("remoteLines") != 4) {
                throw new AssertionError("Sign semantic state was not reconstructed correctly");
            }
            persistForCi(context.takeScreenshot("observer-ui-native-sign-screen"),
                    "observer-ui-native-sign-screen.png");
            context.runOnClient(minecraft -> {
                accept(ObserverSignScreenPayloads.relay(targetId, new ObserverSignScreenPayloads.SignState(
                        ObserverSignScreenPayloads.PROTOCOL_VERSION, 2L, true,
                        ObserverSignScreenPayloads.FAMILY_ID, ObserverSignScreenPayloads.HANGING_SIGN_SCREEN_CLASS,
                        "Edit Hanging Sign", "hanging_sign", false, 1, "red", false,
                        List.of("", "", "", ""))));
                assertProductionSign(minecraft, false, "red", false, 1);
                accept(ObserverSignScreenPayloads.relay(targetId, new ObserverSignScreenPayloads.SignState(
                        ObserverSignScreenPayloads.PROTOCOL_VERSION, 3L, true,
                        ObserverSignScreenPayloads.FAMILY_ID, net.minecraft.client.gui.screens.inventory.SignEditScreen.class.getName(),
                        "Edit Sign", "sign", true, 0, "blue", true,
                        List.of("", "", "", ""))));
                assertProductionSign(minecraft, true, "blue", true, 0);
                accept(ObserverSignScreenPayloads.relay(targetId, ObserverSignScreenPayloads.closed(4L)));
                applySession(false, new UUID(0L, 0L), 0L);
            });
            context.waitForScreen(null);
            if (getLong("suppressedRemovalPackets") < 1L) {
                throw new AssertionError("Observer Sign removal did not prove sign-update packet suppression");
            }
        }
    }

    private static ObserverSignScreenPayloads.SignState openState(long sequence) {
        return new ObserverSignScreenPayloads.SignState(
                ObserverSignScreenPayloads.PROTOCOL_VERSION, sequence, true,
                ObserverSignScreenPayloads.FAMILY_ID, ObserverSignScreenPayloads.HANGING_SIGN_SCREEN_CLASS,
                "Edit Hanging Sign", "hanging_sign", false, 2, "black", true,
                List.of("Observer", "semantic", "sign editing", "works"));
    }

    private static void assertProductionSign(net.minecraft.client.Minecraft minecraft, boolean front,
                                             String color, boolean glowing, int line) {
        var accessor = (dev.totem.observer.mixin.client.AbstractSignEditScreenAccessor) minecraft.gui.screen();
        var text = accessor.totem$getText().asImmutable();
        var expectedColor = net.minecraft.world.item.DyeColor.byName(color, net.minecraft.world.item.DyeColor.BLACK);
        int renderedColor = glowing ? expectedColor.getTextColor()
                : net.minecraft.client.renderer.blockentity.AbstractSignRenderer.getDarkColor(text);
        if (text.getColor() != expectedColor || text.hasGlowingText() != glowing
                || accessor.totem$getTextColor() != renderedColor || accessor.totem$getLine() != line
                || accessor.totem$getSlot() != (front ? net.minecraft.world.level.block.entity.SignTextSlot.FRONT
                : net.minecraft.world.level.block.entity.SignTextSlot.BACK)) {
            throw new AssertionError("Production sign style, side or current line differs from relay");
        }
        if (!color.equals("black") && java.util.Arrays.stream(accessor.totem$getMessages()).anyMatch(value -> !value.isEmpty())) {
            throw new AssertionError("Blank sign snapshot retained old editor text");
        }
    }

    private static void accept(ObserverSignScreenPayloads.SignRelay relay) {
        invoke(ObserverSignScreenClient.class, "acceptRelay",
                new Class<?>[]{ObserverSignScreenPayloads.SignRelay.class}, relay);
    }

    private static void applySession(boolean active, UUID targetId, long capabilities) {
        invoke(ObserverNativeClient.class, "applySession", new Class<?>[]{ObserverNativePayloads.NativeSession.class},
                new ObserverNativePayloads.NativeSession(active, targetId, active ? "SignTarget" : "",
                        ObserverNativePayloads.PROTOCOL_VERSION, capabilities));
    }

    private static void invoke(Class<?> owner, String name, Class<?>[] types, Object... args) {
        try { Method method = owner.getDeclaredMethod(name, types); method.setAccessible(true); method.invoke(null, args); }
        catch (ReflectiveOperationException error) { throw new RuntimeException(error); }
    }
    private static Field field(String name) {
        try { Field field = ObserverSignScreenClient.class.getDeclaredField(name); field.setAccessible(true); return field; }
        catch (ReflectiveOperationException error) { throw new RuntimeException(error); }
    }
    private static boolean getBoolean(String name) {
        try { return field(name).getBoolean(null); } catch (IllegalAccessException error) { throw new RuntimeException(error); }
    }
    private static int getInt(String name) {
        try { return field(name).getInt(null); } catch (IllegalAccessException error) { throw new RuntimeException(error); }
    }
    private static long getLong(String name) {
        try { return field(name).getLong(null); } catch (IllegalAccessException error) { throw new RuntimeException(error); }
    }
    private static String getString(String name) {
        try { return (String) field(name).get(null); } catch (IllegalAccessException error) { throw new RuntimeException(error); }
    }
    private static int getListSize(String name) {
        try { Object value = field(name).get(null); return value instanceof List<?> list ? list.size() : -1; }
        catch (IllegalAccessException error) { throw new RuntimeException(error); }
    }

    private static void persistForCi(Path screenshot, String fileName) {
        String workspace = System.getenv("GITHUB_WORKSPACE");
        if (workspace == null || workspace.isBlank()) return;
        try {
            Path dir = Path.of(workspace).resolve("build/client-gametest-screenshots");
            Files.createDirectories(dir);
            Files.copy(screenshot, dir.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception error) { throw new RuntimeException(error); }
    }
}
