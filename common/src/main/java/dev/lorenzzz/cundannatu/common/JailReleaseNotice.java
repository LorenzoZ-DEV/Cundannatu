package dev.lorenzzz.cundannatu.common;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Notifica inviata dal proxy al server jail quando un ban sparisce senza che sia
 * stato il plugin a rimuoverlo: {@code /unban} dello staff, acquisto sullo store,
 * appeal accolto o scadenza registrata da LiteBans.
 * <p>
 * Serve quando LiteBans gira solo sul proxy: il server jail non puo' interrogare i
 * ban da solo, quindi senza questo messaggio il detenuto resterebbe chiuso dentro.
 */
public record JailReleaseNotice(String type, String uuid, String name, String reason) {

    public static JailReleaseNotice of(UUID uuid, String name, String reason) {
        return new JailReleaseNotice(JailMessages.TYPE_RELEASE, uuid.toString(), name,
                reason == null || reason.isBlank() ? "Ban rimosso" : reason);
    }

    public byte[] toBytes() {
        return JailMessages.GSON.toJson(this).getBytes(StandardCharsets.UTF_8);
    }

    public static JailReleaseNotice fromBytes(byte[] data) {
        return JailMessages.GSON.fromJson(new String(data, StandardCharsets.UTF_8), JailReleaseNotice.class);
    }

    public UUID uniqueId() {
        return UUID.fromString(uuid);
    }

    @Override
    public String toString() {
        return JailMessages.GSON.toJson(this);
    }
}
