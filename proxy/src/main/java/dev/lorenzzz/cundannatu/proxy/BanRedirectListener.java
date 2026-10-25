package dev.lorenzzz.cundannatu.proxy;

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import dev.lorenzzz.cundannatu.common.JailDataPayload;
import dev.lorenzzz.cundannatu.common.JailMessages;
import dev.lorenzzz.cundannatu.common.JailUnbanRequest;
import litebans.api.Entry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Intercetta i ban LiteBans e, invece di espellere il giocatore, lo dirotta
 * sul server jail configurato (default {@code jail}).
 * <p>
 * Flusso:
 * <ol>
 *     <li>{@link LoginEvent}: se LiteBans ha negato il login e il giocatore risulta
 *         bannato, il login viene riabilitato e il giocatore marcato come detenuto</li>
 *     <li>{@link PlayerChooseInitialServerEvent}: il detenuto viene mandato al server jail</li>
 *     <li>{@link KickedFromServerEvent}: qualsiasi kick per ban diventa un redirect</li>
 *     <li>{@link ServerPostConnectEvent}: una volta connesso viene inviato il payload
 *         JSON su {@code jailmining:data}</li>
 * </ol>
 * Motivo e durata arrivano direttamente dalla {@link Entry} di LiteBans; i ban permanenti
 * vengono convertiti nella durata configurata (10 anni).
 */
public class BanRedirectListener {

    private final VelocityJailRedirect plugin;

    /** Payload in attesa di essere spediti appena il giocatore raggiunge il server jail. */
    private final Map<UUID, JailDataPayload> pending = new ConcurrentHashMap<>();

    public BanRedirectListener(VelocityJailRedirect plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------
    // 1) LOGIN NEGATO DA LITEBANS
    // ------------------------------------------------------------------

    @Subscribe(order = PostOrder.LAST)
    public void onLogin(LoginEvent event) {
        Player player = event.getPlayer();
        if (event.getResult().isAllowed()) {
            return;
        }

        String ip = player.getRemoteAddress().getAddress().getHostAddress();
        Entry ban = plugin.getLiteBans().getBan(player.getUniqueId(), ip);
        if (ban == null) {
            return; // il login e' stato negato per altri motivi: non interveniamo
        }

        if (isStaff(player)) {
            // Lo staff entra sempre: sblocchiamo il login e non lo mettiamo in coda per il jail
            event.setResult(ResultedEvent.ComponentResult.allowed());
            plugin.getLogger().info("Ban ignorato per lo staffer {} ({}): login consentito",
                    player.getUsername(), player.getUniqueId());
            return;
        }

        String fallbackReason = event.getResult().getReasonComponent()
                .map(component -> PlainTextComponentSerializer.plainText().serialize(component))
                .orElse("Ban LiteBans");

        // Riabilitiamo il login: il giocatore andra' in prigione invece di essere espulso
        event.setResult(ResultedEvent.ComponentResult.allowed());

        JailDataPayload payload = queue(player, ban, fallbackReason);
        plugin.getLogger().info("Ban intercettato al login: {} ({}) motivo='{}' residuo={}ms",
                player.getUsername(), player.getUniqueId(), payload.reason(), payload.remainingDuration());
    }

    // ------------------------------------------------------------------
    // 2) SCELTA DEL SERVER INIZIALE
    // ------------------------------------------------------------------

    @Subscribe(order = PostOrder.LAST)
    public void onChooseInitialServer(PlayerChooseInitialServerEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (isStaff(player)) {
            // Lo staffer va sul server iniziale normale anche se ha un ban attivo
            pending.remove(uuid);
            return;
        }

        // LiteBans sul proxy controlla il ban quando ci si connette al singolo server
        // (vedi disable_login_ban_check_servers), quindi il login non risulta negato:
        // interroghiamo direttamente le API prima che venga scelto il server iniziale.
        if (!pending.containsKey(uuid)) {
            String ip = player.getRemoteAddress().getAddress().getHostAddress();
            Entry ban = plugin.getLiteBans().getBan(uuid, ip);
            if (ban == null) {
                return;
            }
            queue(player, ban, "Ban LiteBans");
        }

        Optional<RegisteredServer> jail = plugin.getJailServer();
        if (jail.isEmpty()) {
            plugin.getLogger().warn("Server jail '{}' assente da velocity.toml: {} non puo' essere instradato",
                    plugin.getJailServerName(), player.getUsername());
            pending.remove(uuid);
            return;
        }

        event.setInitialServer(jail.get());
        plugin.getLogger().info("{} instradato sul server jail '{}'",
                player.getUsername(), jail.get().getServerInfo().getName());
    }

    // ------------------------------------------------------------------
    // 3) KICK DAL SERVER DI GIOCO
    // ------------------------------------------------------------------

    @Subscribe(order = PostOrder.LAST)
    public void onKickedFromServer(KickedFromServerEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        // Se il kick arriva dal server jail stesso non facciamo nulla
        String from = event.getServer().getServerInfo().getName();
        if (from.equalsIgnoreCase(plugin.getJailServerName())) {
            return;
        }

        if (isStaff(player)) {
            // Nessun redirect in prigione per lo staff: il kick segue il comportamento
            // di default del proxy (fallback configurato in velocity.toml)
            return;
        }

        String ip = player.getRemoteAddress().getAddress().getHostAddress();
        Entry ban = plugin.getLiteBans().getBan(uuid, ip);
        if (ban == null) {
            return; // kick ordinario: lasciamo il comportamento di default
        }

        Optional<RegisteredServer> jail = plugin.getJailServer();
        if (jail.isEmpty()) {
            plugin.getLogger().warn("Server jail '{}' non configurato nel proxy: kick non intercettato",
                    plugin.getJailServerName());
            return;
        }

        String fallbackReason = event.getServerKickReason()
                .map(component -> PlainTextComponentSerializer.plainText().serialize(component))
                .orElse("Ban LiteBans");

        JailDataPayload payload = queue(player, ban, fallbackReason);

        event.setResult(KickedFromServerEvent.RedirectPlayer.create(jail.get(),
                Component.text("Sei stato condannato: ", NamedTextColor.RED)
                        .append(Component.text(payload.reason(), NamedTextColor.YELLOW))));

        plugin.getLogger().info("Ban intercettato: {} ({}) motivo='{}' residuo={}ms -> redirect su '{}'",
                player.getUsername(), uuid, payload.reason(), payload.remainingDuration(),
                plugin.getJailServerName());
    }

    // ------------------------------------------------------------------
    // 4) INVIO DEL PAYLOAD
    // ------------------------------------------------------------------

    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
        Player player = event.getPlayer();
        JailDataPayload payload = pending.get(player.getUniqueId());
        if (payload == null) {
            return;
        }

        Optional<ServerConnection> connection = player.getCurrentServer();
        if (connection.isEmpty()) {
            return;
        }
        if (!connection.get().getServerInfo().getName().equalsIgnoreCase(plugin.getJailServerName())) {
            return; // non e' ancora sul server jail
        }

        boolean sent = connection.get().sendPluginMessage(plugin.getChannel(), payload.toBytes());
        if (sent) {
            pending.remove(player.getUniqueId());
            plugin.getLogger().info("Payload jailmining:data inviato per {} -> {}",
                    player.getUsername(), payload);
        } else {
            plugin.getLogger().warn("Invio del payload jail fallito per {}", player.getUsername());
        }
    }

