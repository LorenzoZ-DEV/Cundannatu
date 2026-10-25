package dev.lorenzzz.cundannatu.proxy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import dev.lorenzzz.cundannatu.common.JailChannel;
import litebans.api.Database;
import litebans.api.Entry;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Plugin Velocity: intercetta i ban di LiteBans e dirotta i giocatori bannati
 * sul server jail invece di espellerli dalla rete.
 * <p>
 * I dati del ban (UUID, motivo, durata originale, durata residua) vengono inviati al
 * backend sul canale plugin {@code jailmining:data} in formato JSON.
 */
@Plugin(
        id = "jailmining",
        name = "JailMining Redirect",
        version = "1.0-SNAPSHOT",
        description = "Redirect dei giocatori bannati sul server jail",
        authors = {"Lorenzzzzzz"},
        dependencies = {@Dependency(id = "litebans", optional = true)}
)
public class VelocityJailRedirect {

    /** Canale plugin condiviso con il backend Spigot. */
    public static final MinecraftChannelIdentifier CHANNEL =
            MinecraftChannelIdentifier.from(JailChannel.NAME);

    private static final long MILLIS_PER_YEAR = TimeUnit.DAYS.toMillis(365L);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;

    private ProxyConfig config = new ProxyConfig();
    private LiteBansHook liteBans;
    private UnbanWatcher unbanWatcher;

