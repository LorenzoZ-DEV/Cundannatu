package dev.lorenzzz.cundannatu.spigot.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import dev.lorenzzz.cundannatu.spigot.JailMiningPlugin;
import dev.lorenzzz.cundannatu.common.JailPlayerData;
import org.bukkit.configuration.ConfigurationSection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * Gestisce il pool di connessioni HikariCP e tutte le query sulla tabella jailed_players.
 * <p>
 * Tutte le operazioni sono asincrone e ritornano un {@link CompletableFuture}: il thread
 * principale del server non viene mai bloccato. La cache in memoria (HashMap con TTL)
 * evita di interrogare il database ad ogni blocco rotto.
 */
public class DatabaseManager {

    private static final String TABLE = "jailed_players";

    private static final String SQL_CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS jailed_players (
              uuid VARCHAR(36) PRIMARY KEY,
              ban_reason VARCHAR(255),
              original_duration_millis BIGINT,
              current_remaining_millis BIGINT,
              jail_credits INT DEFAULT 0,
              blocks_mined BIGINT DEFAULT 0,
              join_time BIGINT,
              last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """;

    private static final String SQL_UPSERT = """
            INSERT INTO jailed_players
              (uuid, ban_reason, original_duration_millis, current_remaining_millis,
               jail_credits, blocks_mined, join_time)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
              ban_reason = VALUES(ban_reason),
              original_duration_millis = VALUES(original_duration_millis),
              current_remaining_millis = VALUES(current_remaining_millis),
              jail_credits = VALUES(jail_credits),
              blocks_mined = VALUES(blocks_mined)
            """;

    private static final String SQL_SELECT = """
            SELECT uuid, ban_reason, original_duration_millis, current_remaining_millis,
                   jail_credits, blocks_mined, join_time
            FROM jailed_players WHERE uuid = ?
            """;

    private static final String SQL_UPDATE_CREDITS =
            "UPDATE jailed_players SET jail_credits = ? WHERE uuid = ?";

    private static final String SQL_UPDATE_REMAINING =
            "UPDATE jailed_players SET current_remaining_millis = ? WHERE uuid = ?";

    private static final String SQL_UPDATE_BOTH =
            "UPDATE jailed_players SET current_remaining_millis = ?, jail_credits = ?, blocks_mined = ? "
                    + "WHERE uuid = ?";

    private static final String SQL_DELETE =
            "DELETE FROM jailed_players WHERE uuid = ?";

    /** TTL della cache in memoria: dopo 5 minuti senza accessi la entry viene rimossa. */
    private static final long CACHE_TTL_MILLIS = TimeUnit.MINUTES.toMillis(5);

    /**
     * TTL della cache negativa: ricorda per 30 secondi che un giocatore NON e' detenuto.
     * Senza di essa scoreboard e tab interrogherebbero il database ad ogni aggiornamento
     * per ogni giocatore libero.
     */
    private static final long NEGATIVE_TTL_MILLIS = TimeUnit.SECONDS.toMillis(30);

    private final JailMiningPlugin plugin;
    private final ExecutorService executor;
    private final Map<UUID, CacheEntry> cache = new ConcurrentHashMap<>();

    /** UUID noti come "non detenuti" con il timestamp dell ultimo controllo. */
    private final Map<UUID, Long> notJailed = new ConcurrentHashMap<>();

    private HikariDataSource dataSource;

    public DatabaseManager(JailMiningPlugin plugin) {
        this.plugin = plugin;
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "JailMining-DB-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.executor = Executors.newFixedThreadPool(4, factory);
    }

    // ------------------------------------------------------------------
    // POOL DI CONNESSIONI
    // ------------------------------------------------------------------

    /** Costruisce il pool HikariCP leggendo database.yml. */
    public void connect() {
        ConfigurationSection section = plugin.getDatabaseConfig().getConfigurationSection("database");
        if (section == null) {
            throw new IllegalStateException("Sezione 'database' mancante in database.yml");
        }

        String host = section.getString("host", "localhost");
        int port = section.getInt("port", 3306);
        String database = section.getString("database", "minecraft");
        String username = section.getString("username", "root");
        String password = section.getString("password", "");

        HikariConfig config = new HikariConfig();
        config.setPoolName("JailMining-Hikari");
        config.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&serverTimezone=UTC");
        config.setUsername(username);
        config.setPassword(password);

        // Tutti i parametri del pool sono leggibili da configurazione
        config.setMaximumPoolSize(section.getInt("max-pool-size", 10));
        config.setMinimumIdle(section.getInt("min-idle", 5));
        config.setMaxLifetime(section.getLong("max-lifetime", 1_800_000L));
        config.setIdleTimeout(section.getLong("idle-timeout", 600_000L));
        config.setConnectionTimeout(section.getLong("connection-timeout", 30_000L));
        config.setLeakDetectionThreshold(section.getLong("leak-detection-threshold", 15_000L));

        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        config.addDataSourceProperty("useServerPrepStmts", "true");

        this.dataSource = new HikariDataSource(config);
        plugin.getLogger().info("[DB] Pool HikariCP avviato -> " + host + ":" + port + "/" + database
                + " (max=" + config.getMaximumPoolSize() + ", minIdle=" + config.getMinimumIdle() + ")");
    }

    /** Chiude e ricrea il pool: usato dal reload della configurazione. */
    public void reload() {
        plugin.getLogger().info("[DB] Refresh del pool di connessioni in corso...");
        closePool();
        cache.clear();
        notJailed.clear();
        connect();
    }

    private Connection getConnection() throws SQLException {
        if (dataSource == null || dataSource.isClosed()) {
            throw new SQLException("Il pool di connessioni non è inizializzato");
        }
        return dataSource.getConnection();
    }

    public boolean isConnected() {
        return dataSource != null && !dataSource.isClosed();
    }

    /** Logga lo stato corrente del pool (connessioni attive, idle, thread in attesa). */
    public void logPoolStatus() {
        if (!isConnected()) {
            plugin.getLogger().warning("[DB] Pool non attivo");
            return;
        }
        HikariPoolMXBean bean = dataSource.getHikariPoolMXBean();
        plugin.getLogger().info("[DB] Pool status -> attive: " + bean.getActiveConnections()
                + ", idle: " + bean.getIdleConnections()
                + ", totali: " + bean.getTotalConnections()
                + ", in attesa: " + bean.getThreadsAwaitingConnection()
                + ", cache entries: " + cache.size());
    }

    // ------------------------------------------------------------------
    // SCHEMA
    // ------------------------------------------------------------------

    /** Crea la tabella e gli indici se non esistono. */
    public CompletableFuture<Void> initializeDatabase() {
        return CompletableFuture.runAsync(() -> {
            try (Connection connection = getConnection(); Statement statement = connection.createStatement()) {
                statement.executeUpdate(SQL_CREATE_TABLE);
                // Tabelle create da versioni precedenti non hanno blocks_mined: la aggiungiamo
                addColumnIfMissing(connection, "blocks_mined", "BIGINT DEFAULT 0");
                // uuid è già PRIMARY KEY, aggiungiamo indici su join_time e crediti
                createIndexIfMissing(connection, "idx_jailed_join_time", "join_time");
                createIndexIfMissing(connection, "idx_jailed_credits", "jail_credits");
                plugin.getLogger().info("[DB] Tabella " + TABLE + " pronta");
            } catch (SQLException exception) {
                plugin.getLogger().log(Level.SEVERE, "[DB] Errore creando la tabella " + TABLE, exception);
                throw new CompletionFailure(exception);
            }
        }, executor);
    }

    /**
     * Migrazione dello schema: aggiunge una colonna se il database e' stato creato
     * da una versione precedente del plugin. I dati esistenti restano intatti.
     */
    private void addColumnIfMissing(Connection connection, String column, String definition) {
        String check = "SELECT COUNT(1) FROM information_schema.COLUMNS "
                + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?";
        try (PreparedStatement statement = connection.prepareStatement(check)) {
            statement.setString(1, TABLE);
            statement.setString(2, column);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next() && resultSet.getInt(1) > 0) {
                    return; // colonna gia' presente
                }
            }
        } catch (SQLException exception) {
            plugin.getLogger().log(Level.WARNING, "[DB] Impossibile verificare la colonna " + column, exception);
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE " + TABLE + " ADD COLUMN " + column + " " + definition);
            plugin.getLogger().info("[DB] Colonna " + column + " aggiunta a " + TABLE + " (migrazione)");
        } catch (SQLException exception) {
            plugin.getLogger().log(Level.SEVERE, "[DB] Impossibile aggiungere la colonna " + column, exception);
        }
    }

    /** MySQL non supporta CREATE INDEX IF NOT EXISTS: controlliamo prima i metadati. */
    private void createIndexIfMissing(Connection connection, String indexName, String column) {
        String check = "SELECT COUNT(1) FROM information_schema.STATISTICS "
                + "WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?";
        try (PreparedStatement statement = connection.prepareStatement(check)) {
            statement.setString(1, TABLE);
            statement.setString(2, indexName);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next() && resultSet.getInt(1) > 0) {
                    return;
                }
            }
        } catch (SQLException exception) {
            plugin.getLogger().log(Level.WARNING, "[DB] Impossibile verificare indice " + indexName, exception);
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE INDEX " + indexName + " ON " + TABLE + " (" + column + ")");
            plugin.getLogger().info("[DB] Indice " + indexName + " creato");
        } catch (SQLException exception) {
            plugin.getLogger().log(Level.WARNING, "[DB] Impossibile creare indice " + indexName, exception);
        }
    }

    // ------------------------------------------------------------------
    // CRUD
    // ------------------------------------------------------------------

    /** Inserisce o aggiorna il detenuto (upsert) e aggiorna la cache. */
    public CompletableFuture<Void> saveJailedPlayer(JailPlayerData data) {
        if (data == null) {
            return CompletableFuture.completedFuture(null);
        }
        cache.put(data.getUuid(), new CacheEntry(data));
        notJailed.remove(data.getUuid());
        return CompletableFuture.runAsync(() -> {
            try (Connection connection = getConnection();
                 PreparedStatement statement = connection.prepareStatement(SQL_UPSERT)) {
                statement.setString(1, data.getUuid().toString());
                statement.setString(2, truncate(data.getBanReason(), 255));
                statement.setLong(3, data.getOriginalDuration());
                statement.setLong(4, data.getCurrentRemaining());
                statement.setInt(5, data.getJailCredits());
                statement.setLong(6, data.getBlocksMined());
                statement.setLong(7, data.getJoinTime());
                statement.executeUpdate();
                data.setDirty(false);
                plugin.getLogger().info("[DB] Salvato detenuto " + data.getUuid()
                        + " (residuo=" + JailPlayerData.formatDuration(data.getCurrentRemaining())
                        + ", crediti=" + data.getJailCredits()
                        + ", blocchi=" + data.getBlocksMined() + ")");
            } catch (SQLException exception) {
                plugin.getLogger().log(Level.SEVERE, "[DB] Errore salvando " + data.getUuid(), exception);
            }
        }, executor);
    }

    /**
     * Carica un detenuto: prima dalla cache (se non scaduta), poi dal database.
     * Il future può completarsi con null se il giocatore non è in jail.
     */
    public CompletableFuture<JailPlayerData> getJailedPlayer(UUID uuid) {
        CacheEntry cached = cache.get(uuid);
        if (cached != null && !cached.isExpired()) {
            cached.touch();
            return CompletableFuture.completedFuture(cached.data);
        }
        Long checkedAt = notJailed.get(uuid);
        if (checkedAt != null) {
            if (System.currentTimeMillis() - checkedAt <= NEGATIVE_TTL_MILLIS) {
                return CompletableFuture.completedFuture(null); // sappiamo gia' che non e' detenuto
            }
            notJailed.remove(uuid);
        }
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = getConnection();
                 PreparedStatement statement = connection.prepareStatement(SQL_SELECT)) {
                statement.setString(1, uuid.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        // null check: giocatore non presente in jail, lo ricordiamo per NEGATIVE_TTL
                        notJailed.put(uuid, System.currentTimeMillis());
                        return null;
                    }
                    JailPlayerData data = new JailPlayerData(
                            uuid,
                            resultSet.getString("ban_reason"),
                            resultSet.getLong("original_duration_millis"),
                            resultSet.getLong("current_remaining_millis"),
                            resultSet.getInt("jail_credits"),
                            resultSet.getLong("blocks_mined"),
                            resultSet.getLong("join_time"));
                    cache.put(uuid, new CacheEntry(data));
                    return data;
                }
            } catch (SQLException exception) {
                plugin.getLogger().log(Level.SEVERE, "[DB] Errore caricando " + uuid, exception);
                return null;
            }
        }, executor);
    }

    /** Ritorna la entry in cache senza toccare il database (null se assente o scaduta). */
    public JailPlayerData getCached(UUID uuid) {
        CacheEntry entry = cache.get(uuid);
        if (entry == null || entry.isExpired()) {
            return null;
        }
        entry.touch();
        return entry.data;
    }

    /** Mette in cache un dato già noto (usato dal canale plugin del proxy). */
    public void putCache(JailPlayerData data) {
        if (data != null) {
            cache.put(data.getUuid(), new CacheEntry(data));
            notJailed.remove(data.getUuid());
        }
    }

    public void invalidate(UUID uuid) {
        cache.remove(uuid);
        notJailed.remove(uuid);
    }

    /** Segna il giocatore come non piu' detenuto: usato subito dopo la liberazione. */
    public void markAsFree(UUID uuid) {
        cache.remove(uuid);
        notJailed.put(uuid, System.currentTimeMillis());
    }

    public CompletableFuture<Void> updateJailCredits(UUID uuid, int credits) {
        return CompletableFuture.runAsync(() -> {
            try (Connection connection = getConnection();
                 PreparedStatement statement = connection.prepareStatement(SQL_UPDATE_CREDITS)) {
                statement.setInt(1, credits);
                statement.setString(2, uuid.toString());
                int rows = statement.executeUpdate();
                if (rows == 0) {
                    plugin.getLogger().warning("[DB] updateJailCredits: nessuna riga per " + uuid);
                }
                plugin.getLogger().fine("[DB] jail_credits change -> " + uuid + " = " + credits);
            } catch (SQLException exception) {
                plugin.getLogger().log(Level.SEVERE, "[DB] Errore aggiornando i crediti di " + uuid, exception);
            }
        }, executor);
    }

    public CompletableFuture<Void> updateRemainingTime(UUID uuid, long remainingMillis) {
        return CompletableFuture.runAsync(() -> {
            try (Connection connection = getConnection();
                 PreparedStatement statement = connection.prepareStatement(SQL_UPDATE_REMAINING)) {
                statement.setLong(1, Math.max(0L, remainingMillis));
                statement.setString(2, uuid.toString());
                int rows = statement.executeUpdate();
                if (rows == 0) {
                    plugin.getLogger().warning("[DB] updateRemainingTime: nessuna riga per " + uuid);
                }
            } catch (SQLException exception) {
                plugin.getLogger().log(Level.SEVERE, "[DB] Errore aggiornando il tempo di " + uuid, exception);
            }
        }, executor);
    }

    /** Update combinato usato dal BlockBreakListener: una sola query per blocco rotto. */
    public CompletableFuture<Void> updateProgress(UUID uuid, long remainingMillis, int credits, long blocksMined) {
        return CompletableFuture.runAsync(() -> {
            try (Connection connection = getConnection();
                 PreparedStatement statement = connection.prepareStatement(SQL_UPDATE_BOTH)) {
                statement.setLong(1, Math.max(0L, remainingMillis));
                statement.setInt(2, credits);
                statement.setLong(3, Math.max(0L, blocksMined));
                statement.setString(4, uuid.toString());
                statement.executeUpdate();
            } catch (SQLException exception) {
                plugin.getLogger().log(Level.SEVERE, "[DB] Errore aggiornando il progresso di " + uuid, exception);
            }
        }, executor);
    }

    public CompletableFuture<Void> deleteJailedPlayer(UUID uuid) {
        markAsFree(uuid);
        return CompletableFuture.runAsync(() -> {
            try (Connection connection = getConnection();
                 PreparedStatement statement = connection.prepareStatement(SQL_DELETE)) {
                statement.setString(1, uuid.toString());
                statement.executeUpdate();
                plugin.getLogger().info("[DB] Rimosso detenuto " + uuid);
            } catch (SQLException exception) {
                plugin.getLogger().log(Level.SEVERE, "[DB] Errore eliminando " + uuid, exception);
            }
        }, executor);
    }

    /** Svuota completamente la tabella: usato da /jailadmin resetdb. */
    public CompletableFuture<Integer> resetDatabase() {
        cache.clear();
        notJailed.clear();
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = getConnection(); Statement statement = connection.createStatement()) {
                int rows = statement.executeUpdate("DELETE FROM " + TABLE);
                plugin.getLogger().warning("[DB] Reset tabella " + TABLE + ": " + rows + " righe eliminate");
                return rows;
            } catch (SQLException exception) {
                plugin.getLogger().log(Level.SEVERE, "[DB] Errore durante il reset della tabella", exception);
                return -1;
            }
        }, executor);
    }

    // ------------------------------------------------------------------
    // CACHE / SHUTDOWN
    // ------------------------------------------------------------------

    /** Rimuove dalla cache (positiva e negativa) le entry scadute e non modificate. */
    public void purgeExpiredCache() {
        cache.entrySet().removeIf(entry -> entry.getValue().isExpired() && !entry.getValue().data.isDirty());
        long now = System.currentTimeMillis();
        notJailed.entrySet().removeIf(entry -> now - entry.getValue() > NEGATIVE_TTL_MILLIS);
    }

    /**
     * Scrive su database tutte le entry ancora sporche.
     * Chiamato prima dello stop del server: la persistenza avviene in modo sincrono.
     */
    public void flushCacheBlocking() {
        List<JailPlayerData> dirty = new ArrayList<>();
        for (CacheEntry entry : cache.values()) {
            if (entry.data.isDirty()) {
                dirty.add(entry.data);
            }
        }
        if (dirty.isEmpty()) {
            return;
        }
        plugin.getLogger().info("[DB] Flush di " + dirty.size() + " detenuti prima dello shutdown...");
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(SQL_UPSERT)) {
            for (JailPlayerData data : dirty) {
                statement.setString(1, data.getUuid().toString());
                statement.setString(2, truncate(data.getBanReason(), 255));
                statement.setLong(3, data.getOriginalDuration());
                statement.setLong(4, data.getCurrentRemaining());
                statement.setInt(5, data.getJailCredits());
                statement.setLong(6, data.getBlocksMined());
                statement.setLong(7, data.getJoinTime());
                statement.addBatch();
            }
            statement.executeBatch();
            dirty.forEach(data -> data.setDirty(false));
            plugin.getLogger().info("[DB] Flush completato");
        } catch (SQLException exception) {
            plugin.getLogger().log(Level.SEVERE, "[DB] Errore durante il flush finale", exception);
        }
    }

    /** Flush + chiusura ordinata di executor e pool. */
    public void shutdown() {
        if (isConnected()) {
            flushCacheBlocking();
            logPoolStatus();
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("[DB] Executor non terminato entro 10s, forzo lo stop");
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
        closePool();
    }

    private void closePool() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            plugin.getLogger().info("[DB] Pool HikariCP chiuso");
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "Nessun motivo";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** Entry di cache con timestamp di ultimo accesso per il TTL. */
    private static final class CacheEntry {
        private final JailPlayerData data;
        private volatile long lastAccess;

        private CacheEntry(JailPlayerData data) {
            this.data = data;
            this.lastAccess = System.currentTimeMillis();
        }

        private void touch() {
            this.lastAccess = System.currentTimeMillis();
        }

        private boolean isExpired() {
            return System.currentTimeMillis() - lastAccess > CACHE_TTL_MILLIS;
        }
    }

    /** Eccezione non controllata usata per propagare le SQLException nei future. */
    public static class CompletionFailure extends RuntimeException {
        public CompletionFailure(Throwable cause) {
            super(cause);
        }
    }
}
