package dev.lorenzzz.cundannatu.spigot;

import co.aikar.commands.PaperCommandManager;
import co.aikar.taskchain.BukkitTaskChainFactory;
import co.aikar.taskchain.TaskChain;
import co.aikar.taskchain.TaskChainFactory;
import dev.lorenzzz.cundannatu.api.JailProvider;
import dev.lorenzzz.cundannatu.api.JailService;
import dev.lorenzzz.cundannatu.common.JailChannel;
import dev.lorenzzz.cundannatu.common.JailUnbanRequest;
import dev.lorenzzz.cundannatu.spigot.api.JailServiceImpl;
import dev.lorenzzz.cundannatu.spigot.ban.BanManager;
import dev.lorenzzz.cundannatu.spigot.ban.BanWatchdog;
import dev.lorenzzz.cundannatu.spigot.ban.ReleaseService;
import dev.lorenzzz.cundannatu.spigot.mining.MiningGuard;
import dev.lorenzzz.cundannatu.spigot.task.SentenceTicker;
import dev.lorenzzz.cundannatu.spigot.command.JailAdminCommands;
import dev.lorenzzz.cundannatu.spigot.command.JailCommands;
import dev.lorenzzz.cundannatu.spigot.command.JailHelpCommands;
import dev.lorenzzz.cundannatu.common.JailPlayerData;
import dev.lorenzzz.cundannatu.spigot.db.DatabaseManager;
import dev.lorenzzz.cundannatu.spigot.item.JailPickaxe;
import dev.lorenzzz.cundannatu.spigot.mining.OreRegenManager;
import dev.lorenzzz.cundannatu.spigot.listener.BlockBreakListener;
import dev.lorenzzz.cundannatu.spigot.listener.ChatListener;
import dev.lorenzzz.cundannatu.spigot.listener.JailChannelListener;
import dev.lorenzzz.cundannatu.spigot.listener.JailSpawnListener;
import dev.lorenzzz.cundannatu.spigot.listener.ProtectionListener;
import dev.lorenzzz.cundannatu.spigot.task.JailScoreboardUpdater;
import dev.lorenzzz.cundannatu.spigot.task.TabListManager;
import dev.lorenzzz.cundannatu.spigot.util.ConfigUpdater;
import dev.lorenzzz.cundannatu.spigot.util.PlaceholderHook;
import dev.lorenzzz.cundannatu.spigot.util.Text;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Classe principale del lato Spigot (server "jail").
 * <p>
 * Si occupa di:
 * <ul>
 *     <li>caricare config.yml e database.yml</li>
 *     <li>inizializzare HikariCP tramite {@link DatabaseManager}</li>
 *     <li>registrare listener, canale plugin e comandi ACF di Aikar</li>
 *     <li>avviare il {@link JailScoreboardUpdater} asincrono ogni 20 tick</li>
 * </ul>
 */
public final class JailMiningPlugin extends JavaPlugin {

    /** Canale usato dal proxy Velocity per inviare i dati del ban. */
    public static final String CHANNEL = JailChannel.NAME;

    private static JailMiningPlugin instance;

    private FileConfiguration databaseConfig;
    private DatabaseManager databaseManager;
    private BanManager banManager;
    private JailScoreboardUpdater scoreboardUpdater;
    private PaperCommandManager commandManager;
    private JailServiceImpl jailService;
    private JailPickaxe jailPickaxe;
    private OreRegenManager oreRegenManager;
    private TabListManager tabListManager;
    private SentenceTicker sentenceTicker;
    private BanWatchdog banWatchdog;
    private MiningGuard miningGuard;
    private ReleaseService releaseService;
    private TaskChainFactory taskChainFactory;

    /** Ponte Adventure verso i giocatori: necessario per i Component di MiniMessage. */
    private BukkitAudiences audiences;

    /** Aggancio opzionale a PlaceholderAPI per i %placeholder% nei testi. */
    private PlaceholderHook placeholderHook;

