package com.tuusuario.blacklist;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class BlacklistConfig {

    public enum Mode { OFF, ALERT, KICK }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE =
            FabricLoader.getInstance().getConfigDir().resolve("blacklist.json");

    public Mode mode = Mode.ALERT; // por defecto solo avisa

    // HUD
    public boolean hudEnabled = true;
    public int hudX = 6;
    public int hudY = 6;

    // Botón del chat: distancia al borde derecho e inferior de la pantalla.
    // Se guarda así (y no como x/y absolutas) para que se adapte si cambias
    // el tamaño de la ventana o la escala de la GUI.
    public static final int DEFAULT_BUTTON_SIZE = 20;
    public static final int MIN_BUTTON_SIZE = 12;
    public static final int MAX_BUTTON_SIZE = 48;
    public static final float MIN_HUD_SCALE = 0.5f;
    public static final float MAX_HUD_SCALE = 3.0f;

    public int buttonFromRight = 24;
    public int buttonFromBottom = 40;
    public int buttonSize = DEFAULT_BUTTON_SIZE;

    // Tamaño de las alertas en pantalla (1.0 = normal)
    public float hudScale = 1.0f;

    // Alertas de shards (Murkbat = Rekindle, Prince = Reborn) al unirse alguien al party
    public boolean shardAlerts = true;

    // Tracker de eventos (Spooky Festival, etc.)
    public boolean eventsEnabled = true;
    public int eventsX = 6;
    public int eventsY = 60;
    public float eventsScale = 1.0f;

    // Profit de cofres de dungeons (a la izquierda por defecto)
    public boolean profitEnabled = true;
    public int profitX = 6;
    public int profitY = 110;
    public float profitScale = 1.0f;

    // Aviso estilo Discord cuando llega un mensaje al chat global (QoL > Chat)
    public boolean chatToastEnabled = true;
    public boolean chatToastParty = false; // avisar tambien los mensajes de la party
    public int chatToastX = 6;
    public int chatToastY = 150;
    public float chatToastScale = 1.0f;

    // Chat del mod: tamano y visibilidad (pestana Chat)
    public static final int DEFAULT_CHAT_W = 320;
    public static final int DEFAULT_CHAT_H = 180;
    public static final int MIN_CHAT_W = 160;
    public static final int MAX_CHAT_W = 640;
    public static final int MIN_CHAT_H = 60;
    public static final int MAX_CHAT_H = 360;

    public int modChatWidth = DEFAULT_CHAT_W;
    public int modChatHeight = DEFAULT_CHAT_H;
    // Dibujar el chat del mod (el de la pestana activa) aunque no tengas abierto el chat del juego
    public boolean chatAlwaysVisible = false;

    // Atajos de comandos (QoL > Atajos)
    public boolean commandKeysEnabled = true;
    public List<CommandKey> commandKeys = new ArrayList<>();

    // Chat del mod (/blc y /blp)
    public boolean chatEnabled = true;
    public boolean chatToVanilla = false; // copiar también los mensajes al chat normal
    public boolean chatAlerts = true;     // aviso en el HUD cuando llega un mensaje y el chat está cerrado

    // Pestaña en la que queda el chat: null = chat normal de Hypixel, GLOBAL o PARTY = chat del mod.
    // Al abrir el chat con T se abre en esta pestaña, hasta que la cambies tú.
    public ChatClient.Room chatTab = null;

    // Tecla (código GLFW) que, junto con Ctrl, pasa al siguiente chat (con Shift, al anterior).
    // 258 = Tab. -1 = desactivado.
    public int chatCycleKey = 258;

    public int buttonX(int screenWidth) {
        return clamp(screenWidth - buttonFromRight, screenWidth - buttonSize);
    }

    public int buttonY(int screenHeight) {
        return clamp(screenHeight - buttonFromBottom, screenHeight - buttonSize);
    }

    /** Cambia el tamaño del botón del chat manteniendo su centro en el mismo sitio. */
    public void resizeButton(int delta, int screenWidth, int screenHeight) {
        int centerX = buttonX(screenWidth) + buttonSize / 2;
        int centerY = buttonY(screenHeight) + buttonSize / 2;

        buttonSize = Math.max(MIN_BUTTON_SIZE, Math.min(MAX_BUTTON_SIZE, buttonSize + delta));

        int x = clamp(centerX - buttonSize / 2, screenWidth - buttonSize);
        int y = clamp(centerY - buttonSize / 2, screenHeight - buttonSize);
        buttonFromRight = screenWidth - x;
        buttonFromBottom = screenHeight - y;
    }

    /** Cambia la escala de las alertas (pasos de 0.1, entre 0.5 y 3.0). */
    public void scaleHud(float delta) {
        float value = Math.round((hudScale + delta) * 10f) / 10f;
        hudScale = Math.max(MIN_HUD_SCALE, Math.min(MAX_HUD_SCALE, value));
    }

    /** Cambia la escala del tracker de eventos (pasos de 0.1, entre 0.5 y 3.0). */
    public void scaleEvents(float delta) {
        eventsScale = stepScale(eventsScale, delta);
    }

    /** Cambia la escala del profit de cofres (pasos de 0.1, entre 0.5 y 3.0). */
    public void scaleProfit(float delta) {
        profitScale = stepScale(profitScale, delta);
    }

    private static float stepScale(float current, float delta) {
        float value = Math.round((current + delta) * 10f) / 10f;
        return Math.max(MIN_HUD_SCALE, Math.min(MAX_HUD_SCALE, value));
    }

    public void resetEvents() {
        eventsX = 6;
        eventsY = 60;
        eventsScale = 1.0f;
    }

    public void scaleChatToast(float delta) {
        chatToastScale = stepScale(chatToastScale, delta);
    }

    public void resetChatToast() {
        chatToastX = 6;
        chatToastY = 150;
        chatToastScale = 1.0f;
    }

    /** Cambia el tamano del chat del mod (limitado entre MIN y MAX). */
    public void resizeChat(int deltaW, int deltaH) {
        modChatWidth = Math.max(MIN_CHAT_W, Math.min(MAX_CHAT_W, modChatWidth + deltaW));
        modChatHeight = Math.max(MIN_CHAT_H, Math.min(MAX_CHAT_H, modChatHeight + deltaH));
    }

    public void resetChatSize() {
        modChatWidth = DEFAULT_CHAT_W;
        modChatHeight = DEFAULT_CHAT_H;
    }

    public void resetProfit() {
        profitX = 6;
        profitY = 110;
        profitScale = 1.0f;
    }

    /** Vuelve el botón del chat y todos los elementos en pantalla a su posición y tamaño originales. */
    public void resetPositions() {
        buttonFromRight = 24;
        buttonFromBottom = 40;
        buttonSize = DEFAULT_BUTTON_SIZE;
        hudX = 6;
        hudY = 6;
        resetEvents();
        resetProfit();
        resetChatToast();
        hudScale = 1.0f;
    }

    // Por si alguien edita el archivo a mano con valores absurdos
    private void sanitize() {
        if (commandKeys == null) commandKeys = new ArrayList<>();
        commandKeys.removeIf(k -> k == null);
        for (CommandKey k : commandKeys) k.fix();
        buttonSize = Math.max(MIN_BUTTON_SIZE, Math.min(MAX_BUTTON_SIZE, buttonSize));
        modChatWidth = Math.max(MIN_CHAT_W, Math.min(MAX_CHAT_W, modChatWidth));
        modChatHeight = Math.max(MIN_CHAT_H, Math.min(MAX_CHAT_H, modChatHeight));
        eventsX = Math.max(0, eventsX);
        eventsY = Math.max(0, eventsY);
        profitX = Math.max(0, profitX);
        profitY = Math.max(0, profitY);
        chatToastX = Math.max(0, chatToastX);
        chatToastY = Math.max(0, chatToastY);
        if (Float.isNaN(chatToastScale) || chatToastScale < MIN_HUD_SCALE || chatToastScale > MAX_HUD_SCALE) {
            chatToastScale = 1.0f;
        }
        if (Float.isNaN(eventsScale) || eventsScale < MIN_HUD_SCALE || eventsScale > MAX_HUD_SCALE) {
            eventsScale = 1.0f;
        }
        if (Float.isNaN(profitScale) || profitScale < MIN_HUD_SCALE || profitScale > MAX_HUD_SCALE) {
            profitScale = 1.0f;
        }
        if (Float.isNaN(hudScale) || hudScale < MIN_HUD_SCALE || hudScale > MAX_HUD_SCALE) {
            hudScale = 1.0f;
        }
    }

    private static int clamp(int value, int max) {
        return Math.max(0, Math.min(max, value));
    }

    public static BlacklistConfig load() {
        try {
            if (Files.exists(FILE)) {
                BlacklistConfig cfg = GSON.fromJson(Files.readString(FILE), BlacklistConfig.class);
                if (cfg != null && cfg.mode != null) {
                    cfg.sanitize();
                    return cfg;
                }
            }
        } catch (Exception ignored) {
            // archivo dañado: se usan los valores por defecto
        }
        BlacklistConfig cfg = new BlacklistConfig();
        cfg.save();
        return cfg;
    }

    public void save() {
        try {
            Files.writeString(FILE, GSON.toJson(this));
        } catch (Exception ignored) {
        }
    }
}
