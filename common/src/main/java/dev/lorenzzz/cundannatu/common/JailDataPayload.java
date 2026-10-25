package dev.lorenzzz.cundannatu.common;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Payload JSON inviato dal proxy al server jail sul canale {@code jailmining:data}.
 * <p>
 * Contiene UUID, motivo del ban, durata originale e durata residua in millisecondi.
 */
public record JailDataPayload(String type,
                              String uuid,
                              String name,
                              String reason,
                              long originalDuration,
                              long remainingDuration) {

    public static JailDataPayload of(UUID uuid, String name, String reason,
                                     long originalDuration, long remainingDuration) {
        return new JailDataPayload(JailMessages.TYPE_JAIL, uuid.toString(), name,
                reason == null ? "Nessun motivo" : reason,
                originalDuration, remainingDuration);
    }

    public byte[] toBytes() {
        return JailMessages.GSON.toJson(this).getBytes(StandardCharsets.UTF_8);
    }

    public static JailDataPayload fromBytes(byte[] data) {
        return JailMessages.GSON.fromJson(new String(data, StandardCharsets.UTF_8), JailDataPayload.class);
    }

    @Override
    public String toString() {
        return JailMessages.GSON.toJson(this);
    }
}
