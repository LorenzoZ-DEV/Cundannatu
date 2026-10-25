package dev.lorenzzz.cundannatu.common;

import dev.lorenzzz.cundannatu.api.JailPlayer;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * POJO che rappresenta una riga della tabella {@code jailed_players}.
 * Viene usato sia come modello di persistenza sia come oggetto di cache in memoria.
 */
public class JailPlayerData implements JailPlayer {

    private final UUID uuid;
    private String banReason;
    /** Durata originale del ban in millisecondi (10 anni se il ban era permanente). */
    private long originalDuration;
    /** Tempo residuo in millisecondi: viene decrementato scavando carbone. */
    private long currentRemaining;
    /** Crediti accumulati minando: a 50 il giocatore ottiene la liberta. */
    private int jailCredits;
    /** Blocchi di carbone scavati in totale: i crediti maturano solo a soglia raggiunta. */
    private long blocksMined;
    /** Timestamp (epoch millis) del primo ingresso nel server jail. */
    private long joinTime;

    /** Flag interno di cache: true se i dati vanno ancora scritti sul database. */
    private transient volatile boolean dirty;

    public JailPlayerData(UUID uuid, String banReason, long originalDuration,
                          long currentRemaining, int jailCredits, long joinTime) {
        this(uuid, banReason, originalDuration, currentRemaining, jailCredits, 0L, joinTime);
    }

    public JailPlayerData(UUID uuid, String banReason, long originalDuration,
                          long currentRemaining, int jailCredits, long blocksMined, long joinTime) {
        this.uuid = uuid;
        this.banReason = banReason;
        this.originalDuration = originalDuration;
        this.currentRemaining = currentRemaining;
        this.jailCredits = jailCredits;
        this.blocksMined = blocksMined;
        this.joinTime = joinTime;
    }

    /** Costruttore comodo per un nuovo detenuto appena arrivato. */
    public JailPlayerData(UUID uuid, String banReason, long duration) {
        this(uuid, banReason, duration, duration, 0, System.currentTimeMillis());
    }

    @Override
    public UUID getUuid() {
        return uuid;
    }

    @Override
    public String getBanReason() {
        return banReason;
    }

    public void setBanReason(String banReason) {
        this.banReason = banReason;
    }

    @Override
    public long getOriginalDuration() {
        return originalDuration;
    }

    public void setOriginalDuration(long originalDuration) {
        this.originalDuration = originalDuration;
    }

    @Override
    public long getCurrentRemaining() {
        return currentRemaining;
    }

    public void setCurrentRemaining(long currentRemaining) {
        this.currentRemaining = Math.max(0L, currentRemaining);
    }

    /** Riduce il tempo residuo (mai sotto zero) e ritorna il nuovo valore. */
    public long reduceRemaining(long millis) {
        this.currentRemaining = Math.max(0L, this.currentRemaining - millis);
        return this.currentRemaining;
    }

    @Override
    public int getJailCredits() {
        return jailCredits;
    }

    public void setJailCredits(int jailCredits) {
        this.jailCredits = Math.max(0, jailCredits);
    }

    /** Incrementa i crediti e ritorna il nuovo totale. */
    public int addCredits(int amount) {
        this.jailCredits += amount;
        return this.jailCredits;
    }

    @Override
    public long getBlocksMined() {
        return blocksMined;
    }

    public void setBlocksMined(long blocksMined) {
        this.blocksMined = Math.max(0L, blocksMined);
    }

    /**
     * Registra un blocco scavato e assegna i crediti solo quando si raggiunge la soglia.
     * <p>
     * Esempio con {@code blocksPerCredit = 500}: i primi 499 blocchi non danno nulla,
     * il cinquecentesimo assegna {@code creditsPerThreshold} crediti.
     *
     * @param blocksPerCredit     blocchi necessari per maturare i crediti (minimo 1)
     * @param creditsPerThreshold crediti assegnati al raggiungimento della soglia
     * @return crediti effettivamente assegnati con questo blocco (0 nella maggior parte dei casi)
     */
    public int mineBlock(long blocksPerCredit, int creditsPerThreshold) {
        long threshold = Math.max(1L, blocksPerCredit);
        this.blocksMined++;
        if (this.blocksMined % threshold != 0L) {
            return 0;
        }
        this.jailCredits += creditsPerThreshold;
        return creditsPerThreshold;
    }

    /** Blocchi ancora da scavare prima del prossimo credito. */
    public long getBlocksToNextCredit(long blocksPerCredit) {
        long threshold = Math.max(1L, blocksPerCredit);
        return threshold - (this.blocksMined % threshold);
    }

    /** Progresso verso il prossimo credito, da 0.0 a 1.0. */
    public double getCreditProgress(long blocksPerCredit) {
        long threshold = Math.max(1L, blocksPerCredit);
        return (this.blocksMined % threshold) / (double) threshold;
    }

    @Override
    public long getJoinTime() {
        return joinTime;
    }

    public void setJoinTime(long joinTime) {
        this.joinTime = joinTime;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void setDirty(boolean dirty) {
        this.dirty = dirty;
    }

    /** true se la pena e' scontata (tempo residuo esaurito). */
    @Override
    public boolean isSentenceServed() {
        return currentRemaining <= 0L;
    }

    /** Percentuale di pena scontata, 0.0 - 1.0 (utile per la scoreboard). */
    @Override
    public double getProgress() {
        if (originalDuration <= 0L) {
            return 1.0D;
        }
        double done = (double) (originalDuration - currentRemaining) / (double) originalDuration;
        return Math.max(0.0D, Math.min(1.0D, done));
    }

    /** Formatta il tempo residuo in formato leggibile: 3a 2g 4h 12m 30s. */
    @Override
    public String getFormattedRemaining() {
        return formatDuration(currentRemaining);
    }

    /** Formatta una durata in millisecondi in stringa leggibile italiana. */
    public static String formatDuration(long millis) {
        if (millis <= 0L) {
            return "scontato";
        }
        long totalSeconds = TimeUnit.MILLISECONDS.toSeconds(millis);
        long years = totalSeconds / 31_536_000L;
        totalSeconds %= 31_536_000L;
        long days = totalSeconds / 86_400L;
        totalSeconds %= 86_400L;
        long hours = totalSeconds / 3_600L;
        totalSeconds %= 3_600L;
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;

        StringBuilder sb = new StringBuilder();
        if (years > 0) sb.append(years).append("a ");
        if (days > 0) sb.append(days).append("g ");
        if (hours > 0) sb.append(hours).append("h ");
        if (minutes > 0) sb.append(minutes).append("m ");
        sb.append(seconds).append("s");
        return sb.toString().trim();
    }

    @Override
    public String toString() {
        return "JailPlayerData{uuid=" + uuid
                + ", reason=" + banReason
                + ", original=" + originalDuration
                + ", remaining=" + currentRemaining
                + ", credits=" + jailCredits
                + ", blocksMined=" + blocksMined
                + ", joinTime=" + joinTime + "}";
    }
}
