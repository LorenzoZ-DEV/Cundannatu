package dev.lorenzzz.cundannatu.spigot.task;

import dev.lorenzzz.cundannatu.common.JailPlayerData;
import dev.lorenzzz.cundannatu.spigot.JailMiningPlugin;
import dev.lorenzzz.cundannatu.spigot.util.Placeholders;
import dev.lorenzzz.cundannatu.spigot.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Aggiorna header, footer e nome nella tab list.
 * <p>
 * Come per la scoreboard esistono due layout configurabili in {@code jail-mining.tab}:
 * {@code jailed} per chi sta scontando una pena e {@code not-jailed} per tutti gli altri.
 * I dati vengono raccolti in asincrono, i pacchetti inviati dal thread principale.
 */
public class TabListManager {

    private static final String PATH = "jail-mining.tab.";

    private final JailMiningPlugin plugin;

    private BukkitTask task;

    public TabListManager(JailMiningPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isEnabled() {
        return plugin.getConfig().getBoolean(PATH + "enabled", true);
    }

    private long periodTicks() {
        return Math.max(1L, plugin.getConfig().getLong(PATH + "update-interval-ticks", 40L));
    }

    /** Avvia (o riavvia dopo un reload) il task periodico. */
    public void start() {
        stop();
        if (!isEnabled()) {
            plugin.getLogger().info("[Tab] Modulo disattivato da config");
            return;
        }
        long period = periodTicks();
        this.task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::tick, period, period);
        plugin.getLogger().info("[Tab] Aggiornamento tab list avviato (ogni " + period + " tick)");
    }

    /** Ferma il task e ripulisce header, footer e nomi personalizzati. */
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (!Bukkit.getOnlinePlayers().isEmpty()) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                player.setPlayerListHeaderFooter("", "");
                player.setPlayerListName(player.getName());
            }
        }
    }

    /** Raccolta dati in asincrono, applicazione in sincrono. */
    private void tick() {
        try {
            Map<Player, JailPlayerData> snapshot = new HashMap<>();
            for (Player player : new ArrayList<>(Bukkit.getOnlinePlayers())) {
                UUID uuid = player.getUniqueId();
                JailPlayerData data = plugin.getDatabaseManager().getCached(uuid);
                if (data == null) {
                    data = plugin.getDatabaseManager().getJailedPlayer(uuid).join();
                }
                snapshot.put(player, data);
            }
            if (snapshot.isEmpty()) {
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> snapshot.forEach((player, data) -> {
                if (player.isOnline()) {
                    apply(player, data);
                }
            }));
        } catch (Exception exception) {
            plugin.getLogger().log(Level.SEVERE, "[Tab] Errore nel task asincrono", exception);
        }
    }

    /** Aggiornamento immediato di un singolo giocatore (join, incarcerazione, liberazione). */
    public void updateNow(Player player, JailPlayerData data) {
        if (player == null || !isEnabled()) {
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
     * Scrive header, footer e nome in lista. Da eseguire sul thread principale.
     *
     * @param data dati della pena, oppure {@code null} se il giocatore e' libero
     */
    private void apply(Player player, JailPlayerData data) {
        if (!isEnabled()) {
            return;
        }
        String section = data == null ? "not-jailed." : "jailed.";
        Map<String, String> placeholders = data == null
                ? Placeholders.of(plugin, player)
                : Placeholders.withJail(plugin, player, data);

        List<String> header = plugin.getConfig().getStringList(PATH + section + "header");
        List<String> footer = plugin.getConfig().getStringList(PATH + section + "footer");

        // Via Adventure header e footer conservano gradienti e colori MiniMessage
        if (plugin.audiences() != null) {
            plugin.audiences().player(player).sendPlayerListHeaderAndFooter(
                    plugin.parseJoined(player, header, placeholders),
                    plugin.parseJoined(player, footer, placeholders));
        } else {
            player.setPlayerListHeaderFooter(Text.join(header, placeholders), Text.join(footer, placeholders));
        }

        String listName = plugin.getConfig().getString(PATH + section + "player-list-name", "");
        if (listName != null && !listName.isBlank()) {
            // Il nome in lista resta legacy: e' l unica forma accettata dall API Spigot.
            // Un solo passaggio di parsing: ri-parsare un testo gia' convertito
            // significherebbe rileggere i §x§R§R... appena prodotti.
            player.setPlayerListName(plugin.parseLegacy(player, listName, placeholders));
        }
    }

    /** Riporta il giocatore al layout "nessuna pena". */
    public void showFreeTab(Player player) {
        updateNow(player, null);
    }
}
