package dev.lorenzzz.cundannatu.spigot.task;

import dev.lorenzzz.cundannatu.spigot.JailMiningPlugin;
import dev.lorenzzz.cundannatu.common.JailPlayerData;
import dev.lorenzzz.cundannatu.spigot.util.Placeholders;
import dev.lorenzzz.cundannatu.spigot.util.Text;
// Variante legacy di FastBoard, non quella Adventure: su Paper quest ultima chiama
// PaperAdventure.asVanilla(net.kyori...Component) via reflection, firma che con le
// nostre classi rilocate non esiste -> ExceptionInInitializerError ad ogni board.
// I colori esadecimali passano comunque, tramite §x§R§R§G§G§B§B.
import fr.mrmicky.fastboard.FastBoard;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Task asincrono che aggiorna la sidebar con FastBoard.
 * <p>
 * Titolo, righe e intervallo sono interamente configurabili in
 * {@code jail-mining.scoreboard}: esiste un layout per i detenuti
 * ({@code scoreboard.jailed}) e uno per chi non sta scontando alcuna pena
 * ({@code scoreboard.not-jailed}).
 * <p>
 * La lettura dei dati avviene in asincrono (cache del DatabaseManager con fallback
 * su MySQL), mentre i pacchetti FastBoard partono sempre dal thread principale.
 */
public class JailScoreboardUpdater {

    private static final String PATH = "jail-mining.scoreboard.";

    /** Limite di righe della sidebar imposto dal client. */
    private static final int MAX_LINES = 15;

    private final JailMiningPlugin plugin;
    private final Map<UUID, FastBoard> boards = new ConcurrentHashMap<>();

    private BukkitTask task;
    private int tickCounter;

    public JailScoreboardUpdater(JailMiningPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isEnabled() {
        return plugin.getConfig().getBoolean(PATH + "enabled", true);
    }

    /** true se anche i giocatori liberi devono vedere la sidebar. */
    public boolean showToFreePlayers() {
        return plugin.getConfig().getBoolean(PATH + "not-jailed.enabled", true);
    }

    private long periodTicks() {
        return Math.max(1L, plugin.getConfig().getLong(PATH + "update-interval-ticks", 20L));
    }

    /** Avvia (o riavvia dopo un reload) il ciclo asincrono ripetuto. */
    public void start() {
        stop();
        if (!isEnabled()) {
            plugin.getLogger().info("[Scoreboard] Modulo disattivato da config");
            return;
        }
        long period = periodTicks();
        this.task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::tick, period, period);
        plugin.getLogger().info("[Scoreboard] Updater FastBoard avviato (ogni " + period + " tick)");
    }

    /** Ferma il task ed elimina tutte le board attive. */
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        boards.values().forEach(board -> {
            if (!board.isDeleted()) {
                board.delete();
            }
        });
        boards.clear();
    }

    /** Corpo del task: gira su thread asincrono. */
    private void tick() {
        try {
            List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
            Map<Player, JailPlayerData> snapshot = new HashMap<>();

            for (Player player : online) {
                UUID uuid = player.getUniqueId();
                JailPlayerData data = plugin.getDatabaseManager().getCached(uuid);
                if (data == null) {
                    // Cache scaduta: ricarichiamo dal database (siamo già async)
                    data = plugin.getDatabaseManager().getJailedPlayer(uuid).join();
                }
                // data == null significa "nessuna pena": la board passa al layout libero
                snapshot.put(player, data);
            }

            // Applicazione sincrona: FastBoard va usata dal thread principale
            if (!snapshot.isEmpty()) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        snapshot.forEach((player, data) -> {
                            if (player.isOnline()) {
                                apply(player, data);
                            }
                        }));
            }

            // Manutenzione periodica della cache e log dello stato del pool ogni 5 minuti
            if (++tickCounter % 300 == 0) {
                plugin.getDatabaseManager().purgeExpiredCache();
                plugin.getDatabaseManager().logPoolStatus();
            }
        } catch (Exception exception) {
            plugin.getLogger().log(Level.SEVERE, "[Scoreboard] Errore nel task asincrono", exception);
        }
    }

    /** Aggiornamento immediato (chiamato dopo un blocco rotto o al join). */
    public void updateNow(Player player, JailPlayerData data) {
        if (player == null) {
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            apply(player, data);
        } else {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    apply(player, data);
                }
            });
        }
    }

    /**
     * Crea o aggiorna la board del giocatore. Da eseguire sul thread principale.
     *
     * @param data dati della pena, oppure {@code null} se il giocatore e' libero
     */
    private void apply(Player player, JailPlayerData data) {
        if (!isEnabled()) {
            return;
        }
        if (data == null && !showToFreePlayers()) {
            // Layout libero disattivato: nessuna sidebar per chi non ha pene
            clear(player);
            return;
        }

        FastBoard board = boards.computeIfAbsent(player.getUniqueId(), uuid -> new FastBoard(player));
        if (board.isDeleted()) {
            boards.remove(player.getUniqueId());
            return;
        }

        String section = data == null ? "not-jailed." : "jailed.";
        Map<String, String> placeholders = data == null
                ? Placeholders.of(plugin, player)
                : Placeholders.withJail(plugin, player, data);

        String title = plugin.getConfig().getString(PATH + section + "title", "&4&lPRIGIONE");
        List<String> lines = plugin.getConfig().getStringList(PATH + section + "lines");

        // Il client accetta al massimo 15 righe: oltre, i pacchetti vengono scartati
        if (lines.size() > MAX_LINES) {
            plugin.getLogger().warning("[Scoreboard] " + PATH + section + "lines ha " + lines.size()
                    + " righe: il massimo e' " + MAX_LINES + ", le altre vengono ignorate");
            lines = lines.subList(0, MAX_LINES);
        }

        board.updateTitle(plugin.parseLegacy(player, title, placeholders));
        board.updateLines(plugin.parseLegacyAll(player, lines, placeholders));
    }

    /** Rimuove la board (liberazione o disconnessione). */
    public void clear(Player player) {
        FastBoard board = boards.remove(player.getUniqueId());
        if (board != null && !board.isDeleted()) {
            board.delete();
        }
    }

    /**
     * Passa il giocatore al layout "nessuna pena" senza attendere il prossimo tick.
     * Usato subito dopo la liberazione.
     */
    public void showFreeBoard(Player player) {
        updateNow(player, null);
    }
}
