package com.tuusuario.blacklist.dungeonrun;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Posicion de los jugadores segun los marcadores del mapa de la dungeon. Sirve para seguir a los que el cliente
 * no ve como entidad (estan lejos). No depende de Minecraft.
 *
 * Los marcadores no traen nombre: Hypixel manda uno por cada jugador vivo, en el orden de la party del tab.
 * Por eso solo se asignan nombres cuando la cantidad de marcadores coincide con la de jugadores vivos.
 */
public final class MapPlayers {

    private MapPlayers() {}

    /** Marcador {x, y} (bytes de Minecraft) -> posicion del mundo {x, z}, o null si cae fuera de la grilla. */
    public static double[] toWorld(DungeonMap map, int markerX, int markerY) {
        double pixelX = (markerX + 128) / 2.0;
        double pixelY = (markerY + 128) / 2.0;
        double cellX = (pixelX - map.startX) / map.step();
        double cellZ = (pixelY - map.startY) / map.step();
        if (cellX < 0 || cellZ < 0 || cellX >= DungeonGrid.CELLS || cellZ >= DungeonGrid.CELLS) return null;
        return new double[] {
                DungeonGrid.ORIGIN + cellX * DungeonGrid.STEP,
                DungeonGrid.ORIGIN + cellZ * DungeonGrid.STEP };
    }

    /** Nombre -> {x, z} del mundo. Vacio si no se puede asignar con seguridad. */
    public static Map<String, double[]> assign(DungeonMap map, List<int[]> markers, List<String> aliveInOrder) {
        Map<String, double[]> out = new LinkedHashMap<>();
        if (map == null || markers.isEmpty() || markers.size() != aliveInOrder.size()) return out;
        for (int i = 0; i < markers.size(); i++) {
            double[] w = toWorld(map, markers.get(i)[0], markers.get(i)[1]);
            if (w != null) out.put(aliveInOrder.get(i), w);
        }
        return out;
    }
}
