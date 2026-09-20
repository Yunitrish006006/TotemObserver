package dev.totem.observer.mixin.client;

import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only access to the current client-side Sign editor state. */
@Mixin(AbstractSignEditScreen.class)
public interface AbstractSignEditScreenAccessor {
    @Accessor("messages")
    String[] totem$getMessages();

    @Accessor("slot")
    SignTextSlot totem$getSlot();

    @Accessor("line")
    int totem$getLine();

    @Accessor("line")
    void totem$setLine(int value);

    @Accessor("text")
    SignText.Mutable totem$getText();

    @Accessor("textColor")
    int totem$getTextColor();

    @Mutable
    @Accessor("textColor")
    void totem$setTextColor(int value);
}
