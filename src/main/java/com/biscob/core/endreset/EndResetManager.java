package com.biscob.core.endreset; // Cambia esto por tu paquete real

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.event.player.PlayerQuitEvent;

import java.sql.*;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import java.util.Random;
import java.util.concurrent.TimeUnit;

public class EndResetManager implements Listener, CommandExecutor {

    private final JavaPlugin plugin;
    private final Random random = new Random();

    private final Set<String> notifyOnJoin = ConcurrentHashMap.newKeySet();

    public EndResetManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        // Comprueba cada 1 minuto si toca reiniciar el End (seguro para Folia)
        Bukkit.getAsyncScheduler().runAtFixedRate(plugin, task -> checkAndResetEnd(), 1, 1, TimeUnit.MINUTES);
    }

    private void checkAndResetEnd() {
        long intervalHours = plugin.getConfig().getLong("end-reset.interval-hours", 168L);
        long intervalMillis = TimeUnit.HOURS.toMillis(intervalHours);
        long lastReset = plugin.getConfig().getLong("end-reset.last-reset", 0L);
        long now = System.currentTimeMillis();

        if (lastReset == 0L) {
            plugin.getConfig().set("end-reset.last-reset", now);
            plugin.saveConfig();
            return;
        }

        if (now - lastReset >= intervalMillis) {
            executeEndReset();
        }
    }

    private World getEndWorld() {
        String endWorldName = plugin.getConfig().getString("end-reset.end-world", "world_the_end");
        World world = Bukkit.getWorld(endWorldName);
        if (world != null) return world;

        // Si el nombre del config no coincide con la carpeta, busca cualquier mundo de tipo End
        for (World w : Bukkit.getWorlds()) {
            if (w.getEnvironment() == World.Environment.THE_END) {
                return w;
            }
        }
        return null;
    }

    private Connection getConnection() throws SQLException {
        String host = plugin.getConfig().getString("mysql.host", "mia.gsdb.qorehost.com");
        int port = plugin.getConfig().getInt("mysql.port", 3306);
        String database = plugin.getConfig().getString("mysql.database", "s147_server");
        String user = plugin.getConfig().getString("mysql.user", "u147_w66TCjSmFM");
        String password = plugin.getConfig().getString("mysql.password", "B.oQEg3ZGG^@LXCJ1YMItjIs");

        String url = "jdbc:mysql://" + host + ":" + port + "/" + database + "?useSSL=false&autoReconnect=true";
        return DriverManager.getConnection(url, user, password);
    }

    public void executeEndReset() {
        String endWorldName = plugin.getConfig().getString("end-reset.end-world", "the_end");
        String spawnWorldName = plugin.getConfig().getString("end-reset.spawn-world", "world");
        String evacuatedMsg = plugin.getConfig().getString("end-reset.messages.evacuated",
                "§6[Biscob] §fEl End se está reiniciando. Has sido llevado al spawn.");
        String broadcastDone = plugin.getConfig().getString("end-reset.messages.broadcast-done",
                "§6[Biscob] §f¡El End ha sido regenerado con una nueva semilla!");

        World endWorld = getEndWorld();
        World spawnWorld = Bukkit.getWorld(spawnWorldName);

        if (spawnWorld == null) {
            plugin.getLogger().warning("[EndReset] No se encontró el mundo de spawn: " + spawnWorldName);
            return;
        }

        // Seguro anti-errores para no tocar el Overworld ni el Nether
        if (endWorldName.equalsIgnoreCase("world") || endWorldName.equalsIgnoreCase("the_nether") || endWorldName.equalsIgnoreCase("world_nether")) {
            plugin.getLogger().severe("[EndReset] ¡Cancelado! Intentaste regenerar un mundo protegido: " + endWorldName);
            return;
        }

        Location spawn = spawnWorld.getSpawnLocation();

        // 1. Evacuar a todos los jugadores conectados en el End
        if (endWorld != null) {
            for (Player player : endWorld.getPlayers()) {
                player.getScheduler().run(plugin, task -> {
                    player.teleportAsync(spawn).thenRun(() -> player.sendMessage(evacuatedMsg));
                }, null);
            }
        }

        // 2. Guardar la fecha exacta del reinicio en MySQL (dimension_states)
        long resetTime = System.currentTimeMillis();
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "INSERT INTO dimension_states (dimension, enabled, last_reset) VALUES ('the_end', 1, ?) " +
                                 "ON DUPLICATE KEY UPDATE last_reset = VALUES(last_reset)")) {
                ps.setLong(1, resetTime);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("[EndReset] Error actualizando dimension_states en MySQL: " + e.getMessage());
            }
        });

        // También actualizamos el config por si el temporizador automático lee de ahí
        plugin.getConfig().set("end-reset.last-reset", resetTime);
        plugin.saveConfig();

        // 3. Esperar 2 segundos (40 ticks) y lanzar el comando de Worlds
        long newSeed = random.nextLong(1, Long.MAX_VALUE);
        String cmd = plugin.getConfig().getString("end-reset.worlds-command", "world regenerate %world% --confirm")
                .replace("%world%", endWorldName)
                .replace("%seed%", String.valueOf(newSeed));

        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            Bukkit.broadcastMessage(broadcastDone);
            plugin.getLogger().info("[EndReset] End regenerado. Comando ejecutado: /" + cmd);
        }, 40L);
    }


    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String id = player.getName().toLowerCase();

        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            boolean shouldTeleport = false;

            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "SELECT e.logout_time, d.last_reset " +
                                 "FROM end_offline_players e " +
                                 "JOIN dimension_states d ON d.dimension = 'the_end' " +
                                 "WHERE e.player_name = ?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        long logoutTime = rs.getLong("logout_time");
                        long lastReset = rs.getLong("last_reset");
                        if (logoutTime < lastReset) {
                            shouldTeleport = true;
                        }
                    }
                }

                // Si toca sacarlo, borramos el registro para no volver a moverlo en el futuro
                if (shouldTeleport) {
                    try (PreparedStatement del = conn.prepareStatement(
                            "DELETE FROM end_offline_players WHERE player_name = ?")) {
                        del.setString(1, id);
                        del.executeUpdate();
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("[EndReset] Error comprobando MySQL al entrar: " + e.getMessage());
                return;
            }

            if (shouldTeleport) {
                String spawnWorldName = plugin.getConfig().getString("end-reset.spawn-world", "world");
                World spawnWorld = Bukkit.getWorld(spawnWorldName);
                if (spawnWorld == null) return;

                Location spawn = spawnWorld.getSpawnLocation();
                String msg = plugin.getConfig().getString("end-reset.messages.offline-teleport",
                        "§6[Biscob] §fEl End se reinició mientras estabas desconectado. Has sido llevado al spawn.");

                player.getScheduler().runDelayed(plugin, t -> {
                    player.teleportAsync(spawn).thenRun(() -> player.sendMessage(msg));
                }, null, 20L);
            }
        });
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        String id = player.getName().toLowerCase();
        boolean isInEnd = player.getWorld().getEnvironment() == World.Environment.THE_END;
        long now = System.currentTimeMillis();

        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try (Connection conn = getConnection()) {
                if (isInEnd) {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO end_offline_players (player_name, logout_time) VALUES (?, ?) " +
                                    "ON DUPLICATE KEY UPDATE logout_time = VALUES(logout_time)")) {
                        ps.setString(1, id);
                        ps.setLong(2, now);
                        ps.executeUpdate();
                    }
                } else {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "DELETE FROM end_offline_players WHERE player_name = ?")) {
                        ps.setString(1, id);
                        ps.executeUpdate();
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("[EndReset] Error en MySQL al salir: " + e.getMessage());
            }
        });
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("biscob.endreset")) {
            sender.sendMessage("§cNo tienes permiso para usar este comando.");
            return true;
        }
        sender.sendMessage("§eIniciando reinicio manual del End...");
        executeEndReset();
        return true;
    }

    public Set<String> getNotifyOnJoin() {
        return notifyOnJoin;
    }
}