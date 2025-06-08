package com.yue.discordlogger;

import org.bukkit.Bukkit;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.ZoneId;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.net.*;
import java.io.OutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class Main extends JavaPlugin implements Listener {
    private String hookChat;
    private String hookLog;
    private String hookLife;
    private int batchSize;
    private final List<String> logBuffer = new ArrayList<>();
    private final SimpleDateFormat fm = new SimpleDateFormat("HH:mm:ss dd/MM/yyyy"); //Time Format
    private static final String USERNAME = "Yue"; //Webhook Name
    private static final String AVATAR_URL = "https://media.discordapp.net/attachments/908685577266806784/1376882037021212764/FUyLRsVWAAA0G8-.jpg"; //Webhook Avatar
    private volatile boolean shutdownHandled = false;
    private final Queue<String> messageQueue = new ConcurrentLinkedQueue<>();
    private final DateTimeFormatter fmt = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault());

    @Override
    public void onEnable() {
        saveDefaultConfig();
        hookLog = getConfig().getString("webhook-log");
        hookLife = getConfig().getString("webhook-life");
        batchSize = getConfig().getInt("batch-size", 20);

        Bukkit.getPluginManager().registerEvents(this, this);
        sendWebhookAsync(hookLife, "**Server is online!**\n> Time: `" + now() + "`\n> IP: `" + getServerIp() + "`");

        // Log would be send after a minute
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::flush, 20 * 60, 20 * 60);

        // Making sure log would be send after shutting down server and notify server shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            synchronized (logBuffer) {
                if (!shutdownHandled) {
                    shutdownHandled = true;
                    // Flush Log
                    flushBatchSync();
                    // Server Closed
                    String t = fmt.format(Instant.now());
                    sendWebhookSync(hookLife,
                        "**Server is offline (shutdown-hook)**\n"
                      + "> Time: `" + t + "`"
                    );
                }

                if (!logBuffer.isEmpty()) {
                    String content = String.join("\n", logBuffer);
                    sendWebhookSync(hookLog, content);
                    logBuffer.clear();
                }
            }
            sendWebhookSync(hookLife, "**Server is offline**\n> Time: `" + now() + "`");
        }));
    }

    @Override
    public void onDisable() {
        if (!shutdownHandled) {
            shutdownHandled = true;
            // Flush batch còn lại
            flushBatchSync();
            // Gửi thông báo server đóng
            String time = fmt.format(Instant.now());
            sendWebhookAsync(hookLife,
                "**Server is offline**\n"
              + "> Time: `" + time + "`"
            );
        }
        getLogger().info("DiscordLoggerPlugin is off.");
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent e) {
        addLog("💬 **" + e.getPlayer().getName() + "**: " + e.getMessage());
    }

    @EventHandler
    public void onPlayerCommand(PlayerCommandPreprocessEvent e) {
        addLog("🧭 **" + e.getPlayer().getName() + "** executed: `" + e.getMessage() + "`");
    }

    @EventHandler
    public void onConsoleCommand(ServerCommandEvent e) {
        CommandSender sender = e.getSender();
        if (sender instanceof ConsoleCommandSender) {
            addLog("🖥️ **Console** executed: `" + e.getCommand() + "`");
        } else {
            addLog("🧭 **" + sender.getName() + "** executed: `" + e.getCommand() + "`");
        }
    }

    /** Flush sync (shutdown hook and onDisable) **/
    private void flushBatchSync() {
        if (messageQueue.isEmpty()) return;

        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = messageQueue.poll()) != null) {
            sb.append(line).append("\n");
        }
        String batch = sb.toString().trim();
        if (!batch.isEmpty()) {
            sendWebhookSync(hookChat, "```" + batch + "```");
        }
    }

    private void addLog(String line) {
        synchronized (logBuffer) {
            logBuffer.add(line);
            if (logBuffer.size() >= batchSize) flush();
        }
    }

    private void flush() {
        List<String> copy;
        synchronized (logBuffer) {
            if (logBuffer.isEmpty()) return;
            copy = new ArrayList<>(logBuffer);
            logBuffer.clear();
        }
        String body = String.join("\n", copy);
        sendWebhookAsync(hookLog, body);
    }

    private void sendWebhookAsync(String url, String content) {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> sendWebhookSync(url, content));
    }

    private void sendWebhookSync(String url, String content) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");

            String json = "{"
                    + "\"username\":\"" + USERNAME + "\","  
                    + "\"avatar_url\":\"" + AVATAR_URL + "\","  
                    + "\"content\":\"" + escape(content) + "\""
                    + "}";

            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            if (code != 204 && code != 200) {
                getLogger().warning("Webhook error code: " + code);
            }

            conn.disconnect();
        } catch (Exception e) {
            getLogger().warning("Webhook error: " + e.getMessage());
        }
    }

    private String escape(String text) {
        return text.replace("\\", "\\\\")
                   .replace("\"", "\\\"")
                   .replace("\n", "\\n");
    }

    private String now() {
        return fm.format(new Date());
    }

    // Phương thức getServerIp
    private String getServerIp() {
        try {
            // Gửi HTTP GET đến dịch vụ ipify để lấy IP công khai
            URL url = new URL("https://api.ipify.org");
            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod("GET");

            try (BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream()))) {
                String ip = in.readLine();
                return ip + ":" + Bukkit.getPort();
            }
        } catch (Exception e) {
            getLogger().warning("Can not get server IP: " + e.getMessage());
            return "unknown:" + Bukkit.getPort();
        }
    }
}
