package com.tuusuario.blacklist; // <- mismo paquete que DungeonRunManager

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

/**
 * Lee los 128x128 colores del mapa que Hypixel pone en el ultimo slot de la hotbar.
 *
 * Es la unica clase que toca la API de mapas de Minecraft. Si en 26.1.2 cambio algun nombre
 * (MapItem.getSavedData, MapItemSavedData.colors, Inventory.getItem) se arregla aca y en ningun otro lado.
 */
public final class DungeonMapReader {

    /** Ultimo slot de la hotbar (el 9 de la pantalla) = indice 8. */
    private static final int HOTBAR_LAST = 8;

    private DungeonMapReader() {}

    /**
     * Marcadores (flechitas) de los jugadores del mapa, en el orden en que el servidor los envia.
     * Cada uno es {x, y} tal como los guarda Minecraft (byte -128..127 = pixel * 2 - 128). Vacio si no hay mapa.
     */
    public static java.util.List<int[]> readMarkers(Minecraft mc) {
        java.util.List<int[]> out = new java.util.ArrayList<>();
        if (mc.player == null || mc.level == null) return out;
        ItemStack stack = mc.player.getInventory().getItem(HOTBAR_LAST);
        if (stack.isEmpty() || !stack.is(Items.FILLED_MAP)) return out;
        MapItemSavedData data = MapItem.getSavedData(stack, mc.level);
        if (data == null) return out;
        for (net.minecraft.world.level.saveddata.maps.MapDecoration d : data.getDecorations()) {
            out.add(new int[] { d.x(), d.y() });
        }
        return out;
    }

    /** Colores crudos del mapa, o null si el slot no tiene un mapa cargado. */
    public static byte[] read(Minecraft mc) {
        if (mc.player == null || mc.level == null) return null;
        ItemStack stack = mc.player.getInventory().getItem(HOTBAR_LAST);
        if (stack.isEmpty() || !stack.is(Items.FILLED_MAP)) return null;
        MapItemSavedData data = MapItem.getSavedData(stack, mc.level);
        return data == null ? null : data.colors;
    }
}
