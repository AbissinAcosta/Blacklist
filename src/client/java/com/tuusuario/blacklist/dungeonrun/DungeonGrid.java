package com.tuusuario.blacklist.dungeonrun;

/**
 * Convierte coordenadas del mundo a celdas de la grilla 6x6 de la dungeon.
 *
 * SUPUESTO A VERIFICAR EN EL JUEGO: la esquina noroeste de la grilla esta en (-200, -200)
 * y cada celda (sala + puerta) ocupa 32 bloques. Si al probar las celdas salen corridas,
 * el unico numero a tocar es ORIGIN.
 */
public final class DungeonGrid {

    public static final int CELLS = 6;
    public static final int ORIGIN = -200;
    public static final int STEP = 32;

    private DungeonGrid() {}

    public static int cellOf(double world) {
        return (int) Math.floor((world - ORIGIN) / STEP);
    }

    /** Posicion del mundo en celdas fraccionarias (2.5 = mitad de la tercera celda). */
    public static double frac(double world) {
        return (world - ORIGIN) / (double) STEP;
    }

    /** Clave "x,z" de la celda, o null si la posicion queda fuera de la grilla. */
    public static String key(double worldX, double worldZ) {
        int x = cellOf(worldX);
        int z = cellOf(worldZ);
        if (x < 0 || x >= CELLS || z < 0 || z >= CELLS) return null;
        return x + "," + z;
    }

    public static int[] parse(String key) {
        String[] p = key.split(",");
        return new int[] { Integer.parseInt(p[0]), Integer.parseInt(p[1]) };
    }
}
