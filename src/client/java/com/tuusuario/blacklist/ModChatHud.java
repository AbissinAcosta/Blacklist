package com.tuusuario.blacklist;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * Chat del mod dibujado siempre (sin tener el chat abierto), en el mismo sitio y con el mismo tamano
 * que ModChatScreen. Se activa en la pestana Chat (chatAlwaysVisible).
 *
 * Solo se dibuja la sala de la pestana activa (config.chatTab = Global o Party). Mientras esta activo,
 * BlacklistClient oculta el chat vanilla para que no se pisen.
 */
public final class ModChatHud {

    private static final int BG = 0x60000000;
    private static final int WHITE = 0xFFFFFFFF;

    private static List<FormattedCharSequence> lines = List.of();
    private static ChatClient.Room cachedRoom;
    private static int cachedCount = -1;
    private static long cachedLastId = -1;
    private static int cachedWidth = -1;

    private ModChatHud() {
    }

    /** true si el chat del mod se tiene que dibujar ahora como HUD (y por tanto el vanilla se oculta). */
    public static boolean active(BlacklistConfig config, Minecraft mc) {
        return config.chatAlwaysVisible
                && config.chatEnabled
                && config.chatTab != null
                && !(mc.screen instanceof ChatScreen)
                && !(mc.screen instanceof ModChatScreen);
    }

    public static void render(GuiGraphicsExtractor g, BlacklistConfig config) {
        Minecraft mc = Minecraft.getInstance();
        if (!active(config, mc)) return;

        ChatClient chat = BlacklistClient.get().chat();
        ChatClient.Room room = config.chatTab;
        if (room == ChatClient.Room.PARTY && chat.party() == null) return;

        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        int w = ModChatScreen.chatWidth(sw);
        int h = ModChatScreen.chatH(sh);
        int bottom = ModChatScreen.chatTop(sh) + h;

        Font font = mc.font;
        rebuild(font, chat.messages(room), room, w);
        if (lines.isEmpty()) return;

        int visible = h / ModChatScreen.LINE_H;
        for (int i = 0; i < visible; i++) {
            int idx = lines.size() - 1 - i;
            if (idx < 0) break;
            FormattedCharSequence line = lines.get(idx);
            int y = bottom - ModChatScreen.LINE_H * (i + 1) - 1;
            g.fill(2, y - 1, 2 + Math.min(w, font.width(line) + 8), y + ModChatScreen.LINE_H - 1, BG);
            g.text(font, line, 6, y, WHITE, true);
        }
    }

    private static void rebuild(Font font, List<ChatClient.ChatMessage> msgs, ChatClient.Room room, int w) {
        long lastId = msgs.isEmpty() ? 0 : msgs.get(msgs.size() - 1).id();
        if (room == cachedRoom && msgs.size() == cachedCount && lastId == cachedLastId && w == cachedWidth) return;

        List<FormattedCharSequence> out = new ArrayList<>();
        for (ChatClient.ChatMessage m : msgs) {
            out.addAll(font.split(ChatClient.format(room, m), w - 8));
        }
        lines = out;
        cachedRoom = room;
        cachedCount = msgs.size();
        cachedLastId = lastId;
        cachedWidth = w;
    }
}
