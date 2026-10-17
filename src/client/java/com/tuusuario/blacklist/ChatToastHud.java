package com.tuusuario.blacklist;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Aviso de mensaje nuevo del chat del mod, con aspecto de notificacion de Discord / Steam:
 * tarjeta oscura con esquinas suavizadas, barra de color a la izquierda, avatar con la inicial del jugador,
 * nombre en negrita, sala y el texto del mensaje.
 *
 * Posicion, tamano y activado se controlan con BlacklistConfig (chatToastX / chatToastY / chatToastScale /
 * chatToastEnabled), la pestana QoL > Chat y el editor de posiciones.
 */
public final class ChatToastHud {

    private static final int MAX_TOASTS = 3;
    private static final long TTL_MS = 9_000;
    private static final long FADE_IN_MS = 180;
    private static final long FADE_OUT_MS = 600;

    private static final int WIDTH = 190;
    private static final int PAD = 6;
    private static final int AVATAR = 22;
    private static final int GAP = 4;
    private static final int MAX_LINES = 3;

    private static final int CARD = 0x313338;      // gris Discord
    private static final int ACCENT_GLOBAL = 0x5865F2; // blurple
    private static final int ACCENT_PARTY = 0x3BA55D;  // verde
    private static final int TEXT = 0xDBDEE1;
    private static final int MUTED = 0x949BA4;

    private static final int[] AVATAR_COLORS = {
            0x5865F2, 0x3BA55D, 0xED4245, 0xFAA61A, 0xEB459E, 0x1ABC9C, 0x9B59B6, 0xE67E22 };

    private record Toast(String room, String uid, String avatar, String name, String text, String className, long time) {
    }

    private static final List<Toast> TOASTS = new CopyOnWriteArrayList<>();

    private ChatToastHud() {
    }

    /** Se llama desde el hilo del chat; la lista es segura entre hilos. */
    public static void push(String room, String uid, String avatar, String name, String text, String className) {
        TOASTS.add(new Toast(room, uid, avatar, name, text, className, System.currentTimeMillis()));
        while (TOASTS.size() > MAX_TOASTS) TOASTS.remove(0);
    }

    public static void clear() {
        TOASTS.clear();
    }

    // ------------------------------------------------------------ dibujo

    private static int argb(int rgb, float alpha) {
        int a = Math.max(0, Math.min(255, (int) (alpha * 255)));
        return (a << 24) | (rgb & 0xFFFFFF);
    }

    /** Rectangulo con las 4 esquinas recortadas (se ve redondeado a este tamano). */
    private static void roundRect(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
        g.fill(x + 1, y, x + w - 1, y + h, color);
        g.fill(x, y + 1, x + w, y + h - 1, color);
    }

    private static int avatarColor(String name, String className) {
        // Si tiene clase, usar el color de la clase
        if (className != null && !className.isEmpty()) {
            return ClassColor.getClassColor(className);
        }
        // Si no tiene clase, usar el color basado en el hash del nombre
        return AVATAR_COLORS[Math.floorMod(name.toLowerCase(Locale.ROOT).hashCode(), AVATAR_COLORS.length)];
    }

    private static int textWidth() {
        return WIDTH - PAD * 2 - AVATAR - GAP;
    }

    private static List<FormattedCharSequence> wrap(Font font, String text) {
        List<FormattedCharSequence> lines = font.split(Component.literal(text), textWidth());
        return lines.size() > MAX_LINES ? lines.subList(0, MAX_LINES) : lines;
    }

    private static int cardHeight(int lineCount) {
        return Math.max(AVATAR + PAD * 2, PAD * 2 + 11 + lineCount * 9);
    }

