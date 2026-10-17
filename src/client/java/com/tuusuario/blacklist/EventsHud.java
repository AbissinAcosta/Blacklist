package com.tuusuario.blacklist; // <- ajusta al paquete de tu proyecto

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Muestra en pantalla cuánto falta para los eventos fijos del calendario de SkyBlock.
 *
 * Cálculo: 1 día de SkyBlock = 20 min reales, 1 año = 12 meses x 31 días = 372 días (124 h reales).
 * La época de SkyBlock (1 de Early Spring, año 1) es el tiempo unix 1560275700.
 *
 * Muestra el evento activo (o los activos, si coinciden varios) y, si no hay ninguno, el que
 * esté más próximo en el tiempo.
 *
 * Para agregar otro evento, añade una línea en EVENTS (si ocurre varias veces al año, una por fecha):
 *   new Event("Nombre", mes (0 = Early Spring ... 11 = Late Winter), día de inicio (1-31), días de duración, icono)
 *
 * Icono: una textura de 16x16 que se dibuja junto al nombre del evento. Por defecto se usan texturas
 * del propio Minecraft. Para usar una imagen tuya, copia un PNG (de preferencia 16x16) a
 *   src/main/resources/assets/shitterlist/textures/gui/spooky.png
 * y cambia el icono por: Identifier.fromNamespaceAndPath("shitterlist", "textures/gui/spooky.png")
 *
 * Posición, tamaño y activado/desactivado se controlan desde BlacklistConfig
 * (eventsX / eventsY / eventsScale / eventsEnabled), la pestaña QoL y el editor de posiciones.
 */
public final class EventsHud {

    private static final long EPOCH_SECONDS = 1_560_275_700L;
    private static final long SB_DAY_SECONDS = 20 * 60;          // 1 día de SkyBlock en segundos reales
    private static final long YEAR_SECONDS = 372 * SB_DAY_SECONDS; // 1 año de SkyBlock en segundos reales

    private static final int TITLE_COLOR = 0xFFFFAA00;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int ACTIVE_COLOR = 0xFF55FF55;

    private static final int ICON_SIZE = 16;
    private static final int ROW_HEIGHT = 16;  // alto de cada fila (el icono)
    private static final int TEXT_OFFSET = ICON_SIZE + 2; // el texto va a la derecha del icono

    private record Event(String name, int month, int day, int days, Identifier icon) {
        long startOffset() {
            return ((long) month * 31 + (day - 1)) * SB_DAY_SECONDS;
        }

        long duration() {
            return days * SB_DAY_SECONDS;
        }
    }

    private record Line(String text, int color, Identifier icon) {
    }

    // Meses: 0 Early Spring, 1 Spring, 2 Late Spring, 3 Early Summer, 4 Summer, 5 Late Summer,
    //        6 Early Autumn, 7 Autumn, 8 Late Autumn, 9 Early Winter, 10 Winter, 11 Late Winter
    private static final Event[] EVENTS = {
            new Event("New Year Celebration", 11, 29, 3,
                    Identifier.withDefaultNamespace("textures/item/cake.png")),
            new Event("Season of Jerry", 11, 1, 3,
                    Identifier.withDefaultNamespace("textures/item/snowball.png")),
            new Event("Traveling Zoo", 3, 1, 3,   // Early Summer 1-3
                    Identifier.withDefaultNamespace("textures/item/lead.png")),
            new Event("Traveling Zoo", 9, 1, 3,   // Early Winter 1-3
                    Identifier.withDefaultNamespace("textures/item/lead.png")),
            new Event("Spooky Festival", 7, 29, 3,
                    Identifier.withDefaultNamespace("textures/block/jack_o_lantern.png"))
    };

    /** Estado de un evento ahora mismo: activo (seconds = lo que queda) o pendiente (seconds = lo que falta). */
    private record Status(Event event, boolean active, long seconds) {
    }

    private EventsHud() {
    }

    private static List<Line> lines() {
        double nowSeconds = System.currentTimeMillis() / 1000.0;
        long yearPos = (long) ((nowSeconds - EPOCH_SECONDS) % YEAR_SECONDS);
        if (yearPos < 0) yearPos += YEAR_SECONDS;

        List<Status> active = new ArrayList<>();
        Status nearest = null;

        for (Event e : EVENTS) {
            long start = e.startOffset();
            long end = start + e.duration();

            if (yearPos >= start && yearPos < end) {
                active.add(new Status(e, true, end - yearPos));
            } else {
                long wait = start - yearPos;
                if (wait < 0) wait += YEAR_SECONDS;
                if (nearest == null || wait < nearest.seconds()) {
                    nearest = new Status(e, false, wait);
                }
            }
        }

        // Si hay eventos activos se muestran esos (el que termina antes primero); si no, el más cercano
        List<Status> shown = new ArrayList<>(active);
        shown.sort(Comparator.comparingLong(Status::seconds));
        if (shown.isEmpty() && nearest != null) shown.add(nearest);

        List<Line> result = new ArrayList<>();
        for (Status st : shown) {
            Event e = st.event();
            if (st.active()) {
                result.add(new Line(e.name() + ": ¡ACTIVO! quedan " + format(st.seconds()),
                        ACTIVE_COLOR, e.icon()));
            } else {
                result.add(new Line(e.name() + ": en " + format(st.seconds()), TEXT_COLOR, e.icon()));
            }
        }
        return result;
    }

    // ---- Vista previa para el editor de posiciones (BlacklistEditScreen) ----
    public static int previewWidth(Font font) {
        int w = font.width("Eventos");
        for (Line l : lines()) {
            w = Math.max(w, TEXT_OFFSET + font.width(l.text()));
        }
        return w;
    }

    public static int previewHeight() {
        return 11 + lines().size() * ROW_HEIGHT;
    }

    public static void renderPreview(GuiGraphicsExtractor g, int px, int py, float scale) {
        draw(g, px, py, scale);
    }

    // ---- Dibujado normal en el juego ----
    public static void render(GuiGraphicsExtractor g, BlacklistConfig config) {
        if (!config.eventsEnabled) return;
        draw(g, config.eventsX, config.eventsY, config.eventsScale);
    }

    private static void draw(GuiGraphicsExtractor g, int px, int py, float scale) {
        Font font = Minecraft.getInstance().font;

        g.pose().pushMatrix();
        g.pose().translate((float) px, (float) py);
        g.pose().scale(scale, scale);

        int y = 0;
        g.text(font, "Eventos", 0, y, TITLE_COLOR, true);
        y += 11;

        for (Line line : lines()) {
            // icono (16x16) a la izquierda y el texto centrado verticalmente a su lado
            g.blit(RenderPipelines.GUI_TEXTURED, line.icon(), 0, y, 0, 0,
                    ICON_SIZE, ICON_SIZE, ICON_SIZE, ICON_SIZE);
            g.text(font, line.text(), TEXT_OFFSET, y + (ICON_SIZE - 8) / 2, line.color(), true);
            y += ROW_HEIGHT;
        }

        g.pose().popMatrix();
    }

    private static String format(long seconds) {
        long d = seconds / 86_400;
        long h = (seconds % 86_400) / 3_600;
        long m = (seconds % 3_600) / 60;
        long s = seconds % 60;
        if (d > 0) return d + "d " + h + "h " + m + "m";
        if (h > 0) return h + "h " + m + "m";
        return m + "m " + s + "s";
    }
}
