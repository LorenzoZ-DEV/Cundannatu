package dev.lorenzzz.cundannatu.spigot.command;

import co.aikar.commands.BaseCommand;
import co.aikar.commands.annotation.CommandAlias;
import co.aikar.commands.annotation.CommandCompletion;
import co.aikar.commands.annotation.CommandPermission;
import co.aikar.commands.annotation.Description;
import co.aikar.commands.annotation.HelpCommand;
import co.aikar.commands.annotation.Optional;
import co.aikar.commands.annotation.Syntax;
import co.aikar.commands.CommandHelp;
import dev.lorenzzz.cundannatu.spigot.JailMiningPlugin;
import dev.lorenzzz.cundannatu.common.JailPlayerData;
import dev.lorenzzz.cundannatu.spigot.util.Placeholders;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Comandi del sistema jail registrati tramite ACF (PaperCommandManager).
 * <p>
 * Tutte le operazioni che toccano il database girano su thread asincroni
 * attraverso le catene TaskChain di Aikar.
 */
@CommandAlias("jailmining|jm")
public class JailCommands extends BaseCommand {

    private final JailMiningPlugin plugin;

    public JailCommands(JailMiningPlugin plugin) {
        this.plugin = plugin;
    }

    @HelpCommand
    @Description("Mostra l elenco dei comandi disponibili")
    public void onHelp(CommandSender sender, CommandHelp help) {
        help.showHelp();
    }

    // ------------------------------------------------------------------
    // /jail <player> <motivo>
    // ------------------------------------------------------------------

    @CommandAlias("jail")
    @CommandPermission("jailmining.admin.jail")
    @CommandCompletion("@players @nothing")
    @Syntax("<giocatore> <motivo>")
    @Description("Manda un giocatore in prigione registrandolo nel database")
    public void onJail(CommandSender sender, OfflinePlayer target, String motivo) {
        UUID uuid = target.getUniqueId();
        String reason = motivo == null || motivo.isBlank() ? "Nessun motivo" : motivo;

        sender.sendMessage(ChatColor.GRAY + "Registrazione di " + target.getName() + " in corso...");

        plugin.<JailPlayerData>newChain()
                .asyncFirst(() -> plugin.getBanManager()
                        .registerJailedPlayer(uuid, reason, plugin.getBanManager().permanentBanMillis())
                        .join())
                .syncLast(data -> {
                    if (data == null) {
                        sender.sendMessage(ChatColor.RED + "Impossibile registrare il detenuto, controlla i log.");
                        return;
                    }
                    sender.sendMessage(ChatColor.GREEN + target.getName() + " e' ora detenuto.");
                    sender.sendMessage(ChatColor.GRAY + "Motivo: " + ChatColor.WHITE + data.getBanReason());
                    sender.sendMessage(ChatColor.GRAY + "Durata: " + ChatColor.WHITE + data.getFormattedRemaining());

                    Player online = target.getPlayer();
                    if (online != null && online.isOnline()) {
                        online.teleport(plugin.getJailSpawn());
                        if (plugin.getJailPickaxe().isGivenOnJoin()) {
                            plugin.getJailPickaxe().give(online);
                        }
                        plugin.getScoreboardUpdater().updateNow(online, data);
                        plugin.getTabListManager().updateNow(online, data);
                        plugin.send(online, "action-bar", Placeholders.withJail(plugin, online, data));
                    }
                    plugin.getLogger().info("[Cmd] " + sender.getName() + " ha incarcerato "
                            + target.getName() + " (" + reason + ")");
                })
                .execute((finished, exception) -> {
                    if (exception != null) {
                        plugin.getLogger().log(Level.SEVERE, "[Cmd] Errore in /jail", exception);
                        sender.sendMessage(ChatColor.RED + "Errore durante l esecuzione, controlla la console.");
                    }
                });
    }

    // ------------------------------------------------------------------
    // /jailinfo
    // ------------------------------------------------------------------

    @CommandAlias("jailinfo")
    @CommandPermission("jailmining.player.info")
    @Description("Mostra lo stato corrente della tua detenzione")
    public void onJailInfo(Player player, @Optional JailPlayerData cached) {
        // Il context resolver inietta il dato dalla cache; se manca lo rileggiamo dal database
        if (cached != null) {
            printInfo(player, cached);
            return;
        }

        plugin.<JailPlayerData>newChain()
                .asyncFirst(() -> plugin.getDatabaseManager().getJailedPlayer(player.getUniqueId()).join())
                .syncLast(data -> {
                    if (data == null) {
                        player.sendMessage(ChatColor.GREEN + "Non sei detenuto: nessun dato nel database.");
                        return;
                    }
                    printInfo(player, data);
                })
                .execute((finished, exception) -> {
                    if (exception != null) {
                        plugin.getLogger().log(Level.SEVERE, "[Cmd] Errore in /jailinfo", exception);
                        player.sendMessage(ChatColor.RED + "Errore leggendo i tuoi dati.");
                    }
                });
    }

    private void printInfo(Player player, JailPlayerData data) {
        Map<String, String> placeholders = Placeholders.withJail(plugin, player, data);
        player.sendMessage(ChatColor.DARK_RED + "--- Stato detenzione ---");
        player.sendMessage(ChatColor.GRAY + "Motivo: " + ChatColor.WHITE + data.getBanReason());
        plugin.send(player, "ban-time-remaining", placeholders);
        plugin.send(player, "jail-credits", placeholders);
        player.sendMessage(ChatColor.GRAY + "Pena scontata: " + ChatColor.WHITE
                + placeholders.get("progress") + "%");
        player.sendMessage(ChatColor.GRAY + "Crediti mancanti: " + ChatColor.WHITE
                + placeholders.get("missing"));
    }
}
