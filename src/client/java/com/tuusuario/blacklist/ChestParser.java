package com.tuusuario.blacklist; // <- ajusta al paquete de tu proyecto

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lee el tooltip de un cofre de dungeon ("Contents" / "Cost") y convierte cada línea del
 * contenido en el id de ítem que usan las APIs de precios. No depende de Minecraft.
 */
public final class ChestParser {

    /** Un cofre ya leído. cost = monedas que cuesta abrirlo (0 si es FREE). */
    public record Chest(String name, List<String> contents, long cost) {
        /** Huella estable del cofre: sirve para reconocer el mismo cofre en la dungeon y en Croesus. */
        public String print() {
            List<String> sorted = new ArrayList<>(contents);
            Collections.sort(sorted);
            return name + "#" + String.join(";", sorted) + "#" + cost;
        }
    }

    /** Una línea del contenido: id para el precio (null si no se supo armar) y cantidad. */
    public record Entry(String id, String text, int count) {
    }

    private static final Pattern COINS = Pattern.compile("^([\\d,\\.]+) Coins?$");
    private static final Pattern QTY = Pattern.compile("^(.*?)(?: x([\\d,]+))?$");
    private static final Pattern BOOK = Pattern.compile("^Enchanted Book \\((.+)\\)$");

    // Encantamientos "ultimate" (en la API llevan ULTIMATE_ delante)
    private static final Set<String> ULTIMATE = Set.of(
            "BANK", "COMBO", "LAST_STAND", "LEGION", "NO_PAIN_NO_GAIN", "ONE_FOR_ALL", "SOUL_EATER",
            "SWARM", "WISDOM", "REND", "CHIMERA", "FATAL_TEMPO", "FLASH", "HABANERO_TACTICS", "INFERNO",
            "REFRIGERATE", "REITERATE", "BOBBIN_TIME", "DUPLEX", "HARDENED_MANA", "THE_ONE");

    // Ítems cuyo id no sale de simplemente poner el nombre en MAYÚSCULAS
    private static final Map<String, String> ALIAS = Map.of(
            "NECRONS_HANDLE", "NECRON_HANDLE",
            "IMPLOSION", "IMPLOSION_SCROLL",
            "WITHER_SHIELD", "WITHER_SHIELD_SCROLL",
            "SHADOW_WARP", "SHADOW_WARP_SCROLL");

    private ChestParser() {
    }

    /** Devuelve el cofre, o null si el tooltip no es de un cofre con Contents + Cost conocidos. */
    public static Chest parse(List<String> lines) {
        if (lines == null || lines.isEmpty()) return null;

        int i = 0;
        while (i < lines.size() && !lines.get(i).strip().equals("Contents")) i++;
        if (i >= lines.size()) return null;

        List<String> contents = new ArrayList<>();
        for (i++; i < lines.size(); i++) {
            String l = lines.get(i).strip();
            if (l.isEmpty()) break;
            contents.add(l);
        }

        long cost = -1;
        for (; i < lines.size(); i++) {
            if (lines.get(i).strip().equals("Cost")) {
                for (int j = i + 1; j < lines.size(); j++) {
                    String c = lines.get(j).strip();
                    if (c.isEmpty()) continue;
                    cost = parseCost(c);
                    break;
                }
                break;
            }
        }

        if (contents.isEmpty() || cost < 0) return null;
        return new Chest(lines.get(0).strip(), contents, cost);
    }

    private static long parseCost(String text) {
        if (text.equalsIgnoreCase("FREE")) return 0;
        Matcher m = COINS.matcher(text);
        if (!m.matches()) return -1;
        try {
            return Long.parseLong(m.group(1).replace(",", "").replace(".", ""));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public static Entry entry(String line) {
        Matcher q = QTY.matcher(line.strip());
        String name = line.strip();
        int count = 1;
        if (q.matches()) {
            name = q.group(1).strip();
            if (q.group(2) != null) {
                try {
                    count = Integer.parseInt(q.group(2).replace(",", ""));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return new Entry(idFor(name), name, count);
    }

    static String idFor(String name) {
        Matcher book = BOOK.matcher(name);
        if (book.matches()) {
            String inner = book.group(1).strip();
            int space = inner.lastIndexOf(' ');
            if (space < 0) return null;
            int level = roman(inner.substring(space + 1));
            if (level <= 0) return null;
            String ench = norm(inner.substring(0, space));
            boolean ultimate = ULTIMATE.contains(ench) || ench.startsWith("ULTIMATE_");
            if (ultimate && !ench.startsWith("ULTIMATE_")) ench = "ULTIMATE_" + ench;
            return "ENCHANTMENT_" + ench + "_" + level;
        }
        if (name.endsWith(" Essence")) {
            return "ESSENCE_" + norm(name.substring(0, name.length() - " Essence".length()));
        }
        String id = norm(name);
        return ALIAS.getOrDefault(id, id);
    }

    private static String norm(String s) {
        return s.toUpperCase(Locale.ROOT)
                .replace("'", "").replace("’", "")
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
    }

    static int roman(String s) {
        int total = 0;
        int prev = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            int v = switch (s.charAt(i)) {
                case 'I' -> 1;
                case 'V' -> 5;
                case 'X' -> 10;
                default -> -1;
            };
            if (v < 0) return -1;
            total += v < prev ? -v : v;
            prev = Math.max(prev, v);
        }
        return total;
    }
}
