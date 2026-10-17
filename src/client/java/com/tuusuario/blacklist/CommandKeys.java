package com.tuusuario.blacklist; // <- ajusta al paquete de tu proyecto

import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/**
 * Atajos de teclado que mandan un comando (QoL > Atajos).
 *
 * Cada tick mira si alguna de las teclas configuradas se acaba de pulsar. Dispara una sola vez
 * por pulsación (no repite si la mantienes), solo con el juego en pantalla (no con el chat ni
 * un menú abierto), solo si la zona actual está permitida y respetando un pequeño cooldown.
 */
public final class CommandKeys {

    private static final long COOLDOWN_MS = 400;

    private CommandKeys() {
    }

    public static void tick(Minecraft mc, BlacklistConfig config) {
        if (!config.commandKeysEnabled || mc.player == null) return;

        long window = mc.getWindow().handle();
        boolean screenOpen = mc.screen != null;
        Zone zone = Location.current();
        long now = System.currentTimeMillis();

        for (CommandKey k : config.commandKeys) {
            if (k.key < 0) continue;

            boolean down = isDown(window, k);
            boolean pressed = down && !k.wasDown;
            k.wasDown = down; // se actualiza siempre, así una tecla mantenida al cerrar un menú no dispara

            if (!pressed || screenOpen) continue;
            if (!k.allows(zone)) continue;
            if (now - k.lastFire < COOLDOWN_MS) continue;

            String command = k.command.strip();
            while (command.startsWith("/")) command = command.substring(1).strip();
            if (command.isEmpty()) continue;

            k.lastFire = now;
            mc.player.connection.sendCommand(command);
        }
    }

    private static boolean isDown(long window, CommandKey k) {
        int state = k.mouse
                ? GLFW.glfwGetMouseButton(window, k.key)
                : GLFW.glfwGetKey(window, k.key);
        return state == GLFW.GLFW_PRESS;
    }
}
