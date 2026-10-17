package com.tuusuario.blacklist.dungeonrun;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Lo que se guarda en disco por cada run (un JSON). Solo datos, sin logica. */
public final class RunData {

    public int version = 1;
    public String id;
    public String savedAt;
    /** Etiqueta libre, por ejemplo "F7". */
    public String floor = "?";

    public double totalSeconds;
    /** Segundos desde el inicio hasta llegar a 300 de score; null si no se llego. */
    public Double secondsTo300;
    public Integer secretsFound;

    public List<String> players = new ArrayList<>();
    /** Clase de cada jugador (índice coincide con players); null si no se pudo detectar. */
    public List<String> playerClasses = new ArrayList<>();

    /** Tiempo crudo: clave de celda "x,z" -> jugador -> segundos. */
    public Map<String, Map<String, Double>> cellTimes = new LinkedHashMap<>();

    /** Salas ya agrupadas (lo que usa el GUI): salas reales del mapa de la dungeon. */
    public List<RoomEntry> rooms = new ArrayList<>();

    /** Puertas entre salas: {x, z, dir (0 derecha, 1 abajo), tipo (0 normal, 1 wither, 2 sangre, 3 entrada, 4 sin explorar)}. */
    public List<int[]> doors = new ArrayList<>();

    public List<BonusEntry> bonus = new ArrayList<>();

    /**
     * Recorrido de cada jugador: nombre -> "x,z;x,z;..." en celdas fraccionarias de la grilla
     * (2.5,3.0 = mitad de la celda 2 en X, inicio de la celda 3 en Z). Vacio en runs viejas.
     */
    public Map<String, String> paths = new LinkedHashMap<>();

    /** Decodifica un recorrido guardado; devuelve una lista de {x, z}. */
    public static List<float[]> decodePath(String s) {
        List<float[]> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        for (String pair : s.split(";")) {
            int comma = pair.indexOf(',');
            if (comma < 0) continue;
            try {
                out.add(new float[] { Float.parseFloat(pair.substring(0, comma)),
                        Float.parseFloat(pair.substring(comma + 1)) });
            } catch (NumberFormatException ignored) {
                // punto danado: se salta
            }
        }
        return out;
    }

    public static final class RoomEntry {
        public String name;
        public String type;
        public String shape;
        /** Secretos segun rooms.json (null si no se pudo identificar la sala). */
        public Integer secrets;
        /** true si el nombre viene de rooms.json / del tipo; false si es un "Sala ?". */
        public boolean identified;
        /** Celdas [x, z] que ocupa la sala. */
        public List<int[]> cells = new ArrayList<>();
        /** Jugador -> segundos dentro de la sala. */
        public Map<String, Double> seconds = new LinkedHashMap<>();
    }

    public static final class BonusEntry {
        public String source;
        public int points;
        public double atSeconds;
        public String note;
    }

    public int bonusTotal() {
        int sum = 0;
        for (BonusEntry b : bonus) sum += b.points;
        return sum;
    }
}
