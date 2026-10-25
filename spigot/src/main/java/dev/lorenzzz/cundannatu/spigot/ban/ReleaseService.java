package dev.lorenzzz.cundannatu.spigot.ban;

import dev.lorenzzz.cundannatu.common.JailPlayerData;
import dev.lorenzzz.cundannatu.spigot.JailMiningPlugin;
import dev.lorenzzz.cundannatu.spigot.api.event.PlayerReleasedEvent;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Punto unico di uscita dalla prigione.
 * <p>
 * Serve a tre percorsi diversi che prima avrebbero duplicato la stessa logica:
 * crediti raggiunti, tempo esaurito e sban effettuato dallo staff dall esterno
 * (rilevato dal {@link BanWatchdog}). Un set di UUID in corso di liberazione
 * impedisce doppi sban quando due percorsi scattano nello stesso momento.
 */
public class ReleaseService {

    private final JailMiningPlugin plugin;

    /** Liberazioni gia' avviate: evita di eseguire due volte il comando di sban. */
    private final Set<UUID> releasing = ConcurrentHashMap.newKeySet();

    public ReleaseService(JailMiningPlugin plugin) {
        this.plugin = plugin;
    }

    /** true se per questo giocatore e' gia' partita una liberazione. */
    public boolean isReleasing(UUID uuid) {
        return releasing.contains(uuid);
    }

    /**
     * Libera il giocatore: sban, pulizia del database e uscita dalla prigione.
     *
     * @param data   dati della pena (possono essere null se gia' rimossi)
     * @param reason motivo della liberazione, riportato nel {@link PlayerReleasedEvent}
     */
    public void release(Player player, JailPlayerData data, PlayerReleasedEvent.Reason reason) {
        UUID uuid = player.getUniqueId();
        if (!releasing.add(uuid)) {
            return;
        }

        plugin.getLogger().info("[Release] Liberazione di " + player.getName() + " (motivo: " + reason + ")");

        plugin.<Boolean>newChain()
                .asyncFirst(() -> plugin.getBanManager().unbanPlayer(uuid, reason).join())
                .syncLast(success -> finish(player, reason))
                .execute((finished, exception) -> {
                    releasing.remove(uuid);
                    if (exception != null) {
                        plugin.getLogger().log(Level.SEVERE,
                                "[Release] Errore durante la liberazione di " + uuid, exception);
                    }
                });
    }

    /**
     * Chiude la liberazione lato giocatore: messaggi, scoreboard, tab e uscita.
     * Da eseguire sul thread principale.
     */
    private void finish(Player player, PlayerReleasedEvent.Reason reason) {
        releasing.remove(player.getUniqueId());
        if (!player.isOnline()) {
            return;
        }

        player.playSound(player.getLocation(),
                plugin.getSound("on-freedom", Sound.ENTITY_PLAYER_LEVELUP), 1.0F, 1.0F);
        plugin.send(player, messageKey(reason));

        // La sidebar e la tab passano al layout "nessuna pena"
        plugin.getScoreboardUpdater().showFreeBoard(player);
        plugin.getTabListManager().showFreeTab(player);

        if (shouldKick(reason)) {
            String kickMessage = plugin.getMessage("kick-released");
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    player.kickPlayer(kickMessage);
                }
            });
        } else {
            player.teleport(player.getWorld().getSpawnLocation());
        }
    }

    /** Messaggio mostrato al giocatore in base al motivo della liberazione. */
    private String messageKey(PlayerReleasedEvent.Reason reason) {
        return switch (reason) {
            case CREDITS -> "freedom-achieved";
            case TIME_SERVED -> "freedom-time-served";
            // Sban dallo staff o dallo store: il giocatore non ha fatto nulla per uscire
            default -> "freedom-pardoned";
        };
    }

    /**
     * true se il giocatore va espulso dal server jail.
     * <p>
     * Con i crediti resta in gioco e viene teletrasportato allo spawn, mentre con la
     * pena esaurita o lo sban dello staff conviene farlo rientrare dal lobby.
     */
    private boolean shouldKick(PlayerReleasedEvent.Reason reason) {
        String path = "jail-mining.release.kick-on-";
        return switch (reason) {
            case CREDITS -> plugin.getConfig().getBoolean(path + "credits", false);
            case TIME_SERVED -> plugin.getConfig().getBoolean(path + "time-served", true);
            default -> plugin.getConfig().getBoolean(path + "pardon", true);
        };
    }
}
