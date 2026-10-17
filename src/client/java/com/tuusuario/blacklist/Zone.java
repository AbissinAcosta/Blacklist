package com.tuusuario.blacklist; // <- ajusta al paquete de tu proyecto

import java.util.List;
import java.util.Locale;

/**
 * Zonas de SkyBlock que el mod sabe reconocer. Se usan para decidir dónde funciona cada atajo
 * (ver CommandKey) y para saber si estás en el Dungeon Hub o en una dungeon (ver Location).
 *
 * El nombre se compara con la línea "Area: ..." del Tab de Hypixel (ignorando mayúsculas,
 * símbolos y el "The" inicial). Catacombs, Kuudra y Dungeon Hub también se reconocen por el
 * marcador lateral, porque dentro de la run el Tab no trae la línea Area.
 *
 * Para añadir una zona: agrega una línea aquí (el nombre tal como sale en "Area:" del Tab).
 * Si el nombre del Tab es distinto al que se muestra, pasa los nombres extra como alias.
 * /blacklistmod profit zone te dice qué Area está leyendo el mod.
 */
public enum Zone {
    PRIVATE_ISLAND("Private Island"),
    GARDEN("Garden"),
    HUB("Hub"),
    BARN("The Barn"),
    MUSHROOM_DESERT("Mushroom Desert"),
    PARK("The Park"),
    SPIDERS_DEN("Spider's Den"),
    BLAZING_FORTRESS("Blazing Fortress"),
    THE_END("The End"),
    DEEP_CAVERNS("Deep Caverns"),
    GOLD_MINE("Gold Mine"),
    DWARVEN_MINES("Dwarven Mines"),
    CRYSTAL_HOLLOWS("Crystal Hollows"),
    MINESHAFT("Mineshaft", "Mineshaft", "Glacite Mineshaft"),
    JERRYS_WORKSHOP("Jerry's Workshop"),
    CRIMSON_ISLE("Crimson Isle"),
    KUUDRAS_HOLLOW("Kuudra's Hollow"),
    DUNGEON_HUB("Dungeon Hub"),
    CATACOMBS("Catacombs", "The Catacombs"),
    THE_RIFT("The Rift"),
    GALATEA("Galatea"),
    DARK_AUCTION("Dark Auction"),
    BACKWATER_BAYOU("Backwater Bayou");

    /** Zonas que se reconocen por el marcador lateral (tienen prioridad sobre el Tab). */
    private static final Zone[] FROM_SIDEBAR = {CATACOMBS, KUUDRAS_HOLLOW, DUNGEON_HUB};

    public final String label;
    private final String[] keys;

    Zone(String label, String... aliases) {
        this.label = label;
        String[] source = aliases.length == 0 ? new String[]{label} : aliases;
        this.keys = new String[source.length];
        for (int i = 0; i < source.length; i++) {
            keys[i] = norm(source[i]);
        }
    }

    /** Minúsculas, solo letras y números, sin el "the" del principio: "⏣ The Park" -> "park". */
    static String norm(String text) {
        StringBuilder sb = new StringBuilder();
        for (char c : Location.clean(text).toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(c)) sb.append(c);
        }
        String s = sb.toString();
        return s.startsWith("the") && s.length() > 3 ? s.substring(3) : s;
    }

    /** Zona que corresponde al valor de "Area:" del Tab, o null si no es ninguna conocida. */
    static Zone fromTabArea(String area) {
        String n = norm(area);
        if (n.isEmpty()) return null;
        for (Zone z : values()) {
            for (String key : z.keys) {
                if (n.equals(key)) return z;
            }
        }
        return null;
    }

    /** Zona que corresponde a las líneas del marcador lateral, o null. */
    static Zone fromSidebar(List<String> lines) {
        for (Zone z : FROM_SIDEBAR) {
            for (String line : lines) {
                String n = norm(line);
                for (String key : z.keys) {
                    if (n.contains(key)) return z;
                }
            }
        }
        return null;
    }

    /** ¿Existe una zona con este nombre (el de name(), tal como se guarda en la config)? */
    static boolean exists(String name) {
        for (Zone z : values()) {
            if (z.name().equals(name)) return true;
        }
        return false;
    }
}
