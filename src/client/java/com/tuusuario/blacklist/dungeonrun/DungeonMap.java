package com.tuusuario.blacklist.dungeonrun;

import java.util.ArrayList;
import java.util.List;

/**
 * Interpreta el mapa que Hypixel pone en el ultimo slot de la hotbar (un mapa de 128x128 bytes).
 * No depende de Minecraft: recibe el array de colores crudos (MapItemSavedData.colors).
 *
 * Como esta armado el mapa:
 *  - cada sala ocupa un cuadrado de roomPx pixeles (16 en F5-F7) y entre salas hay 4 pixeles de puerta,
 *    asi que una celda mide roomPx + 4 pixeles;
 *  - la sala de entrada es VERDE (30); de su borde superior sale el tamano de sala y el desplazamiento
 *    de la grilla dentro de los 128x128;
 *  - una sala de varias celdas (1x2, L, 2x2...) tiene tambien rellenado de su color el hueco entre celdas;
 *    una puerta normal solo ocupa el centro del hueco, por eso se mira cerca del borde.
 *
 * La celda (c, r) del mapa es la misma celda (c, r) de DungeonGrid (x = columna, z = fila).
 */
public final class DungeonMap {

    public static final int PIXELS = 128;
    public static final int MAX_CELLS = DungeonGrid.CELLS;
    private static final int DOOR_GAP = 4;

    // Colores crudos del mapa de Hypixel (id de color * 4 + brillo)
    static final int C_NONE = 0;
    static final int C_BLOOD = 18;
    static final int C_ENTRANCE = 30;
    static final int C_TRAP = 62;
    static final int C_NORMAL = 63;
    static final int C_PUZZLE = 66;
    static final int C_YELLOW = 74;
    static final int C_FAIRY = 82;
    static final int C_UNKNOWN = 85;

    /** Una sala del mapa: una o mas celdas unidas. */
    public static final class Room {
        public String type;                 // normal, yellow, puzzle, trap, fairy, entrance, blood, unknown
        public String shape;                // 1x1, 1x2, 1x3, 1x4, 2x2, L
        public final List<int[]> cells = new ArrayList<>(); // {x, z}
    }

    /** Puerta entre dos salas distintas: de la celda (x, z) hacia la derecha (dir 0) o hacia abajo (dir 1). */
    public static final class Door {
        public final int x, z, dir;
        /** 0 normal, 1 wither (negra), 2 sangre (roja), 3 entrada (verde), 4 sin explorar. */
        public final int kind;

        Door(int x, int z, int dir, int kind) {
            this.x = x;
            this.z = z;
            this.dir = dir;
            this.kind = kind;
        }
    }

    public final int roomPx;
    public final int startX;
    public final int startY;
    public final int cols;
    public final int rows;
    public final List<Room> rooms = new ArrayList<>();
    public final List<Door> doors = new ArrayList<>();

    private final Room[] roomOf = new Room[MAX_CELLS * MAX_CELLS];

    private DungeonMap(int roomPx, int startX, int startY, int cols, int rows) {
        this.roomPx = roomPx;
        this.startX = startX;
        this.startY = startY;
        this.cols = cols;
        this.rows = rows;
    }

    /** Pixeles que mide una celda del mapa (sala + puerta). */
    public int step() {
        return roomPx + DOOR_GAP;
    }

    /** Sala que ocupa la celda, o null si el mapa no muestra nada ahi (todavia sin descubrir). */
    public Room roomAt(int x, int z) {
        if (x < 0 || z < 0 || x >= MAX_CELLS || z >= MAX_CELLS) return null;
        return roomOf[z * MAX_CELLS + x];
    }

    /** Cantidad de celdas ya descubiertas (distintas de "desconocida"); sirve para elegir el mejor mapa. */
    public int knownCells() {
        int n = 0;
        for (Room r : rooms) {
            if (!"unknown".equals(r.type)) n += r.cells.size();
        }
        return n;
    }

    // -- Parseo ---------------------------------------------------------------------------

