package dev.lorenzzz.cundannatu.common;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Richiesta di sban inviata dal backend al proxy sul canale {@code jailmining:data}.
 * <p>
 * Serve quando LiteBans e' installato solo sul proxy: il server jail non puo'
 * eseguire {@code /ltunban} in locale, quindi delega l operazione alla console
 * del proxy.
 */
public record JailUnbanRequest(String type, String uuid, String name, String reason, String command) {

    public static JailUnbanRequest of(UUID uuid, String name, String reason) {
        return of(uuid, name, reason, null);
    }

    /**
     * @param command comando gia' formattato che il proxy deve eseguire in console
     *                (arriva da {@code jail-mining.unban.command} del server jail);
     *                se null il proxy usa il proprio comando di riserva
     */
    public static JailUnbanRequest of(UUID uuid, String name, String reason, String command) {
        return new JailUnbanRequest(JailMessages.TYPE_UNBAN, uuid.toString(), name,
                reason == null || reason.isBlank() ? "Pena scontata con il jail mining" : reason,
                command);
    }

    public byte[] toBytes() {
        return JailMessages.GSON.toJson(this).getBytes(StandardCharsets.UTF_8);
    }

    public static JailUnbanRequest fromBytes(byte[] data) {
        return JailMessages.GSON.fromJson(new String(data, StandardCharsets.UTF_8), JailUnbanRequest.class);
    }

    public UUID uniqueId() {
        return UUID.fromString(uuid);
    }

    @Override
    public String toString() {
        return JailMessages.GSON.toJson(this);
    }
}
