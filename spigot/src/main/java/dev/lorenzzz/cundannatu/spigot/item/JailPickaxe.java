package dev.lorenzzz.cundannatu.spigot.item;

import dev.lorenzzz.cundannatu.spigot.JailMiningPlugin;
import dev.lorenzzz.cundannatu.spigot.util.Text;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Piccone consegnato al detenuto per scavare il carbone.
 * <p>
 * L oggetto viene marchiato con una chiave nel PersistentDataContainer, cosi da
 * poterlo riconoscere in modo affidabile anche se il giocatore lo rinomina.
 */
public class JailPickaxe {

    private static final String CONFIG_PATH = "jail-mining.pickaxe.";

    private final JailMiningPlugin plugin;
    private final NamespacedKey key;

    public JailPickaxe(JailMiningPlugin plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "jail_pickaxe");
    }

    public boolean isEnabled() {
        return plugin.getConfig().getBoolean(CONFIG_PATH + "enabled", true);
    }

    public boolean isDropPrevented() {
        return plugin.getConfig().getBoolean(CONFIG_PATH + "prevent-drop", true);
    }

    /** true se il piccone non puo' essere spostato nell inventario. */
    public boolean isMovePrevented() {
        return plugin.getConfig().getBoolean(CONFIG_PATH + "prevent-move", true);
    }

    /** true se il piccone va consegnato automaticamente all ingresso in prigione. */
    public boolean isGivenOnJoin() {
        return isEnabled() && plugin.getConfig().getBoolean(CONFIG_PATH + "give-on-join", true);
    }

    /** Costruisce il piccone leggendo la configurazione. */
    public ItemStack create() {
        Material material = resolveMaterial();
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }

        // Nome e lore accettano MiniMessage, codici & e i placeholder di configurazione.
        // Sugli item si usa il testo legacy: ItemMeta di Spigot non accetta Component.
        String name = plugin.getConfig().getString(CONFIG_PATH + "name", "&e⛏ Piccone del detenuto");
        meta.setDisplayName(Text.legacy(plugin.applyGlobalPlaceholders(name)));

        List<String> lore = new ArrayList<>();
        for (String line : plugin.getConfig().getStringList(CONFIG_PATH + "lore")) {
            lore.add(Text.legacy(plugin.applyGlobalPlaceholders(line)));
        }
        if (!lore.isEmpty()) {
            meta.setLore(lore);
        }

        meta.setUnbreakable(plugin.getConfig().getBoolean(CONFIG_PATH + "unbreakable", true));

        int efficiency = plugin.getConfig().getInt(CONFIG_PATH + "efficiency", 0);
        if (efficiency > 0) {
            meta.addEnchant(Enchantment.EFFICIENCY, efficiency, true);
        }

        // Marchio interno per riconoscere il piccone
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    /**
     * Consegna il piccone al detenuto, se non ne ha gia' uno.
     * Da eseguire sul thread principale.
     */
    public void give(Player player) {
        give(player, false);
    }

    /**
     * Consegna il piccone al detenuto.
     *
     * @param force true per consegnarlo anche a chi ne possiede gia' uno
     *              (usato da {@code /jailadmin pickaxe})
     */
    public void give(Player player, boolean force) {
        if (!isEnabled() || (!force && has(player))) {
            return;
        }
        PlayerInventory inventory = player.getInventory();
        int slot = plugin.getConfig().getInt(CONFIG_PATH + "slot", 0);

        if (slot >= 0 && slot <= 8) {
            ItemStack current = inventory.getItem(slot);
            if (current == null || current.getType() == Material.AIR) {
                inventory.setItem(slot, create());
            } else {
                inventory.addItem(create());
            }
        } else {
            inventory.addItem(create());
        }
        plugin.getLogger().fine("[Pickaxe] Consegnato il piccone a " + player.getName());
    }

    /** true se l oggetto e' il piccone del jail. */
    public boolean isJailPickaxe(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    /** true se il giocatore ha gia' il piccone in inventario. */
    public boolean has(Player player) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (isJailPickaxe(item)) {
                return true;
            }
        }
        return false;
    }

    private Material resolveMaterial() {
        String name = plugin.getConfig().getString(CONFIG_PATH + "material", "IRON_PICKAXE");
        try {
            Material material = Material.valueOf(name.toUpperCase(Locale.ROOT));
            if (material.isItem()) {
                return material;
            }
        } catch (IllegalArgumentException ignored) {
            // gestito sotto
        }
        plugin.getLogger().warning("[Pickaxe] Materiale non valido in config: " + name + ", uso IRON_PICKAXE");
        return Material.IRON_PICKAXE;
    }
}
