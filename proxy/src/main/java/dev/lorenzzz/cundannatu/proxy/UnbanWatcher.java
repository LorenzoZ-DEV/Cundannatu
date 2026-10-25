package dev.lorenzzz.cundannatu.proxy;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import dev.lorenzzz.cundannatu.common.JailReleaseNotice;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Sorveglia i detenuti collegati al server jail e avvisa il backend quando il loro
 * ban sparisce da LiteBans.
 * <p>
 * Il server jail non ha LiteBans installato, quindi non puo' accorgersi da solo di
 * uno sban deciso altrove (comando dello staff, acquisto sullo store, appeal
 * accolto, scadenza del ban). Senza questa notifica il giocatore resterebbe chiuso
 * in prigione fino al riavvio.
 */
public class UnbanWatcher {

    private final VelocityJailRedirect plugin;

    private ScheduledTask task;

    public UnbanWatcher(VelocityJailRedirect plugin) {
        this.plugin = plugin;
    }

    /** Avvia il controllo periodico, se abilitato e con LiteBans disponibile. */
    public void start() {
        stop();
        if (!plugin.isUnbanWatcherEnabled()) {
            plugin.getLogger().info("Controllo degli sban disattivato da config.json");
            return;
        }
        if (!plugin.getLiteBans().isAvailable()) {
            plugin.getLogger().warn("LiteBans assente: impossibile sorvegliare gli sban");
            return;
        }

        int seconds = Math.max(5, plugin.getUnbanCheckSeconds());
        this.task = plugin.getProxy().getScheduler()
                .buildTask(plugin, this::tick)
                .repeat(seconds, TimeUnit.SECONDS)
                .schedule();
        plugin.getLogger().info("Controllo degli sban avviato (ogni {} secondi)", seconds);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /** Gira su un thread dello scheduler Velocity: le query LiteBans sono consentite. */
    private void tick() {
        Optional<RegisteredServer> jail = plugin.getJailServer();
        if (jail.isEmpty()) {
            return;
        }
        RegisteredServer server = jail.get();
        if (server.getPlayersConnected().isEmpty()) {
            return;
        }

        for (Player player : server.getPlayersConnected()) {
            try {
                if (plugin.isStaffExempt(player)) {
                    // Uno staffer sul jail e' in visita, non detenuto: senza questo controllo
                    // il "non risulta bannato" lo farebbe liberare e rispedire al lobby
                    continue;
                }
                if (plugin.getLiteBans().getBan(player.getUniqueId(), null) != null) {
                    continue; // ancora bannato: resta dov e'
                }
                JailReleaseNotice notice = JailReleaseNotice.of(player.getUniqueId(),
                        player.getUsername(), "Ban rimosso o scaduto");
                server.sendPluginMessage(plugin.getChannel(), notice.toBytes());
                plugin.getLogger().info("{} non risulta piu' bannato: notifica di rilascio inviata al server jail",
                        player.getUsername());
            } catch (Exception exception) {
                plugin.getLogger().error("Errore controllando il ban di {}", player.getUsername(), exception);
            }
        }
    }
}
