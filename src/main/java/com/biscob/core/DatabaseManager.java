package com.biscob.core;

import org.bukkit.Bukkit;

import java.io.File;
import java.sql.*;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class DatabaseManager {

    private final Biscob plugin;
    private final String url;
    private final Map<String, Boolean> dimensionStates = new ConcurrentHashMap<>();

    public static class PlayerLevelData {
        public int level;
        public int exp;
        public int minutesPlayed;
        public int boostMultiplier;
        public long boostExpires; // 0 = permanente, >0 = timestamp en milisegundos

        public PlayerLevelData(int level, int exp, int minutesPlayed, int boostMultiplier, long boostExpires) {
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
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }
        File dbFile = new File(plugin.getDataFolder(), "database.db");
        this.url = "jdbc:sqlite:" + dbFile.getAbsolutePath();
        initDatabase();
    }

    private void initDatabase() {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            plugin.getLogger().severe("No se encontro el driver de SQLite: " + e.getMessage());
            return;
        }

        try (Connection conn = DriverManager.getConnection(url);
             Statement stmt = conn.createStatement()) {

            stmt.execute("CREATE TABLE IF NOT EXISTS dimension_states (" +
                    "dimension TEXT PRIMARY KEY, " +
                    "enabled INTEGER NOT NULL" +
                    ");");

            stmt.execute("CREATE TABLE IF NOT EXISTS player_levels (" +
                    "uuid TEXT PRIMARY KEY, " +
                    "level INTEGER NOT NULL DEFAULT 1, " +
                    "exp INTEGER NOT NULL DEFAULT 0, " +
                    "minutes INTEGER NOT NULL DEFAULT 0, " +
                    "boost_multiplier INTEGER NOT NULL DEFAULT 1, " +
                    "boost_expires BIGINT NOT NULL DEFAULT 0" +
                    ");");

            // Migración automática por si la tabla ya existía sin las columnas de boost
            try { stmt.execute("ALTER TABLE player_levels ADD COLUMN boost_multiplier INTEGER NOT NULL DEFAULT 1;"); } catch (SQLException ignored) {}
            try { stmt.execute("ALTER TABLE player_levels ADD COLUMN boost_expires BIGINT NOT NULL DEFAULT 0;"); } catch (SQLException ignored) {}

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

            plugin.getLogger().info("Base de datos SQLite cargada correctamente.");

        } catch (SQLException e) {
            plugin.getLogger().severe("Error al inicializar SQLite: " + e.getMessage());
        }
    }

    private void ensureDimensionRow(Connection conn, String dimension, boolean defaultState) throws SQLException {
        String sql = "INSERT OR IGNORE INTO dimension_states (dimension, enabled) VALUES (?, ?)";
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
                    "ON CONFLICT(dimension) DO UPDATE SET enabled = excluded.enabled";
            try (Connection conn = DriverManager.getConnection(url);
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, dimKey);
                ps.setInt(2, enabled ? 1 : 0);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Error al guardar dimension en SQLite: " + e.getMessage());
            }
        });
    }

    public PlayerLevelData loadPlayerLevel(UUID uuid) {
        String sql = "SELECT level, exp, minutes, boost_multiplier, boost_expires FROM player_levels WHERE uuid = ?";
        try (Connection conn = DriverManager.getConnection(url);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new PlayerLevelData(
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

        PlayerLevelData initial = new PlayerLevelData(1, 0, 0, 1, 0L);
        savePlayerLevelAsync(uuid, initial);
        return initial;
    }

    public void savePlayerLevelAsync(UUID uuid, PlayerLevelData data) {
        final int lvl = data.level;
        final int xp = data.exp;
        final int mins = data.minutesPlayed;
        final int mult = data.boostMultiplier;
        final long expTime = data.boostExpires;
        Bukkit.getAsyncScheduler().runNow(plugin, task -> savePlayerLevelSync(uuid, lvl, xp, mins, mult, expTime));
    }

    public void savePlayerLevelSync(UUID uuid, int level, int exp, int minutes, int boostMultiplier, long boostExpires) {
        String sql = "INSERT INTO player_levels (uuid, level, exp, minutes, boost_multiplier, boost_expires) VALUES (?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT(uuid) DO UPDATE SET " +
                "level = excluded.level, " +
                "exp = excluded.exp, " +
                "minutes = excluded.minutes, " +
                "boost_multiplier = excluded.boost_multiplier, " +
                "boost_expires = excluded.boost_expires";
        try (Connection conn = DriverManager.getConnection(url);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, level);
            ps.setInt(3, exp);
            ps.setInt(4, minutes);
            ps.setInt(5, boostMultiplier);
            ps.setLong(6, boostExpires);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Error guardando nivel de " + uuid + ": " + e.getMessage());
        }
    }
}