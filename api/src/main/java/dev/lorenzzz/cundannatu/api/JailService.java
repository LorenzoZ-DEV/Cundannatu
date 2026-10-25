package dev.lorenzzz.cundannatu.api;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Contratto pubblico del sistema jail.
 * <p>
 * Tutte le operazioni che toccano il database sono asincrone e ritornano un
 * {@link CompletableFuture}: nessun metodo di questa interfaccia va atteso
 * sul thread principale del server.
 * <p>
 * Si ottiene con {@link JailProvider#get()} oppure, su Bukkit, dal
 * {@code ServicesManager}.
 */
public interface JailService {

    /**
     * Carica il detenuto dal database (o dalla cache se ancora valida).
     * Il future si completa con {@link Optional#empty()} se il giocatore non e' detenuto.
     */
    CompletableFuture<Optional<JailPlayer>> getJailedPlayer(UUID uuid);

    /** Lettura immediata dalla sola cache, senza query. */
    Optional<JailPlayer> getCachedJailedPlayer(UUID uuid);

    /** true se il giocatore risulta detenuto secondo la cache. */
    boolean isJailed(UUID uuid);

    /**
     * Incarcera un giocatore.
     *
     * @param uuid           giocatore da incarcerare
     * @param reason         motivo, usato se LiteBans non ha un ban attivo
     * @param durationMillis durata di fallback in millisecondi
     * @return il detenuto appena registrato
     */
    CompletableFuture<JailPlayer> jail(UUID uuid, String reason, long durationMillis);

    /**
     * Libera un detenuto: sban su LiteBans e rimozione dal database.
     *
     * @return true se lo sban e' andato a buon fine
     */
    CompletableFuture<Boolean> release(UUID uuid);

    /** Aggiunge (o toglie, con valori negativi) crediti al detenuto. */
    CompletableFuture<Void> addCredits(UUID uuid, int amount);

    /** Riduce il tempo residuo della pena. */
    CompletableFuture<Void> reduceRemaining(UUID uuid, long millis);

    /** Informazioni sul ban attivo secondo LiteBans, vuoto se non bannato. */
    CompletableFuture<Optional<BanInfo>> getBanInfo(UUID uuid);

    /** Crediti necessari per ottenere la liberta (da config). */
    int getCreditsRequiredForFreedom();

    /** Secondi di pena tolti per ogni blocco di carbone (da config). */
    long getReductionPerCoalMillis();

    /** Nome del server jail configurato. */
    String getJailServerName();
}
