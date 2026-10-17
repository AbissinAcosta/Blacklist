package com.tuusuario.blacklist; // <- ajusta al paquete de tu proyecto

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Profit de cofres de dungeons: UN cofre por run, el que más profit da entre los que has visto
 * (dentro de la dungeon o en Croesus). No importa de qué cofre venga ni si lo abriste.
 *
 * Cada run se identifica con una clave (runKey). Si ves otro cofre de la misma run y da más
 * profit, REEMPLAZA al anterior (no se suma). Profit de un cofre = valor del contenido - costo.
 *
 * Muestra: el profit acumulado con, entre paréntesis, el número de runs en las que se vio un cofre,
 * y los cofres sin abrir que informa el servidor en el Tab (TabInfo), con el profit de los que sí
 * se llegaron a ver. Todo se guarda en config/blacklist-profit.json (se mantiene entre sesiones).
 *
 * La lectura de los cofres la hace ChestTracker (que llama a recordChest / markClaimed) y los
 * precios los pone PriceService.
 *
 * Posición, tamaño y activado/desactivado se controlan desde BlacklistConfig
 * (profitX / profitY / profitScale / profitEnabled), la pestaña QoL y el editor de posiciones.
 */
public final class ProfitHud {

    private static final int TITLE_COLOR = 0xFFFFAA00;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int GREEN = 0xFF55FF55;
    private static final int YELLOW = 0xFFFFFF55;

    private static final int MAX_RUNS = 1000; // si se pasa, se olvidan las más antiguas
    private static final long CROESUS_MS = 72L * 3600 * 1000; // Croesus descarta los cofres a las 72 h

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE =
            FabricLoader.getInstance().getConfigDir().resolve("blacklist-profit.json");

    /** El mejor cofre visto de una run. Campos públicos y sin records para que Gson lo guarde. */
    private static class Run {
        String chest = "";
        long value;
        long cost;
        boolean claimed;   // ya se abrió un cofre de esta run
        long seen;         // cuándo se vio por primera vez (Croesus guarda los cofres 72 h)
        long time;         // cuándo se vio por última vez
        List<String> prints = new ArrayList<>(); // huellas de los cofres vistos (ver ChestParser)

        long net() {
            return value - cost;
        }

        long profit() {
            return Math.max(0, net());
        }
    }

    private static class Data {
        // runKey -> mejor cofre (se mantiene el orden de inserción)
        Map<String, Run> runs = new LinkedHashMap<>();
        // Cofres sin abrir que informa el servidor en el Tab (-1 = todavía no se ha visto)
        int unopened = -1;
    }

    private static Data data = load();

    private record Line(String text, int color) {
    }

    private ProfitHud() {
    }

    // ------------------------------------------------------------------ datos

    /**
     * Busca una run ya guardada que comparta algún cofre (misma huella) con los indicados.
     * Los Wood se ignoran: se repiten demasiado entre runs distintas. Devuelve su clave o null.
     */
    public static synchronized String findRun(Collection<String> prints) {
        for (Map.Entry<String, Run> e : data.runs.entrySet()) {
            for (String p : prints) {
                if (!p.startsWith("Wood#") && e.getValue().prints.contains(p)) return e.getKey();
            }
        }
        return null;
    }

    /**
     * Registra un cofre visto de la run indicada. Si la run ya tenía un cofre, solo se
     * reemplaza cuando este da MÁS profit. Devuelve true si el mejor cofre cambió.
     */
    public static synchronized boolean recordChest(String runKey, String chestName, long value, long cost,
                                                   Collection<String> prints) {
        long now = System.currentTimeMillis();
        Run run = data.runs.get(runKey);
        boolean changed = false;
        boolean dirty = false;

        if (run == null) {
            run = new Run();
            run.chest = chestName;
            run.value = value;
            run.cost = cost;
            run.seen = now;
            data.runs.put(runKey, run);
            changed = true;
            dirty = true;
            trimOldRuns();
        } else if (value - cost > run.net()) {
            run.chest = chestName;
            run.value = value;
            run.cost = cost;
            changed = true;
            dirty = true;
        }

        for (String p : prints) {
            if (!run.prints.contains(p)) {
                run.prints.add(p);
                dirty = true;
            }
        }

        run.time = now;
        if (dirty) save();
        return changed;
    }

    /** Cofres sin abrir según el Tab de Hypixel (lo llama TabInfo). */
    public static synchronized void setUnopened(int value) {
        if (data.unopened != value) {
            data.unopened = value;
            save();
        }
    }

