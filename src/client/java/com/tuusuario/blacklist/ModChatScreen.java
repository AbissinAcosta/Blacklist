package com.tuusuario.blacklist; // <- ajusta al paquete de tu proyecto

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Chat del mod (Global / Party) dibujado justo donde va el chat normal.
 *
 * Mientras esta pantalla está abierta, BlacklistClient oculta el chat vanilla (HudElementRegistry).
 * Las pestañas (Chat | Global | Party) se agregan también al ChatScreen normal con {@link #tabs}.
 *  - Clic en Global / Party        -> abre esta pantalla en esa sala
 *  - Clic en la pestaña ya activa  -> vuelve al chat normal
 *  - Clic en "Chat"                -> vuelve al chat normal
 * Enter envía el mensaje (queda abierto); Esc cierra. Si empiezas con "/" se ejecuta como comando normal.
 */
public class ModChatScreen extends Screen {

    // El tamano del chat sale de la configuracion (pestana Chat); por defecto 320 x 180, como el chat vanilla
    private static final int TAB_H = 14;
    private static final int TAB_W = 56;
    static final int LINE_H = 9;

    private static final int BG = 0x80000000;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GRAY = 0xFFAAAAAA;
    private static final int RED = 0xFFFF5555;

    private final ChatClient.Room room;
    private final ChatClient chat;

    private EditBox input;
    private String draft = "";
    private int scroll = 0; // líneas desplazadas hacia arriba

    // Líneas ya partidas al ancho del chat (se recalculan solo si cambian los mensajes)
    private List<FormattedCharSequence> lines = List.of();
    private int cachedCount = -1;
    private long cachedLastId = -1;
    private int cachedWidth = -1;

    // Menu colapsable de conectados (usuarios con el mod), a la derecha de las pestañas
    private static final int ONLINE_W = 112;
    private static final int ONLINE_ROW_H = 11;
    private static final int ONLINE_MAX_ROWS = 10;
    private static final int GREEN = 0xFF55FF55;
    private static final int ACCENT = 0xFF55AAFF;
    private boolean onlineOpen = false;
    private int onlineScroll = 0;

    // Autocompletado de emojis (:sm -> menú con :smile:, :smiley: ...)
    private static final int SUGGEST_MAX = 8;
    private static final int SUGGEST_ROW_H = 12;
    private List<Emojis.Entry> suggestions = List.of();
    private int suggestSel = 0;
    private int tokenStart = -1;      // posición del ":" que empezó lo que se está escribiendo
    private boolean tokenHyphen;      // si escribió con guiones (:wilted-fl...) se inserta igual

    public ModChatScreen(ChatClient.Room room) {
        super(Component.literal("Chat del mod"));
        this.room = room;
        this.chat = BlacklistClient.get().chat();
        // Queda guardada: la próxima vez que abras el chat (T) se abrirá aquí
        BlacklistClient.get().setChatTab(room);
    }

    public ChatClient.Room room() {
        return room;
    }

    // ------------------------------------------------------------ pestañas (se reutilizan en ChatScreen)

    /** Alto del chat del mod, recortado si la pantalla es pequena. */
    static int chatH(int screenHeight) {
        int configured = BlacklistClient.get().getConfig().modChatHeight;
        return Math.max(LINE_H * 3, Math.min(configured, screenHeight - 40 - (TAB_H + 4)));
    }

    static int chatTop(int screenHeight) {
        return Math.max(TAB_H + 4, screenHeight - 40 - chatH(screenHeight));
    }

    static int chatWidth(int screenWidth) {
        return Math.min(BlacklistClient.get().getConfig().modChatWidth, screenWidth - 4);
    }

    /** Arriba del chat normal (que mide siempre lo mismo): ahi van las pestanas cuando estas en el chat vanilla. */
    private static int vanillaTop(int screenHeight) {
        return Math.max(TAB_H + 4, screenHeight - 40 - BlacklistConfig.DEFAULT_CHAT_H);
    }

    /**
     * Botones de pestañas. active == null significa que estás en el chat normal.
     * Se agregan con addRenderableWidget (esta pantalla) o Screens.getWidgets(screen).add (ChatScreen).
     */
    public static List<Button> tabs(Minecraft mc, int screenHeight, ChatClient chat, ChatClient.Room active) {
        int y = (active == null ? vanillaTop(screenHeight) : chatTop(screenHeight)) - TAB_H - 1;
        List<Button> out = new ArrayList<>();

        Button normal = Button.builder(tabLabel("Chat", 0, active == null), b -> openVanilla(mc))
                .bounds(2, y, TAB_W, TAB_H).build();
        normal.active = active != null; // la pestaña actual no hace nada
        out.add(normal);

        int x = 2 + TAB_W + 2;
        for (ChatClient.Room r : ChatClient.Room.values()) {
            boolean current = r == active;
            Component label = tabLabel(r == ChatClient.Room.GLOBAL ? "Global" : "Party",
                    current ? 0 : chat.unread(r), current);
            out.add(Button.builder(label, b -> {
                if (current) {
                    openVanilla(mc); // volver al chat de verdad
                } else {
                    mc.setScreen(new ModChatScreen(r));
                }
            }).bounds(x, y, TAB_W, TAB_H).build());
            x += TAB_W + 2;
        }
        return out;
    }

    /** Vuelve al chat normal de Hypixel y lo deja como el chat por defecto. */
    public static void openVanilla(Minecraft mc) {
        BlacklistClient.get().setChatTab(null);
        mc.setScreen(new ChatScreen("", false));
    }

    private static Component tabLabel(String name, int unread, boolean current) {
        Component c = Component.literal(name).withStyle(current ? ChatFormatting.AQUA : ChatFormatting.WHITE);
        if (unread > 0) {
            c = c.copy().append(Component.literal(" (" + Math.min(unread, 99) + ")").withStyle(ChatFormatting.YELLOW));
        }
        return c;
    }

    // ------------------------------------------------------------ init

    @Override
    protected void init() {
        for (Button b : tabs(minecraft, height, chat, room)) {
            addRenderableWidget(b);
        }

        // Barra de escritura, igual que la del chat normal (el fondo lo dibujamos nosotros)
        input = new EditBox(font, 4, height - 12, width - 4, 12, Component.literal("Mensaje"));
        input.setBordered(false);
        input.setMaxLength(200);
        input.setHint(Component.literal("Mensaje para " + (room == ChatClient.Room.GLOBAL ? "Global" : "tu Party") + "..."));
        input.setValue(draft);
        suggestions = List.of();
        input.setResponder(t -> {
            draft = t;
            updateSuggestions();
        });
        addRenderableWidget(input);
        setInitialFocus(input);
    }

    // ------------------------------------------------------------ teclado / ratón

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();

        // Con el menú de emojis abierto, las flechas / Tab / Enter / Esc son para el menú
        if (!suggestions.isEmpty()) {
            int n = suggestions.size();
            if (key == GLFW.GLFW_KEY_DOWN) {
                suggestSel = (suggestSel + 1) % n;
                return true;
            }
            if (key == GLFW.GLFW_KEY_UP) {
                suggestSel = (suggestSel - 1 + n) % n;
                return true;
            }
            if ((key == GLFW.GLFW_KEY_TAB && !ctrlDown())   // Ctrl+Tab cambia de chat, no elige
                    || key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                acceptSuggestion(suggestSel);
                return true;
            }
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                suggestions = List.of(); // cierra solo el menú, no el chat
                return true;
            }
        }

        if (onlineOpen && key == GLFW.GLFW_KEY_ESCAPE) {
            onlineOpen = false; // cierra solo el menu, no el chat
            return true;
        }

        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            String text = input.getValue().strip();
            if (!text.isEmpty()) {
                if (text.startsWith("/")) {
                    // Un comando normal: se manda al servidor y se vuelve al juego
                    if (minecraft.player != null) minecraft.player.connection.sendCommand(text.substring(1));
                    minecraft.setScreen(null);
                    return true;
                }
                chat.send(room, text);
                input.setValue("");
                draft = "";
                scroll = 0;
            }
            return true;
        }
        boolean handled = super.keyPressed(event);
        // Mover el cursor no dispara el responder: se recalcula a mano
        if (key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_RIGHT
                || key == GLFW.GLFW_KEY_HOME || key == GLFW.GLFW_KEY_END) {
            updateSuggestions();
        }
        return handled;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (chat.isEnabledForOnline()) {
            if (inRect(event.x(), event.y(), onlineX(), onlineY(), ONLINE_W, TAB_H)) {
                onlineOpen = !onlineOpen;
                onlineScroll = 0;
                if (onlineOpen) chat.refreshOnline();
                return true;
            }
            // Con el menu abierto, un clic dentro de la lista no debe llegar al chat; fuera de ella lo cierra
            if (onlineOpen) {
                if (inRect(event.x(), event.y(), dropX(), dropY(), dropW(), dropH())) return true;
                onlineOpen = false;
            }
        }
        if (!suggestions.isEmpty()) {
            int idx = suggestionAt(event.x(), event.y());
            if (idx >= 0) {
                acceptSuggestion(idx);
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (onlineOpen && inRect(mouseX, mouseY, dropX(), dropY(), dropW(), dropH())) {
            int max = Math.max(0, chat.onlineNames().size() - ONLINE_MAX_ROWS);
            onlineScroll = Math.max(0, Math.min(max, onlineScroll + (scrollY > 0 ? -1 : 1)));
            return true;
        }
        int visible = chatH(height) / LINE_H;
        int max = Math.max(0, lines.size() - visible);
        scroll = Math.max(0, Math.min(max, scroll + (scrollY > 0 ? 3 : -3)));
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------ conectados

    private static boolean inRect(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private int onlineX() {
        return 2 + chatWidth(width) - ONLINE_W;
    }

    private int onlineY() {
        return chatTop(height) - TAB_H - 1;
    }

    private List<String> shownOnline() {
        return chat.onlineNames();
    }

    private int dropRows() {
        return Math.max(1, Math.min(ONLINE_MAX_ROWS, shownOnline().size()));
    }

    private int dropW() {
        int w = ONLINE_W;
        for (String n : shownOnline()) w = Math.max(w, font.width(n) + 22);
        return Math.min(w, chatWidth(width) - 4);
    }

    private int dropH() {
        return 14 + dropRows() * ONLINE_ROW_H + 3;
    }

    private int dropX() {
        return onlineX() + ONLINE_W - dropW(); // alineado a la derecha del boton
    }

    private int dropY() {
        return onlineY() + TAB_H + 1;
    }

    private void drawOnline(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int x = onlineX();
        int y = onlineY();
        boolean over = inRect(mouseX, mouseY, x, y, ONLINE_W, TAB_H);

        // Boton: punto verde + cantidad + flecha
        g.fill(x, y, x + ONLINE_W, y + TAB_H, over || onlineOpen ? 0xC0303030 : 0x90000000);
        g.fill(x, y + TAB_H - 1, x + ONLINE_W, y + TAB_H, onlineOpen ? ACCENT : 0xFF3A3A3A);
        int count = chat.onlineCount();
        boolean unsupported = !chat.onlineSupported();
        g.fill(x + 5, y + 5, x + 9, y + 9, unsupported || count < 0 ? GRAY : GREEN); // el "punto" de estado
        String label = unsupported ? "Conectados: --" : (count < 0 ? "Conectados..." : count + " conectados");
        g.text(font, label, x + 13, y + 3, WHITE, false);
        g.text(font, onlineOpen ? "▲" : "▼", x + ONLINE_W - 11, y + 3, GRAY, false);

        if (!onlineOpen) return;

        // Lista desplegable
        int dx = dropX();
        int dy = dropY();
        int dw = dropW();
        int dh = dropH();
        g.fill(dx - 1, dy - 1, dx + dw + 1, dy + dh + 1, 0xFF3A3A3A);
        g.fill(dx, dy, dx + dw, dy + dh, 0xF0101010);
        g.text(font, "Usuarios con el mod", dx + 5, dy + 3, GRAY, false);
        g.fill(dx + 4, dy + 12, dx + dw - 4, dy + 13, 0xFF2B2B2B);

        List<String> names = shownOnline();
        String self = minecraft.getUser().getName();
        if (unsupported) {
            g.text(font, "El servidor aún no", dx + 5, dy + 17, GRAY, false);
            g.text(font, "tiene esta función.", dx + 5, dy + 28, GRAY, false);
            return;
        }
        if (names.isEmpty()) {
            g.text(font, count < 0 ? "Cargando..." : "Nadie conectado", dx + 5, dy + 17, GRAY, false);
            return;
        }
        int max = Math.max(0, names.size() - ONLINE_MAX_ROWS);
        onlineScroll = Math.max(0, Math.min(max, onlineScroll));
        for (int i = 0; i < ONLINE_MAX_ROWS; i++) {
            int idx = onlineScroll + i;
            if (idx >= names.size()) break;
            String n = names.get(idx);
            int ry = dy + 15 + i * ONLINE_ROW_H;
            boolean me = n.equalsIgnoreCase(self);
            g.fill(dx + 6, ry + 3, dx + 9, ry + 6, GREEN);
            g.text(font, me ? n + " (tú)" : n, dx + 13, ry + 1, me ? ACCENT : WHITE, false);
        }
        if (max > 0) {
            String more = (onlineScroll + 1) + "-" + Math.min(names.size(), onlineScroll + ONLINE_MAX_ROWS)
                    + "/" + names.size();
            g.text(font, more, dx + dw - font.width(more) - 4, dy + 3, GRAY, false);
        }
    }

    // ------------------------------------------------------------ autocompletado de emojis

    private boolean ctrlDown() {
        long window = minecraft.getWindow().handle();
        return GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS;
    }

    /** Mira lo que hay justo antes del cursor: si es ":" + 2 o más letras, busca emojis. */
    private void updateSuggestions() {
        suggestions = List.of();
        tokenStart = -1;
        if (input == null || !Emojis.available()) return;

        String text = input.getValue();
        int cursor = Math.min(input.getCursorPosition(), text.length());
        int colon = text.lastIndexOf(':', cursor - 1);
        if (colon < 0 || cursor - colon - 1 < 2) return;
        if (colon > 0 && Character.isLetterOrDigit(text.charAt(colon - 1))) return; // "12:30"

        String prefix = text.substring(colon + 1, cursor);
        for (int i = 0; i < prefix.length(); i++) {
            char c = prefix.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '+')) return;
        }

        List<Emojis.Entry> found = Emojis.suggest(prefix, SUGGEST_MAX);
        if (found.isEmpty()) return;

        suggestions = found;
        suggestSel = 0;
        tokenStart = colon;
        tokenHyphen = prefix.indexOf('-') >= 0;
    }

    private String shownName(Emojis.Entry e) {
        return tokenHyphen ? e.name().replace('_', '-') : e.name();
    }

    private void acceptSuggestion(int index) {
        if (index < 0 || index >= suggestions.size() || tokenStart < 0) return;

        String text = input.getValue();
        int cursor = Math.min(input.getCursorPosition(), text.length());
        String tail = text.substring(cursor);

        boolean spaceAfter = tail.startsWith(" ");
        String insert = ":" + shownName(suggestions.get(index)) + ":" + (spaceAfter ? "" : " ");
        int newCursor = tokenStart + insert.length() + (spaceAfter ? 1 : 0);

        String result = text.substring(0, tokenStart) + insert + tail;
        suggestions = List.of();
        input.setValue(result);
        input.setCursorPosition(Math.min(newCursor, result.length()));
        updateSuggestions(); // queda vacío: el cursor ya está después del ":nombre:"
    }

    private String suggestionLabel(Emojis.Entry e) {
        return e.glyph() + "  :" + shownName(e) + ":";
    }

    private int suggestWidth() {
        int w = 0;
        for (Emojis.Entry e : suggestions) w = Math.max(w, font.width(suggestionLabel(e)));
        return w + 12;
    }

    private int suggestTop() {
        return height - 16 - suggestions.size() * SUGGEST_ROW_H;
    }

    /** Fila del menú bajo el ratón, o -1. */
    private int suggestionAt(double mx, double my) {
        int top = suggestTop();
        if (mx < 4 || mx >= 4 + suggestWidth() || my < top || my >= height - 16) return -1;
        return (int) ((my - top) / SUGGEST_ROW_H);
    }

    private void drawSuggestions(GuiGraphicsExtractor g) {
        int x = 4;
        int w = suggestWidth();
        int top = suggestTop();
        int bottom = height - 16;

        g.fill(x - 1, top - 1, x + w + 1, bottom + 1, 0xFF3A3A3A);
        g.fill(x, top, x + w, bottom, 0xF0101010);

        for (int i = 0; i < suggestions.size(); i++) {
            int ry = top + i * SUGGEST_ROW_H;
            if (i == suggestSel) g.fill(x, ry, x + w, ry + SUGGEST_ROW_H, 0xFF2F4F7F);
            g.text(font, suggestionLabel(suggestions.get(i)), x + 6, ry + 2, WHITE, false);
        }
    }

    // ------------------------------------------------------------ dibujado

    /** Sin fondo oscuro ni desenfoque: se tiene que ver el juego detrás, como en el chat normal. */
    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
    }

    private void rebuildLines(int w) {
        List<ChatClient.ChatMessage> msgs = chat.messages(room);
        long lastId = msgs.isEmpty() ? 0 : msgs.get(msgs.size() - 1).id();
        if (msgs.size() == cachedCount && lastId == cachedLastId && w == cachedWidth) return;

        List<FormattedCharSequence> out = new ArrayList<>();
        for (ChatClient.ChatMessage m : msgs) {
            out.addAll(font.split(ChatClient.format(room, m), w - 8));
        }
        lines = out;
        cachedCount = msgs.size();
        cachedLastId = lastId;
        cachedWidth = w;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        chat.markRead(room);

        int w = chatWidth(width);
        int top = chatTop(height);
        int bottom = top + chatH(height);

        // Fondo del historial y de la barra de escritura
        g.fill(2, top, 2 + w, bottom, BG);
        g.fill(2, height - 14, width - 2, height - 2, BG);

        BlacklistConfig cfg = BlacklistClient.get().getConfig();
        if (!cfg.chatEnabled) {
            g.text(font, "El chat del mod está desactivado (/blacklistmod chat on).", 8, bottom - 12, RED, true);
        } else if (room == ChatClient.Room.PARTY && chat.party() == null) {
            g.text(font, "No estás en una party (o aún no se detectó).", 8, bottom - 12, GRAY, true);
        } else {
            rebuildLines(w);
            int visible = chatH(height) / LINE_H;
            scroll = Math.min(scroll, Math.max(0, lines.size() - visible));
            for (int i = 0; i < visible; i++) {
                int idx = lines.size() - 1 - scroll - i;
                if (idx < 0) break;
                g.text(font, lines.get(idx), 6, bottom - LINE_H * (i + 1) - 1, WHITE, true);
            }
            if (chat.isMuted()) {
                g.text(font, "Estás silenciado en este chat.", 8, top + 3, RED, true);
            }
        }

        super.extractRenderState(g, mouseX, mouseY, delta); // pestañas + barra de escritura encima

        if (chat.isEnabledForOnline()) drawOnline(g, mouseX, mouseY);

        if (!suggestions.isEmpty()) drawSuggestions(g);
    }
}
