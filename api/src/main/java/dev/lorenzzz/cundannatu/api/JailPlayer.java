package dev.lorenzzz.cundannatu.api;

import java.util.UUID;

/**
 * Vista in sola lettura di un detenuto.
 * <p>
 * Questa e' l interfaccia esposta agli altri plugin: l implementazione concreta
 * (il POJO persistito su MySQL) vive nel modulo common e non fa parte del contratto.
 */
public interface JailPlayer {

    /** UUID del giocatore detenuto. */
    UUID getUuid();

    /** Motivo del ban cosi come registrato da LiteBans. */
    String getBanReason();

    /** Durata totale della pena in millisecondi (10 anni se il ban era permanente). */
    long getOriginalDuration();

    /** Millisecondi ancora da scontare. */
    long getCurrentRemaining();

    /** Crediti accumulati minando il carbone. */
    int getJailCredits();

    /**
     * Blocchi di carbone scavati in totale.
     * <p>
     * I crediti maturano solo al raggiungimento della soglia configurata
     * ({@code jail-mining.blocks-per-credit}), quindi questo contatore e' il vero
     * indicatore del lavoro svolto dal detenuto.
     */
    default long getBlocksMined() {
        return 0L;
    }

    /** Timestamp (epoch millis) del primo ingresso nel server jail. */
    long getJoinTime();

    /** true se il tempo residuo e' esaurito. */
    boolean isSentenceServed();

    /** Percentuale di pena scontata, da 0.0 a 1.0. */
    double getProgress();

    /** Tempo residuo gia' formattato, es. {@code "3a 2g 4h 12m 30s"}. */
    String getFormattedRemaining();
}
