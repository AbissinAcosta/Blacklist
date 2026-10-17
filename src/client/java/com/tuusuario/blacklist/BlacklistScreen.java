package com.tuusuario.blacklist; // <- ajusta al paquete de tu proyecto

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import org.lwjgl.glfw.GLFW;

import com.tuusuario.blacklist.dungeonrun.RunData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pantalla principal del mod: barra lateral con pestañas (estilo SkyHanni).
 *  - Inicio:  nombre del mod, descripción, changelog y el botón para editar el GUI
 *  - Lista:   jugadores en la blacklist, con buscador y paginación
 *  - QoL:     timer de eventos y profit de cofres (mostrar, tamaño y posición numérica)
 *  - Ajustes: modo (desactivado / warning / auto-kick) y alertas en pantalla
 *
 * Todo lo clicable son widgets vanilla (Button / EditBox), así que no hace falta
 * tocar la API de ratón, que cambia bastante entre versiones.
 */
public class BlacklistScreen extends Screen {

    private enum Tab {
        HOME("Inicio"), LIST("Lista"), DUNGEONS("Dungeons"), QOL("QoL"), CHAT("Chat"), SETTINGS("Ajustes");

        final String label;

        Tab(String label) {
            this.label = label;
        }
    }

    // Colores (ARGB)
    private static final int PANEL = 0xF0161616;
    private static final int SIDEBAR = 0xF0101010;
    private static final int BORDER = 0xFF3A3A3A;
    private static final int CARD = 0xFF2B2B2B;
    private static final int CARD_ALT = 0xFF242424;
    private static final int ACCENT = 0xFF55AAFF;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GRAY = 0xFFAAAAAA;
    private static final int GREEN = 0xFF55FF55;
    private static final int YELLOW = 0xFFFFAA00;
    private static final int RED = 0xFFFF5555;

    // Medidas
    private static final int PANEL_W = 420;
    private static final int PANEL_H = 300;
    private static final int SIDEBAR_W = 90;
    private static final int ROWS_PER_PAGE = 10;
    private static final int ROW_H = 18;
    private static final int POPUP_W = 240;
    private static final int POPUP_H = 150;

    private final Screen parent;
    private final BlacklistClient mod;

    // Sub-secciones de la pestaña QoL
    private enum QolSection {
        EVENTS("Eventos"), CHAT("Chat"), KEYS("Atajos");

        final String label;

        QolSection(String label) {
            this.label = label;
        }
    }

    private Tab tab = Tab.HOME;
    private QolSection qolSection = QolSection.EVENTS;

    // Sub-secciones de la pestaña Dungeons
    private enum DungeonSection {
        PROFIT("Profit"), SHARDS("Shards"), MAP("Dungeon map");

        final String label;

        DungeonSection(String label) {
            this.label = label;
        }
    }

    private DungeonSection dungeonSection = DungeonSection.PROFIT;
    private static final int SUB_H = 26; // alto de la fila de sub-secciones de Dungeons

    // Atajos de comandos (QoL > Atajos)
    private static final int KEY_ROWS = 4;
    private static final int KEY_ROW_H = 22;
    private static final int ZP_W = 320;        // ventana de zonas
    private static final int ZP_H = 214;
    private static final int ZONE_TOP = 32;     // donde empieza la lista dentro de la ventana
    private static final int ZONE_ROW_H = 12;
    private int keyPage = 0;
    private int capturing = -1;                 // atajo que espera una tecla (-1 = ninguno)
    private int zoneEdit = -1;                  // atajo cuyas zonas se editan (-1 = ninguno)
    private int page = 0;
    private String filter = "";
    private List<Map.Entry<String, String>> filtered = List.of();
    private Map<String, String> lastSeen;

    // Ventana "Agregar jugador"
    private boolean popup = false;
    private String popupName = "";
    private String popupReason = "";
    private String popupError = "";
    private long popupOkAt = 0;
    private Button popupSubmit;
    private Button updateButton; // botón de actualización de la pestaña Inicio

    private int left;
    private int top;

    public BlacklistScreen(Screen parent) {
        super(Component.literal("Blacklist"));
        this.parent = parent;
        this.mod = BlacklistClient.get();
    }

    // ---------------------------------------------------------------- layout

    private int contentX() {
        return left + SIDEBAR_W + 10;
    }

    private int contentY() {
        return top + 30;
    }

    private int contentW() {
        return PANEL_W - SIDEBAR_W - 20;
    }

    /** Fila de botones propios de cada pestaña (misma posición de siempre). */
    private int bottomY() {
        return top + PANEL_H - 50;
    }

    /** Fila inferior común a todas las pestañas (Cerrar a la izquierda; en Inicio también sus botones). */
    private int footerY() {
        return top + PANEL_H - 26;
    }

    // ------------------------------------------------------------------ init

    @Override
    protected void init() {
        left = (width - PANEL_W) / 2;
        top = (height - PANEL_H) / 2;
        updateButton = null;
        refreshFiltered();

        if (zoneEdit >= 0 && zoneKey() == null) zoneEdit = -1;
        if (zoneEdit >= 0) {
            initZonePopup();
            return;
        }

        if (popup) {
            initPopup();
            return;
        }

        // Barra lateral
        int y = top + 34;
        for (Tab t : Tab.values()) {
            Component label = Component.literal(t == tab ? "» " + t.label : t.label)
                    .withStyle(t == tab ? ChatFormatting.AQUA : ChatFormatting.WHITE);
            addRenderableWidget(Button.builder(label, b -> {
                tab = t;
                page = 0;
                runView = null;
                runDeleteArmed = false;
                rebuildWidgets();
            }).bounds(left + 6, y, SIDEBAR_W - 12, 20).build());
            y += 24;
        }

        addRenderableWidget(Button.builder(Component.literal("Cerrar"), b -> onClose())
                .bounds(left + 6, footerY(), SIDEBAR_W - 12, 20).build());

        switch (tab) {
            case HOME -> initHome();
            case LIST -> initList();
            case QOL -> initQol();
            case DUNGEONS -> initDungeons();
            case CHAT -> initChat();
            case SETTINGS -> initSettings();
        }
    }

    private void initHome() {
        int cx = contentX();
        int cw = contentW();
        int fy = footerY();

        addRenderableWidget(Button.builder(Component.literal("Actualizar"),
                b -> mod.refreshAsync()).bounds(cx, fy, 74, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Cambiar modo"), b -> {
            BlacklistConfig cfg = mod.getConfig();
            BlacklistConfig.Mode[] all = BlacklistConfig.Mode.values();
            cfg.mode = all[(cfg.mode.ordinal() + 1) % all.length];
            cfg.save();
        }).bounds(cx + 79, fy, 76, 20).build());

        // Abre el editor de posiciones (lo mismo que /blacklistmod gui): abajo a la derecha
        addRenderableWidget(Button.builder(Component.literal("Editar GUI"),
                b -> minecraft.setScreen(new BlacklistEditScreen(this)))
                .bounds(cx + cw - 80, fy, 80, 20).build());

