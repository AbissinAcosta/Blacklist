package com.tuusuario.blacklist;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Asigna colores a los jugadores según su clase de dungeon.
 *
 * Berserker: Rojo
 * Mage: Púrpura/Magenta
 * Archer: Verde
 * Tank: Turquesa/Cyan
 * Healer: Blanco
 */
public final class ClassColor {

    private static final Pattern CLASS_PATTERN = Pattern.compile("\\((Archer|Berserk|Healer|Mage|Tank)\\b", Pattern.CASE_INSENSITIVE);

    // Colores RGB (sin alpha) para cada clase
    public static final int BERSERKER = 0xFF5555;   // Rojo
    public static final int MAGE = 0xAA55FF;        // Púrpura/Magenta
    public static final int ARCHER = 0x55FF55;      // Verde
    public static final int TANK = 0x55FFFF;        // Turquesa/Cyan
    public static final int HEALER = 0xFFFFFF;      // Blanco
    public static final int UNKNOWN = 0xAAAAAA;     // Gris (clase desconocida)

    private ClassColor() {}

    /**
     * Extrae la clase del jugador a partir de una línea del tab.
     * Ejemplo: "[123] Nombre (Mage 45)" -> "Mage"
     */
    public static String extractClass(String tabLine) {
        if (tabLine == null) return null;
        String line = tabLine.replaceAll("\u00a7[0-9a-fk-orA-FK-OR]", "").strip();
        Matcher m = CLASS_PATTERN.matcher(line);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    /**
     * Devuelve el color RGB correspondiente a una clase.
     * @param className el nombre de la clase (ej: "Mage", "Archer", etc.)
     * @return el color RGB de la clase, o UNKNOWN si no se reconoce
     */
    public static int getClassColor(String className) {
        if (className == null) return UNKNOWN;
        return switch (className.toLowerCase()) {
            case "berserk", "berserker" -> BERSERKER;
            case "mage" -> MAGE;
            case "archer" -> ARCHER;
            case "tank" -> TANK;
            case "healer" -> HEALER;
            default -> UNKNOWN;
        };
    }

    /**
     * Devuelve el color ARGB completo (con alpha) para un jugador dado su nombre de clase.
     * @param className el nombre de la clase
     * @return el color ARGB (0xFFRRGGBB)
     */
    public static int getClassColorArgb(String className) {
        return 0xFF000000 | getClassColor(className);
    }
}
