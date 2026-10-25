package dev.lorenzzz.cundannatu.spigot.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility di formattazione testo condivisa da scoreboard, tab, chat e messaggi.
 * <p>
 * Le due sintassi possono convivere <b>nella stessa riga</b>: i codici legacy
 * ({@code &c}, {@code &l}, {@code &#RRGGBB}) vengono tradotti nei corrispondenti tag
 * MiniMessage e poi l intera riga passa da un unico parse. Cosi funziona anche una
 * riga come:
 * <pre>{@code <shadow:#000000:1>&#9863E7&lVENRIX}</pre>
 * che con due parser separati avrebbe lasciato a vista meta' dei codici.
 * <p>
 * I tag MiniMessage sconosciuti (ad esempio {@code <head:...>} del plugin TAB) non
 * vengono interpretati e restano nel testo: non sono placeholder di questo plugin.
 */
public final class Text {

    /** &#RRGGBB scritto a mano in configurazione. */
    private static final Pattern LEGACY_HEX = Pattern.compile("&#([A-Fa-f0-9]{6})");

    /**
     * §x§R§R§G§G§B§B, il formato che il client usa davvero.
     * I sei gruppi sono scritti per esteso di proposito: in Java un gruppo dentro una
     * ripetizione {@code (?:...){6}} resta un gruppo solo e {@code group(2)} esplode.
     */
    private static final Pattern SECTION_HEX = Pattern.compile(
            "§x§([A-Fa-f0-9])§([A-Fa-f0-9])§([A-Fa-f0-9])§([A-Fa-f0-9])§([A-Fa-f0-9])§([A-Fa-f0-9])");
    private static final Pattern LEGACY_CODE = Pattern.compile("[&§]([0-9A-Fa-fK-Ok-oRr])");

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /**
     * Serializer legacy con esadecimali nel formato §x§R§R§G§G§B§B, l unico che il
     * client accetta: senza {@code hexColors()} i gradienti verrebbero ridotti ai
     * 16 colori classici.
     */
    private static final LegacyComponentSerializer SECTION = LegacyComponentSerializer.builder()
            .character('§')
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();

    private Text() {
    }

    /** true se la riga contiene almeno un tag in stile MiniMessage. */
    public static boolean isMiniMessage(String input) {
        return input != null && input.indexOf('<') >= 0 && input.indexOf('>') > input.indexOf('<');
    }

    /**
     * Converte i codici legacy nei tag MiniMessage equivalenti, lasciando intatto
     * tutto il resto (tag gia' presenti compresi).
     */
    public static String legacyToMiniMessage(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }

        // 1) esadecimali nel formato del client: §x§9§D§4§E§D§D -> <#9D4EDD>
        Matcher sectionHex = SECTION_HEX.matcher(input);
        StringBuilder step = new StringBuilder(input.length());
        while (sectionHex.find()) {
            StringBuilder hex = new StringBuilder("<#");
            for (int group = 1; group <= 6; group++) {
                hex.append(sectionHex.group(group));
            }
            sectionHex.appendReplacement(step, hex.append('>').toString());
        }
        sectionHex.appendTail(step);

        // 2) esadecimali scritti a mano: &#9D4EDD -> <#9D4EDD>
        Matcher hexMatcher = LEGACY_HEX.matcher(step.toString());
        StringBuilder result = new StringBuilder(step.length());
        while (hexMatcher.find()) {
            hexMatcher.appendReplacement(result, "<#" + hexMatcher.group(1) + ">");
        }
        hexMatcher.appendTail(result);

        // 3) codici classici: &c -> <red>, &l -> <bold>, &r -> <reset>
        Matcher codeMatcher = LEGACY_CODE.matcher(result.toString());
        StringBuilder finalText = new StringBuilder(result.length());
        while (codeMatcher.find()) {
            String tag = tagFor(codeMatcher.group(1).toLowerCase(Locale.ROOT));
            codeMatcher.appendReplacement(finalText, Matcher.quoteReplacement(tag));
        }
        codeMatcher.appendTail(finalText);

        return finalText.toString();
    }

    /** Tag MiniMessage corrispondente a un codice colore classico. */
    private static String tagFor(String code) {
        return switch (code) {
            case "0" -> "<black>";
            case "1" -> "<dark_blue>";
            case "2" -> "<dark_green>";
            case "3" -> "<dark_aqua>";
            case "4" -> "<dark_red>";
            case "5" -> "<dark_purple>";
            case "6" -> "<gold>";
            case "7" -> "<gray>";
            case "8" -> "<dark_gray>";
            case "9" -> "<blue>";
            case "a" -> "<green>";
            case "b" -> "<aqua>";
            case "c" -> "<red>";
            case "d" -> "<light_purple>";
            case "e" -> "<yellow>";
            case "f" -> "<white>";
            case "k" -> "<obfuscated>";
            case "l" -> "<bold>";
            case "m" -> "<strikethrough>";
            case "n" -> "<underlined>";
            case "o" -> "<italic>";
            case "r" -> "<reset>";
            default -> "";
        };
    }

    /** Traduce solo i codici legacy (compatibilita': non tocca i tag MiniMessage). */
    public static String color(String input) {
        return legacy(input);
    }

    /** Converte una riga di configurazione in {@link Component}. */
    public static Component component(String input) {
        if (input == null || input.isEmpty()) {
            return Component.empty();
        }
        return MINI.deserialize(legacyToMiniMessage(input));
    }

    /** Come {@link #component(String)} ma applicando prima i placeholder. */
    public static Component component(String input, Map<String, String> placeholders) {
        return component(replace(input, placeholders));
    }

    /**
     * Testo in formato legacy (con i {@code §}), per le API che accettano solo stringhe:
     * lore degli item, formato della chat e messaggi di kick.
     * <p>
     * I tag vengono comunque interpretati; si perdono soltanto click, hover e ombre,
     * che il formato legacy non sa rappresentare.
     */
    public static String legacy(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        return SECTION.serialize(component(input));
    }

    /** Serializza in legacy un Component gia' costruito, senza rifare il parsing. */
    public static String toLegacy(Component component) {
        return component == null ? "" : SECTION.serialize(component);
    }

    /** Sostituisce i placeholder ({@code {chiave}}) e restituisce testo legacy. */
    public static String format(String input, Map<String, String> placeholders) {
        return legacy(replace(input, placeholders));
    }

    /** Versione su lista: utile per le righe di scoreboard e tab. */
    public static List<String> format(List<String> input, Map<String, String> placeholders) {
        List<String> result = new ArrayList<>(input == null ? 0 : input.size());
        if (input == null) {
            return result;
        }
        for (String line : input) {
            result.add(format(line, placeholders));
        }
        return result;
    }

    /** Lista di Component, per scoreboard e tab in versione Adventure. */
    public static List<Component> components(List<String> input, Map<String, String> placeholders) {
        List<Component> result = new ArrayList<>(input == null ? 0 : input.size());
        if (input == null) {
            return result;
        }
        for (String line : input) {
            result.add(component(line, placeholders));
        }
        return result;
    }

    /** Unisce piu' righe in un unico testo multilinea (header/footer del tab). */
    public static String join(List<String> lines, Map<String, String> placeholders) {
        return String.join("\n", format(lines, placeholders));
    }

    /** Unisce piu' righe in un unico {@link Component} multilinea. */
    public static Component joinComponent(List<String> lines, Map<String, String> placeholders) {
        if (lines == null || lines.isEmpty()) {
            return Component.empty();
        }
        Component result = Component.empty();
        for (int index = 0; index < lines.size(); index++) {
            if (index > 0) {
                result = result.append(Component.newline());
            }
            result = result.append(component(lines.get(index), placeholders));
        }
        return result;
    }

    /**
     * Component pensato per nome e lore degli item: disattiva il corsivo che
     * Minecraft applica di default alle scritte personalizzate.
     */
    public static Component itemComponent(String input, Map<String, String> placeholders) {
        return component(input, placeholders).decoration(TextDecoration.ITALIC, false);
    }

    private static String replace(String input, Map<String, String> placeholders) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        String result = input;
        if (placeholders != null) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                result = result.replace("{" + entry.getKey() + "}", entry.getValue());
            }
        }
        return result;
    }

    /**
     * Barra di progresso testuale.
     *
     * @param current  valore attuale
     * @param max      valore massimo (se {@code <= 0} la barra risulta piena)
     * @param length   numero di caratteri totali
     * @param symbol   carattere ripetuto
     * @param filled   colore della parte completata
     * @param empty    colore della parte mancante
     */
    public static String progressBar(double current, double max, int length,
                                     String symbol, String filled, String empty) {
        int total = Math.max(1, length);
        int done = max <= 0D ? total : (int) Math.round(Math.min(1.0D, current / max) * total);
        done = Math.max(0, Math.min(total, done));
        // La barra viene poi inserita dentro altre righe: restiamo su testo grezzo
        return filled + symbol.repeat(done) + empty + symbol.repeat(total - done);
    }

    /** Taglia una stringa aggiungendo i puntini di sospensione. */
    public static String shorten(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, Math.max(1, max - 3)) + "...";
    }
}
