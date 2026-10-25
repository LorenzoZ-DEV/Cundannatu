package dev.lorenzzz.cundannatu.spigot.listener;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.lorenzzz.cundannatu.spigot.JailMiningPlugin;
import dev.lorenzzz.cundannatu.common.JailMessages;
import dev.lorenzzz.cundannatu.common.JailPlayerData;
import dev.lorenzzz.cundannatu.common.JailReleaseNotice;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Riceve dal proxy Velocity il payload JSON sul canale {@code jailmining:data}.
 * <p>
 * Formato: {@code {uuid, name, reason, originalDuration, remainingDuration}}.
 * Alla ricezione il detenuto viene salvato su MySQL e messo in cache.
 */
public class JailChannelListener implements PluginMessageListener {

    private final JailMiningPlugin plugin;

    public JailChannelListener(JailMiningPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, byte @NotNull [] message) {
        if (!JailMiningPlugin.CHANNEL.equals(channel)) {
            return;
        }

        String type = JailMessages.typeOf(message);

        // Il proxy ci avvisa che il ban e' stato rimosso da fuori: liberazione immediata
        if (JailMessages.TYPE_RELEASE.equals(type)) {
            handleRelease(message);
            return;
        }

        // Le richieste di sban viaggiano sullo stesso canale ma in direzione opposta
        if (!JailMessages.TYPE_JAIL.equals(type)) {
            return;
        }

        try {
            String json = new String(message, StandardCharsets.UTF_8);
            JsonObject object = JsonParser.parseString(json).getAsJsonObject();

            UUID uuid = UUID.fromString(object.get("uuid").getAsString());
            String reason = object.has("reason") && !object.get("reason").isJsonNull()
                    ? object.get("reason").getAsString()
                    : "Nessun motivo";
            long original = object.get("originalDuration").getAsLong();
            long remaining = object.get("remainingDuration").getAsLong();

            plugin.getLogger().info("[Channel] Payload jail ricevuto per " + uuid
                    + " motivo=" + reason
                    + " residuo=" + JailPlayerData.formatDuration(remaining));

            // Se il detenuto esiste gia' manteniamo crediti e tempo gia' scontato
            plugin.<JailPlayerData>newChain()
                    .asyncFirst(() -> {
                        JailPlayerData existing = plugin.getDatabaseManager().getJailedPlayer(uuid).join();
                        JailPlayerData data;
                        if (existing == null) {
                            data = new JailPlayerData(uuid, reason, original, remaining, 0,
                                    System.currentTimeMillis());
                        } else {
                            data = existing;
                            data.setBanReason(reason);
                            data.setOriginalDuration(original);
                        }
                        plugin.getDatabaseManager().saveJailedPlayer(data).join();
                        return data;
                    })
                    .syncLast(data -> {
                        plugin.getDatabaseManager().putCache(data);
                        if (player.isOnline()) {
                            player.teleport(plugin.getJailSpawn());
                            if (plugin.getJailPickaxe().isGivenOnJoin()) {
                                plugin.getJailPickaxe().give(player);
                            }
                            plugin.getScoreboardUpdater().updateNow(player, data);
                            plugin.getTabListManager().updateNow(player, data);
                        }
                    })
                    .execute((finished, exception) -> {
                        if (exception != null) {
                            plugin.getLogger().log(Level.SEVERE,
                                    "[Channel] Errore salvando il payload di " + uuid, exception);
                        }
                    });

        } catch (Exception exception) {
            plugin.getLogger().log(Level.SEVERE, "[Channel] Payload jailmining:data non valido", exception);
        }
    }

    /**
     * Sban deciso fuori dal plugin: il proxy lo comunica, noi liberiamo il detenuto.
     * Il messaggio arriva dalla connessione di un giocatore qualsiasi, quindi il
     * destinatario va cercato per UUID.
     */
    private void handleRelease(byte[] message) {
        try {
            JailReleaseNotice notice = JailReleaseNotice.fromBytes(message);
            UUID uuid = notice.uniqueId();
            plugin.getLogger().info("[Channel] Il proxy segnala lo sban di " + notice.name()
                    + " (" + notice.reason() + ")");

            Player target = Bukkit.getPlayer(uuid);
            if (target == null || !target.isOnline()) {
                // Non e' qui: basta ripulire il database, uscira' libero al prossimo login
                plugin.getDatabaseManager().deleteJailedPlayer(uuid);
                return;
            }
            // Uno staffer non va mai liberato: sarebbe uno sban a vuoto seguito da un
            // kick verso il lobby. La riga sul database resta, cosi' se perde il grado
            // torna a scontare la pena residua.
            if (target.hasPermission("jailmining.bypass") || !plugin.isJailed(uuid)) {
                plugin.getLogger().info("[Channel] " + notice.name()
                        + " non e' un detenuto: notifica di sban ignorata");
                return;
            }
            plugin.getBanWatchdog().releaseNow(target);
        } catch (Exception exception) {
            plugin.getLogger().log(Level.SEVERE, "[Channel] Notifica di sban non valida", exception);
        }
    }
}