        // Actualización del mod: arriba a la derecha, junto al nombre y la versión
        updateButton = Button.builder(Component.literal(Updater.shortLabel()), b -> {
            switch (Updater.state()) {
                case AVAILABLE, ERROR -> Updater.downloadAsync();
                case UP_TO_DATE -> Updater.checkAsync(BlacklistClient.BASE_URL);
                default -> {
                }
            }
        }).bounds(cx + cw - 110, contentY() - 2, 110, 16).build();
        addRenderableWidget(updateButton);
    }

    private void initList() {
        int cx = contentX();

        EditBox search = new EditBox(font, cx, contentY(), contentW() - 86, 18, Component.literal("Buscar"));
        search.setHint(Component.literal("Buscar jugador o razón..."));
        search.setValue(filter);
        search.setResponder(text -> {
            filter = text;
            page = 0;
            refreshFiltered();
        });
        addRenderableWidget(search);

        addRenderableWidget(Button.builder(Component.literal("+ Agregar"), b -> openPopup())
                .bounds(cx + contentW() - 80, contentY() - 1, 80, 20).build());

        int by = bottomY();
        addRenderableWidget(Button.builder(Component.literal("<"),
                b -> page = Math.max(0, page - 1)).bounds(cx, by, 20, 20).build());
        addRenderableWidget(Button.builder(Component.literal(">"),
                b -> page = Math.min(maxPage(), page + 1)).bounds(cx + contentW() - 20, by, 20, 20).build());
    }

    // ------------------------------------------------------------------ QoL

    private void initQol() {
        int cx = contentX();
        int cy = contentY();
        int cw = contentW();

        int half = (cw - 12) / 3;
        for (QolSection sec : QolSection.values()) {
            boolean active = qolSection == sec;
            Component label = Component.literal(active ? "» " + sec.label : sec.label)
                    .withStyle(active ? ChatFormatting.AQUA : ChatFormatting.WHITE);
            addRenderableWidget(Button.builder(label, b -> {
                qolSection = sec;
                rebuildWidgets();
            }).bounds(cx + sec.ordinal() * (half + 6), cy, half, 20).build());
        }

        if (qolSection == QolSection.KEYS) {
            initKeys(cx, cy, cw);
            return;
        }
        initElementControls(cx, cy, cw, currentElement());
    }

    private enum Element { EVENTS, PROFIT, CHAT }

    /** Elemento del HUD que se edita segun la pestaña: QoL > Eventos / Chat, o Dungeons > Profit. */
    private Element currentElement() {
        if (tab != Tab.QOL) return Element.PROFIT;
        return qolSection == QolSection.CHAT ? Element.CHAT : Element.EVENTS;
    }

    /** Mostrar/ocultar, tamaño, posición y restablecer de un elemento del HUD (Eventos o Profit). */
    private void initElementControls(int cx, int cy, int cw, Element el) {
        BlacklistConfig cfg = mod.getConfig();

        boolean on = switch (el) {
            case EVENTS -> cfg.eventsEnabled;
            case PROFIT -> cfg.profitEnabled;
            case CHAT -> cfg.chatToastEnabled;
        };
        Component toggle = Component.literal("Mostrar en pantalla: ")
                .append(Component.literal(on ? "ON" : "OFF")
                        .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED));
        addRenderableWidget(Button.builder(toggle, b -> {
            switch (el) {
                case EVENTS -> cfg.eventsEnabled = !cfg.eventsEnabled;
                case PROFIT -> cfg.profitEnabled = !cfg.profitEnabled;
                case CHAT -> cfg.chatToastEnabled = !cfg.chatToastEnabled;
            }
            cfg.save();
            rebuildWidgets();
        }).bounds(cx, cy + 28, cw, 20).build());

        addRenderableWidget(Button.builder(Component.literal("-"), b -> changeScale(-0.1f))
                .bounds(cx + 60, cy + 56, 20, 20).build());
        addRenderableWidget(Button.builder(Component.literal("+"), b -> changeScale(0.1f))
                .bounds(cx + 124, cy + 56, 20, 20).build());

        int px = switch (el) { case EVENTS -> cfg.eventsX; case PROFIT -> cfg.profitX; case CHAT -> cfg.chatToastX; };
        int py = switch (el) { case EVENTS -> cfg.eventsY; case PROFIT -> cfg.profitY; case CHAT -> cfg.chatToastY; };

        EditBox xBox = new EditBox(font, cx + 50, cy + 84, 46, 18, Component.literal("X"));
        xBox.setMaxLength(4);
        xBox.setValue(String.valueOf(px));
        xBox.setResponder(text -> setQolPos(text, true));
        addRenderableWidget(xBox);

        EditBox yBox = new EditBox(font, cx + 116, cy + 84, 46, 18, Component.literal("Y"));
        yBox.setMaxLength(4);
        yBox.setValue(String.valueOf(py));
        yBox.setResponder(text -> setQolPos(text, false));
        addRenderableWidget(yBox);

        addRenderableWidget(Button.builder(Component.literal("Restablecer"), b -> {
            switch (el) {
                case EVENTS -> cfg.resetEvents();
                case PROFIT -> cfg.resetProfit();
                case CHAT -> cfg.resetChatToast();
            }
            cfg.save();
            rebuildWidgets();
        }).bounds(cx, cy + 110, cw, 20).build());

        if (el == Element.CHAT) {
            boolean party = cfg.chatToastParty;
            Component partyLabel = Component.literal("Avisar también los de la party: ")
                    .append(Component.literal(party ? "ON" : "OFF")
                            .withStyle(party ? ChatFormatting.GREEN : ChatFormatting.RED));
            addRenderableWidget(Button.builder(partyLabel, b -> {
                cfg.chatToastParty = !cfg.chatToastParty;
                cfg.save();
                rebuildWidgets();
            }).bounds(cx, cy + 136, cw, 20).build());
        }
    }


    // -------------------------------------------------------------- Chat (tamaño, visibilidad y foto de perfil)

    private static final int CHAT_STEP = 10;

    private void initChat() {
        int cx = contentX();
        int cy = contentY();
        int cw = contentW();
        BlacklistConfig cfg = mod.getConfig();

        boolean always = cfg.chatAlwaysVisible;
        Component toggle = Component.literal("Mostrar siempre (con el chat cerrado): ")
                .append(Component.literal(always ? "ON" : "OFF")
                        .withStyle(always ? ChatFormatting.GREEN : ChatFormatting.RED));
        addRenderableWidget(Button.builder(toggle, b -> {
            cfg.chatAlwaysVisible = !cfg.chatAlwaysVisible;
            cfg.save();
            rebuildWidgets();
        }).bounds(cx, cy, cw, 20).build());

        // Ancho
        addRenderableWidget(Button.builder(Component.literal("-"), b -> {
            cfg.resizeChat(-CHAT_STEP, 0);
            cfg.save();
        }).bounds(cx + 60, cy + 28, 20, 20).build());
        addRenderableWidget(Button.builder(Component.literal("+"), b -> {
            cfg.resizeChat(CHAT_STEP, 0);
            cfg.save();
        }).bounds(cx + 124, cy + 28, 20, 20).build());

        // Alto
        addRenderableWidget(Button.builder(Component.literal("-"), b -> {
            cfg.resizeChat(0, -CHAT_STEP);
            cfg.save();
        }).bounds(cx + 60, cy + 54, 20, 20).build());
        addRenderableWidget(Button.builder(Component.literal("+"), b -> {
            cfg.resizeChat(0, CHAT_STEP);
            cfg.save();
        }).bounds(cx + 124, cy + 54, 20, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Restablecer tamaño"), b -> {
            cfg.resetChatSize();
            cfg.save();
        }).bounds(cx + 160, cy + 41, cw - 160, 20).build());

        // Foto de perfil
        Button pick = Button.builder(Component.literal("Cambiar foto (PNG)"), b -> AvatarManager.pickAndUpload())
                .bounds(cx, cy + 126, 130, 20).build();
        pick.active = !AvatarManager.busy();
        addRenderableWidget(pick);
    }

    private void drawChat(GuiGraphicsExtractor g) {
        int cx = contentX();
        int cy = contentY();
        int cw = contentW();
        BlacklistConfig cfg = mod.getConfig();

        g.text(font, "Ancho", cx, cy + 34, GRAY, false);
        String w = String.valueOf(cfg.modChatWidth);
        g.text(font, w, cx + 80 + (44 - font.width(w)) / 2, cy + 34, WHITE, false);

        g.text(font, "Alto", cx, cy + 60, GRAY, false);
        String h = String.valueOf(cfg.modChatHeight);
        g.text(font, h, cx + 80 + (44 - font.width(h)) / 2, cy + 60, WHITE, false);

        String desc = "Se muestra el chat de la pestaña activa (Global o Party) en el sitio del chat, aunque no lo tengas "
                + "abierto. Si tu pestaña activa es el chat normal de Hypixel, no cambia nada.";
        List<String> lines = wrap(desc, cw);
        for (int i = 0; i < Math.min(4, lines.size()); i++) {
            g.text(font, lines.get(i), cx, cy + 82 + i * 10, GRAY, false);
        }

        g.text(font, "Foto de perfil", cx, cy + 112, WHITE, false);

        // Vista previa de tu foto (si ya se conoce) a la derecha del boton
        int size = 32;
        int px = cx + cw - size - 4;
        int py = cy + 112;
        g.fill(px - 1, py - 1, px + size + 1, py + size + 1, BORDER);
        g.fill(px, py, px + size, py + size, CARD);
        AvatarManager.drawSelf(g, px, py, size);

        String status = AvatarManager.status();
        if (!status.isEmpty()) {
            List<String> sl = wrap(status, cw - size - 12);
            for (int i = 0; i < Math.min(2, sl.size()); i++) {
                g.text(font, sl.get(i), cx, cy + 152 + i * 10, AvatarManager.statusColor(), false);
            }
        }
        List<String> hint = wrap("Solo PNG, máximo 256 KB. Se recorta a cuadrado. Podrás cambiarla una vez por minuto.", cw);
        for (int i = 0; i < Math.min(2, hint.size()); i++) {
            g.text(font, hint.get(i), cx, cy + 176 + i * 10, GRAY, false);
        }
    }

    // -------------------------------------------------------------- Dungeons

    private void initDungeons() {
        int cx = contentX();
        int cy = contentY();
        int cw = contentW();

        int third = (cw - 12) / 3;
        for (DungeonSection sec : DungeonSection.values()) {
            boolean active = dungeonSection == sec;
            Component label = Component.literal(active ? "» " + sec.label : sec.label)
                    .withStyle(active ? ChatFormatting.AQUA : ChatFormatting.WHITE);
            addRenderableWidget(Button.builder(label, b -> {
                dungeonSection = sec;
                runView = null;
                runDeleteArmed = false;
                rebuildWidgets();
            }).bounds(cx + sec.ordinal() * (third + 6), cy, third, 20).build());
        }

        switch (dungeonSection) {
            case PROFIT -> initElementControls(cx, cy, cw, Element.PROFIT);
            case SHARDS -> initShards(cx, cy, cw);
            case MAP -> initRuns();
        }
    }

    private void initShards(int cx, int cy, int cw) {
        BlacklistConfig cfg = mod.getConfig();
        boolean on = cfg.shardAlerts;
        Component toggle = Component.literal("Alertas de shards: ")
                .append(Component.literal(on ? "ON" : "OFF")
                        .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED));
        addRenderableWidget(Button.builder(toggle, b -> {
            cfg.shardAlerts = !cfg.shardAlerts;
            cfg.save();
            rebuildWidgets();
        }).bounds(cx, cy + 28, cw, 20).build());
    }

    private void drawDungeons(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        switch (dungeonSection) {
            case PROFIT -> drawElementControls(g, Element.PROFIT);
            case SHARDS -> {
                int cx = contentX();
                int cy = contentY();
                List<String> lines = wrap("Al entrar un jugador a la dungeon se revisan sus shards y se avisa en el chat "
                        + "(también cuenta el murkbat para el bonus de la run).", contentW());
                for (int i = 0; i < Math.min(4, lines.size()); i++) {
                    g.text(font, lines.get(i), cx, cy + 58 + i * 10, GRAY, false);
                }
            }
            case MAP -> drawRuns(g, mouseX, mouseY);
        }
    }

    private void changeScale(float delta) {
        BlacklistConfig cfg = mod.getConfig();
        switch (currentElement()) {
            case EVENTS -> cfg.scaleEvents(delta);
            case PROFIT -> cfg.scaleProfit(delta);
            case CHAT -> cfg.scaleChatToast(delta);
        }
        cfg.save();
    }

    private void setQolPos(String text, boolean isX) {
        // Solo números de hasta 4 dígitos; cualquier otra cosa se ignora
        if (!text.matches("\\d{1,4}")) return;
        int value = Math.min(4000, Integer.parseInt(text));
        BlacklistConfig cfg = mod.getConfig();
        switch (currentElement()) {
            case EVENTS -> { if (isX) cfg.eventsX = value; else cfg.eventsY = value; }
            case PROFIT -> { if (isX) cfg.profitX = value; else cfg.profitY = value; }
            case CHAT -> { if (isX) cfg.chatToastX = value; else cfg.chatToastY = value; }
        }
        cfg.save();
    }

    private void drawQol(GuiGraphicsExtractor g) {
        if (qolSection == QolSection.KEYS) {
            drawKeys(g);
            return;
        }
        drawElementControls(g, currentElement());
    }

    private void drawElementControls(GuiGraphicsExtractor g, Element el) {
        int cx = contentX();
        int cy = contentY();
        int cw = contentW();
        BlacklistConfig cfg = mod.getConfig();

        float scale = switch (el) {
            case EVENTS -> cfg.eventsScale;
            case PROFIT -> cfg.profitScale;
            case CHAT -> cfg.chatToastScale;
        };
        String value = scale + "x";
        g.text(font, "Tamaño", cx, cy + 62, GRAY, false);
        g.text(font, value, cx + 80 + (44 - font.width(value)) / 2, cy + 62, WHITE, false);

        g.text(font, "Pos. X", cx, cy + 90, GRAY, false);
        g.text(font, "Y", cx + 102, cy + 90, GRAY, false);

        String desc = switch (el) {
            case EVENTS -> "Muestra cuánto falta para el próximo evento del calendario de SkyBlock (solo en Hypixel).";
            case PROFIT -> "Cuenta 1 cofre por run (el de más profit), el total y los cofres sin abrir. "
                    + "Solo se muestra en el Dungeon Hub y dentro de las dungeons.";
            case CHAT -> "Tarjeta tipo Discord que aparece cuando llega un mensaje al chat global y tienes el chat cerrado. "
                    + "Mueve y cambia el tamaño con Editar GUI (Inicio).";
        };
        int descY = cy + (el == Element.CHAT ? 164 : 138);
        List<String> lines = wrap(desc, cw);
        for (int i = 0; i < Math.min(4, lines.size()); i++) {
            g.text(font, lines.get(i), cx, descY + i * 10, GRAY, false);
        }
    }

    private void initSettings() {
        int cx = contentX();
        int cy = contentY();
        int cw = contentW();
        BlacklistConfig cfg = mod.getConfig();

        // Un botón por modo; el activo se marca en verde
        for (BlacklistConfig.Mode m : BlacklistConfig.Mode.values()) {
            boolean active = cfg.mode == m;
            Component label = Component.literal((active ? "» " : "") + modeName(m))
                    .withStyle(active ? ChatFormatting.GREEN : ChatFormatting.WHITE);
            addRenderableWidget(Button.builder(label, b -> {
                cfg.mode = m;
                cfg.save();
                rebuildWidgets();
            }).bounds(cx, cy + 14 + m.ordinal() * 24, cw, 20).build());
        }

        // Alertas en pantalla (el HUD de alertas que ya tienes)
        boolean on = cfg.hudEnabled;
        Component hudLabel = Component.literal("Alertas en pantalla: ")
                .append(Component.literal(on ? "ON" : "OFF")
                        .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED));
        addRenderableWidget(Button.builder(hudLabel, b -> {
            cfg.hudEnabled = !cfg.hudEnabled;
            cfg.save();
            rebuildWidgets();
        }).bounds(cx, cy + 96, cw, 20).build());
    }

    @Override
    public void onClose() {
        if (capturing >= 0) {
            capturing = -1;
            rebuildWidgets();
            return;
        }
        if (zoneEdit >= 0) {
            zoneEdit = -1;
            rebuildWidgets();
            return;
        }
        // Si la ventana "Agregar jugador" está abierta, Esc la cierra a ella primero
        if (popup) {
            closePopup();
            return;
        }
        // Dentro de una run, Esc vuelve a la lista de runs
        if (runView != null) {
            runView = null;
            runDeleteArmed = false;
            rebuildWidgets();
            return;
        }
        // Vuelve al chat desde el que se abrió
        minecraft.setScreen(parent);
    }

    // ------------------------------------------------------- atajos (QoL)

    private int keyMaxPage() {
        return Math.max(0, (mod.getConfig().commandKeys.size() - 1) / KEY_ROWS);
    }

    private void initKeys(int cx, int cy, int cw) {
        BlacklistConfig cfg = mod.getConfig();
        List<CommandKey> list = cfg.commandKeys;
        keyPage = Math.max(0, Math.min(keyPage, keyMaxPage()));

        int start = keyPage * KEY_ROWS;
        for (int i = 0; i < KEY_ROWS; i++) {
            int idx = start + i;
            if (idx >= list.size()) break;
            CommandKey k = list.get(idx);
            int ry = cy + 28 + i * KEY_ROW_H;

            // Tecla: al pulsarlo espera la siguiente tecla o botón del ratón
            Component keyLabel = capturing == idx
                    ? Component.literal("> Pulsa <").withStyle(ChatFormatting.YELLOW)
                    : Component.literal(k.keyName());
            addRenderableWidget(Button.builder(keyLabel, b -> {
                capturing = idx;
                rebuildWidgets();
            }).bounds(cx, ry, 70, 20).build());

            // Comando
            EditBox cmd = new EditBox(font, cx + 74, ry + 1, 96, 18, Component.literal("Comando"));
            cmd.setMaxLength(100);
            cmd.setHint(Component.literal("/comando"));
            cmd.setValue(k.command);
            cmd.setResponder(text -> {
                k.command = text;
                cfg.save();
            });
            addRenderableWidget(cmd);

            // Zonas
            int n = k.zones.size();
            String zoneText = n == 0 ? "Todas" : n + (n == 1 ? " zona" : " zonas");
            addRenderableWidget(Button.builder(Component.literal(zoneText), b -> {
                capturing = -1;
                zoneEdit = idx;
                rebuildWidgets();
            }).bounds(cx + 174, ry, 44, 20).build());

            // Borrar
            addRenderableWidget(Button.builder(Component.literal("X").withStyle(ChatFormatting.RED), b -> {
                list.remove(idx);
                capturing = -1;
                cfg.save();
                rebuildWidgets();
            }).bounds(cx + 222, ry, 18, 20).build());
        }

        int by = bottomY();
        addRenderableWidget(Button.builder(Component.literal("<"), b -> {
            keyPage = Math.max(0, keyPage - 1);
            capturing = -1;
            rebuildWidgets();
        }).bounds(cx, by, 20, 20).build());

        addRenderableWidget(Button.builder(Component.literal("+ Añadir"), b -> {
            list.add(new CommandKey());
            keyPage = keyMaxPage();
            capturing = -1;
            cfg.save();
            rebuildWidgets();
        }).bounds(cx + 24, by, 84, 20).build());

        boolean on = cfg.commandKeysEnabled;
        Component toggle = Component.literal("Atajos: ")
                .append(Component.literal(on ? "ON" : "OFF")
                        .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED));
        addRenderableWidget(Button.builder(toggle, b -> {
            cfg.commandKeysEnabled = !cfg.commandKeysEnabled;
            cfg.save();
            rebuildWidgets();
        }).bounds(cx + 112, by, 104, 20).build());

        addRenderableWidget(Button.builder(Component.literal(">"), b -> {
            keyPage = Math.min(keyMaxPage(), keyPage + 1);
            capturing = -1;
            rebuildWidgets();
        }).bounds(cx + cw - 20, by, 20, 20).build());
    }

    private void drawKeys(GuiGraphicsExtractor g) {
        int cx = contentX();
        int cy = contentY();
        int cw = contentW();

        if (mod.getConfig().commandKeys.isEmpty()) {
            g.text(font, "No hay atajos todavía.", cx + 4, cy + 36, GRAY, false);
            g.text(font, "Pulsa + Añadir para crear uno.", cx + 4, cy + 48, GRAY, false);
        }

        String msg;
        int color;
        if (capturing >= 0) {
            msg = "Pulsa una tecla o botón del ratón (Esc cancela, Supr la quita).";
            color = YELLOW;
        } else {
            msg = "Sin zonas marcadas = funciona en todas partes. Solo con el juego en pantalla.";
            color = GRAY;
        }

        boolean paged = keyMaxPage() > 0;
        List<String> lines = wrap(msg, cw - (paged ? 56 : 0));
        for (int i = 0; i < Math.min(2, lines.size()); i++) {
            g.text(font, lines.get(i), cx, cy + 121 + i * 10, color, false);
        }
        if (paged) {
            String info = "Pág. " + (keyPage + 1) + "/" + (keyMaxPage() + 1);
            g.text(font, info, cx + cw - font.width(info), cy + 121, GRAY, false);
        }
    }

    /** Asigna la tecla o botón capturado al atajo que estaba esperando. */
    private void bindCaptured(int code, boolean mouse) {
        List<CommandKey> list = mod.getConfig().commandKeys;
        if (capturing >= 0 && capturing < list.size()) {
            CommandKey k = list.get(capturing);
            k.key = code;
            k.mouse = mouse;
            k.wasDown = true; // la tecla sigue pulsada: que no dispare al cerrar la pantalla
            mod.getConfig().save();
        }
        capturing = -1;
        rebuildWidgets();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (capturing >= 0) {
            int code = event.key();
            if (code == GLFW.GLFW_KEY_ESCAPE) {
                capturing = -1;
                rebuildWidgets();
            } else if (code == GLFW.GLFW_KEY_BACKSPACE || code == GLFW.GLFW_KEY_DELETE) {
                bindCaptured(-1, false); // quita la tecla
            } else {
                bindCaptured(code, false);
            }
            return true;
        }
        if (zoneEdit >= 0 && event.key() == GLFW.GLFW_KEY_ESCAPE) {
            zoneEdit = -1;
            rebuildWidgets();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (capturing >= 0) {
            // Clic izquierdo o derecho cancelan; del botón central en adelante (Mouse 3, 4, 5...) se asignan
            if (event.button() >= 2) {
                bindCaptured(event.button(), true);
            } else {
                capturing = -1;
                rebuildWidgets();
            }
            return true;
        }

        if (zoneEdit >= 0) {
            if (super.mouseClicked(event, doubleClick)) return true; // botones de la ventana
            CommandKey k = zoneKey();
            Zone z = event.button() == 0 ? zoneAt(event.x(), event.y()) : null;
            if (k != null && z != null) {
                k.setZone(z, !k.hasZone(z));
                mod.getConfig().save();
            }
            return true;
        }

        return super.mouseClicked(event, doubleClick);
    }

    // ---- ventana de zonas (casillas, se pueden marcar varias) ----

    private int zonePopupX() {
        return left + (PANEL_W - ZP_W) / 2;
    }

    private int zonePopupY() {
        return top + (PANEL_H - ZP_H) / 2;
    }

    private CommandKey zoneKey() {
        List<CommandKey> list = mod.getConfig().commandKeys;
        return zoneEdit >= 0 && zoneEdit < list.size() ? list.get(zoneEdit) : null;
    }

    private void initZonePopup() {
        CommandKey k = zoneKey();
        int px = zonePopupX();
        int by = zonePopupY() + ZP_H - 26;

        addRenderableWidget(Button.builder(Component.literal("Marcar todas"), b -> {
            k.zones.clear();
            for (Zone z : Zone.values()) k.zones.add(z.name());
            mod.getConfig().save();
        }).bounds(px + 10, by, 92, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Ninguna"), b -> {
            k.zones.clear();
            mod.getConfig().save();
        }).bounds(px + 106, by, 70, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Listo"), b -> {
            zoneEdit = -1;
            rebuildWidgets();
        }).bounds(px + ZP_W - 70, by, 60, 20).build());
    }

    /** Casilla bajo el ratón: dos columnas, se llenan de arriba abajo. */
    private Zone zoneAt(double mx, double my) {
        Zone[] zones = Zone.values();
        int rows = (zones.length + 1) / 2;
        int colW = (ZP_W - 20) / 2;
        int ox = zonePopupX() + 10;
        int oy = zonePopupY() + ZONE_TOP;
        if (mx < ox || my < oy) return null;

        int col = (int) ((mx - ox) / colW);
        int row = (int) ((my - oy) / ZONE_ROW_H);
        if (col > 1 || row >= rows) return null;

        int idx = col * rows + row;
        return idx < zones.length ? zones[idx] : null;
    }

    private void drawZonePopup(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        CommandKey k = zoneKey();
        if (k == null) return;

        int px = zonePopupX();
        int py = zonePopupY();

        String title = "Zonas del atajo" + (k.command.isBlank() ? "" : ": /" + k.command.strip());
        g.text(font, font.plainSubstrByWidth(title, ZP_W - 20), px + 10, py + 8, WHITE, true);
        g.text(font, "Sin ninguna marcada = funciona en todas partes", px + 10, py + 19, GRAY, false);

        Zone[] zones = Zone.values();
        int rows = (zones.length + 1) / 2;
        int colW = (ZP_W - 20) / 2;
        int ox = px + 10;
        int oy = py + ZONE_TOP;
        Zone hover = zoneAt(mouseX, mouseY);

        for (int i = 0; i < zones.length; i++) {
            Zone z = zones[i];
            int x = ox + (i / rows) * colW;
            int y = oy + (i % rows) * ZONE_ROW_H;
            boolean on = k.hasZone(z);

            g.fill(x, y + 1, x + 9, y + 10, z == hover ? ACCENT : GRAY);
            g.fill(x + 1, y + 2, x + 8, y + 9, 0xFF1E1E1E);
            if (on) g.fill(x + 2, y + 3, x + 7, y + 8, GREEN);
            g.text(font, z.label, x + 14, y + 2, on ? WHITE : GRAY, false);
        }

        Zone now = Location.current();
        g.text(font, "Zona actual: " + (now == null ? "no reconocida" : now.label),
                px + 10, py + ZP_H - 37, GRAY, false);
    }

    // ------------------------------------------------------- Dungeon run

    private static final int RUN_ROWS = 8;
    private static final int RUN_ROW_H = 20;
    private static final int RUN_CELL = 17; // lado de cada celda del mapa
    private static final int RUN_GAP = 1;

    private List<RunData> runs = List.of();
    private int runPage = 0;
    private RunData runView;                  // run abierta (null = lista de runs)
    private boolean runDeleteArmed = false;   // el primer clic en Borrar pide confirmación
    private Map<String, RunData.RoomEntry> runCellRoom = Map.of(); // "x,z" -> sala
    private double runMaxRoom = 1;
    private static final int STATS_W = 118;
    private static final int PATH_BTN_H = 13;
    private static final int PATH_BTN_PITCH = 14;
    /** Colores de respaldo para runs viejas sin clase guardada. */
    private static final int[] PATH_COLORS_FALLBACK = {
            0xFFFF5555, 0xFF55FF55, 0xFF55FFFF, 0xFFFFFF55, 0xFFFF55FF, 0xFFFFFFFF };

    /** Devuelve el color de un jugador según su clase, o un color de respaldo si no tiene clase. */
    private int getPlayerColor(RunData r, int playerIndex) {
        if (r.playerClasses != null && playerIndex < r.playerClasses.size()) {
            String cls = r.playerClasses.get(playerIndex);
            if (cls != null) {
                return ClassColor.getClassColorArgb(cls);
            }
        }
        return PATH_COLORS_FALLBACK[playerIndex % PATH_COLORS_FALLBACK.length];
    }
    /** Recorrido mostrado: null = ninguno, "*" = todos, o el nombre de un jugador. */
    private String pathSel = null;
    private final Map<String, List<float[]>> pathCache = new HashMap<>();
    private int runMinX, runMinZ, runMaxX, runMaxZ; // recuadro de celdas usadas (el mapa se recorta a esto)

    private int runMaxPage() {
        return Math.max(0, (runs.size() - 1) / RUN_ROWS);
    }

    private void initRuns() {
        int cx = contentX();
        int cy = contentY() + SUB_H;
        int cw = contentW();
        DungeonRunManager dr = mod.dungeonRuns();

        if (runView != null) {
            addRenderableWidget(Button.builder(Component.literal("< Volver"), b -> {
                runView = null;
                runDeleteArmed = false;
                rebuildWidgets();
            }).bounds(cx, bottomY(), 70, 20).build());

            Component del = runDeleteArmed
                    ? Component.literal("¿Seguro? Borrar").withStyle(ChatFormatting.RED)
                    : Component.literal("Borrar run");
            addRenderableWidget(Button.builder(del, b -> {
                if (!runDeleteArmed) {
                    runDeleteArmed = true;
                    rebuildWidgets();
                    return;
                }
                dr.delete(runView.id);
                runView = null;
                runDeleteArmed = false;
                rebuildWidgets();
            }).bounds(cx + cw - 100, bottomY(), 100, 20).build());

            // Selector de recorrido: "Todos" + un boton por jugador (clic otra vez = ocultar)
            List<String> names = pathNames(runView);
            int sx = cx + cw - STATS_W;
            int by0 = pathButtonsTop(names.size());
            addRenderableWidget(Button.builder(pathLabel("Todos", "*".equals(pathSel)), b -> {
                pathSel = "*".equals(pathSel) ? null : "*";
                rebuildWidgets();
            }).bounds(sx + 8, by0, STATS_W - 8, PATH_BTN_H).build());
            for (int i = 0; i < names.size(); i++) {
                String name = names.get(i);
                addRenderableWidget(Button.builder(pathLabel(name, name.equals(pathSel)), b -> {
                    pathSel = name.equals(pathSel) ? null : name;
                    rebuildWidgets();
                }).bounds(sx + 8, by0 + (i + 1) * PATH_BTN_PITCH, STATS_W - 8, PATH_BTN_H).build());
            }
            return;
        }

        runs = dr.list();
        runPage = Math.max(0, Math.min(runPage, runMaxPage()));

        if (dr.hasPending()) {
            addRenderableWidget(Button.builder(Component.literal("Guardar la última run"), b -> {
                dr.save(null);
                rebuildWidgets();
            }).bounds(cx, cy, cw, 20).build());
        }

        int listTop = cy + 24;
        int start = runPage * RUN_ROWS;
        for (int i = 0; i < RUN_ROWS; i++) {
            int idx = start + i;
            if (idx >= runs.size()) break;
            RunData r = runs.get(idx);
            addRenderableWidget(Button.builder(Component.literal("Ver"), b -> openRun(r))
                    .bounds(cx + cw - 40, listTop + i * RUN_ROW_H, 40, 18).build());
        }

        int by = bottomY();
        addRenderableWidget(Button.builder(Component.literal("<"),
                b -> { runPage = Math.max(0, runPage - 1); rebuildWidgets(); })
                .bounds(cx, by, 20, 20).build());
        addRenderableWidget(Button.builder(Component.literal(">"),
                b -> { runPage = Math.min(runMaxPage(), runPage + 1); rebuildWidgets(); })
                .bounds(cx + cw - 20, by, 20, 20).build());
    }

    /** Jugadores a los que se les puede mostrar el recorrido (maximo 5). */
    private static List<String> pathNames(RunData r) {
        List<String> out = new ArrayList<>();
        if (r.players != null) for (String n : r.players) if (out.size() < 5) out.add(n);
        return out;
    }

    private int pathButtonsTop(int playerCount) {
        return bottomY() - 4 - (playerCount + 1) * PATH_BTN_PITCH;
    }

    private static Component pathLabel(String text, boolean selected) {
        return Component.literal(selected ? "» " + text : text)
                .withStyle(selected ? ChatFormatting.AQUA : ChatFormatting.WHITE);
    }

    private void openRun(RunData r) {
        runView = r;
        pathSel = null;
        pathCache.clear();
        if (r.paths != null) {
            for (Map.Entry<String, String> e : r.paths.entrySet()) {
                pathCache.put(e.getKey(), RunData.decodePath(e.getValue()));
            }
        }
        runDeleteArmed = false;
        runCellRoom = new HashMap<>();
        runMaxRoom = 1;
        runMinX = 99; runMinZ = 99; runMaxX = -1; runMaxZ = -1;
        if (r.rooms != null) {
            for (RunData.RoomEntry room : r.rooms) {
                runMaxRoom = Math.max(runMaxRoom, sumSeconds(room.seconds));
                if (room.cells == null) continue;
                for (int[] c : room.cells) {
                    runCellRoom.put(c[0] + "," + c[1], room);
                    runMinX = Math.min(runMinX, c[0]);
                    runMaxX = Math.max(runMaxX, c[0]);
                    runMinZ = Math.min(runMinZ, c[1]);
                    runMaxZ = Math.max(runMaxZ, c[1]);
                }
            }
        }
        if (runMaxX < 0) { runMinX = 0; runMinZ = 0; runMaxX = 5; runMaxZ = 5; }
        rebuildWidgets();
    }

    private static double sumSeconds(Map<String, Double> seconds) {
        double sum = 0;
        if (seconds != null) for (double v : seconds.values()) sum += v;
        return sum;
    }

    /** 83 -> "1:23" */
    private static String fmtTime(double seconds) {
        int s = (int) Math.round(seconds);
        return (s / 60) + ":" + String.format("%02d", s % 60);
    }

    /** Azul (poco tiempo) -> naranja (el máximo de la run). */
    private static int heat(double f) {
        f = Math.max(0, Math.min(1, f));
        int rr = (int) (0x2E + (0xFF - 0x2E) * f);
        int gg = (int) (0x4A + (0xAA - 0x4A) * f);
        int bb = (int) (0x6B + (0x00 - 0x6B) * f);
        return 0xFF000000 | (rr << 16) | (gg << 8) | bb;
    }

    private static String runWhen(RunData r) {
        return r.savedAt == null || r.savedAt.length() < 16 ? "" : r.savedAt.substring(5, 16).replace('T', ' ');
    }

    private void drawRuns(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (runView != null) {
            drawRunDetail(g, mouseX, mouseY);
            return;
        }

        int cx = contentX();
        int cy = contentY() + SUB_H;
        int cw = contentW();
        int listTop = cy + 24;

        if (!mod.dungeonRuns().hasPending()) {
            String head = "Runs guardadas: " + runs.size()
                    + (mod.dungeonRuns().isRunning() ? "  ·  run en curso..." : "");
            g.text(font, head, cx, cy + 6, GRAY, false);
        }

        if (runs.isEmpty()) {
            g.text(font, "No hay runs guardadas todavía.", cx + 4, listTop + 6, GRAY, false);
            g.text(font, "Se guardan con /blacklistmod run save", cx + 4, listTop + 18, GRAY, false);
        }

        int start = runPage * RUN_ROWS;
        for (int i = 0; i < RUN_ROWS; i++) {
            int idx = start + i;
            if (idx >= runs.size()) break;
            RunData r = runs.get(idx);
            int ry = listTop + i * RUN_ROW_H;
            g.fill(cx, ry, cx + cw - 44, ry + RUN_ROW_H - 2, i % 2 == 0 ? CARD : CARD_ALT);

            String to300 = r.secondsTo300 == null ? "" : "  ·  300 en " + fmtTime(r.secondsTo300);
            String line = r.floor + "  ·  " + runWhen(r) + "  ·  " + fmtTime(r.totalSeconds) + to300;
            g.text(font, font.plainSubstrByWidth(line, cw - 54), cx + 5, ry + 5, WHITE, false);
        }

        String info = "Pág. " + (runPage + 1) + "/" + (runMaxPage() + 1);
        g.text(font, info, cx + (cw - font.width(info)) / 2, bottomY() + 6, GRAY, false);
    }

    private void drawRunDetail(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int cx = contentX();
        int cy = contentY() + SUB_H;
        int cw = contentW();
        RunData r = runView;

        g.text(font, font.plainSubstrByWidth(r.floor + "  ·  " + runWhen(r), cw), cx, cy + 2, WHITE, true);

        // Mapa recortado a las celdas usadas y dibujado como el del juego: salas separadas por un hueco
        // (la puerta) y salas de varias celdas unidas sin hueco.
        final int GAP = 4;
        int cols = runMaxX - runMinX + 1;
        int rows = runMaxZ - runMinZ + 1;
        int areaX = cx;
        int areaY = cy + 14;
        int areaW = cw - STATS_W - 8;
        int areaH = bottomY() - 18 - areaY;
        int cell = Math.max(8, Math.min(24, Math.min((areaW - (cols - 1) * GAP) / cols,
                (areaH - (rows - 1) * GAP) / rows)));
        int pitch = cell + GAP;
        int mapW = cols * cell + (cols - 1) * GAP;
        int mapH = rows * cell + (rows - 1) * GAP;
        int mapX = areaX + (areaW - mapW) / 2;   // centrado en su zona
        int mapY = areaY + (areaH - mapH) / 2;
        RunData.RoomEntry hovered = null;

        for (int z = runMinZ; z <= runMaxZ; z++) {
            for (int x = runMinX; x <= runMaxX; x++) {
                int px = mapX + (x - runMinX) * pitch;
                int py = mapY + (z - runMinZ) * pitch;
                RunData.RoomEntry room = runCellRoom.get(x + "," + z);
                if (room == null) continue;   // sin sala: queda el fondo del panel
                double total = sumSeconds(room.seconds);
                int color = roomColor(room.type);
                g.fill(px, py, px + cell, py + cell, color);
                if (inRect(mouseX, mouseY, px, py, cell, cell)) hovered = room;

                // Une las celdas de una misma sala (derecha y abajo): sin hueco
                boolean right = runCellRoom.get((x + 1) + "," + z) == room;
                boolean down = runCellRoom.get(x + "," + (z + 1)) == room;
                if (right) {
                    g.fill(px + cell, py, px + pitch, py + cell, color);
                    if (inRect(mouseX, mouseY, px + cell, py, GAP, cell)) hovered = room;
                }
                if (down) {
                    g.fill(px, py + cell, px + cell, py + pitch, color);
                    if (inRect(mouseX, mouseY, px, py + cell, cell, GAP)) hovered = room;
                }
                if (right && down && runCellRoom.get((x + 1) + "," + (z + 1)) == room) {
                    g.fill(px + cell, py + cell, px + pitch, py + pitch, color);
                }

                // Tiempo de la party en la sala: tira de calor en el borde inferior de la sala
                if (total > 0 && !down) {
                    int hc = heat(total / runMaxRoom);
                    int hx2 = right ? px + pitch : px + cell;
                    g.fill(px, py + cell - 3, hx2, py + cell, hc);
                }
            }
        }

        // Puertas: un rectangulo en el centro del hueco entre dos salas
        if (r.doors != null) {
            for (int[] d : r.doors) {
                if (d.length < 4) continue;
                int dx = d[0] - runMinX;
                int dz = d[1] - runMinZ;
                if (dx < 0 || dz < 0 || dx >= cols || dz >= rows) continue;
                int px = mapX + dx * pitch;
                int py = mapY + dz * pitch;
                int dc = doorColor(d[3]);
                int half = Math.max(2, cell / 5);
                if (d[2] == 0) {
                    int mid = py + cell / 2;
                    g.fill(px + cell, mid - half, px + pitch, mid + half, dc);
                } else {
                    int mid = px + cell / 2;
                    g.fill(mid - half, py + cell, mid + half, py + pitch, dc);
                }
            }
        }

        // Contorno de la sala bajo el raton (siguiendo su forma)
        if (hovered != null && hovered.cells != null) {
            for (int[] c : hovered.cells) {
                int px = mapX + (c[0] - runMinX) * pitch;
                int py = mapY + (c[1] - runMinZ) * pitch;
                boolean edgeL = !hasCell(hovered, c[0] - 1, c[1]);
                boolean edgeR = !hasCell(hovered, c[0] + 1, c[1]);
                boolean edgeU = !hasCell(hovered, c[0], c[1] - 1);
                boolean edgeD = !hasCell(hovered, c[0], c[1] + 1);
                if (edgeU) g.fill(px - 1, py - 1, px + cell + 1, py, WHITE);
                if (edgeD) g.fill(px - 1, py + cell, px + cell + 1, py + cell + 1, WHITE);
                if (edgeL) g.fill(px - 1, py, px, py + cell, WHITE);
                if (edgeR) g.fill(px + cell, py, px + cell + 1, py + cell, WHITE);
            }
        }

        // Recorridos de los jugadores (encima del mapa, debajo del tooltip)
        drawPaths(g, r, mapX, mapY, pitch);

        // Datos de la run (columna derecha)
        int sx = cx + cw - STATS_W;
        int sw = STATS_W;
        int y = areaY;
        g.text(font, "Pasa el mouse por una sala", areaX, bottomY() - 12, GRAY, false);

        y = statLine(g, sx, y, sw, "Tiempo: ", fmtTime(r.totalSeconds), WHITE);
        y = statLine(g, sx, y, sw, "300 de score: ",
                r.secondsTo300 == null ? "no llegó" : fmtTime(r.secondsTo300),
                r.secondsTo300 == null ? YELLOW : GREEN);
        y = statLine(g, sx, y, sw, "Secretos: ",
                r.secretsFound == null ? "?" : String.valueOf(r.secretsFound), WHITE);
        y = statLine(g, sx, y, sw, "Bonus: ", "+" + r.bonusTotal(), r.bonusTotal() > 0 ? GREEN : GRAY);
        if (r.bonus != null) {
            for (RunData.BonusEntry b : r.bonus) {
                String text = "· " + b.source + " +" + b.points;
                g.text(font, font.plainSubstrByWidth(text, sw), sx, y, GRAY, false);
                y += 10;
            }
        }

        // Selector de recorrido: titulo y una muestra del color de cada jugador
        List<String> names = pathNames(r);
        int by0 = pathButtonsTop(names.size());
        boolean hasPaths = r.paths != null && !r.paths.isEmpty();
        g.text(font, hasPaths ? "Recorrido:" : "Sin recorrido guardado", sx, by0 - 11, GRAY, false);
        for (int i = 0; i < names.size(); i++) {
            int playerIdx = r.players.indexOf(names.get(i));
            int col = getPlayerColor(r, playerIdx);
            int sy = by0 + (i + 1) * PATH_BTN_PITCH;
            g.fill(sx, sy + 3, sx + 5, sy + 8, col);
        }

        // Tooltip propio: cuánto tiempo pasó cada persona en la sala (se dibuja al final, encima de todo)
        if (hovered != null) {
            drawRoomTooltip(g, hovered, mouseX, mouseY);
        }
    }

    private static int shade(int argb, float f) {
        int rr = (int) (((argb >> 16) & 0xFF) * f);
        int gg = (int) (((argb >> 8) & 0xFF) * f);
        int bb = (int) ((argb & 0xFF) * f);
        return 0xFF000000 | (rr << 16) | (gg << 8) | bb;
    }

    private boolean inMapRange(float[] p) {
        return p[0] >= runMinX && p[0] < runMaxX + 1 && p[1] >= runMinZ && p[1] < runMaxZ + 1;
    }

    /** Linea de 2 px hecha con fill (no depende de ninguna funcion de lineas del GUI). */
    private static void drawLine(GuiGraphicsExtractor g, float x0, float y0, float x1, float y1, int color) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        int steps = Math.max(1, (int) Math.ceil(Math.max(Math.abs(dx), Math.abs(dy))));
        for (int i = 0; i <= steps; i++) {
            int x = Math.round(x0 + dx * i / steps);
            int y = Math.round(y0 + dy * i / steps);
            g.fill(x, y, x + 2, y + 2, color);
        }
    }

    /**
     * Dibuja el recorrido del jugador elegido (o de todos). La linea va de oscura (inicio) a brillante (final)
     * para que se vea el sentido; el inicio es un cuadrado blanco y el final un punto del color del jugador.
     */
    private void drawPaths(GuiGraphicsExtractor g, RunData r, int mapX, int mapY, int pitch) {
        if (pathSel == null || r.players == null) return;
        for (int i = 0; i < r.players.size(); i++) {
            String name = r.players.get(i);
            if (!"*".equals(pathSel) && !pathSel.equals(name)) continue;
            List<float[]> pts = pathCache.get(name);
            if (pts == null || pts.size() < 2) continue;
            int base = getPlayerColor(r, i);
            int n = pts.size() - 1;
            for (int k = 0; k < n; k++) {
                float[] a = pts.get(k);
                float[] b = pts.get(k + 1);
                if (!inMapRange(a) || !inMapRange(b)) continue;
                float jx = b[0] - a[0];
                float jz = b[1] - a[1];
                if (jx * jx + jz * jz > 2.25f) continue; // salto >1.5 celdas: el jugador salio del rango de vision
                drawLine(g, mapX + (a[0] - runMinX) * pitch, mapY + (a[1] - runMinZ) * pitch,
                        mapX + (b[0] - runMinX) * pitch, mapY + (b[1] - runMinZ) * pitch,
                        shade(base, 0.35f + 0.65f * k / n));
            }
            float[] first = pts.get(0);
            float[] last = pts.get(n);
            if (inMapRange(first)) {
                int fx = Math.round(mapX + (first[0] - runMinX) * pitch);
                int fy = Math.round(mapY + (first[1] - runMinZ) * pitch);
                g.fill(fx - 2, fy - 2, fx + 3, fy + 3, WHITE);
            }
            if (inMapRange(last)) {
                int lx = Math.round(mapX + (last[0] - runMinX) * pitch);
                int ly = Math.round(mapY + (last[1] - runMinZ) * pitch);
                g.fill(lx - 3, ly - 3, lx + 4, ly + 4, 0xFF000000);
                g.fill(lx - 2, ly - 2, lx + 3, ly + 3, base);
            }
        }
    }

    private static boolean inRect(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private static int doorColor(int kind) {
        return switch (kind) {
            case 1 -> DOOR_WITHER;
            case 2 -> ROOM_BLOOD;
            case 3 -> ROOM_ENTRANCE;
            case 4 -> ROOM_UNKNOWN;
            default -> DOOR_NORMAL;
        };
    }

    private static boolean hasCell(RunData.RoomEntry room, int x, int z) {
        for (int[] c : room.cells) if (c[0] == x && c[1] == z) return true;
        return false;
    }

    // Colores por tipo de sala (ARGB)
    private static final int ROOM_ENTRANCE = 0xFF148500; // (20, 133, 0)
    private static final int ROOM_NORMAL = 0xFF6B3A11;   // (107, 58, 17)
    private static final int ROOM_YELLOW = 0xFFFEDF00;   // (254, 223, 0)
    private static final int ROOM_PUZZLE = 0xFF750085;   // (117, 0, 133)
    private static final int ROOM_FAIRY = 0xFFE000FF;    // (224, 0, 255)
    private static final int ROOM_BLOOD = 0xFFFF0000;    // (255, 0, 0)
    private static final int ROOM_TRAP = 0xFFD87F33;     // (216, 127, 51)
    private static final int ROOM_UNKNOWN = 0xFF414141;  // (65, 65, 65)
    private static final int DOOR_NORMAL = 0xFF5C340E;   // (92, 52, 14)
    private static final int DOOR_WITHER = 0xFF000000;   // (0, 0, 0)

    private static int roomColor(String type) {
        if (type == null) return ROOM_UNKNOWN;
        return switch (type) {
            case "entrance" -> ROOM_ENTRANCE;
            case "normal", "rare", "miniboss" -> ROOM_NORMAL;
            case "yellow" -> ROOM_YELLOW;
            case "puzzle" -> ROOM_PUZZLE;
            case "fairy" -> ROOM_FAIRY;
            case "blood" -> ROOM_BLOOD;
            case "trap" -> ROOM_TRAP;
            default -> ROOM_UNKNOWN;
        };
    }

    private int statLine(GuiGraphicsExtractor g, int x, int y, int maxW, String label, String value, int color) {
        g.text(font, label, x, y, GRAY, false);
        g.text(font, font.plainSubstrByWidth(value, Math.max(10, maxW - font.width(label))),
                x + font.width(label), y, color, false);
        return y + 11;
    }

    private void drawRoomTooltip(GuiGraphicsExtractor g, RunData.RoomEntry room, int mouseX, int mouseY) {
        List<Map.Entry<String, Double>> rows = room.seconds == null ? List.of()
                : room.seconds.entrySet().stream()
                        .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                        .toList();

        String title = room.name == null ? "Sala" : room.name;
        String sub = (room.type == null ? "?" : room.type) + " · " + (room.shape == null ? "?" : room.shape)
                + (room.secrets == null ? "" : " · " + room.secrets + " secretos");
        int w = Math.max(font.width(title), font.width(sub));
        for (Map.Entry<String, Double> e : rows) {
            w = Math.max(w, font.width(e.getKey() + "  " + fmtTime(e.getValue())));
        }
        if (rows.isEmpty()) w = Math.max(w, font.width("Nadie pasó por acá"));
        w += 10;
        int h = 24 + Math.max(1, rows.size()) * 10 + 4;

        int bx = mouseX + 10;
        int by = mouseY + 10;
        if (bx + w > width - 4) bx = mouseX - w - 6;
        if (by + h > height - 4) by = height - 4 - h;

        g.fill(bx - 1, by - 1, bx + w + 1, by + h + 1, ACCENT);
        g.fill(bx, by, bx + w, by + h, 0xF0101010);
        g.text(font, title, bx + 5, by + 4, WHITE, true);
        g.text(font, sub, bx + 5, by + 15, GRAY, false);

        int ty = by + 26;
        if (rows.isEmpty()) {
            g.text(font, "Nadie pasó por acá", bx + 5, ty, GRAY, false);
            return;
        }
        for (Map.Entry<String, Double> e : rows) {
            g.text(font, e.getKey(), bx + 5, ty, WHITE, false);
            String t = fmtTime(e.getValue());
            g.text(font, t, bx + w - 5 - font.width(t), ty, YELLOW, false);
            ty += 10;
        }
    }

    // ---------------------------------------------------------------- popup

    private int popupX() {
        return left + (PANEL_W - POPUP_W) / 2;
    }

    private int popupY() {
        return top + (PANEL_H - POPUP_H) / 2;
    }

    private void openPopup() {
        popup = true;
        popupName = "";
        popupReason = "";
        popupError = "";
        popupOkAt = 0;
        mod.resetAddState();
        rebuildWidgets();
    }

    private void closePopup() {
        popup = false;
        popupOkAt = 0;
        popupSubmit = null;
        mod.resetAddState();
        rebuildWidgets();
    }

    private void initPopup() {
        int px = popupX();
        int py = popupY();
        int bw = POPUP_W - 20;

        EditBox nameBox = new EditBox(font, px + 10, py + 34, bw, 18, Component.literal("Jugador"));
        nameBox.setMaxLength(16);
        nameBox.setHint(Component.literal("Nombre en Minecraft"));
        nameBox.setValue(popupName);
        nameBox.setResponder(text -> popupName = text);
        addRenderableWidget(nameBox);

        EditBox reasonBox = new EditBox(font, px + 10, py + 68, bw, 18, Component.literal("Razón"));
        reasonBox.setMaxLength(200);
        reasonBox.setHint(Component.literal("Por qué se agrega"));
        reasonBox.setValue(popupReason);
        reasonBox.setResponder(text -> popupReason = text);
        addRenderableWidget(reasonBox);

        addRenderableWidget(Button.builder(Component.literal("Cancelar"), b -> closePopup())
                .bounds(px + 10, py + POPUP_H - 28, 100, 20).build());

        popupSubmit = Button.builder(Component.literal("Agregar"), b -> submitPopup())
                .bounds(px + POPUP_W - 110, py + POPUP_H - 28, 100, 20).build();
        addRenderableWidget(popupSubmit);

        setInitialFocus(nameBox);
    }

    private void submitPopup() {
        popupError = "";
        String error = mod.startAdd(popupName, popupReason);
        if (error != null) {
            popupError = error;
        }
    }

    /** Parte un texto en líneas que quepan en maxWidth píxeles. */
    private List<String> wrap(String text, int maxWidth) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = current.isEmpty() ? word : current + " " + word;
            if (current.isEmpty() || font.width(candidate) <= maxWidth) {
                current = new StringBuilder(candidate);
            } else {
                lines.add(current.toString());
                current = new StringBuilder(word);
            }
        }
        if (!current.isEmpty()) lines.add(current.toString());
        return lines;
    }

    private void drawPopup(GuiGraphicsExtractor g) {
        int px = popupX();
        int py = popupY();
        BlacklistClient.AddState state = mod.getAddState();

        g.text(font, "Agregar a la blacklist", px + 10, py + 10, WHITE, true);
        g.text(font, "Jugador", px + 10, py + 24, GRAY, false);
        g.text(font, "Razón", px + 10, py + 58, GRAY, false);

        // Mensaje de estado: error local, enviando, éxito o error del servidor
        String message = "";
        int color = GRAY;
        if (!popupError.isEmpty()) {
            message = popupError;
            color = RED;
        } else if (state == BlacklistClient.AddState.SENDING) {
            message = mod.getAddMessage();
            color = YELLOW;
        } else if (state == BlacklistClient.AddState.OK) {
            message = mod.getAddMessage();
            color = GREEN;
        } else if (state == BlacklistClient.AddState.ERROR) {
            message = mod.getAddMessage();
            color = RED;
        }

        if (!message.isEmpty()) {
            List<String> lines = wrap(message, POPUP_W - 20);
            for (int i = 0; i < Math.min(2, lines.size()); i++) {
                String line = lines.get(i);
                if (i == 1 && lines.size() > 2) {
                    line = font.plainSubstrByWidth(line, POPUP_W - 20 - font.width("...")) + "...";
                }
                g.text(font, line, px + 10, py + 94 + i * 10, color, false);
            }
        }

        // Mientras se envía (o ya se agregó) el botón queda desactivado
        if (popupSubmit != null) {
            popupSubmit.active = state != BlacklistClient.AddState.SENDING
                    && state != BlacklistClient.AddState.OK;
        }

        // Al agregarse bien, se deja ver el mensaje un momento y se cierra sola
        if (state == BlacklistClient.AddState.OK) {
            long now = System.currentTimeMillis();
            if (popupOkAt == 0) {
                popupOkAt = now;
            } else if (now - popupOkAt > 1200) {
                closePopup();
            }
        }
    }

    // ------------------------------------------------------------- datos

    private void refreshFiltered() {
        lastSeen = mod.getBlacklist();
        String f = filter.toLowerCase().trim();
        filtered = lastSeen.entrySet().stream()
                .filter(e -> f.isEmpty()
                        || e.getKey().contains(f)
                        || e.getValue().toLowerCase().contains(f))
                .sorted(Map.Entry.comparingByKey())
                .toList();
        page = Math.min(page, maxPage());
    }

    private int maxPage() {
        return Math.max(0, (filtered.size() - 1) / ROWS_PER_PAGE);
    }

    private static String modeName(BlacklistConfig.Mode mode) {
        return switch (mode) {
            case OFF -> "Desactivado";
            case ALERT -> "Solo avisar (warning)";
            case KICK -> "Auto-kick";
        };
    }

    private static int modeColor(BlacklistConfig.Mode mode) {
        return switch (mode) {
            case OFF -> GRAY;
            case ALERT -> YELLOW;
            case KICK -> RED;
        };
    }

    // ----------------------------------------------------------- dibujado

    /** Fondo + panel. Se dibuja ANTES que los widgets para que los botones queden encima. */
    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractBackground(g, mouseX, mouseY, delta);

        g.fill(left - 1, top - 1, left + PANEL_W + 1, top + PANEL_H + 1, BORDER);
        g.fill(left, top, left + PANEL_W, top + PANEL_H, PANEL);
        g.fill(left, top, left + SIDEBAR_W, top + PANEL_H, SIDEBAR);
        g.fill(left + SIDEBAR_W, top, left + SIDEBAR_W + 1, top + PANEL_H, BORDER);

        g.text(font, ModInfo.NAME, left + 10, top + 12, WHITE, true);
        g.fill(left + 6, top + 26, left + SIDEBAR_W - 6, top + 27, ACCENT);

        if (zoneEdit >= 0) {
            g.fill(left, top, left + PANEL_W, top + PANEL_H, 0xB0000000);
            int zx = zonePopupX();
            int zy = zonePopupY();
            g.fill(zx - 1, zy - 1, zx + ZP_W + 1, zy + ZP_H + 1, ACCENT);
            g.fill(zx, zy, zx + ZP_W, zy + ZP_H, 0xFF1E1E1E);
        }

        if (popup) {
            // Atenúa el panel y dibuja el marco de la ventana (los widgets quedan encima)
            g.fill(left, top, left + PANEL_W, top + PANEL_H, 0xB0000000);
            int px = popupX();
            int py = popupY();
            g.fill(px - 1, py - 1, px + POPUP_W + 1, py + POPUP_H + 1, ACCENT);
            g.fill(px, py, px + POPUP_W, py + POPUP_H, 0xFF1E1E1E);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        // La lista se reemplaza entera al refrescar, así que basta comparar la referencia
        if (mod.getBlacklist() != lastSeen) {
            refreshFiltered();
        }

        // El estado del updater cambia desde otro hilo: se refresca el botón cada fotograma
        if (updateButton != null) {
            updateButton.setMessage(Component.literal(Updater.shortLabel()));
            Updater.State us = Updater.state();
            updateButton.active = us == Updater.State.AVAILABLE
                    || us == Updater.State.ERROR || us == Updater.State.UP_TO_DATE;
        }

        super.extractRenderState(g, mouseX, mouseY, delta); // fondo + widgets

        if (zoneEdit >= 0) {
            drawZonePopup(g, mouseX, mouseY);
            return;
        }

        if (popup) {
            drawPopup(g);
            return;
        }

        switch (tab) {
            case HOME -> drawHome(g);
            case LIST -> drawList(g);
            case QOL -> drawQol(g);
            case DUNGEONS -> drawDungeons(g, mouseX, mouseY);
            case CHAT -> drawChat(g);
            case SETTINGS -> drawSettings(g);
        }
    }

    private void card(GuiGraphicsExtractor g, int x, int y, int w, String title, String value, int valueColor) {
        g.fill(x, y, x + w, y + 34, CARD);
        g.fill(x, y, x + 2, y + 34, ACCENT);
        g.text(font, title, x + 8, y + 6, GRAY, false);
        g.text(font, value, x + 8, y + 19, valueColor, false);
    }

    // ---- Inicio: nombre, descripción y changelog ----

    private int changelogScroll = 0;

    /** Una línea ya partida del changelog. prefix solo va en la primera línea de cada cambio. */
    private record LogLine(String text, int color, String prefix, int prefixColor) {
    }

    private List<LogLine> changelogLines(int width) {
        List<LogLine> result = new ArrayList<>();
        for (ModInfo.Release r : ModInfo.CHANGELOG) {
            String head = "v" + r.version() + (r.date().isEmpty() ? "" : "  ·  " + r.date());
            result.add(new LogLine(head, ACCENT, null, 0));
            for (ModInfo.Change c : r.changes()) {
                String prefix = c.kind().label + ": ";
                int prefixColor = switch (c.kind()) {
                    case NEW -> GREEN;
                    case IMPROVED -> YELLOW;
                    case FIX -> RED;
                };
                List<String> wrapped = wrap(prefix + c.text(), width - 6);
                for (int i = 0; i < wrapped.size(); i++) {
                    result.add(new LogLine(wrapped.get(i), WHITE, i == 0 ? prefix : null, prefixColor));
                }
            }
        }
        return result;
    }

    /** Cuántas líneas del changelog caben en pantalla. */
    private static final int LOG_VISIBLE = 14;

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!popup && tab == Tab.HOME && scrollY != 0) {
            int max = Math.max(0, changelogLines(contentW()).size() - LOG_VISIBLE);
            changelogScroll = Math.max(0, Math.min(max, changelogScroll + (scrollY > 0 ? -1 : 1)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void drawHome(GuiGraphicsExtractor g) {
        int cx = contentX();
        int cy = contentY();
        int cw = contentW();

        // Nombre del mod (más grande) + versión
        g.pose().pushMatrix();
        g.pose().translate((float) cx, (float) cy);
        g.pose().scale(1.6f, 1.6f);
        g.text(font, ModInfo.NAME, 0, 0, WHITE, true);
        g.pose().popMatrix();
        g.text(font, "v" + ModInfo.VERSION, cx + (int) (font.width(ModInfo.NAME) * 1.6f) + 6, cy + 5, GRAY, false);

        // Motivo del fallo del updater, justo debajo del botón
        if (Updater.state() == Updater.State.ERROR) {
            String err = font.plainSubstrByWidth(Updater.error(), 110);
            g.text(font, err, cx + cw - font.width(err), cy + 17, RED, false);
        }

        // Estado resumido
        BlacklistConfig.Mode mode = mod.getConfig().mode;
        g.text(font, "Modo: ", cx, cy + 17, GRAY, false);
        g.text(font, modeName(mode), cx + font.width("Modo: "), cy + 17, modeColor(mode), false);

        long last = mod.getLastRefresh();
        String when;
        if (last == 0) {
            when = "sin datos aún";
        } else {
            long min = (System.currentTimeMillis() - last) / 60000;
            when = min < 1 ? "hace menos de 1 min" : "hace " + min + " min";
        }
        g.text(font, mod.getBlacklist().size() + " jugadores en la lista  ·  actualizada " + when,
                cx, cy + 27, last == 0 ? YELLOW : GRAY, false);

        // Descripción
        List<String> desc = wrap(ModInfo.DESCRIPTION, cw);
        for (int i = 0; i < Math.min(2, desc.size()); i++) {
            String line = desc.get(i);
            if (i == 1 && desc.size() > 2) {
                line = font.plainSubstrByWidth(line, cw - font.width("...")) + "...";
            }
            g.text(font, line, cx, cy + 41 + i * 10, GRAY, false);
        }

        // Changelog
        int headerY = cy + 66;
        g.text(font, "Últimos cambios", cx, headerY, WHITE, true);
        g.fill(cx, headerY + 11, cx + cw, headerY + 12, ACCENT);

        List<LogLine> lines = changelogLines(cw);
        int max = Math.max(0, lines.size() - LOG_VISIBLE);
        changelogScroll = Math.max(0, Math.min(max, changelogScroll));
        if (max > 0) {
            String hint = "rueda para ver más";
            g.text(font, hint, cx + cw - font.width(hint), headerY + 1, GRAY, false);
        }

        int y = headerY + 16;
        for (int i = changelogScroll; i < Math.min(lines.size(), changelogScroll + LOG_VISIBLE); i++) {
            LogLine l = lines.get(i);
            if (l.prefix() != null) {
                g.text(font, l.prefix(), cx + 2, y, l.prefixColor(), false);
                g.text(font, l.text().substring(l.prefix().length()),
                        cx + 2 + font.width(l.prefix()), y, l.color(), false);
            } else {
                boolean head = l.color() == ACCENT;
                g.text(font, l.text(), cx + (head ? 0 : 8), y, l.color(), false);
            }
            y += 10;
        }
    }

    private void drawList(GuiGraphicsExtractor g) {
        int cx = contentX();
        int cw = contentW();
        int listTop = contentY() + 24;

        if (filtered.isEmpty()) {
            g.text(font, filter.isEmpty() ? "La lista está vacía" : "Sin resultados",
                    cx + 4, listTop + 6, GRAY, false);
        }

        int start = page * ROWS_PER_PAGE;
        for (int i = 0; i < ROWS_PER_PAGE; i++) {
            int idx = start + i;
            if (idx >= filtered.size()) break;

            Map.Entry<String, String> e = filtered.get(idx);
            int ry = listTop + i * ROW_H;
            g.fill(cx, ry, cx + cw, ry + ROW_H - 2, i % 2 == 0 ? CARD : CARD_ALT);
            g.text(font, e.getKey(), cx + 5, ry + 5, WHITE, false);
            g.text(font, font.plainSubstrByWidth(e.getValue(), cw - 110), cx + 100, ry + 5, GRAY, false);
        }

        String info = "Pág. " + (page + 1) + "/" + (maxPage() + 1) + "  ·  " + filtered.size() + " jugadores";
        g.text(font, info, cx + (cw - font.width(info)) / 2, bottomY() + 6, GRAY, false);
    }

    private void drawSettings(GuiGraphicsExtractor g) {
        int cx = contentX();
        int cy = contentY();

        g.text(font, "Al detectar un jugador de la lista:", cx, cy, GRAY, false);

        String desc = switch (mod.getConfig().mode) {
            case OFF -> "No hace nada.";
            case ALERT -> "Muestra una alerta en el chat y en pantalla.";
            case KICK -> "Alerta y expulsa del party (/p kick).";
        };
        g.text(font, desc, cx, cy + 124, GRAY, false);
    }
}
