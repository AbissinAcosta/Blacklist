package com.tuusuario.blacklist.dungeonrun;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decide cuales de las entidades "jugador" del mundo son jugadores de verdad.
 *
 * En una dungeon de Hypixel muchos mobs (Shadow Assassin, Lost Adventurer, Sniper...) son entidades con
 * forma de jugador y aparecen en la lista de jugadores, asi que "esta en el tab" no alcanza. Se combinan tres filtros:
 *  1. UUID version 4: los NPC y mobs de Hypixel usan otra version;
 *  2. el nombre tiene forma de nick de Minecraft (3-16 letras, numeros o _);
 *  3. el nombre esta en la lista de la party que muestra el tab de la dungeon ("[123] Nombre (Mage 45)").
 */
public final class PartyParser {

    private static final Pattern NICK = Pattern.compile("[A-Za-z0-9_]{3,16}");
    private static final Pattern CLASS_TAG = Pattern.compile("\\((?:Archer|Berserk|Healer|Mage|Tank)\\b[^)]*\\)|\\((?:DEAD|EMPTY)\\)");
    private static final Pattern LEVEL_TAG = Pattern.compile("\\[[^\\]]*\\]");

    private PartyParser() {}

    /** Nombre y clase del jugador en una linea del tab de la dungeon, o null si la linea no es de un miembro de la party. */
    public static java.util.Map.Entry<String, String> memberNameAndClass(String tabLine) {
        if (tabLine == null) return null;
        String line = tabLine.replaceAll("\u00a7[0-9a-fk-orA-FK-OR]", "").strip();
        Matcher tag = CLASS_TAG.matcher(line);
        if (!tag.find()) return null;
        String className = null;
        String tagText = tag.group(0);
        if (tagText.contains("Archer")) className = "Archer";
        else if (tagText.contains("Berserk")) className = "Berserk";
        else if (tagText.contains("Healer")) className = "Healer";
        else if (tagText.contains("Mage")) className = "Mage";
        else if (tagText.contains("Tank")) className = "Tank";
        line = line.substring(0, tag.start());
        line = LEVEL_TAG.matcher(line).replaceAll(" ");
        for (String token : line.split("[^A-Za-z0-9_]+")) {
            if (NICK.matcher(token).matches()) return new java.util.AbstractMap.SimpleEntry<>(token, className);
        }
        return null;
    }

    /** Nombre del jugador en una linea del tab de la dungeon, o null si la linea no es de un miembro de la party. */
    public static String memberName(String tabLine) {
        java.util.Map.Entry<String, String> e = memberNameAndClass(tabLine);
        return e == null ? null : e.getKey();
    }

    /**
     * Jugadores VIVOS de la party en el orden del tab. Hypixel dibuja un marcador en el mapa por cada
     * jugador vivo, en ese mismo orden; los muertos ("(DEAD)") no tienen marcador.
     */
    public static java.util.List<String> aliveInOrder(Collection<String> orderedTabLines) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String l : orderedTabLines) {
            String n = memberName(l);
            if (n == null || l.contains("(DEAD)") || out.contains(n)) continue;
            out.add(n);
        }
        return out;
    }

    public static Set<String> members(Collection<String> tabLines) {
        Set<String> out = new LinkedHashSet<>();
        for (String l : tabLines) {
            String n = memberName(l);
            if (n != null) out.add(n);
        }
        return out;
    }

    /**
     * @param party nombres de la party leidos del tab (puede estar vacio si todavia no se leyo)
     * @return true si la entidad cuenta como jugador de la run
     */
    public static boolean isRealPlayer(UUID uuid, String name, Set<String> party, boolean isLocal) {
        if (isLocal) return true;
        if (uuid == null || uuid.version() != 4) return false;
        if (name == null || !NICK.matcher(name).matches()) return false;
        return party.isEmpty() || party.contains(name);
    }
}
