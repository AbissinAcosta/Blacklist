package com.tuusuario.blacklist; // <- ajusta al paquete de tu proyecto

import java.util.List;

/**
 * Datos que muestra la pantalla de inicio del mod: nombre, versión, descripción y changelog.
 *
 * Para anunciar algo nuevo: sube VERSION y añade una Release AL PRINCIPIO de CHANGELOG.
 * Las más recientes salen arriba en la pantalla.
 */
public final class ModInfo {

    public static final String NAME = "Blacklist";
    public static final String VERSION = "1.8";

    public static final String DESCRIPTION =
            "Mod para Hypixel SkyBlock: avisa (o expulsa) a los jugadores de tu blacklist al entrar al party, "
                    + "revisa sus shards y trae utilidades como el timer de eventos, el profit de cofres y atajos de comandos.";

    public enum Kind {
        NEW("Nuevo"), IMPROVED("Mejora"), FIX("Arreglo");

        public final String label;

        Kind(String label) {
            this.label = label;
        }
    }

    public record Change(Kind kind, String text) {
    }

    public record Release(String version, String date, List<Change> changes) {
    }

    public static final List<Release> CHANGELOG = List.of(
            new Release("1.8", "03/10/2026", List.of(
                    new Change(Kind.NEW, "Foto de perfil: sube un PNG desde la pestaña Chat y se muestra en el aviso "
                            + "estilo Discord de los mensajes que envías. El resto de usuarios del mod la ven al recibirlos."),
                    new Change(Kind.NEW, "Pestaña Chat en el GUI: cambia el ancho y el alto del chat del mod."),
                    new Change(Kind.NEW, "Opción \"Mostrar siempre\": el chat del mod (Global o Party, según tu pestaña activa) "
                            + "se dibuja aunque no tengas abierto el chat del juego.")
            )),
            new Release("1.7", "03/10/2026", List.of(
                    new Change(Kind.NEW, "Recorrido de cada jugador en el Dungeon map: elige \"Todos\" o un jugador y se "
                            + "dibuja su camino sobre el mapa de la run. También sigue a los que están lejos usando "
                            + "los marcadores del mapa."),
                    new Change(Kind.NEW, "Aviso de mensajes del chat global con estilo Discord: tarjeta con avatar, nombre "
                            + "y mensaje. Se mueve y cambia de tamaño en Editar GUI o en QoL > Chat."),
                    new Change(Kind.NEW, "Menú de conectados en el chat del mod: muestra cuántos usuarios del mod hay "
                            + "conectados y, al hacer clic, sus nombres de Minecraft."),
                    new Change(Kind.IMPROVED, "Nueva pestaña Dungeons con Profit, Shards y Dungeon map. QoL queda con "
                            + "Eventos, Chat y Atajos."),
                    new Change(Kind.IMPROVED, "El GUI del mod es más grande y muestra más filas por página.")
            )),
            new Release("1.5", "03/10/2026", List.of(
                    new Change(Kind.NEW, "Auto-update: el mod avisa cuando hay una versión nueva y la descarga con "
                            + "un botón en Inicio. Se instala al cerrar el juego.")
            )),
            new Release("1.4", "03/10/2026", List.of(
                    new Change(Kind.NEW, "Emojis en el chat del mod, como en Discord: escribe :smile: o :wilted_flower: "
                            + "y se ven como imagen. Al escribir : y dos letras aparece un menú para autocompletar "
                            + "(flechas, Tab o Enter para elegir)."),
                    new Change(Kind.IMPROVED, "El chat se queda en la pestaña elegida (Global o Party) al abrirlo con T. "
                            + "Ctrl + Tab pasa al siguiente chat.")
            )),
            new Release("1.3", "03/10/2026", List.of(
                    new Change(Kind.NEW, "Chat entre usuarios del mod: /blc <mensaje> para el global y /blp <mensaje> "
                            + "para tu party. Pestañas Chat | Global | Party sobre el chat."),
                    new Change(Kind.IMPROVED, "Los mensajes del mod se ven donde va el chat normal; "
                            + "pulsa la pestaña activa para volver al chat de verdad.")
            )),
            new Release("1.2", "03/10/2026", List.of(
                    new Change(Kind.NEW, "Atajos de comandos (QoL > Atajos): elige una tecla o botón del ratón, "
                            + "el comando y las zonas de SkyBlock donde debe funcionar."),
                    new Change(Kind.IMPROVED, "El mod ahora reconoce la zona de SkyBlock leyendo el Tab y el marcador."),
                    new Change(Kind.FIX, "El profit de cofres ya se ve en el Dungeon Hub y dentro de las dungeons.")
            )),
            new Release("1.1", "02/10/2026", List.of(
                    new Change(Kind.NEW, "Profit de cofres: lee los cofres de Croesus y de la dungeon, los valora con "
                            + "precios de BIN y Bazaar y cuenta el mejor cofre de cada run."),
                    new Change(Kind.NEW, "Los cofres sin abrir se leen del Tab de Hypixel."),
                    new Change(Kind.IMPROVED, "El timer de eventos muestra el evento más próximo "
                            + "(Spooky Festival, New Year, Season of Jerry, Traveling Zoo)."),
                    new Change(Kind.IMPROVED, "El profit solo se ve en el Dungeon Hub y dentro de las dungeons."),
                    new Change(Kind.IMPROVED, "Nueva pantalla de inicio con changelog. Editar GUI ahora está solo aquí.")
            )),
            new Release("1.0", "", List.of(
                    new Change(Kind.NEW, "Blacklist con alertas en pantalla y auto-kick."),
                    new Change(Kind.NEW, "Alertas de shards (Murkbat y Prince) al unirse alguien."),
                    new Change(Kind.NEW, "Timer del Spooky Festival y editor de posiciones.")
            ))
    );

    private ModInfo() {
    }
}
