package com.tuusuario.blacklist; // <- ajusta al paquete de tu proyecto

import com.mojang.blaze3d.platform.InputConstants;

import java.util.ArrayList;
import java.util.List;

/**
 * Un atajo: al pulsar una tecla (o un botón del ratón) se manda un comando al servidor.
 * Se guarda tal cual en blacklist.json, por eso los campos son públicos y simples.
 *
 *  - key / mouse: código GLFW. mouse = true significa botón del ratón (Mouse 5 = 4 en GLFW).
 *  - command: sin la barra inicial (con ella también vale).
 *  - zones: nombres de Zone donde funciona. Vacío = en todas partes.
 */
public class CommandKey {

    public String command = "";
    public int key = -1;
    public boolean mouse = false;
    public List<String> zones = new ArrayList<>();

    // Estado en ejecución (transient: Gson no lo guarda)
    transient boolean wasDown;
    transient long lastFire;

    /** ¿Debe funcionar en la zona actual? current puede ser null si no se pudo detectar. */
    public boolean allows(Zone current) {
        if (zones.isEmpty()) return true;
        return current != null && zones.contains(current.name());
    }

    public boolean hasZone(Zone zone) {
        return zones.contains(zone.name());
    }

    public void setZone(Zone zone, boolean on) {
        if (on) {
            if (!zones.contains(zone.name())) zones.add(zone.name());
        } else {
            zones.remove(zone.name());
        }
    }

    /** Nombre de la tecla para mostrar en pantalla. */
    public String keyName() {
        if (key < 0) return "Sin tecla";
        InputConstants.Key k = mouse
                ? InputConstants.Type.MOUSE.getOrCreate(key)
                : InputConstants.Type.KEYSYM.getOrCreate(key);
        return k.getDisplayName().getString();
    }

    /** Por si alguien edita el archivo a mano. */
    void fix() {
        if (command == null) command = "";
        if (zones == null) zones = new ArrayList<>();
        zones.removeIf(n -> n == null || !Zone.exists(n));
    }
}
