package dev.lorenzzz.cundannatu.spigot.ban;

import dev.lorenzzz.cundannatu.common.JailPlayerData;
import dev.lorenzzz.cundannatu.spigot.JailMiningPlugin;
import dev.lorenzzz.cundannatu.spigot.api.event.PlayerReleasedEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Sorveglia gli sban decisi fuori dal plugin.
 * <p>
 * Se uno staffer esegue {@code /unban}, se il giocatore acquista lo sban sullo store
 * o se un appeal viene accolto, LiteBans rimuove il ban ma il detenuto resterebbe
 * chiuso in prigione: la riga su {@code jailed_players} non ne sa nulla. Questo task
 * ricontrolla periodicamente i detenuti online e, appena il ban sparisce, avvia la
 * liberazione automatica.
 * <p>
 * Il controllo richiede LiteBans sul server jail. Quando invece LiteBans gira solo
 * sul proxy, e' il proxy stesso a notificare la rimozione sul canale
 * {@code jailmining:data} (messaggio di tipo {@code release}).
 */
public class BanWatchdog {

    private static final String PATH = "jail-mining.release.watchdog.";

    private final JailMiningPlugin plugin;

    private BukkitTask task;

    public BanWatchdog(JailMiningPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isEnabled() {
        return plugin.getConfig().getBoolean(PATH + "enabled", true);
    }

    private long periodTicks() {
        return Math.max(20L, plugin.getConfig().getLong(PATH + "check-interval-seconds", 10L) * 20L);
    }

    /** Avvia (o riavvia dopo un reload) il controllo periodico. */
    public void start() {
        stop();
        if (!isEnabled()) {
            plugin.getLogger().info("[Watchdog] Controllo degli sban esterni disattivato da config");
            return;
        }
        if (!plugin.getBanManager().isLiteBansPresent()) {
            // Senza LiteBans in locale non possiamo interrogare i ban: ci pensa il proxy
            plugin.getLogger().info("[Watchdog] LiteBans non presente in locale: "
                    + "gli sban esterni arriveranno dal proxy sul canale " + JailMiningPlugin.CHANNEL);
            return;
        }
        long period = periodTicks();
        this.task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::tick, period, period);
        plugin.getLogger().info("[Watchdog] Controllo degli sban esterni avviato (ogni " + period + " tick)");
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /** Gira su thread asincrono: le query LiteBans non possono stare sul main thread. */
    private void tick() {
        try {
            List<Player> jailed = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.hasPermission("jailmining.bypass")) {
                    continue; // lo staff non sconta pene: niente liberazione automatica
                }
                if (plugin.isJailed(player.getUniqueId())
                        && !plugin.getReleaseService().isReleasing(player.getUniqueId())) {
                    jailed.add(player);
                }
            }
            for (Player player : jailed) {
                UUID uuid = player.getUniqueId();
                boolean stillBanned = plugin.getBanManager().isStillBanned(uuid).join();
                if (stillBanned) {
                    continue;
                }
                plugin.getLogger().info("[Watchdog] " + player.getName()
                        + " non risulta piu' bannato: liberazione automatica");
                Bukkit.getScheduler().runTask(plugin, () -> releaseNow(player));
            }
        } catch (Exception exception) {
            plugin.getLogger().log(Level.SEVERE, "[Watchdog] Errore nel controllo dei ban", exception);
        }
    }

    /** Libera il giocatore per sban esterno. Da eseguire sul thread principale. */
    public void releaseNow(Player player) {
        if (!player.isOnline()) {
            return;
        }
        JailPlayerData data = plugin.getDatabaseManager().getCached(player.getUniqueId());
        plugin.getReleaseService().release(player, data, PlayerReleasedEvent.Reason.PARDONED);
    }
}