    /** Cache dei blocchi "carbone" letti da config: invalidata dal reload. */
    private volatile Set<Material> coalBlocks;

    public static JailMiningPlugin getInstance() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;

        // 1) Configurazioni: le opzioni nuove vengono aggiunte automaticamente al file esistente
        saveDefaultConfig();
        ConfigUpdater.update(this, "config.yml");
        saveResourceIfMissing("database.yml");
        this.databaseConfig = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "database.yml"));

        if (!getConfig().getBoolean("jail-mining.enabled", true)) {
            getLogger().warning("jail-mining.enabled = false, il plugin resta inattivo");
            return;
        }

        // 2) Adventure: serve per inviare i Component di MiniMessage (click, hover, gradienti)
        this.audiences = BukkitAudiences.create(this);
        this.placeholderHook = new PlaceholderHook(this);

        // 3) Scheduler Aikar (TaskChain): pattern async/sync per tutte le operazioni pesanti
        this.taskChainFactory = BukkitTaskChainFactory.create(this);

        // 3) Database + pool HikariCP
        this.databaseManager = new DatabaseManager(this);
        try {
            databaseManager.connect();
            databaseManager.initializeDatabase().join();
            databaseManager.logPoolStatus();
        } catch (Exception exception) {
            getLogger().log(Level.SEVERE, "Impossibile inizializzare il database, disabilito il plugin", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 4) Manager dei ban (LiteBans)
        this.banManager = new BanManager(this, databaseManager);

        // 5) Piccone del detenuto, rigenerazione della miniera e controllo anti fast-break
        this.jailPickaxe = new JailPickaxe(this);
        this.oreRegenManager = new OreRegenManager(this);
        this.miningGuard = new MiningGuard(this);
        this.releaseService = new ReleaseService(this);

        // 6) Listener
        getServer().getPluginManager().registerEvents(new JailSpawnListener(this), this);
        getServer().getPluginManager().registerEvents(new BlockBreakListener(this), this);
        getServer().getPluginManager().registerEvents(new ChatListener(this), this);
        getServer().getPluginManager().registerEvents(new ProtectionListener(this), this);

        // 6) Canale plugin in arrivo dal proxy
        getServer().getMessenger().registerIncomingPluginChannel(this, CHANNEL, new JailChannelListener(this));
        getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL);
        getLogger().info("Canale plugin " + CHANNEL + " registrato (in entrata e in uscita)");

        // 7) Comandi Aikar ACF
        registerCommands();

        // 8) API pubbliche: JailProvider + ServicesManager di Bukkit
        this.jailService = new JailServiceImpl(this);
        JailProvider.register(jailService);
        getServer().getServicesManager().register(JailService.class, jailService, this, ServicePriority.Normal);
        getLogger().info("JailService registrato (dev.lorenzzz.cundannatu.api)");

        // 9) Scoreboard asincrona (intervallo da config)
        this.scoreboardUpdater = new JailScoreboardUpdater(this);
        scoreboardUpdater.start();

        // 10) Header/footer della tab list
        this.tabListManager = new TabListManager(this);
        tabListManager.start();

        // 11) Conto alla rovescia delle pene a termine
        this.sentenceTicker = new SentenceTicker(this);
        sentenceTicker.start();

        // 12) Rilevamento degli sban decisi fuori dal plugin (staff, store, appeal)
        this.banWatchdog = new BanWatchdog(this);
        banWatchdog.start();

        getLogger().info("JailMining abilitato - server jail: "
                + getConfig().getString("jail-mining.jail-server-name", "jail"));
    }

    @Override
    public void onDisable() {
        JailProvider.unregister();
        getServer().getServicesManager().unregisterAll(this);

        if (scoreboardUpdater != null) {
            scoreboardUpdater.stop();
        }
        if (tabListManager != null) {
            tabListManager.stop();
        }
        if (sentenceTicker != null) {
            sentenceTicker.stop();
        }
        if (banWatchdog != null) {
            banWatchdog.stop();
        }
        if (miningGuard != null) {
            miningGuard.clear();
        }
        if (oreRegenManager != null) {
            // Rimettiamo il carbone prima dello spegnimento
            oreRegenManager.restoreAll();
        }
        if (commandManager != null) {
            commandManager.unregisterCommands();
        }
        getServer().getMessenger().unregisterIncomingPluginChannel(this);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this);

        if (banManager != null) {
            banManager.shutdown();
        }

        // Persistenza dei dati prima dello stop: flush della cache e chiusura del pool
        if (databaseManager != null) {
            databaseManager.shutdown();
        }
        if (audiences != null) {
            audiences.close();
            audiences = null;
        }
        getLogger().info("JailMining disabilitato");
    }

    // ------------------------------------------------------------------
    // COMANDI ACF
    // ------------------------------------------------------------------

    private void registerCommands() {
        this.commandManager = new PaperCommandManager(this);
        commandManager.enableUnstableAPI("help"); // help automatico di ACF

        // Completion custom: solo i giocatori attualmente in jail
        commandManager.getCommandCompletions().registerAsyncCompletion("jailed", context ->
                Bukkit.getOnlinePlayers().stream()
                        .filter(player -> databaseManager.getCached(player.getUniqueId()) != null)
                        .map(Player::getName)
                        .toList());

        // Context resolver: inietta direttamente il JailPlayerData del giocatore che esegue il comando
        commandManager.getCommandContexts().registerIssuerOnlyContext(JailPlayerData.class, context -> {
            Player player = context.getPlayer();
            if (player == null) {
                throw new co.aikar.commands.InvalidCommandArgument("Comando disponibile solo in gioco", false);
            }
            return databaseManager.getCached(player.getUniqueId());
        });

        commandManager.registerCommand(new JailCommands(this));
        commandManager.registerCommand(new JailAdminCommands(this));
        commandManager.registerCommand(new JailHelpCommands(this));
        getLogger().info("Comandi ACF registrati");
    }

    /** Ricarica config.yml, database.yml, i moduli grafici e rigenera il pool di connessioni. */
    public void reloadAll() {
        reloadConfig();
        ConfigUpdater.update(this, "config.yml");
        this.databaseConfig = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "database.yml"));
        databaseManager.reload();
        this.coalBlocks = null; // ricalcolata alla prossima lettura

        // Scoreboard e tab ripartono con i nuovi intervalli e le nuove righe
        if (scoreboardUpdater != null) {
            scoreboardUpdater.start();
        }
        if (tabListManager != null) {
            tabListManager.start();
        }
        if (sentenceTicker != null) {
            sentenceTicker.start();
        }
        if (banWatchdog != null) {
            banWatchdog.start();
        }
        getLogger().info("Configurazione ricaricata: pool, scoreboard, tab, timer e watchdog aggiornati");
    }

    // ------------------------------------------------------------------
    // ACCESSORI
    // ------------------------------------------------------------------

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public BanManager getBanManager() {
        return banManager;
    }

    /** Servizio pubblico esposto agli altri plugin. */
    public JailService getJailService() {
        return jailService;
    }

    public JailPickaxe getJailPickaxe() {
        return jailPickaxe;
    }

    public OreRegenManager getOreRegenManager() {
        return oreRegenManager;
    }

    public TabListManager getTabListManager() {
        return tabListManager;
    }

    public SentenceTicker getSentenceTicker() {
        return sentenceTicker;
    }

    public BanWatchdog getBanWatchdog() {
        return banWatchdog;
    }

    public MiningGuard getMiningGuard() {
        return miningGuard;
    }

    /** Punto unico di uscita dalla prigione (crediti, tempo, sban esterno). */
    public ReleaseService getReleaseService() {
        return releaseService;
    }

    /**
     * true se la pena e' di fatto un ergastolo: durata pari o superiore alla soglia
     * di {@code permanent-ban-start-years}. Da questi ban il tempo non scorre da solo.
     */
    public boolean isPermanentSentence(dev.lorenzzz.cundannatu.common.JailPlayerData data) {
        return data != null && data.getOriginalDuration() >= getBanManager().permanentBanMillis();
    }

    /** true se in prigione si puo' rompere soltanto il carbone. */
    public boolean isOnlyCoalMining() {
        return getConfig().getBoolean("jail-mining.only-coal-mining", true);
    }

    /**
     * Blocchi che riducono la pena, letti da {@code jail-mining.coal-blocks}.
     * Il risultato viene messo in cache e invalidato dal reload.
     */
    public Set<Material> getCoalBlocks() {
        Set<Material> cached = this.coalBlocks;
        if (cached != null) {
            return cached;
        }
        Set<Material> materials = EnumSet.noneOf(Material.class);
        for (String name : getConfig().getStringList("jail-mining.coal-blocks")) {
            try {
                Material material = Material.valueOf(name.toUpperCase(Locale.ROOT));
                if (material.isBlock()) {
                    materials.add(material);
                } else {
                    getLogger().warning("[Config] coal-blocks: " + name + " non e' un blocco");
                }
            } catch (IllegalArgumentException exception) {
                getLogger().warning("[Config] coal-blocks: materiale sconosciuto " + name);
            }
        }
        if (materials.isEmpty()) {
            materials.add(Material.COAL_ORE);
            materials.add(Material.DEEPSLATE_COAL_ORE);
        }
        this.coalBlocks = materials;
        return materials;
    }

    public JailScoreboardUpdater getScoreboardUpdater() {
        return scoreboardUpdater;
    }

    public FileConfiguration getDatabaseConfig() {
        return databaseConfig;
    }

    /** Nuova catena Aikar: {@code newChain().asyncFirst(...).syncLast(...).execute()}. */
    public <T> TaskChain<T> newChain() {
        return taskChainFactory.newChain();
    }

    // ------------------------------------------------------------------
    // HELPER DI CONFIGURAZIONE
    // ------------------------------------------------------------------

    public String getJailServerName() {
        return getConfig().getString("jail-mining.jail-server-name", "jail");
    }

    /** Punto di spawn della prigione letto da config.yml. */
    public Location getJailSpawn() {
        String worldName = getConfig().getString("jail-mining.spawn-location.world", "world");
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            getLogger().warning("Mondo '" + worldName + "' non trovato, uso il primo mondo disponibile");
            world = Bukkit.getWorlds().get(0);
        }
        return new Location(world,
                getConfig().getDouble("jail-mining.spawn-location.x", 0D),
                getConfig().getDouble("jail-mining.spawn-location.y", 64D),
                getConfig().getDouble("jail-mining.spawn-location.z", 0D),
                (float) getConfig().getDouble("jail-mining.spawn-location.yaw", 0D),
                (float) getConfig().getDouble("jail-mining.spawn-location.pitch", 0D));
    }

    public long getCarboneReductionSeconds() {
        return getConfig().getLong("jail-mining.carbone-reduction-seconds", 3L);
    }

    public long getCarboneReductionMillis() {
        return getCarboneReductionSeconds() * 1000L;
    }

    /** Crediti assegnati ogni volta che si completa la soglia di blocchi. */
    public int getCreditsPerCoal() {
        return getConfig().getInt("jail-mining.credits-per-coal-block", 1);
    }

    /**
     * Blocchi di carbone da scavare per maturare i crediti.
     * Con il valore di default (500) un credito richiede 500 blocchi.
     */
    public long getBlocksPerCredit() {
        return Math.max(1L, getConfig().getLong("jail-mining.blocks-per-credit", 500L));
    }

    public int getCreditsForFreedom() {
        return getConfig().getInt("jail-mining.credits-required-for-freedom", 50);
    }

    public List<String> getAllowedCommands() {
        return getConfig().getStringList("jail-mining.allowed-commands");
    }

    /** Testo grezzo di un messaggio, senza traduzione dei colori. */
    public String getRawMessage(String key) {
        return getConfig().getString("jail-mining.messages." + key, "");
    }

    /**
     * Legge un messaggio da config traducendo colori e placeholder globali
     * ({@code {seconds}}, {@code {required}}, {@code {credits_per_block}}, {@code {server}}).
     */
    public String getMessage(String key) {
        return getMessage(key, null);
    }

    /** Come {@link #getMessage(String)} ma con placeholder aggiuntivi. */
    public String getMessage(String key, Map<String, String> placeholders) {
        return Text.format(applyGlobalPlaceholders(getRawMessage(key)), placeholders);
    }

    /**
     * Sostituisce i placeholder che dipendono solo dalla configurazione, disponibili
     * in ogni testo: {@code {seconds} {blocks_per_credit} {credits_per_threshold}
     * {required} {server}}.
     */
    public String applyGlobalPlaceholders(String raw) {
        if (raw == null) {
            return "";
        }
        return raw
                .replace("{seconds}", String.valueOf(getCarboneReductionSeconds()))
                .replace("{blocks_per_credit}", String.valueOf(getBlocksPerCredit()))
                .replace("{credits_per_threshold}", String.valueOf(getCreditsPerCoal()))
                .replace("{credits_per_block}", String.valueOf(getCreditsPerCoal()))
                .replace("{required}", String.valueOf(getCreditsForFreedom()))
                .replace("{server}", getJailServerName());
    }

    /** Ponte Adventure: {@code null} solo se il plugin non e' stato abilitato. */
    public BukkitAudiences audiences() {
        return audiences;
    }

    public PlaceholderHook getPlaceholderHook() {
        return placeholderHook;
    }

    /**
     * Trasforma una riga di configurazione nel {@link Component} finale, applicando
     * nell ordine: placeholder globali, placeholder interni {@code {chiave}},
     * placeholder di PlaceholderAPI e infine colori (MiniMessage e legacy insieme).
     */
    public Component parse(Player viewer, String raw, Map<String, String> placeholders) {
        if (raw == null || raw.isEmpty()) {
            return Component.empty();
        }
        String text = applyGlobalPlaceholders(raw);
        if (placeholders != null) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                text = text.replace("{" + entry.getKey() + "}", entry.getValue());
            }
        }
        if (placeholderHook != null) {
            text = placeholderHook.apply(viewer, text);
        }
        return Text.component(text);
    }

    /**
     * Come {@link #parse}, ma restituisce testo legacy (con i {@code §}).
     * <p>
     * Serve alle API che accettano solo stringhe: sidebar FastBoard, nome nella tab
     * list, lore degli item. Click, hover e {@code <shadow>} non sono rappresentabili
     * in questo formato e vengono persi; colori esadecimali e stili restano.
     */
    public String parseLegacy(Player viewer, String raw, Map<String, String> placeholders) {
        return Text.toLegacy(parse(viewer, raw, placeholders));
    }

    /** Versione su lista di {@link #parseLegacy}. */
    public List<String> parseLegacyAll(Player viewer, List<String> lines, Map<String, String> placeholders) {
        List<String> result = new ArrayList<>(lines == null ? 0 : lines.size());
        if (lines == null) {
            return result;
        }
        for (String line : lines) {
            result.add(parseLegacy(viewer, line, placeholders));
        }
        return result;
    }

    /** Righe unite in un solo Component multilinea (header e footer del tab). */
    public Component parseJoined(Player viewer, List<String> lines, Map<String, String> placeholders) {
        List<Component> parsed = parseAll(viewer, lines, placeholders);
        Component result = Component.empty();
        for (int index = 0; index < parsed.size(); index++) {
            if (index > 0) {
                result = result.append(Component.newline());
            }
            result = result.append(parsed.get(index));
        }
        return result;
    }

    /** Versione su lista di {@link #parse}. */
    public List<Component> parseAll(Player viewer, List<String> lines, Map<String, String> placeholders) {
        List<Component> result = new ArrayList<>(lines == null ? 0 : lines.size());
        if (lines == null) {
            return result;
        }
        for (String line : lines) {
            result.add(parse(viewer, line, placeholders));
        }
        return result;
    }

    /**
     * Invia un messaggio di configurazione, interpretando MiniMessage o codici legacy.
     * <p>
     * Passando dai Component, i tag {@code <click>} e {@code <hover>} scritti in config
     * restano funzionanti: con il vecchio {@code sendMessage(String)} andavano persi.
     */
    public void send(CommandSender target, String messageKey, Map<String, String> placeholders) {
        String raw = applyGlobalPlaceholders(getRawMessage(messageKey));
        if (raw.isBlank()) {
            return;
        }
        sendRaw(target, raw, placeholders);
    }

    public void send(CommandSender target, String messageKey) {
        send(target, messageKey, null);
    }

    /** Come {@link #send}, ma partendo da un testo gia' letto altrove nel config. */
    public void sendRaw(CommandSender target, String rawText, Map<String, String> placeholders) {
        if (rawText == null || rawText.isBlank()) {
            return;
        }
        Component component = parse(target instanceof Player player ? player : null, rawText, placeholders);
        if (audiences != null) {
            audiences.sender(target).sendMessage(component);
        } else {
            // Fallback difensivo: senza Adventure restiamo sul testo legacy
            target.sendMessage(Text.format(rawText, placeholders));
        }
    }

    /** Barra di progresso generica (crediti, blocchi...), con lunghezza e colori da config. */
    public String buildProgressBar(double credits, double required) {
        String path = "jail-mining.scoreboard.progress-bar.";
        return Text.progressBar(credits, required,
                getConfig().getInt(path + "length", 20),
                getConfig().getString(path + "symbol", "|"),
                getConfig().getString(path + "filled-color", "&a"),
                getConfig().getString(path + "empty-color", "&8"));
    }

    /** Legge un suono da config con fallback se il nome non esiste. */
    public Sound getSound(String key, Sound fallback) {
        String name = getConfig().getString("jail-mining.sounds." + key, "");
        if (name == null || name.isBlank()) {
            return fallback;
        }
        try {
            return Sound.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            getLogger().warning("Suono sconosciuto in config: " + name);
            return fallback;
        }
    }

    /** true se il giocatore e' un detenuto attivo (dato presente in cache). */
    public boolean isJailed(UUID uuid) {
        return databaseManager.getCached(uuid) != null;
    }

    /**
     * Chiede al proxy di eseguire lo sban con {@code /ltunban}.
     * <p>
     * Serve quando LiteBans e' installato solo sul proxy: il messaggio viaggia
     * sul canale {@code jailmining:data} e richiede almeno un giocatore online
     * (i plugin message di Bukkit passano sempre dalla connessione di un player).
     *
     * @return true se il messaggio e' stato inoltrato
     */
    public boolean requestProxyUnban(UUID uuid, String name, String reason) {
        return requestProxyUnban(uuid, name, reason, null);
    }

    /**
     * Come {@link #requestProxyUnban(UUID, String, String)}, inoltrando al proxy anche
     * il comando da eseguire in console (letto da {@code jail-mining.unban.command}).
     */
    public boolean requestProxyUnban(UUID uuid, String name, String reason, String command) {
        Player carrier = Bukkit.getPlayer(uuid);
        if (carrier == null || !carrier.isOnline()) {
            carrier = Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
        }
        if (carrier == null) {
            getLogger().warning("[Channel] Nessun giocatore online: impossibile inoltrare lo sban di " + uuid);
            return false;
        }
        carrier.sendPluginMessage(this, CHANNEL, JailUnbanRequest.of(uuid, name, reason, command).toBytes());
        getLogger().info("[Channel] Richiesta di sban inoltrata al proxy per " + uuid
                + (command == null ? "" : " -> /" + command));
        return true;
    }

    private void saveResourceIfMissing(String resource) {
        File file = new File(getDataFolder(), resource);
        if (!file.exists()) {
            saveResource(resource, false);
        }
    }
}
