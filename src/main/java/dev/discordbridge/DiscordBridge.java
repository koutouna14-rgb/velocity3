package dev.discordbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.slf4j.Logger;

import java.awt.Color;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Plugin(
        id = "discordbridge",
        name = "DiscordBridge",
        version = "1.1.0",
        description = "Syncs chat between Discord and every server behind Velocity",
        authors = {"You"}
)
public class DiscordBridge {

    private static final Color GREEN = new Color(0x57F287);
    private static final Color RED = new Color(0xED4245);
    private static final Color BLUE = new Color(0x5865F2);
    private static final Pattern URL = Pattern.compile("https?://\\S+");

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private final HttpClient http = HttpClient.newHttpClient();

    private final Properties config = new Properties();
    private JDA jda;
    private String channelId;
    private String webhookUrl;
    private String avatarTemplate;
    private boolean relayBetweenServers;
    private boolean announceJoinLeave;
    private String lobbyServer;

    @Inject
    public DiscordBridge(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    // ------------------------------------------------------------------ lifecycle

    @Subscribe
    public void onProxyInit(ProxyInitializeEvent event) {
        if (!loadConfig()) return;

        String token = config.getProperty("token", "").trim();
        channelId = config.getProperty("channel-id", "").trim();
        webhookUrl = config.getProperty("webhook-url", "").trim();
        avatarTemplate = config.getProperty("avatar-url", "https://mc-heads.net/avatar/{uuid}/128").trim();
        relayBetweenServers = Boolean.parseBoolean(config.getProperty("relay-between-servers", "true"));
        announceJoinLeave = Boolean.parseBoolean(config.getProperty("announce-join-leave", "true"));

        lobbyServer = config.getProperty("lobby-server", "lobby").trim();
        CommandMeta hubMeta = proxy.getCommandManager().metaBuilder("hub")
                .aliases("lobby", "l")
                .plugin(this)
                .build();
        proxy.getCommandManager().register(hubMeta, new HubCommand());

        if (token.isEmpty() || token.equals("YOUR_BOT_TOKEN") || channelId.isEmpty()) {
            logger.error("Set 'token' and 'channel-id' in {} and restart the proxy.",
                    dataDirectory.resolve("config.properties"));
            return;
        }

        try {
            jda = JDABuilder.createLight(token,
                            GatewayIntent.GUILD_MESSAGES,
                            GatewayIntent.MESSAGE_CONTENT)
                    .addEventListeners(new DiscordListener())
                    .build();
            jda.awaitReady();
            logger.info("Discord bot connected as {}", jda.getSelfUser().getName());
            sendEmbed("Network is online", null, GREEN, null);
        } catch (Exception e) {
            logger.error("Failed to start Discord bot", e);
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (jda == null) return;
        try {
            TextChannel channel = jda.getTextChannelById(channelId);
            if (channel != null) {
                channel.sendMessageEmbeds(new EmbedBuilder()
                        .setColor(RED).setAuthor("Network is offline").build()).complete();
            }
        } catch (Exception ignored) {
        }
        jda.shutdown();
    }

    // ------------------------------------------------------------------ Minecraft -> Discord / other servers

    @Subscribe
    public void onChat(PlayerChatEvent event) {
        if (!event.getResult().isAllowed()) return;

        Player player = event.getPlayer();
        String server = currentServerName(player);
        String label = labelFor(server);
        String message = event.getMessage();

        // Discord: webhook = shows the player's name + skin head as the sender
        if (!webhookUrl.isEmpty()) {
            sendWebhook(player.getUsername() + " • " + label, avatarFor(player), message);
        } else {
            sendEmbed(player.getUsername() + " (" + label + ")", avatarFor(player), BLUE, message);
        }

        // Other Minecraft servers (the sender's own server already shows the message)
        if (relayBetweenServers) {
            Component out = Component.text("[" + label + "] ", NamedTextColor.GREEN)
                    .append(Component.text(player.getUsername(), NamedTextColor.WHITE))
                    .append(Component.text(" » ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(message, NamedTextColor.GRAY));
            for (Player other : proxy.getAllPlayers()) {
                if (!currentServerName(other).equals(server)) {
                    other.sendMessage(out);
                }
            }
        }
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        if (!announceJoinLeave) return;

        Player player = event.getPlayer();
        String to = labelFor(event.getServer().getServerInfo().getName());

        if (event.getPreviousServer().isEmpty()) {
            sendEmbed(player.getUsername() + " joined the server", avatarFor(player), GREEN, to);
            broadcastMc(Component.text("+ " + player.getUsername() + " joined the network", NamedTextColor.YELLOW));
        } else {
            String from = labelFor(event.getPreviousServer().get().getServerInfo().getName());
            sendEmbed(player.getUsername() + " moved from " + from + " to " + to, avatarFor(player), BLUE, null);
            broadcastMc(Component.text(player.getUsername() + " moved " + from + " → " + to, NamedTextColor.YELLOW));
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        if (!announceJoinLeave) return;
        if (event.getLoginStatus() != DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN) return;

        Player player = event.getPlayer();
        sendEmbed(player.getUsername() + " left the server", avatarFor(player), RED, null);
        broadcastMc(Component.text("- " + player.getUsername() + " left the network", NamedTextColor.YELLOW));
    }

    // ------------------------------------------------------------------ Discord -> Minecraft

    private class DiscordListener extends ListenerAdapter {
        @Override
        public void onMessageReceived(MessageReceivedEvent event) {
            // ignores bots + webhooks, so our own relayed messages never loop back
            if (event.getAuthor().isBot() || event.isWebhookMessage()) return;
            if (!event.getChannel().getId().equals(channelId)) return;

            Message msg = event.getMessage();
            String name = event.getMember() != null
                    ? event.getMember().getEffectiveName()
                    : event.getAuthor().getName();

            // strip legacy colour codes so people can't fake formatting
            String clean = msg.getContentDisplay().replaceAll("[§&][0-9a-fk-orA-FK-OR]", "");

            TextComponent.Builder out = Component.text()
                    .append(Component.text("[Discord] ", NamedTextColor.BLUE))
                    .append(Component.text(name, NamedTextColor.WHITE))
                    .append(Component.text(" » ", NamedTextColor.DARK_GRAY));

            boolean hasContent = false;
            if (!clean.isBlank()) {
                out.append(linkify(clean));
                hasContent = true;
            }

            // attachments become clickable links (Minecraft chat can't render images)
            for (Message.Attachment att : msg.getAttachments()) {
                String tag = att.isImage() ? "[Image] " : "[File: " + att.getFileName() + "] ";
                out.append(Component.text(" "))
                        .append(Component.text(tag, NamedTextColor.AQUA)
                                .decorate(TextDecoration.UNDERLINED)
                                .clickEvent(ClickEvent.openUrl(att.getUrl()))
                                .hoverEvent(HoverEvent.showText(
                                        Component.text("Click to open " + att.getFileName(), NamedTextColor.GRAY))));
                hasContent = true;
            }

            if (!hasContent) return;
            broadcastMc(out.build());
        }
    }

    /** Turns URLs in text into clickable links. */
    private Component linkify(String text) {
        TextComponent.Builder b = Component.text();
        Matcher m = URL.matcher(text);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) {
                b.append(Component.text(text.substring(last, m.start()), NamedTextColor.GRAY));
            }
            String url = m.group();
            b.append(Component.text(url, NamedTextColor.AQUA)
                    .decorate(TextDecoration.UNDERLINED)
                    .clickEvent(ClickEvent.openUrl(url))
                    .hoverEvent(HoverEvent.showText(Component.text("Click to open", NamedTextColor.GRAY))));
            last = m.end();
        }
        if (last < text.length()) {
            b.append(Component.text(text.substring(last), NamedTextColor.GRAY));
        }
        return b.build();
    }

    // ------------------------------------------------------------------ helpers

    private void broadcastMc(Component component) {
        for (Player p : proxy.getAllPlayers()) {
            p.sendMessage(component);
        }
    }

    /** Embed with a coloured side bar and the player's head as the icon (like the join/leave cards). */
    private void sendEmbed(String text, String iconUrl, Color color, String footerOrDescription) {
        if (jda == null) return;
        TextChannel channel = jda.getTextChannelById(channelId);
        if (channel == null) return;

        EmbedBuilder eb = new EmbedBuilder().setColor(color).setAuthor(text, null, iconUrl);
        if (footerOrDescription != null) {
            // join/leave use the footer for the server name; chat fallback uses it as the body
            if (text.contains("(") ) eb.setDescription(footerOrDescription);
            else eb.setFooter(footerOrDescription);
        }
        channel.sendMessageEmbeds(eb.build())
                .setAllowedMentions(Collections.emptyList())
                .queue(null, err -> logger.warn("Discord send failed: {}", err.getMessage()));
    }

    /** Posts a chat line through the webhook so Discord shows the player's name and skin as the sender. */
    private void sendWebhook(String username, String avatarUrl, String content) {
        JsonObject body = new JsonObject();
        body.addProperty("username", username);
        body.addProperty("avatar_url", avatarUrl);
        body.addProperty("content", content);
        JsonObject mentions = new JsonObject();
        mentions.add("parse", new JsonArray()); // no pings
        body.add("allowed_mentions", mentions);

        HttpRequest req = HttpRequest.newBuilder(URI.create(webhookUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenAccept(res -> {
            if (res.statusCode() >= 300) {
                logger.warn("Webhook failed ({}): {}", res.statusCode(), res.body());
            }
        });
    }

    private String avatarFor(Player player) {
        return avatarTemplate
                .replace("{uuid}", player.getUniqueId().toString().replace("-", ""))
                .replace("{name}", player.getUsername());
    }

    private String currentServerName(Player player) {
        return player.getCurrentServer()
                .map(s -> s.getServerInfo().getName())
                .orElse("none");
    }

    private String labelFor(String serverName) {
        return config.getProperty("server." + serverName, serverName);
    }

    // ------------------------------------------------------------------ /hub command

    private class HubCommand implements SimpleCommand {
        @Override
        public void execute(Invocation invocation) {
            if (!(invocation.source() instanceof Player player)) {
                invocation.source().sendMessage(Component.text("Only players can use this command.", NamedTextColor.RED));
                return;
            }

            RegisteredServer target = proxy.getServer(lobbyServer).orElse(null);
            if (target == null) {
                player.sendMessage(Component.text("The lobby server is not available.", NamedTextColor.RED));
                logger.warn("lobby-server '{}' is not defined in velocity.toml", lobbyServer);
                return;
            }

            boolean alreadyThere = player.getCurrentServer()
                    .map(s -> s.getServerInfo().getName().equalsIgnoreCase(lobbyServer))
                    .orElse(false);
            if (alreadyThere) {
                player.sendMessage(Component.text("You are already in the lobby.", NamedTextColor.YELLOW));
                return;
            }

            player.sendMessage(Component.text("Sending you to the lobby...", NamedTextColor.GREEN));
            player.createConnectionRequest(target).connect().thenAccept(result -> {
                if (!result.isSuccessful()) {
                    player.sendMessage(Component.text("Could not connect to the lobby.", NamedTextColor.RED));
                }
            });
        }
    }

    private boolean loadConfig() {
        try {
            Files.createDirectories(dataDirectory);
            Path file = dataDirectory.resolve("config.properties");
            if (!Files.exists(file)) {
                try (InputStream in = getClass().getResourceAsStream("/config.properties")) {
                    if (in != null) Files.copy(in, file);
                }
            }
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                config.load(reader);
            }
            return true;
        } catch (IOException e) {
            logger.error("Could not load config", e);
            return false;
        }
    }
}
