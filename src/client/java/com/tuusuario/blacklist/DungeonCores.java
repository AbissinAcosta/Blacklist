package com.tuusuario.blacklist; // <- mismo paquete que DungeonRunManager

import com.tuusuario.blacklist.dungeonrun.DungeonGrid;
import com.tuusuario.blacklist.dungeonrun.LegacyBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.util.Set;

/**
 * Calcula el "core" de una sala: el hash que usa rooms.json para reconocerla.
 *
 * Algoritmo (el de Odin / Dungeon Rooms Mod): en el centro de la celda, de y=140 a y=12, se concatena el id
 * 1.8 de cada bloque, salteando madera (5), cofre (54) y cofre trampa (146), y se toma String.hashCode().
 * El centro de la celda (c, z) es -185 + 32 * indice.
 */
public final class DungeonCores {

    private static final int SKIP_PLANKS = 5;
    private static final int SKIP_CHEST = 54;
    private static final int SKIP_TRAPPED = 146;

    private DungeonCores() {}

    public static int centerOf(int cell) {
        return DungeonGrid.ORIGIN + 15 + DungeonGrid.STEP * cell;
    }

    /**
     * @param unmapped si no es null, recibe los nombres de bloque que LegacyBlocks no conoce
     * @return el hash, o null si el chunk todavia no esta cargado
     */
    public static Integer hash(Level level, int cellX, int cellZ, Set<String> unmapped) {
        int x = centerOf(cellX);
        int z = centerOf(cellZ);
        if (!level.hasChunkAt(new BlockPos(x, 64, z))) return null;

        StringBuilder sb = new StringBuilder(160);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = 140; y >= 12; y--) {
            BlockState st = level.getBlockState(pos.set(x, y, z));
            String name = BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
            boolean lit = st.hasProperty(BlockStateProperties.LIT) && st.getValue(BlockStateProperties.LIT);
            boolean dbl = st.hasProperty(BlockStateProperties.SLAB_TYPE)
                    && st.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE;
            int id = LegacyBlocks.idOf(name, lit, dbl);
            if (id < 0 && unmapped != null) unmapped.add(name);
            if (id == SKIP_PLANKS || id == SKIP_CHEST || id == SKIP_TRAPPED) continue;
            sb.append(id);
        }
        return sb.toString().hashCode();
    }
}
