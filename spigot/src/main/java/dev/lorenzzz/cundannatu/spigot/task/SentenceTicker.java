package dev.lorenzzz.cundannatu.spigot.task;

import dev.lorenzzz.cundannatu.common.JailPlayerData;
import dev.lorenzzz.cundannatu.spigot.JailMiningPlugin;
import dev.lorenzzz.cundannatu.spigot.api.event.PlayerReleasedEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.UUID;
import java.util.logging.Level;

/**
 * Fa scorrere il tempo residuo delle pene a termine.
 * <p>
 * Senza questo task il contatore si muoverebbe solo scavando carbone: per un ban
 * temporaneo la scoreboard resterebbe ferma anche mentre il tempo passa davvero.
 * I ban permanenti (durata pari o superiore a {@code permanent-ban-start-years})
 * restano invece fermi: da quelli si esce solo lavorando in miniera.
 * <p>
 * Il decremento e' in memoria; su MySQL si scrive a intervalli piu' larghi
 * ({@code flush-interval-seconds}) per non generare una query al secondo.
 */
public class SentenceTicker {

    private static final String PATH = "jail-mining.sentence-timer.";

    private final JailMiningPlugin plugin;

    private BukkitTask task;
    private int elapsedTicks;

    public SentenceTicker(JailMiningPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isEnabled() {
        return plugin.getConfig().getBoolean(PATH + "enabled", true);
    }

    private long periodTicks() {
        return Math.max(1L, plugin.getConfig().getLong(PATH + "interval-ticks", 20L));
    }

    /** Avvia (o riavvia dopo un reload) il conto alla rovescia. */
    public void start() {
        stop();
        if (!isEnabled()) {
            plugin.getLogger().info("[Timer] Conto alla rovescia della pena disattivato da config");
            return;
        }
        long period = periodTicks();
        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, period, period);
        plugin.getLogger().info("[Timer] Conto alla rovescia avviato (ogni " + period + " tick)");
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /** Gira sul thread principale: tocca solo la cache in memoria. */
    private void tick() {
        long step = periodTicks() * 50L; // 1 tick = 50 ms
        elapsedTicks += (int) periodTicks();

        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            JailPlayerData data = plugin.getDatabaseManager().getCached(uuid);
            if (data == null || plugin.getReleaseService().isReleasing(uuid)) {
                continue;
            }
            if (plugin.isPermanentSentence(data)) {
                continue; // ergastolo: il tempo non scorre, servono i crediti
            }

            long remaining = data.reduceRemaining(step);
            data.setDirty(true);

            if (remaining <= 0L) {
                plugin.getLogger().info("[Timer] Pena esaurita per " + player.getName());
                plugin.getReleaseService().release(player, data, PlayerReleasedEvent.Reason.TIME_SERVED);
            }
        }

        int flushSeconds = Math.max(5, plugin.getConfig().getInt(PATH + "flush-interval-seconds", 30));
        if (elapsedTicks >= flushSeconds * 20) {
            elapsedTicks = 0;
            flushDirty();
        }
    }

    /** Scrive su MySQL i detenuti online con dati ancora non persistiti. */
    private void flushDirty() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            JailPlayerData data = plugin.getDatabaseManager().getCached(player.getUniqueId());
            if (data == null || !data.isDirty()) {
                continue;
            }
            plugin.getDatabaseManager()
                    .updateProgress(data.getUuid(), data.getCurrentRemaining(),
                            data.getJailCredits(), data.getBlocksMined())
                    .whenComplete((ignored, throwable) -> {
                        if (throwable != null) {
                            plugin.getLogger().log(Level.WARNING,
                                    "[Timer] Errore nel flush di " + data.getUuid(), throwable);
                            return;
                        }
                        data.setDirty(false);
                    });
        }
    }
}
