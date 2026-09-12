package dev.totem.observer.e2e;

import dev.totem.nexus.space.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.material.MapColor;
import java.util.Set;

/** Server-owned fixture: the observer never receives client-invented terrain. */
final class ObserverNexusTerrainFixture {
    static void install(ServerPlayer target) {
        var level=target.level(); var anchor=new BlockPos(10,64,10);
        level.setBlockAndUpdate(anchor, Blocks.LODESTONE.defaultBlockState());
        var source=java.util.UUID.fromString("10000000-0000-0000-0000-000000000001");
        var units=level.getServer().overworld().getDataStorage().computeIfAbsent(NexusSpaceUnitSavedData.TYPE);
        units.put(new NexusSpaceUnitRecord(source,SpaceUnitType.LODESTONE,level.dimension(),anchor,target.getUUID(),
                "E2E terrain",SpaceUnitVisibility.PUBLIC,SpaceUnitStatus.ACTIVE,Set.of(),Set.of(),
                SpaceStructureSnapshot.EMPTY,0,0));
        var data=dev.totem.nexus.mixin.NexusMapItemSavedDataInvoker.totem$createExact(10,10,(byte)2,false,false,false,level.dimension());
        java.util.Arrays.fill(data.colors,MapColor.GRASS.getPackedId(MapColor.Brightness.NORMAL));
        var id=new MapId(8801); level.setMapData(id,data);
        level.getServer().overworld().getDataStorage().computeIfAbsent(NexusMapBindingSavedData.TYPE)
                .bind(id,source,GlobalPos.of(level.dimension(),anchor),data);
        var stack=new ItemStack(Items.FILLED_MAP);stack.set(DataComponents.MAP_ID,id);
        NexusInterfaceBinding.write(stack,level,anchor,source);
        target.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND,stack);
        // Optional on the old pinned owner; current Nexus must exercise a real sparse page.
        try {
            var type=Class.forName("dev.totem.nexus.space.NexusMapDetailSavedData");
            var store=type.getMethod("get",net.minecraft.server.level.ServerLevel.class).invoke(null,level);
            var record=type.getMethod("record",net.minecraft.server.level.ServerLevel.class,int.class,int.class,int.class,byte.class);
            for(int z=0;z<128;z++) for(int x=0;x<128;x++)
                record.invoke(store,level,8801,x,z,MapColor.SAND.getPackedId(MapColor.Brightness.NORMAL));
        } catch(ClassNotFoundException oldOwner) { /* Pinned pre-detail owner. */ }
        catch(ReflectiveOperationException error) { throw new IllegalStateException("Cannot create owner terrain fixture",error); }
    }
}