    /** Dibuja una tarjeta con la esquina superior izquierda en (0, 0) y devuelve su alto. */
    private static int drawCard(GuiGraphicsExtractor g, Font font, String room, String uid, String avatar,
                                String name, String text, String className, float alpha, String when) {
        List<FormattedCharSequence> lines = wrap(font, text);
        int h = cardHeight(lines.size());
        boolean party = "Party".equals(room);

        roundRect(g, 0, 0, WIDTH, h, argb(CARD, 0.96f * alpha));
        g.fill(0, 2, 3, h - 2, argb(party ? ACCENT_PARTY : ACCENT_GLOBAL, alpha)); // barra de color

        // Avatar: la foto de perfil del jugador si tiene; si no, cuadrado con la inicial
        int ax = PAD + 2;
        int ay = PAD;
        roundRect(g, ax, ay, AVATAR, AVATAR, argb(avatarColor(name, className), alpha));
        if (!AvatarManager.draw(g, uid, avatar, ax, ay, AVATAR, alpha)) {
            String initial = name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.ROOT);
            g.text(font, Component.literal(initial).withStyle(ChatFormatting.BOLD),
                    ax + (AVATAR - font.width(initial) - 1) / 2, ay + (AVATAR - 8) / 2, argb(0xFFFFFF, alpha), false);
        }

        // Nombre + sala + hora
        int tx = ax + AVATAR + GAP;
        int ty = PAD - 1;
        Component nameC = Component.literal(name).withStyle(ChatFormatting.BOLD);
        g.text(font, nameC, tx, ty, argb(0xFFFFFF, alpha), false);
        int after = tx + font.width(nameC) + 5;
        String meta = room + (when.isEmpty() ? "" : " · " + when);
        if (after + font.width(meta) <= WIDTH - PAD) {
            g.text(font, meta, after, ty, argb(MUTED, alpha), false);
        }

        // Mensaje
        int ly = ty + 11;
        for (FormattedCharSequence line : lines) {
            g.text(font, line, tx, ly, argb(TEXT, alpha), false);
            ly += 9;
        }
        return h;
    }

    public static void render(GuiGraphicsExtractor g, BlacklistConfig config) {
        if (!config.chatToastEnabled) return;

        long now = System.currentTimeMillis();
        TOASTS.removeIf(t -> now - t.time() > TTL_MS);
        if (TOASTS.isEmpty()) return;

        Font font = Minecraft.getInstance().font;
        g.pose().pushMatrix();
        g.pose().translate((float) config.chatToastX, (float) config.chatToastY);
        g.pose().scale(config.chatToastScale, config.chatToastScale);

        int y = 0;
        for (Toast t : TOASTS) {
            long age = now - t.time();
            float alpha = 1f;
            if (age < FADE_IN_MS) alpha = age / (float) FADE_IN_MS;
            else if (age > TTL_MS - FADE_OUT_MS) alpha = (TTL_MS - age) / (float) FADE_OUT_MS;
            alpha = Math.max(0.02f, Math.min(1f, alpha));

            // Entrada: se desliza unos pixeles desde la izquierda
            float slide = age < FADE_IN_MS ? -(1f - alpha) * 12f : 0f;
            g.pose().pushMatrix();
            g.pose().translate(slide, (float) y);
            int h = drawCard(g, font, t.room(), t.uid(), t.avatar(), t.name(), t.text(), t.className(), alpha,
                    age < 3000 ? "ahora" : "");
            g.pose().popMatrix();
            y += h + 4;
        }
        g.pose().popMatrix();
    }

    // ---- Vista previa para el editor de posiciones (BlacklistEditScreen) ----

    private static final String SAMPLE_NAME = "GatoChan";
    private static final String SAMPLE_TEXT = "hay que matarse, vamos a una run de F7?";

    public static int previewWidth(Font font) {
        return WIDTH;
    }

    public static int previewHeight() {
        Font font = Minecraft.getInstance().font;
        return cardHeight(wrap(font, SAMPLE_TEXT).size());
    }

    public static void renderPreview(GuiGraphicsExtractor g, int px, int py, float scale) {
        Font font = Minecraft.getInstance().font;
        g.pose().pushMatrix();
        g.pose().translate((float) px, (float) py);
        g.pose().scale(scale, scale);
        drawCard(g, font, "Global", "", "", SAMPLE_NAME, SAMPLE_TEXT, null, 1f, "ahora");
        g.pose().popMatrix();
    }
}