    /** Devuelve null si el array no parece el mapa de una dungeon (por ejemplo, no hay entrada verde). */
    public static DungeonMap parse(byte[] colors) {
        if (colors == null || colors.length < PIXELS * PIXELS) return null;

        // 1) La entrada: primera tira horizontal larga de verde (borde superior de la sala de entrada)
        int size = -1;
        int ex = -1;
        int ey = -1;
        outer:
        for (int y = 0; y < PIXELS; y++) {
            int run = 0;
            for (int x = 0; x <= PIXELS; x++) {
                boolean green = x < PIXELS && px(colors, x, y) == C_ENTRANCE;
                if (green) {
                    run++;
                } else {
                    if (run >= 12 && run <= 32) {
                        size = run;
                        ex = x - run;
                        ey = y;
                        break outer;
                    }
                    run = 0;
                }
            }
        }
        if (size < 0) return null;

        int step = size + DOOR_GAP;
        int startX = ex % step;
        int startY = ey % step;
        int cols = Math.min(MAX_CELLS, (PIXELS - startX + DOOR_GAP) / step);
        int rows = Math.min(MAX_CELLS, (PIXELS - startY + DOOR_GAP) / step);

        DungeonMap map = new DungeonMap(size, startX, startY, cols, rows);

        // 2) Color de cada celda (se mira cerca de la esquina: el centro puede tener un check o una cruz)
        int[] color = new int[MAX_CELLS * MAX_CELLS];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                color[r * MAX_CELLS + c] = px(colors, startX + c * step + 2, startY + r * step + 2);
            }
        }

        // 3) Unir celdas contiguas con el hueco rellenado del mismo color
        int[] parent = new int[MAX_CELLS * MAX_CELLS];
        for (int i = 0; i < parent.length; i++) parent[i] = i;

        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int col = color[r * MAX_CELLS + c];
                if (col == C_NONE) continue;
                int left = startX + c * step;
                int top = startY + r * step;
                if (c + 1 < cols && color[r * MAX_CELLS + c + 1] == col
                        && px(colors, left + size + 1, top + 2) == col) {
                    union(parent, r * MAX_CELLS + c, r * MAX_CELLS + c + 1);
                }
                if (r + 1 < rows && color[(r + 1) * MAX_CELLS + c] == col
                        && px(colors, left + 2, top + size + 1) == col) {
                    union(parent, r * MAX_CELLS + c, (r + 1) * MAX_CELLS + c);
                }
            }
        }

        // 3b) Puertas: en el centro del hueco entre dos salas que NO se unieron hay un pixel de color de puerta
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int i = r * MAX_CELLS + c;
                if (color[i] == C_NONE) continue;
                int left = startX + c * step;
                int top = startY + r * step;
                if (c + 1 < cols && color[i + 1] != C_NONE && find(parent, i) != find(parent, i + 1)) {
                    int d = px(colors, left + size + 2, top + size / 2);
                    if (d != C_NONE) map.doors.add(new Door(c, r, 0, doorKind(d)));
                }
                if (r + 1 < rows && color[i + MAX_CELLS] != C_NONE && find(parent, i) != find(parent, i + MAX_CELLS)) {
                    int d = px(colors, left + size / 2, top + size + 2);
                    if (d != C_NONE) map.doors.add(new Door(c, r, 1, doorKind(d)));
                }
            }
        }

        // 4) Armar las salas
        Room[] byRoot = new Room[MAX_CELLS * MAX_CELLS];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int i = r * MAX_CELLS + c;
                int col = color[i];
                if (col == C_NONE) continue;
                int root = find(parent, i);
                Room room = byRoot[root];
                if (room == null) {
                    room = new Room();
                    room.type = typeOf(col);
                    byRoot[root] = room;
                    map.rooms.add(room);
                }
                room.cells.add(new int[] { c, r });
                map.roomOf[i] = room;
            }
        }
        for (Room room : map.rooms) room.shape = shapeOf(room.cells);
        return map;
    }

    private static int px(byte[] colors, int x, int y) {
        if (x < 0 || y < 0 || x >= PIXELS || y >= PIXELS) return C_NONE;
        return colors[y * PIXELS + x] & 0xFF;
    }

    static int doorKind(int color) {
        switch (color) {
            case 119: return 1;
            case C_BLOOD: return 2;
            case C_ENTRANCE: return 3;
            case C_UNKNOWN: return 4;
            default: return 0;
        }
    }

    /** Formato para guardar en la run: {x, z, dir, kind}. */
    public List<int[]> doorList() {
        List<int[]> out = new ArrayList<>();
        for (Door d : doors) out.add(new int[] { d.x, d.z, d.dir, d.kind });
        return out;
    }

    static String typeOf(int color) {
        switch (color) {
            case C_ENTRANCE: return "entrance";
            case C_BLOOD: return "blood";
            case C_NORMAL: return "normal";
            case C_PUZZLE: return "puzzle";
            case C_TRAP: return "trap";
            case C_YELLOW: return "yellow";
            case C_FAIRY: return "fairy";
            default: return "unknown";
        }
    }

    /** Misma nomenclatura que rooms.json: 1x1, 1x2, 1x3, 1x4, 2x2, L. */
    static String shapeOf(List<int[]> cells) {
        int minX = 99, maxX = -1, minZ = 99, maxZ = -1;
        for (int[] c : cells) {
            minX = Math.min(minX, c[0]);
            maxX = Math.max(maxX, c[0]);
            minZ = Math.min(minZ, c[1]);
            maxZ = Math.max(maxZ, c[1]);
        }
        int w = maxX - minX + 1;
        int h = maxZ - minZ + 1;
        int n = cells.size();
        if (n == 1) return "1x1";
        if (n == 4 && w == 2 && h == 2) return "2x2";
        if (w == 1 || h == 1) return "1x" + n;
        return "L";
    }

    private static int find(int[] p, int i) {
        while (p[i] != i) {
            p[i] = p[p[i]];
            i = p[i];
        }
        return i;
    }

    private static void union(int[] p, int a, int b) {
        p[find(p, a)] = find(p, b);
    }
}