    /** Marca una run como ya abierta (deja de contar como "sin abrir"). */
    public static synchronized void markClaimed(String runKey) {
        Run run = data.runs.get(runKey);
        if (run != null && !run.claimed) {
            run.claimed = true;
            save();
        }
    }

    /** Pone todo a cero (también el archivo guardado). */
    public static synchronized void reset() {
        data = new Data();
        save();
    }

    private static void trimOldRuns() {
        Iterator<String> it = data.runs.keySet().iterator();
        while (data.runs.size() > MAX_RUNS && it.hasNext()) {
            it.next();
            it.remove();
        }
    }

    private static Data load() {
        try {
            if (Files.exists(FILE)) {
                Data loaded = GSON.fromJson(Files.readString(FILE), Data.class);
                if (loaded != null) {
                    if (loaded.runs == null) loaded.runs = new LinkedHashMap<>();
                    for (Run run : loaded.runs.values()) {
                        if (run.prints == null) run.prints = new ArrayList<>();
                        if (run.seen == 0) run.seen = run.time;
                    }
                    return loaded;
                }
            }
        } catch (Exception ignored) {
            // archivo dañado: se empieza de cero
        }
        return new Data();
    }

    private static void save() {
        try {
            Files.writeString(FILE, GSON.toJson(data));
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ texto

    private static synchronized List<Line> lines() {
        int viewed = data.runs.size();
        long total = 0;
        int localUnopened = 0;
        long unopenedProfit = 0;

        long now = System.currentTimeMillis();
        for (Run run : new ArrayList<>(data.runs.values())) {
            total += run.profit();
            // Una run sin abrir deja de contar cuando Croesus ya la habría descartado
            if (!run.claimed && now - run.seen < CROESUS_MS) {
                localUnopened++;
                unopenedProfit += run.profit();
            }
        }

        // Cofres sin abrir: manda el número del servidor (Tab); si aún no se ha visto, el cálculo local
        int unopened = data.unopened >= 0 ? data.unopened : localUnopened;

        List<Line> result = new ArrayList<>();
        // Entre paréntesis: en cuántas runs se llegó a ver un cofre (de ahí sale el profit)
        result.add(new Line("Profit: " + signed(total) + " (" + viewed + ")", total > 0 ? GREEN : TEXT_COLOR));
        result.add(new Line("Sin abrir: " + unopened
                + (unopenedProfit > 0 ? " (visto: " + signed(unopenedProfit) + ")" : ""), YELLOW));
        return result;
    }

    // ---- Vista previa para el editor de posiciones (BlacklistEditScreen) ----
    public static int previewWidth(Font font) {
        int w = font.width("Profit de cofres");
        for (Line l : lines()) {
            w = Math.max(w, font.width(l.text()));
        }
        return w;
    }

    public static int previewHeight() {
        return 11 + lines().size() * 10;
    }

    public static void renderPreview(GuiGraphicsExtractor g, int px, int py, float scale) {
        draw(g, px, py, scale);
    }

    // ---- Dibujado normal en el juego ----
    public static void render(GuiGraphicsExtractor g, BlacklistConfig config) {
        if (!config.profitEnabled) return;
        draw(g, config.profitX, config.profitY, config.profitScale);
    }

    private static void draw(GuiGraphicsExtractor g, int px, int py, float scale) {
        Font font = Minecraft.getInstance().font;

        g.pose().pushMatrix();
        g.pose().translate((float) px, (float) py);
        g.pose().scale(scale, scale);

        int y = 0;
        g.text(font, "Profit de cofres", 0, y, TITLE_COLOR, true);
        y += 11;

        for (Line line : lines()) {
            g.text(font, line.text(), 0, y, line.color(), true);
            y += 10;
        }

        g.pose().popMatrix();
    }

    private static String signed(long value) {
        return (value > 0 ? "+" : "") + formatCoins(value);
    }

    /** 1_250_000 -> "1.25M", 350_000 -> "350k", 900 -> "900". */
    static String formatCoins(long value) {
        long abs = Math.abs(value);
        String sign = value < 0 ? "-" : "";
        if (abs >= 1_000_000_000L) return sign + trim(abs / 1_000_000_000.0) + "B";
        if (abs >= 1_000_000L) return sign + trim(abs / 1_000_000.0) + "M";
        if (abs >= 1_000L) return sign + trim(abs / 1_000.0) + "k";
        return sign + abs;
    }

    private static String trim(double v) {
        String s = String.format(java.util.Locale.ROOT, "%.2f", v);
        s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return s;
    }
}