    @Inject
    public VelocityJailRedirect(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        loadConfig();

        this.liteBans = new LiteBansHook(this);
        proxy.getChannelRegistrar().register(CHANNEL);
        proxy.getEventManager().register(this, new BanRedirectListener(this));

        // Avvisa il server jail quando un ban viene rimosso fuori dal plugin
        this.unbanWatcher = new UnbanWatcher(this);
        unbanWatcher.start();

        logger.info("JailMining Redirect attivo - server jail: '{}', ban permanente = {} anni",
                config.jailServerName, config.permanentBanYears);

        if (proxy.getServer(config.jailServerName).isEmpty()) {
            logger.warn("Il server '{}' non e' presente in velocity.toml: il redirect non funzionera",
                    config.jailServerName);
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (unbanWatcher != null) {
            unbanWatcher.stop();
        }
        proxy.getChannelRegistrar().unregister(CHANNEL);
        logger.info("JailMining Redirect disattivato");
    }

    // ------------------------------------------------------------------
    // CONFIGURAZIONE
    // ------------------------------------------------------------------

    /** Carica (o crea) {@code plugins/jailmining/config.json}. */
    private void loadConfig() {
        try {
            Files.createDirectories(dataDirectory);
            Path file = dataDirectory.resolve("config.json");
            if (Files.notExists(file)) {
                try (Writer writer = Files.newBufferedWriter(file)) {
                    GSON.toJson(new ProxyConfig(), writer);
                }
                logger.info("Creato config.json di default");
            }
            try (Reader reader = Files.newBufferedReader(file)) {
                ProxyConfig loaded = GSON.fromJson(reader, ProxyConfig.class);
                if (loaded != null) {
                    this.config = loaded;
                }
            }
        } catch (IOException exception) {
            logger.error("Impossibile leggere la configurazione, uso i valori di default", exception);
        }
    }

    // ------------------------------------------------------------------
    // ACCESSORI
    // ------------------------------------------------------------------

    public ProxyServer getProxy() {
        return proxy;
    }

    public Logger getLogger() {
        return logger;
    }

    public MinecraftChannelIdentifier getChannel() {
        return CHANNEL;
    }

    public LiteBansHook getLiteBans() {
        return liteBans;
    }

    public String getJailServerName() {
        return config.jailServerName;
    }

    public Optional<RegisteredServer> getJailServer() {
        return proxy.getServer(config.jailServerName);
    }

    /** Durata assegnata ai ban permanenti (default 10 anni). */
    public long getPermanentBanMillis() {
        return config.permanentBanYears * MILLIS_PER_YEAR;
    }

    /** true se il proxy deve sorvegliare gli sban dei detenuti collegati al jail. */
    public boolean isUnbanWatcherEnabled() {
        return config.unbanWatcherEnabled;
    }

    /** Intervallo fra due controlli degli sban, in secondi. */
    public int getUnbanCheckSeconds() {
        return config.unbanCheckSeconds;
    }

    /** true se chi ha il permesso staff deve entrare normalmente anche da bannato. */
    public boolean isStaffBypassEnabled() {
        return config.staffBypassEnabled;
    }

    /** Permesso che esonera' dal redirect in prigione. */
    public String getStaffBypassPermission() {
        return config.staffBypassPermission;
    }

    /**
     * true se il giocatore e' esonerato dalla prigione: il ban viene ignorato al login
     * e la sua presenza sul server jail non va mai trattata come una detenzione.
     * <p>
     * Il permesso arriva dal gestore permessi del proxy (LuckPerms) ed e'
     * configurabile con {@code staffBypassPermission} in config.json.
     */
    public boolean isStaffExempt(Player player) {
        return config.staffBypassEnabled && player.hasPermission(config.staffBypassPermission);
    }

    /** Configurazione JSON del lato proxy. */
    public static class ProxyConfig {
        public String jailServerName = "jail";
        public int permanentBanYears = 10;
        /** Notifica al server jail gli sban decisi altrove (staff, store, appeal). */
        public boolean unbanWatcherEnabled = true;
        public int unbanCheckSeconds = 10;
        /** Lo staff entra normalmente anche con un ban attivo: niente jail, niente kick. */
        public boolean staffBypassEnabled = true;
        public String staffBypassPermission = "jailmining.staff";
    }

    /**
     * Ponte verso le API ufficiali di LiteBans lato proxy
     * (https://gitlab.com/ruany/LiteBansAPI).
     * <p>
     * {@link Database#isPlayerBanned(UUID, String)} dice se il giocatore e' bannato,
     * {@link Database#getBan(UUID, String, String)} restituisce la {@link Entry} da cui
     * si leggono motivo, durata totale e durata residua.
     */
    public static class LiteBansHook {

        private final VelocityJailRedirect plugin;
        private final boolean available;

        public LiteBansHook(VelocityJailRedirect plugin) {
            this.plugin = plugin;
            this.available = plugin.getProxy().getPluginManager().getPlugin("litebans").isPresent();
            if (available) {
                plugin.getLogger().info("API LiteBans rilevate sul proxy");
            } else {
                plugin.getLogger().warn("LiteBans non presente sul proxy: i ban non verranno intercettati");
            }
        }

        public boolean isAvailable() {
            return available;
        }

        /** true se il giocatore risulta bannato secondo LiteBans (qualsiasi server scope). */
        public boolean isBanned(UUID uuid, String ip) {
            if (!available) {
                return false;
            }
            try {
                return Database.get().isPlayerBanned(uuid, ip, Database.ANY_SERVER_SCOPE);
            } catch (Exception exception) {
                plugin.getLogger().error("Errore interrogando le API LiteBans per {}", uuid, exception);
                return false;
            }
        }

        /** Ban attivo del giocatore, null se non ne esiste uno. */
        public Entry getBan(UUID uuid, String ip) {
            if (!available) {
                return null;
            }
            try {
                return Database.get().getBan(uuid, ip, Database.ANY_SERVER_SCOPE);
            } catch (Exception exception) {
                plugin.getLogger().error("Errore leggendo il ban di {}", uuid, exception);
                return null;
            }
        }

        /**
         * Durata residua del ban in millisecondi.
         * I ban permanenti (e i valori -1 restituiti da LiteBans) diventano la durata
         * configurata per i permanenti, di default 10 anni.
         */
        public long getRemainingTime(UUID uuid, String ip) {
            Entry entry = getBan(uuid, ip);
            if (entry == null) {
                return 0L;
            }
            if (entry.isPermanent()) {
                return plugin.getPermanentBanMillis();
            }
            long remaining = entry.getRemainingDuration(System.currentTimeMillis());
            return remaining <= 0L ? plugin.getPermanentBanMillis() : remaining;
        }

        /** Durata totale del ban, dalla creazione alla scadenza. */
        public long getOriginalDuration(UUID uuid, String ip) {
            Entry entry = getBan(uuid, ip);
            if (entry == null) {
                return 0L;
            }
            if (entry.isPermanent()) {
                return plugin.getPermanentBanMillis();
            }
            long duration = entry.getDuration();
            return duration <= 0L ? plugin.getPermanentBanMillis() : duration;
        }

        /** Motivo del ban, con fallback se assente. */
        public String getReason(UUID uuid, String ip) {
            Entry entry = getBan(uuid, ip);
            return entry == null || entry.getReason() == null ? null : entry.getReason();
        }
    }
}
