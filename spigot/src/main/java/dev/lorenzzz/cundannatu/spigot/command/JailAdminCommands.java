package dev.lorenzzz.cundannatu.spigot.command;

import co.aikar.commands.BaseCommand;
import co.aikar.commands.CommandHelp;
import co.aikar.commands.annotation.CommandAlias;
import co.aikar.commands.annotation.CommandCompletion;
import co.aikar.commands.annotation.CommandPermission;
import co.aikar.commands.annotation.Default;
import co.aikar.commands.annotation.Description;
import co.aikar.commands.annotation.HelpCommand;
import co.aikar.commands.annotation.Optional;
import co.aikar.commands.annotation.Subcommand;
import co.aikar.commands.annotation.Syntax;
import co.aikar.commands.contexts.OnlinePlayer;
import dev.lorenzzz.cundannatu.spigot.JailMiningPlugin;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.logging.Level;

/**
 * Comandi amministrativi: {@code /jailadmin reload}, {@code /jailadmin resetdb},
 * {@code /jailadmin status} e {@code /jailadmin free}.
 */
@CommandAlias("jailadmin|ja")
@CommandPermission("jailmining.admin")
@Description("Comandi amministrativi del sistema jail")
public class JailAdminCommands extends BaseCommand {

    private final JailMiningPlugin plugin;

    public JailAdminCommands(JailMiningPlugin plugin) {
        this.plugin = plugin;
    }

    @Default
    @HelpCommand
    @Description("Mostra l elenco dei sottocomandi")
    public void onDefault(CommandSender sender, CommandHelp help) {
        help.showHelp();
    }

    @Subcommand("reload")
    @CommandPermission("jailmining.admin.reload")
    @Description("Ricarica config.yml, database.yml e il pool di connessioni")
    public void onReload(CommandSender sender) {
        try {
            plugin.reloadAll();
            sender.sendMessage(ChatColor.GREEN + "Configurazione ricaricata, pool di connessioni aggiornato.");
        } catch (Exception exception) {
            plugin.getLogger().log(Level.SEVERE, "[Cmd] Errore nel reload", exception);
            sender.sendMessage(ChatColor.RED + "Errore nel reload, controlla la console.");
        }
    }

    @Subcommand("resetdb")
    @CommandPermission("jailmining.admin.resetdb")
    @CommandCompletion("conferma")
    @Syntax("<conferma>")
    @Description("Svuota la tabella jailed_players (operazione irreversibile)")
    public void onResetDb(CommandSender sender, @Optional String conferma) {
        if (!"conferma".equalsIgnoreCase(conferma)) {
            sender.sendMessage(ChatColor.RED + "Operazione irreversibile: conferma con "
                    + ChatColor.YELLOW + "/jailadmin resetdb conferma");
            return;
        }

        plugin.<Integer>newChain()
                .asyncFirst(() -> plugin.getDatabaseManager().resetDatabase().join())
                .syncLast(rows -> {
                    if (rows == null || rows < 0) {
                        sender.sendMessage(ChatColor.RED + "Reset fallito, controlla la console.");
                        return;
                    }
                    sender.sendMessage(ChatColor.GREEN + "Database ripulito: " + rows + " righe eliminate.");
                    plugin.getLogger().warning("[Cmd] " + sender.getName() + " ha resettato jailed_players");
                })
                .execute((finished, exception) -> {
                    if (exception != null) {
                        plugin.getLogger().log(Level.SEVERE, "[Cmd] Errore nel resetdb", exception);
                    }
                });
    }

    @Subcommand("status")
    @Description("Scrive nei log lo stato del pool di connessioni")
    public void onStatus(CommandSender sender) {
        plugin.getDatabaseManager().logPoolStatus();
        sender.sendMessage(ChatColor.GREEN + "Stato del pool scritto nei log del server.");
    }

    @Subcommand("pickaxe|piccone")
    @CommandPermission("jailmining.admin.pickaxe")
    @CommandCompletion("@jailed")
    @Syntax("[giocatore]")
    @Description("Riconsegna il piccone del detenuto (utile se e' andato perso)")
    public void onPickaxe(CommandSender sender, @Optional OnlinePlayer target) {
        Player receiver;
        if (target != null) {
            receiver = target.getPlayer();
        } else if (sender instanceof Player self) {
            receiver = self;
        } else {
            sender.sendMessage(ChatColor.RED + "Dalla console devi indicare il giocatore: "
                    + ChatColor.YELLOW + "/jailadmin pickaxe <giocatore>");
            return;
        }

        if (!plugin.getJailPickaxe().isEnabled()) {
            sender.sendMessage(ChatColor.RED + "Il piccone e' disattivato in config (pickaxe.enabled: false).");
            return;
        }

        boolean hadOne = plugin.getJailPickaxe().has(receiver);
        // force = true: il comando admin lo consegna anche a chi ne ha gia' uno
        plugin.getJailPickaxe().give(receiver, true);

        sender.sendMessage(ChatColor.GREEN + "Piccone consegnato a " + receiver.getName()
                + (hadOne ? ChatColor.GRAY + " (ne aveva gia' uno)" : ""));
        if (!receiver.equals(sender)) {
            plugin.send(receiver, "pickaxe-given");
        }
        plugin.getLogger().info("[Cmd] " + sender.getName() + " ha consegnato il piccone a " + receiver.getName());
    }

    @Subcommand("free")
    @CommandPermission("jailmining.admin.free")
    @CommandCompletion("@jailed")
    @Syntax("<giocatore>")
    @Description("Libera immediatamente un detenuto")
    public void onFree(CommandSender sender, OfflinePlayer target) {
        UUID uuid = target.getUniqueId();
        plugin.<Boolean>newChain()
                .asyncFirst(() -> plugin.getBanManager().unbanPlayer(uuid).join())
                .syncLast(success -> {
                    sender.sendMessage(ChatColor.GREEN + target.getName() + " e' stato liberato.");
                    Player online = target.getPlayer();
                    if (online != null && online.isOnline()) {
                        // Sidebar e tab tornano al layout "nessuna pena"
                        plugin.getScoreboardUpdater().showFreeBoard(online);
                        plugin.getTabListManager().showFreeTab(online);
                        plugin.send(online, "freedom-achieved");
                    }
                })
                .execute((finished, exception) -> {
                    if (exception != null) {
                        plugin.getLogger().log(Level.SEVERE, "[Cmd] Errore nella liberazione di " + uuid, exception);
                    }
                });
    }
}
