package dev.lorenzzz.cundannatu.common;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;

/**
 * Discriminatore dei messaggi che viaggiano sul canale {@code jailmining:data}.
 * <p>
 * Il canale e' bidirezionale:
 * <ul>
 *     <li>{@link #TYPE_JAIL}: proxy -> backend, dati del ban appena intercettato</li>
 *     <li>{@link #TYPE_UNBAN}: backend -> proxy, richiesta di sban da eseguire con
 *         {@code /ltunban} sull istanza LiteBans del proxy</li>
 *     <li>{@link #TYPE_RELEASE}: proxy -> backend, il ban e' stato rimosso da fuori
 *         (staff, store, appeal) e il detenuto va liberato subito</li>
 * </ul>
 */
public final class JailMessages {

    public static final String TYPE_JAIL = "jail";
    public static final String TYPE_UNBAN = "unban";
    public static final String TYPE_RELEASE = "release";

    static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private JailMessages() {
    }

    /** Legge il campo {@code type} del payload, con fallback su {@link #TYPE_JAIL}. */
    public static String typeOf(byte[] data) {
        try {
            JsonObject object = JsonParser.parseString(new String(data, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            if (object.has("type") && !object.get("type").isJsonNull()) {
                return object.get("type").getAsString();
            }
        } catch (Exception ignored) {
            // payload non valido: lo tratteremo come messaggio jail e verra' scartato a valle
        }
        return TYPE_JAIL;
    }
}
