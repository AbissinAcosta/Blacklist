package com.tuusuario.blacklist; // <- ajusta al paquete de tu proyecto

import com.google.gson.Gson;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Emojis estilo Discord (:smile:, :wilted_flower:, ...).
 *
 * Los mensajes viajan como texto normal (con el :nombre:). Al mostrarlos se cambia cada :nombre:
 * por un carácter de uso privado que la fuente de Minecraft dibuja como imagen (los registra
 * tools/generate_emojis.py en assets/minecraft/font/default.json). Así funciona en cualquier
 * texto del juego: el chat del mod, los avisos en pantalla y el chat normal.
 *
 * Los guiones valen igual que los guiones bajos (:wilted-flower: = :wilted_flower:).
 * Si todavía no has generado los emojis, todo esto no hace nada y el texto queda como está.
 */
public final class Emojis {

    // Mismo id que en fabric.mod.json
    private static final String RESOURCE = "/assets/shitterlist/emoji/emojis.json";
    private static final int MAX_NAME = 64;

    public record Entry(String name, String glyph) {
    }

    // Formato de emojis.json (lo escribe generate_emojis.py)
    private static class FileData {
        List<Item> emojis;
    }

    private static class Item {
        String c;       // carácter de la fuente
        String u;       // el emoji Unicode
        List<String> n; // nombres
    }

    private static final Map<String, String> BY_NAME = new HashMap<>();
    private static final Map<String, String> BY_UNICODE = new HashMap<>();
    private static final List<Entry> ALL = new ArrayList<>();
    private static int maxUnicodeLen = 1;

    static {
        load();
    }

    private Emojis() {
    }

    private static void load() {
        try (InputStream in = Emojis.class.getResourceAsStream(RESOURCE)) {
            if (in == null) return; // todavía no se han generado
            FileData data = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), FileData.class);
            if (data == null || data.emojis == null) return;

            for (Item item : data.emojis) {
                if (item == null || item.c == null || item.n == null) continue;

                if (item.u != null) {
                    String key = item.u.replace("\uFE0F", "");
                    if (!key.isEmpty()) {
                        BY_UNICODE.putIfAbsent(key, item.c);
                        maxUnicodeLen = Math.max(maxUnicodeLen, key.length());
                    }
                }
                for (String n : item.n) {
                    String name = normalize(n);
                    if (name.isEmpty() || BY_NAME.containsKey(name)) continue;
                    BY_NAME.put(name, item.c);
                    ALL.add(new Entry(name, item.c));
                }
            }
            ALL.sort(Comparator.comparing(Entry::name));
        } catch (Exception ignored) {
            // sin emojis: el chat funciona igual
        }
    }

    /** ¿Hay emojis cargados? */
    public static boolean available() {
        return !ALL.isEmpty();
    }

    /** Minúsculas y con guiones bajos: "Wilted-Flower" -> "wilted_flower". */
    static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT).replace('-', '_').strip();
    }

    private static boolean isNameChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '_' || c == '-' || c == '+';
    }

    /** Cambia los :nombres: (y los emojis Unicode pegados) por el carácter que dibuja la imagen. */
    public static String replace(String text) {
        if (text == null || text.isEmpty() || BY_NAME.isEmpty()) return text;
        return replaceUnicode(replaceShortcodes(text));
    }

    private static String replaceShortcodes(String text) {
        if (text.indexOf(':') < 0) return text;

        StringBuilder sb = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == ':') {
                int j = i + 1;
                while (j < text.length() && j - i <= MAX_NAME && isNameChar(text.charAt(j))) j++;
                if (j < text.length() && j > i + 1 && text.charAt(j) == ':') {
                    String glyph = BY_NAME.get(normalize(text.substring(i + 1, j)));
                    if (glyph != null) {
                        sb.append(glyph);
                        i = j + 1;
                        continue;
                    }
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    private static String replaceUnicode(String text) {
        // Rápido: si no hay nada fuera de ASCII (salvo teclas "1️⃣"), no hay nada que cambiar
        boolean any = false;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) >= 0x2000) {
                any = true;
                break;
            }
        }
        if (!any) return text;

        String s = text.replace("\uFE0F", "");
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            // Solo se prueba donde puede empezar un emoji (evita cambiar © ® ™ y similares de texto)
            boolean candidate = c >= 0x2000
                    || (i + 1 < s.length() && s.charAt(i + 1) == '\u20E3'); // 1️⃣ 2️⃣ ...
            if (candidate) {
                int max = Math.min(maxUnicodeLen, s.length() - i);
                String hit = null;
                int len = 0;
                for (int l = max; l >= 1; l--) {
                    String glyph = BY_UNICODE.get(s.substring(i, i + l));
                    if (glyph != null) {
                        hit = glyph;
                        len = l;
                        break;
                    }
                }
                if (hit != null) {
                    sb.append(hit);
                    i += len;
                    continue;
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    /**
     * Sugerencias para el autocompletado: primero los nombres que EMPIEZAN con lo escrito y
     * luego los que lo CONTIENEN. Un mismo emoji sale una sola vez.
     */
    public static List<Entry> suggest(String typed, int max) {
        List<Entry> out = new ArrayList<>();
        String p = normalize(typed);
        if (p.isEmpty() || ALL.isEmpty()) return out;

        Set<String> seen = new HashSet<>();
        for (Entry e : ALL) {
            if (e.name().startsWith(p) && seen.add(e.glyph())) {
                out.add(e);
                if (out.size() >= max) return out;
            }
        }
        for (Entry e : ALL) {
            if (e.name().contains(p) && seen.add(e.glyph())) {
                out.add(e);
                if (out.size() >= max) return out;
            }
        }
        return out;
    }
}
