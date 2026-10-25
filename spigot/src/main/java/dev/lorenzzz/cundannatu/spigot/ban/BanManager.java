package dev.lorenzzz.cundannatu.spigot.ban;

import dev.lorenzzz.cundannatu.spigot.JailMiningPlugin;
import dev.lorenzzz.cundannatu.api.BanInfo;
import dev.lorenzzz.cundannatu.common.JailPlayerData;
import dev.lorenzzz.cundannatu.spigot.api.event.PlayerJailedEvent;
import dev.lorenzzz.cundannatu.spigot.api.event.PlayerReleasedEvent;
import dev.lorenzzz.cundannatu.spigot.db.DatabaseManager;
import litebans.api.Database;
import litebans.api.Entry;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * Ponte fra LiteBans e il sistema jail.
 * <p>
 * Usa le API ufficiali di LiteBans ({@code litebans.api.Database} e {@code litebans.api.Entry}):
 * {@link Database#getBan(UUID, String, String)} restituisce il ban attivo con motivo,
 * durata totale ({@link Entry#getDuration()}) e durata residua
 * ({@link Entry#getRemainingDuration(long)}).
 * <p>
 * Le query LiteBans lanciano IllegalStateException se eseguite sul main thread di Bukkit,
 * quindi ogni chiamata passa da un executor dedicato. I risultati vengono poi mantenuti
 * nella cache del {@link DatabaseManager} per non interrogare LiteBans ad ogni tick.
 */
public class BanManager {

    private static final long MILLIS_PER_YEAR = TimeUnit.DAYS.toMillis(365L);

    private final JailMiningPlugin plugin;
    private final DatabaseManager databaseManager;
    private final ExecutorService executor;
    private final boolean liteBansPresent;

    public BanManager(JailMiningPlugin plugin, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;

        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "JailMining-LiteBans-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.executor = Executors.newFixedThreadPool(2, factory);

        this.liteBansPresent = Bukkit.getPluginManager().getPlugin("LiteBans") != null;
        if (liteBansPresent) {
            plugin.getLogger().info("[Ban] Integrazione LiteBans attiva (API litebans.api.Database)");
        } else {
            plugin.getLogger().warning("[Ban] LiteBans non trovato: uso solo i dati salvati su jailed_players");
        }
    }

    public boolean isLiteBansPresent() {
        return liteBansPresent;
    }

    // ------------------------------------------------------------------
    // LETTURA BAN
    // ------------------------------------------------------------------

    /**
     * Recupera le informazioni sul ban attivo di un giocatore.
     * Il future si completa con null se il giocatore non risulta bannato.
     */
    public CompletableFuture<BanInfo> getBanInfo(UUID uuid) {
        // 1) cache locale: se il detenuto è già nel database jail evitiamo la query su LiteBans
        JailPlayerData cached = databaseManager.getCached(uuid);
        if (cached != null) {
            return CompletableFuture.completedFuture(new BanInfo(
                    uuid, cached.getBanReason(), cached.getOriginalDuration(),
                    cached.getCurrentRemaining(), cached.getOriginalDuration() >= permanentBanMillis()));
        }
        if (!liteBansPresent) {
            return CompletableFuture.completedFuture(null);
        }

        // 2) query alle API LiteBans (mai sul main thread)
        return CompletableFuture.supplyAsync(() -> {
            try {
                Entry entry = Database.get().getBan(uuid, null, Database.ANY_SERVER_SCOPE);
                if (entry == null) {
                    return null; // null check: nessun ban attivo
                }
                return toBanInfo(uuid, entry);
            } catch (Exception exception) {
                plugin.getLogger().log(Level.SEVERE, "[Ban] Errore leggendo il ban di " + uuid, exception);
                return null;
            }
        }, executor);
    }

    /** Converte una Entry di LiteBans nel modello interno. */
    private BanInfo toBanInfo(UUID uuid, Entry entry) {
        boolean permanent = entry.isPermanent();
        long permanentMillis = permanentBanMillis();

        // getDuration() e getRemainingDuration() ritornano -1 sui ban permanenti
        long original = permanent ? permanentMillis : Math.max(0L, entry.getDuration());
        long remaining = permanent
                ? permanentMillis
                : Math.max(0L, entry.getRemainingDuration(System.currentTimeMillis()));

        return new BanInfo(uuid, entry.getReason(), original, remaining, permanent);
    }

    /**
     * Tempo residuo del ban in millisecondi.
     * Ritorna 0 se il giocatore non è bannato, la durata configurata se il ban è permanente.
     */
    public CompletableFuture<Long> getRemainingTime(UUID uuid) {
        return getBanInfo(uuid).thenApply(info -> info == null ? 0L : info.remaining());
    }

    /** Verifica sincrona sulla sola cache (nessuna query). */
    public boolean isJailed(UUID uuid) {
        return databaseManager.getCached(uuid) != null;
    }

    /**
     * Chiede direttamente a LiteBans se il ban e' ancora attivo, ignorando la cache
     * del jail: e' il controllo usato dal {@link BanWatchdog} per accorgersi degli
     * sban decisi dallo staff, dallo store o da un appeal accolto.
     * <p>
     * Senza LiteBans in locale la risposta e' sempre {@code true}: non potendo
     * verificare, il detenuto non viene liberato per errore.
     */
    public CompletableFuture<Boolean> isStillBanned(UUID uuid) {
        if (!liteBansPresent) {
            return CompletableFuture.completedFuture(true);
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                return Database.get().getBan(uuid, null, Database.ANY_SERVER_SCOPE) != null;
            } catch (Exception exception) {
                plugin.getLogger().log(Level.WARNING,
                        "[Ban] Errore verificando il ban di " + uuid + ", lo considero attivo", exception);
                return true;
            }
        }, executor);
    }

    /** Durata assegnata ai ban permanenti, letta da config (default 10 anni). */
    public long permanentBanMillis() {
        int years = plugin.getConfig().getInt("jail-mining.permanent-ban-start-years", 10);
        return years * MILLIS_PER_YEAR;
    }

    // ------------------------------------------------------------------
    // SBAN
    // ------------------------------------------------------------------

    /**
     * Rimuove il ban con il comando {@code /ltunban} eseguito dalla console
     * e ripulisce riga e cache del database jail.
     */
    public CompletableFuture<Boolean> unbanPlayer(UUID uuid) {
        return unbanPlayer(uuid, PlayerReleasedEvent.Reason.ADMIN);
    }

    /**
     * Come {@link #unbanPlayer(UUID)}, indicando il motivo della liberazione
     * riportato nel {@link PlayerReleasedEvent}.
     */
    public CompletableFuture<Boolean> unbanPlayer(UUID uuid, PlayerReleasedEvent.Reason reason) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();

        // Il dispatch dei comandi deve avvenire sul thread principale
        Bukkit.getScheduler().runTask(plugin, () -> {
            boolean dispatched = false;
            String motivo = unbanReason();
            String playerName = resolveName(uuid);
            String command = buildUnbanCommand(uuid, playerName, motivo);
            try {
                if (liteBansPresent) {
                    dispatched = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
                    plugin.getLogger().info("[Ban] Console: /" + command + " -> esito " + dispatched);
                } else {
                    // LiteBans gira solo sul proxy: deleghiamo lo stesso comando via plugin channel
                    dispatched = plugin.requestProxyUnban(uuid, playerName, motivo, command);
                }
            } catch (Exception exception) {
                plugin.getLogger().log(Level.SEVERE, "[Ban] Errore durante lo sban di " + uuid, exception);
            }

            boolean result = dispatched;
            databaseManager.deleteJailedPlayer(uuid)
                    .whenComplete((ignored, throwable) -> {
                        if (throwable != null) {
                            plugin.getLogger().log(Level.SEVERE,
                                    "[Ban] Errore ripulendo il database per " + uuid, throwable);
                        }
                        Bukkit.getScheduler().runTask(plugin, () ->
                                Bukkit.getPluginManager().callEvent(new PlayerReleasedEvent(uuid, reason)));
                        future.complete(result);
                    });
        });

        return future;
    }

    /**
     * Costruisce il comando di sban leggendo {@code jail-mining.unban.command}.
     * <p>
     * Default: {@code unban {player} -s}. Placeholder disponibili: {@code {player}},
     * {@code {uuid}} e {@code {reason}}. L eventuale slash iniziale viene rimosso
     * perche' {@code dispatchCommand} vuole il comando senza prefisso.
     */
    public String buildUnbanCommand(UUID uuid, String playerName, String reason) {
        String raw = plugin.getConfig().getString("jail-mining.unban.command", "unban {player} -s");
        if (raw == null || raw.isBlank()) {
            raw = "unban {player} -s";
        }
        // Se il nome non e' noto (giocatore mai visto) ripieghiamo sull uuid
        String name = playerName == null || playerName.isBlank() ? uuid.toString() : playerName;
        return raw.trim()
                .replaceFirst("^/", "")
                .replace("{player}", name)
                .replace("{uuid}", uuid.toString())
                .replace("{reason}", reason);
    }

    /** Motivo dello sban riportato nel comando, letto da config. */
    public String unbanReason() {
        String reason = plugin.getConfig().getString("jail-mining.unban.reason",
                "Pena scontata con il jail mining");
        return reason == null || reason.isBlank() ? "Pena scontata con il jail mining" : reason;
    }

    /** Nome del giocatore: prima gli online, poi la cache degli OfflinePlayer. */
    private String resolveName(UUID uuid) {
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            return online.getName();
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
        return offline.getName();
    }

    /**
     * Registra un nuovo detenuto: legge il ban da LiteBans e crea la riga su jailed_players.
     * Se LiteBans non ha un ban attivo viene usata la durata passata come fallback.
     */
    public CompletableFuture<JailPlayerData> registerJailedPlayer(UUID uuid, String reason, long fallbackDuration) {
        return getBanInfo(uuid).thenCompose(info -> {
            long original = info != null ? info.originalDuration() : fallbackDuration;
            long remaining = info != null ? info.remaining() : fallbackDuration;
            String finalReason = info != null && info.reason() != null ? info.reason() : reason;

            JailPlayerData data = new JailPlayerData(uuid, finalReason, original,
                    remaining, 0, System.currentTimeMillis());
            plugin.getLogger().info("[Ban] Registrato detenuto " + uuid + " motivo=" + finalReason
                    + " durata=" + JailPlayerData.formatDuration(original));
            return databaseManager.saveJailedPlayer(data).thenApply(ignored -> {
                Bukkit.getScheduler().runTask(plugin, () ->
                        Bukkit.getPluginManager().callEvent(new PlayerJailedEvent(data)));
                return data;
            });
        });
    }

    /** Chiusura dell executor dedicato alle query LiteBans. */
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
