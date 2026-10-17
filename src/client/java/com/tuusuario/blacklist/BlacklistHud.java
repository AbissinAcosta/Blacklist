package com.tuusuario.blacklist; // <- ajusta al paquete de tu proyecto

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Overlay con las alertas de jugadores de la blacklist que acaban de entrar al party.
 * Aparece en la posición hudX / hudY de la config y cada alerta desaparece sola a los 30 s.
 */
public final class BlacklistHud {

    private static final long DURATION_MS = 30_000;
    private static final int MAX_ALERTS = 8;

    private static final int TITLE_COLOR = 0xFFFF5555;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int KICK_COLOR = 0xFFFFAA00;
    private static final int INFO_COLOR = 0xFF55FF55;
    private static final int CHAT_COLOR = 0xFF55FFFF;
    private static final int PARTY_COLOR = 0xFF5599FF;
    private static final long CHAT_DURATION_MS = 12_000;

    // Cada alerta es una línea de texto ya armada, con su color
    private record Alert(String text, int color, long time, long ttl) {
    }

    private static final List<Alert> ALERTS = new CopyOnWriteArrayList<>();

    private BlacklistHud() {
    }

    /** Se llama desde el hilo del chat; la lista es segura entre hilos. */
    public static void addAlert(String name, String reason, boolean kick) {
        String line = name + " - " + reason + (kick ? " (kick)" : "");
        add(new Alert(line, kick ? KICK_COLOR : TEXT_COLOR, System.currentTimeMillis(), DURATION_MS));
    }

    /** Línea informativa en verde (por ejemplo los shards de un jugador). */
    public static void addInfo(String text) {
        add(new Alert(text, INFO_COLOR, System.currentTimeMillis(), DURATION_MS));
    }

    /** Línea de aviso en rojo (por ejemplo "Steve es medio burro"). */
    public static void addWarning(String text) {
        add(new Alert(text, TITLE_COLOR, System.currentTimeMillis(), DURATION_MS));
    }

    /** Mensaje del chat del mod (dura menos que las alertas normales). */
    public static void addChat(String text, boolean party) {
        add(new Alert(text, party ? PARTY_COLOR : CHAT_COLOR, System.currentTimeMillis(), CHAT_DURATION_MS));
    }

    private static void add(Alert alert) {
        ALERTS.add(alert);
        while (ALERTS.size() > MAX_ALERTS) {
            ALERTS.remove(0);
        }
    }

    public static void clearAlerts() {
        ALERTS.clear();
    }

    // ---- Vista previa para el editor de posiciones (BlacklistEditScreen) ----
    private static final String[] SAMPLE = {"Steve - Scam en el bazar (kick)", "Alex - Estafa"};

    public static int previewWidth(Font font) {
        int w = font.width("Blacklist");
        for (String s : SAMPLE) {
            w = Math.max(w, font.width(s));
        }
        return w;
    }

    public static int previewHeight() {
        return 11 + SAMPLE.length * 10;
    }

    public static void renderPreview(GuiGraphicsExtractor g, int px, int py, float scale) {
        Font font = Minecraft.getInstance().font;

        // Matrix3x2fStack: translate + scale, igual que en render()
        g.pose().pushMatrix();
        g.pose().translate((float) px, (float) py);
        g.pose().scale(scale, scale);

        int y = 0;
        g.text(font, "Blacklist", 0, y, TITLE_COLOR, true);
        y += 11;
        g.text(font, SAMPLE[0], 0, y, KICK_COLOR, true);
        y += 10;
        g.text(font, SAMPLE[1], 0, y, TEXT_COLOR, true);

        g.pose().popMatrix();
    }

    public static void render(GuiGraphicsExtractor g, DeltaTracker delta,
                              BlacklistConfig config, int listSize, long lastRefresh) {
        if (!config.hudEnabled) return;

        long now = System.currentTimeMillis();
        ALERTS.removeIf(a -> now - a.time() > a.ttl());
        if (ALERTS.isEmpty()) return;

        var font = Minecraft.getInstance().font;
        // Posición y tamaño: se dibuja en coordenadas locales (0, 0) con la escala aplicada
        g.pose().pushMatrix();
        g.pose().translate((float) config.hudX, (float) config.hudY);
        g.pose().scale(config.hudScale, config.hudScale);
        int x = 0;
        int y = 0;

        g.text(font, "Blacklist", x, y, TITLE_COLOR, true);
        y += 11;

        for (Alert a : ALERTS) {
            g.text(font, a.text(), x, y, a.color(), true);
            y += 10;
        }

        g.pose().popMatrix();
    }
}