    // ------------------------------------------------------------------
    // 5) RICHIESTA DI SBAN DAL SERVER JAIL
    // ------------------------------------------------------------------

    /**
     * Il server jail non ha LiteBans installato, quindi delega lo sban al proxy:
     * qui riceviamo la richiesta ed eseguiamo {@code /ltunban} come console.
     */
    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!plugin.getChannel().equals(event.getIdentifier())) {
            return;
        }
        // Messaggio interno: non deve proseguire verso il client
        event.setResult(PluginMessageEvent.ForwardResult.handled());

        if (!(event.getSource() instanceof ServerConnection connection)) {
            return; // accettiamo richieste solo dai server backend, mai dai client
        }
        if (!JailMessages.TYPE_UNBAN.equals(JailMessages.typeOf(event.getData()))) {
            return;
        }

        try {
            JailUnbanRequest request = JailUnbanRequest.fromBytes(event.getData());

            // Il comando arriva gia' formattato dal server jail (jail-mining.unban.command).
            // Le versioni precedenti non lo inviavano: in quel caso ripieghiamo su /ltunban.
            String command = request.command() == null || request.command().isBlank()
                    ? "ltunban " + request.uuid() + " " + request.reason()
                    : request.command();

            plugin.getProxy().getCommandManager()
                    .executeAsync(plugin.getProxy().getConsoleCommandSource(), command)
                    .thenAccept(executed -> plugin.getLogger().info(
                            "Sban richiesto da '{}' per {} ({}): /{} -> esito {}",
                            connection.getServerInfo().getName(), request.name(), request.uuid(),
                            command, executed));
        } catch (Exception exception) {
            plugin.getLogger().error("Richiesta di sban non valida da '{}'",
                    connection.getServerInfo().getName(), exception);
        }
    }

    /** Pulizia della coda quando il giocatore lascia il proxy. */
    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------
    // UTILITY
    // ------------------------------------------------------------------

    /**
     * true se il giocatore e' staff e va quindi esonerato dal jail: il ban viene
     * ignorato e il login prosegue normalmente.
     * <p>
     * Il permesso arriva dal gestore permessi del proxy (LuckPerms) ed e'
     * configurabile con {@code staffBypassPermission} in config.json.
     */
    private boolean isStaff(Player player) {
        return plugin.isStaffExempt(player);
    }

    /**
     * Prepara il payload leggendo la Entry di LiteBans:
     * UUID, motivo, durata originale e durata residua.
     */
    private JailDataPayload queue(Player player, Entry ban, String fallbackReason) {
        long permanent = plugin.getPermanentBanMillis();
        boolean isPermanent = ban.isPermanent();

        // getDuration() e getRemainingDuration() valgono -1 sui ban permanenti
        long original = isPermanent ? permanent : positiveOr(ban.getDuration(), permanent);
        long remaining = isPermanent
                ? permanent
                : positiveOr(ban.getRemainingDuration(System.currentTimeMillis()), permanent);

        String reason = sanitize(ban.getReason() != null ? ban.getReason() : fallbackReason);

        JailDataPayload payload = JailDataPayload.of(
                player.getUniqueId(), player.getUsername(), reason, original, remaining);
        pending.put(player.getUniqueId(), payload);
        return payload;
    }

    private long positiveOr(long value, long fallback) {
        return value <= 0L ? fallback : value;
    }

    private String sanitize(String reason) {
        if (reason == null || reason.isBlank()) {
            return "Nessun motivo";
        }
        String single = reason.replace('\n', ' ').trim();
        return single.length() <= 200 ? single : single.substring(0, 200);
    }
}
