package com.tuusuario.blacklist.dungeonrun;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Resolver por defecto: cada celda con tiempo se guarda como una sala de 1x1 sin nombre real. */
public final class CellRoomResolver implements RoomResolver {

    @Override
    public List<RunData.RoomEntry> resolve(Map<String, Map<String, Double>> cellTimes) {
        List<RunData.RoomEntry> out = new ArrayList<>();
        for (Map.Entry<String, Map<String, Double>> e : cellTimes.entrySet()) {
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
}
