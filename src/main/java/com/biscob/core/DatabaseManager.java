package com.biscob.core;

import org.bukkit.Bukkit;

import java.sql.*;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class DatabaseManager {

    private final Biscob plugin;
    private final String url;
    private final String user;
    private final String password;
    private final Map<String, Boolean> dimensionStates = new ConcurrentHashMap<>();

    public static class PlayerLevelData {
        public String username;
        public int level;
        public int exp;
        public int minutesPlayed;
        public int boostMultiplier;
        public long boostExpires; // 0 = permanente, >0 = timestamp en milisegundos

        public PlayerLevelData(int level, int exp, int minutesPlayed, int boostMultiplier, long boostExpires) {
            this("", level, exp, minutesPlayed, boostMultiplier, boostExpires);
        }

        public PlayerLevelData(String username, int level, int exp, int minutesPlayed, int boostMultiplier, long boostExpires) {
            this.username = username != null ? username : "";
            this.level = level;
            this.exp = exp;
            this.minutesPlayed = minutesPlayed;
            this.boostMultiplier = Math.max(1, boostMultiplier);
            this.boostExpires = boostExpires;
        }

        public int getActiveMultiplier() {
            if (boostMultiplier <= 1) return 1;
            if (boostExpires > 0 && System.currentTimeMillis() > boostExpires) {
                boostMultiplier = 1;
                boostExpires = 0;
                return 1;
            }
            return boostMultiplier;
        }
    }

    public DatabaseManager(Biscob plugin) {
        this.plugin = plugin;
        ensureDatabaseConfig();

        String host = plugin.getConfig().getString("mysql.host", "localhost");
        int port = plugin.getConfig().getInt("mysql.port", 3306);
        String database = plugin.getConfig().getString("mysql.database", "biscob");

        this.user = plugin.getConfig().getString("mysql.username", "root");
        this.password = plugin.getConfig().getString("mysql.password", "");
        this.url = "jdbc:mysql://" + host + ":" + port + "/" + database +
                "?useSSL=false&allowPublicKeyRetrieval=true&autoReconnect=true&characterEncoding=utf8";

        initDatabase();
    }

    private void ensureDatabaseConfig() {
        boolean changed = false;
        if (!plugin.getConfig().contains("mysql.host")) {
            plugin.getConfig().set("mysql.host", "localhost");
            plugin.getConfig().set("mysql.port", 3306);
            plugin.getConfig().set("mysql.database", "biscob");
            plugin.getConfig().set("mysql.username", "root");
            plugin.getConfig().set("mysql.password", "");
            changed = true;
        }
        if (changed) {
            plugin.saveConfig();
        }
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    private void initDatabase() {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            plugin.getLogger().severe("No se encontro el driver de MySQL: " + e.getMessage());
            return;
        }

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.execute("CREATE TABLE IF NOT EXISTS dimension_states (" +
                    "dimension VARCHAR(32) PRIMARY KEY, " +
                    "enabled TINYINT(1) NOT NULL" +
                    ");");

            stmt.execute("CREATE TABLE IF NOT EXISTS player_levels (" +
                    "uuid VARCHAR(36) PRIMARY KEY, " +
                    "username VARCHAR(32) NOT NULL DEFAULT '', " +
                    "level INT NOT NULL DEFAULT 1, " +
                    "exp INT NOT NULL DEFAULT 0, " +
                    "minutes INT NOT NULL DEFAULT 0, " +
                    "boost_multiplier INT NOT NULL DEFAULT 1, " +
                    "boost_expires BIGINT NOT NULL DEFAULT 0" +
                    ");");

            // Añade la columna username si la tabla ya existía en phpMyAdmin
            try {
                stmt.execute("ALTER TABLE player_levels ADD COLUMN username VARCHAR(32) NOT NULL DEFAULT '' AFTER uuid;");
            } catch (SQLException ignored) {
            }

            boolean defaultEnd = plugin.getConfig().getBoolean("dimensions.end.enabled", false);
            boolean defaultNether = plugin.getConfig().getBoolean("dimensions.nether.enabled", true);

            ensureDimensionRow(conn, "end", defaultEnd);
            ensureDimensionRow(conn, "nether", defaultNether);

            try (ResultSet rs = stmt.executeQuery("SELECT dimension, enabled FROM dimension_states")) {
                while (rs.next()) {
                    String dim = rs.getString("dimension").toLowerCase();
                    boolean enabled = rs.getInt("enabled") == 1;
                    dimensionStates.put(dim, enabled);
                }
            }

            plugin.getLogger().info("Base de datos MySQL cargada correctamente.");

        } catch (SQLException e) {
            plugin.getLogger().severe("Error al inicializar MySQL: " + e.getMessage());
            if (e.getCause() != null) {
                plugin.getLogger().severe("Causa real: " + e.getCause().getMessage());
            }
        }
    }

    private void ensureDimensionRow(Connection conn, String dimension, boolean defaultState) throws SQLException {
        String sql = "INSERT IGNORE INTO dimension_states (dimension, enabled) VALUES (?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, dimension.toLowerCase());
            ps.setInt(2, defaultState ? 1 : 0);
            ps.executeUpdate();
        }
    }

    public boolean isDimensionEnabled(String dimension) {
        return dimensionStates.getOrDefault(dimension.toLowerCase(), true);
    }

    public void setDimensionEnabled(String dimension, boolean enabled) {
        String dimKey = dimension.toLowerCase();
        dimensionStates.put(dimKey, enabled);

        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            String sql = "INSERT INTO dimension_states (dimension, enabled) VALUES (?, ?) " +
                    "ON DUPLICATE KEY UPDATE enabled = VALUES(enabled)";
            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, dimKey);
                ps.setInt(2, enabled ? 1 : 0);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Error al guardar dimension en MySQL: " + e.getMessage());
            }
        });
    }

    public PlayerLevelData loadPlayerLevel(UUID uuid) {
        String sql = "SELECT username, level, exp, minutes, boost_multiplier, boost_expires FROM player_levels WHERE uuid = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new PlayerLevelData(
                            rs.getString("username"),
                            rs.getInt("level"),
                            rs.getInt("exp"),
                            rs.getInt("minutes"),
                            rs.getInt("boost_multiplier"),
                            rs.getLong("boost_expires")
                    );
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Error cargando nivel de " + uuid + ": " + e.getMessage());
        }

        PlayerLevelData initial = new PlayerLevelData("", 1, 0, 0, 1, 0L);
        savePlayerLevelAsync(uuid, initial);
        return initial;
    }

    public void savePlayerLevelAsync(UUID uuid, PlayerLevelData data) {
        final String name = data.username != null ? data.username : "";
        final int lvl = data.level;
        final int xp = data.exp;
        final int mins = data.minutesPlayed;
        final int mult = data.boostMultiplier;
        final long expTime = data.boostExpires;
        Bukkit.getAsyncScheduler().runNow(plugin, task -> savePlayerLevelSync(uuid, name, lvl, xp, mins, mult, expTime));
    }

    public void savePlayerLevelSync(UUID uuid, int level, int exp, int minutes, int boostMultiplier, long boostExpires) {
        savePlayerLevelSync(uuid, "", level, exp, minutes, boostMultiplier, boostExpires);
    }

    public void savePlayerLevelSync(UUID uuid, String username, int level, int exp, int minutes, int boostMultiplier, long boostExpires) {
        String sql = "INSERT INTO player_levels (uuid, username, level, exp, minutes, boost_multiplier, boost_expires) VALUES (?, ?, ?, ?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE " +
                "username = IF(VALUES(username) != '', VALUES(username), username), " +
                "level = VALUES(level), " +
                "exp = VALUES(exp), " +
                "minutes = VALUES(minutes), " +
                "boost_multiplier = VALUES(boost_multiplier), " +
                "boost_expires = VALUES(boost_expires)";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, username != null ? username : "");
            ps.setInt(3, level);
            ps.setInt(4, exp);
            ps.setInt(5, minutes);
            ps.setInt(6, boostMultiplier);
            ps.setLong(7, boostExpires);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Error guardando nivel de " + uuid + ": " + e.getMessage());
        }
    }
}