package com.tuusuario.blacklist.dungeonrun;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agrupa el tiempo por celda en las salas reales del mapa de la dungeon.
 *
 * Nombre de cada sala, en orden de confianza:
 *  1. por core (hash de la columna de bloques) contra rooms.json -> exacto;
 *  2. si el mapa + la forma dejan una sola sala posible en rooms.json;
 *  3. si es entrada / sangre / hada, por el tipo;
 *  4. si no, "Sala ?" (el tipo y la forma igual se guardan).
 */
public final class MapRoomResolver implements RoomResolver {

    private final DungeonMap map;
    private final RoomIndex index;
    private final Map<String, Integer> coreByCell;

    public MapRoomResolver(DungeonMap map, RoomIndex index, Map<String, Integer> coreByCell) {
        this.map = map;
        this.index = index;
        this.coreByCell = coreByCell;
    }

    @Override
    public List<RunData.RoomEntry> resolve(Map<String, Map<String, Double>> cellTimes) {
        if (map == null) return new CellRoomResolver().resolve(cellTimes);

        List<RunData.RoomEntry> out = new ArrayList<>();
        Set<String> used = new HashSet<>();

        for (DungeonMap.Room mr : map.rooms) {
            RunData.RoomEntry room = new RunData.RoomEntry();
            room.type = mr.type;
            room.shape = mr.shape;
            room.seconds = new LinkedHashMap<>();

            for (int[] c : mr.cells) {
                room.cells.add(c);
                String key = c[0] + "," + c[1];
                used.add(key);
                Map<String, Double> t = cellTimes.get(key);
                if (t != null) {
                    for (Map.Entry<String, Double> e : t.entrySet()) room.seconds.merge(e.getKey(), e.getValue(), Double::sum);
                }
            }
            name(room, mr);
            out.add(room);
        }

        // Tiempo en celdas que el mapa no muestra (por ejemplo la sala del boss): no se pierde
        for (Map.Entry<String, Map<String, Double>> e : cellTimes.entrySet()) {
            if (used.contains(e.getKey())) continue;
            RunData.RoomEntry room = new RunData.RoomEntry();
            room.name = "Celda " + e.getKey();
            room.type = "unknown";
            room.shape = "1x1";
            room.cells.add(DungeonGrid.parse(e.getKey()));
            room.seconds = new LinkedHashMap<>(e.getValue());
            out.add(room);
        }
        return out;
    }

    private void name(RunData.RoomEntry room, DungeonMap.Room mr) {
        // 1) core exacto
        for (int[] c : mr.cells) {
            Integer core = coreByCell.get(c[0] + "," + c[1]);
            RoomIndex.Entry hit = core == null ? null : index.byCore(core);
            if (hit != null) {
                room.name = hit.name;
                room.secrets = hit.secrets;
                room.identified = true;
                if (hit.type != null && !"normal".equals(room.type)) room.type = hit.type;
                return;
            }
        }
        // 2) una sola candidata (no para salas sin explorar: el mapa todavia no dice que tipo son)
        List<RoomIndex.Entry> cand = "unknown".equals(mr.type) ? List.of() : index.candidates(mr.type, mr.shape);
        if (cand.size() == 1) {
            room.name = cand.get(0).name;
            room.secrets = cand.get(0).secrets;
            room.identified = true;
            return;
        }
        // 3) por tipo
        switch (mr.type) {
            case "entrance": room.name = "Entrance"; room.identified = true; return;
            case "blood": room.name = "Blood"; room.identified = true; return;
            case "fairy": room.name = "Fairy"; room.identified = true; return;
            default: break;
        }
        // 4) sin identificar
        room.name = "unknown".equals(mr.type) ? "Sala sin explorar" : "Sala ? (" + mr.shape + ")";
    }
}
