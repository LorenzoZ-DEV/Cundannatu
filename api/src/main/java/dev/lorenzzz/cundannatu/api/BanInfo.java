package dev.lorenzzz.cundannatu.api;

import java.util.UUID;

/**
 * Fotografia di un ban attivo letto dalle API di LiteBans.
 *
 * @param uuid             giocatore bannato
 * @param reason           motivo del ban
 * @param originalDuration durata totale in millisecondi
 * @param remaining        millisecondi mancanti alla scadenza
 * @param permanent        true se il ban non ha scadenza
 */
public record BanInfo(UUID uuid,
                      String reason,
                      long originalDuration,
                      long remaining,
                      boolean permanent) {
}
