package com.tuusuario.blacklist.dungeonrun;

import com.google.gson.Gson;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Indice de rooms.json: dado el "core" de una sala (hash de su columna de bloques) devuelve la sala,
 * o dado tipo + forma devuelve las salas posibles.
 *
 * rooms.json se busca primero en un archivo (para poder cambiarlo sin recompilar) y despues dentro del mod
 * como recurso /rooms.json (src/main/resources/rooms.json).
 */
public final class RoomIndex {

    /** Una fila de rooms.json. Los campos que no se usan (fingerprints, doors...) los ignora Gson. */
    public static final class Entry {
        public String name;
        public String type;
        public String shape;
        public int secrets;
        public int[] cores;
        public int roomID;
    }

    private final List<Entry> all = new ArrayList<>();
    private final Map<Integer, Entry> byCore = new HashMap<>();

    public static RoomIndex load(Path overrideFile) {
        RoomIndex idx = new RoomIndex();
        try {
            Reader r = null;
            if (overrideFile != null && Files.isRegularFile(overrideFile)) {
                r = Files.newBufferedReader(overrideFile, StandardCharsets.UTF_8);
            } else {
                InputStream in = RoomIndex.class.getResourceAsStream("/rooms.json");
                if (in != null) r = new InputStreamReader(in, StandardCharsets.UTF_8);
            }
            if (r != null) {
                try (Reader reader = r) {
                    Entry[] rows = new Gson().fromJson(reader, Entry[].class);
                    if (rows != null) {
                        for (Entry e : rows) idx.add(e);
                    }
                }
            }
        } catch (Exception ignored) {
            // sin rooms.json las salas quedan sin nombre, pero el mapa y los tiempos funcionan igual
        }
        return idx;
    }

    /** Para pruebas: arma un indice a partir de filas ya creadas. */
    public static RoomIndex of(List<Entry> rows) {
        RoomIndex idx = new RoomIndex();
        for (Entry e : rows) idx.add(e);
        return idx;
    }

    private void add(Entry e) {
        if (e == null || e.name == null) return;
        all.add(e);
        if (e.cores != null) {
            for (int core : e.cores) byCore.putIfAbsent(core, e);
        }
    }

    public int size() {
        return all.size();
    }

    public Entry byCore(int core) {
        return byCore.get(core);
    }

    /** Salas que pueden ser: el tipo del mapa ("normal" tambien admite "rare") y la forma deben coincidir. */
    public List<Entry> candidates(String mapType, String shape) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : all) {
            if (shape != null && e.shape != null && !shape.equals(e.shape)) continue;
            if (!typeMatches(mapType, e.type)) continue;
            out.add(e);
        }
        return out;
    }

    private static boolean typeMatches(String mapType, String jsonType) {
        if (mapType == null || "unknown".equals(mapType)) return true;
        if ("normal".equals(mapType)) return "normal".equals(jsonType) || "rare".equals(jsonType);
        return mapType.equals(jsonType);
    }
}
