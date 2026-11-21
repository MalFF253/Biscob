package com.biscob.core.levelsystem;

import com.biscob.core.Biscob;
import com.biscob.core.DatabaseManager;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class LevelSystem implements Listener, CommandExecutor, TabCompleter {

    public static final int MAX_LEVEL = 100;

    private final Biscob plugin;
    private final DatabaseManager db;
    private final Map<UUID, DatabaseManager.PlayerLevelData> cache = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastValidMoveTime = new ConcurrentHashMap<>();
    private final Map<UUID, Location> lastMinuteLocation = new ConcurrentHashMap<>();

    // Variables del Boost Global (persistente en config.yml)
    private int globalBoostMultiplier = 1;
    private long globalBoostExpires = 0L;

    private ScheduledTask paydayTask;
    private final DecimalFormat df = new DecimalFormat("#,###");

    private static final int[] PATH_SLOTS = {
            0, 9, 18, 27, 36,       // Baja por Columna 0
            37,                     // Conector inferior
            38, 29, 20, 11, 2,      // Sube por Columna 2
            3,                      // Conector superior
            4, 13, 22, 31, 40, 49,  // Baja por Columna 4
            50,                     // Conector inferior
            51, 42, 33, 24, 15, 6,  // Sube por Columna 6
            7,                      // Conector superior
            8, 17, 26, 35, 44       // Baja por Columna 8
    };

    private static final int PREV_PAGE_SLOT = 45;
    private static final int NEXT_PAGE_SLOT = 53;

    public LevelSystem(Biscob plugin, DatabaseManager db) {
        this.plugin = plugin;
        this.db = db;
        ensureConfigDefaults();
        loadGlobalBoost();

        for (Player online : Bukkit.getOnlinePlayers()) {
            loadPlayer(online);
        }

        startPaydayTimer();
    }

    private void ensureConfigDefaults() {
        boolean changed = false;

        if (!plugin.getConfig().contains("levels.rank-boosts")) {
            plugin.getConfig().set("levels.rank-boosts.vip", 2);
            plugin.getConfig().set("levels.rank-boosts.vip2", 3);
            plugin.getConfig().set("levels.rank-boosts.tiktok", 2);
            plugin.getConfig().set("levels.rank-boosts.youtube", 2);
            changed = true;
        }

        if (!plugin.getConfig().contains("levels.afk-bypass-groups")) {
            plugin.getConfig().set("levels.afk-bypass-groups", Arrays.asList("vip2", "admin", "owner"));
            changed = true;
        }

        if (!plugin.getConfig().contains("levels.global-boost.multiplier")) {
            plugin.getConfig().set("levels.global-boost.multiplier", 1);
            plugin.getConfig().set("levels.global-boost.expires", 0L);
            changed = true;
        }

        if (!plugin.getConfig().contains("levels.levelup-broadcast")) {
            plugin.getConfig().set("levels.levelup-broadcast", "&8&m----------------------------------------\n&6&lBISCOB &8» &f¡El jugador &6%player% &fha subido al &e&lNivel %level%&f!\n&8&m----------------------------------------");
            changed = true;
        }
        if (!plugin.getConfig().contains("levels.menu-title")) {
            plugin.getConfig().set("levels.menu-title", "&8Niveles de Jugador (Pág. %page%)");
            plugin.getConfig().set("levels.tab-format-enabled", true);
            plugin.getConfig().set("levels.tab-prefix", "&8[&6%level%&8] &r");
            changed = true;
        }
        if (changed) {
            plugin.saveConfig();
        }
    }

    private void loadGlobalBoost() {
        this.globalBoostMultiplier = Math.max(1, plugin.getConfig().getInt("levels.global-boost.multiplier", 1));
        this.globalBoostExpires = plugin.getConfig().getLong("levels.global-boost.expires", 0L);
    }

    private void saveGlobalBoost() {
        plugin.getConfig().set("levels.global-boost.multiplier", this.globalBoostMultiplier);
        plugin.getConfig().set("levels.global-boost.expires", this.globalBoostExpires);
        plugin.saveConfig();
    }

    public int getActiveGlobalBoost() {
        if (globalBoostMultiplier <= 1) return 1;
        if (globalBoostExpires > 0 && System.currentTimeMillis() > globalBoostExpires) {
            globalBoostMultiplier = 1;
            globalBoostExpires = 0L;
            saveGlobalBoost();
            return 1;
        }
        return globalBoostMultiplier;
    }

    private boolean isInvalidMovementState(Player player) {
        if (player.isInsideVehicle()) {
            Entity vehicle = player.getVehicle();
            if (vehicle instanceof Boat || vehicle instanceof Minecart) {
                return true;
            }
            if (vehicle != null) {
                String typeName = vehicle.getType().name();
                if (typeName.contains("BOAT") || typeName.contains("MINECART") || typeName.contains("RAFT")) {
                    return true;
                }
            }
        }

        if (player.isInWater() || player.isSwimming()) {
            return true;
        }

        Block feet = player.getLocation().getBlock();
        Block under = feet.getRelative(0, -1, 0);
        Material feetType = feet.getType();
        Material underType = under.getType();

        return feetType == Material.WATER || feetType == Material.BUBBLE_COLUMN
                || underType == Material.WATER || underType == Material.BUBBLE_COLUMN;
    }

    public boolean hasAfkBypass(Player player) {
        if (player.hasPermission("biscob.afkbypass")) return true;
        List<String> bypassGroups = plugin.getConfig().getStringList("levels.afk-bypass-groups");
        for (String group : bypassGroups) {
            if (player.hasPermission("group." + group.toLowerCase())) return true;
        }
        return false;
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;

        Player player = event.getPlayer();

        if (hasAfkBypass(player)) {
            lastValidMoveTime.put(player.getUniqueId(), System.currentTimeMillis());
            return;
        }

        if (Math.abs(from.getX() - to.getX()) < 0.1 && Math.abs(from.getZ() - to.getZ()) < 0.1) {
            return;
        }

        if (isInvalidMovementState(player)) {
            return;
        }

        lastValidMoveTime.put(player.getUniqueId(), System.currentTimeMillis());
    }

    public void startPaydayTimer() {
        paydayTask = Bukkit.getAsyncScheduler().runAtFixedRate(plugin, task -> {
            long now = System.currentTimeMillis();

            for (Player player : Bukkit.getOnlinePlayers()) {
                player.getScheduler().run(plugin, playerTask -> {
                    UUID uuid = player.getUniqueId();
                    DatabaseManager.PlayerLevelData data = cache.get(uuid);
                    if (data == null) return;

                    Location currentLoc = player.getLocation();
                    boolean bypass = hasAfkBypass(player);

                    if (!bypass) {
                        if (isInvalidMovementState(player)) {
                            lastMinuteLocation.put(uuid, currentLoc);
                            return;
                        }

                        long lastMove = lastValidMoveTime.getOrDefault(uuid, 0L);
                        if (now - lastMove > 15000L) {
                            lastMinuteLocation.put(uuid, currentLoc);
                            return;
                        }

                        Location prevLoc = lastMinuteLocation.put(uuid, currentLoc);
                        if (prevLoc != null && prevLoc.getWorld().equals(currentLoc.getWorld())) {
                            double distSqHorizontal = Math.pow(currentLoc.getX() - prevLoc.getX(), 2) + Math.pow(currentLoc.getZ() - prevLoc.getZ(), 2);
                            if (distSqHorizontal < 9.0) {
                                return;
                            }
                        }
                    } else {
                        lastMinuteLocation.put(uuid, currentLoc);
                        lastValidMoveTime.put(uuid, now);
                    }

                    data.minutesPlayed++;
                    if (data.minutesPlayed >= 60) {
                        data.minutesPlayed = 0;

                        int dbMult = data.getActiveMultiplier();
                        int rankMult = getRankBoost(player);
                        int globalMult = getActiveGlobalBoost();
                        int activeMult = Math.max(globalMult, Math.max(dbMult, rankMult));

                        int gainedRep = 1 * activeMult;
                        data.exp += gainedRep;

                        player.sendMessage(Biscob.color("&8&m----------------------------------------"));
                        if (activeMult > 1) {
                            player.sendMessage(Biscob.color("&6&lPAYDAY &8» &fHas jugado 1 hora y recibiste &a+" + gainedRep + " de Reputación &e(Boost x" + activeMult + ")&f."));
                        } else {
                            player.sendMessage(Biscob.color("&6&lPAYDAY &8» &fHas jugado 1 hora y recibiste &a+1 Punto de Reputación&f."));
                        }
                        player.sendMessage(Biscob.color("&8» &7Tu Reputación acumulada: &e" + df.format(data.exp) + " &7(Usa &6/nivel &7para subir de nivel)."));
                        player.sendMessage(Biscob.color("&8&m----------------------------------------"));
                        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.5f);
                    }

                    db.savePlayerLevelAsync(uuid, data);
                }, null);
            }
        }, 1, 1, TimeUnit.MINUTES);
    }

    public void stopTimer() {
        if (paydayTask != null) {
            paydayTask.cancel();
        }
        for (Map.Entry<UUID, DatabaseManager.PlayerLevelData> entry : cache.entrySet()) {
            DatabaseManager.PlayerLevelData d = entry.getValue();
            db.savePlayerLevelSync(entry.getKey(), d.level, d.exp, d.minutesPlayed, d.boostMultiplier, d.boostExpires);
        }
    }

    public int getRequiredExp(int targetLevel) {
        if (targetLevel <= 1) return 0;
        if (targetLevel >= 10) return 512;
        return 1 << (targetLevel - 1);
    }

    public int getPlayerLevel(UUID uuid) {
        DatabaseManager.PlayerLevelData data = cache.get(uuid);
        return data != null ? data.level : 1;
    }

    public int getPlayerExp(UUID uuid) {
        DatabaseManager.PlayerLevelData data = cache.get(uuid);
        return data != null ? data.exp : 0;
    }

    public int getPlayerBoost(UUID uuid) {
        DatabaseManager.PlayerLevelData data = cache.get(uuid);
        int dbBoost = data != null ? data.getActiveMultiplier() : 1;
        int finalBoost = Math.max(dbBoost, getActiveGlobalBoost());
        Player p = Bukkit.getPlayer(uuid);
        if (p != null) {
            finalBoost = Math.max(finalBoost, getRankBoost(p));
        }
        return finalBoost;
    }

    public int getPlayerMinutes(UUID uuid) {
        DatabaseManager.PlayerLevelData data = cache.get(uuid);
        return data != null ? data.minutesPlayed : 0;
    }

    public int getRankBoost(Player player) {
        int maxBoost = 1;
        org.bukkit.configuration.ConfigurationSection section = plugin.getConfig().getConfigurationSection("levels.rank-boosts");
        if (section != null) {
            for (String rank : section.getKeys(false)) {
                if (player.hasPermission("group." + rank.toLowerCase())) {
                    int b = section.getInt(rank, 1);
                    if (b > maxBoost) maxBoost = b;
                }
            }
        }
        return maxBoost;
    }

    private void loadPlayer(Player player) {
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            DatabaseManager.PlayerLevelData data = db.loadPlayerLevel(player.getUniqueId());
            cache.put(player.getUniqueId(), data);
            player.getScheduler().run(plugin, t -> {
                lastMinuteLocation.put(player.getUniqueId(), player.getLocation());
                updateTabList(player);
            }, null);
        });
    }

    public void updateTabList(Player player) {
        if (Bukkit.getPluginManager().getPlugin("TAB") != null) return;
        if (!plugin.getConfig().getBoolean("levels.tab-format-enabled", true)) return;

        int lvl = getPlayerLevel(player.getUniqueId());
        String lvlTag = plugin.getConfig().getString("levels.tab-prefix", "&8[&6%level%&8] &r")
                .replace("%level%", String.valueOf(lvl))
                .replace("%biscob_level%", String.valueOf(lvl));

        String lpPrefix = plugin.getLuckPermsPrefix(player);
        String lpSuffix = plugin.getLuckPermsSuffix(player);

        String fullTabName = Biscob.color(lvlTag + lpPrefix + player.getName() + lpSuffix);
        player.setPlayerListName(fullTabName);
    }

    public void broadcastLevelUp(String playerName, int newLevel) {
        String rawMsg = plugin.getConfig().getString("levels.levelup-broadcast",
                "&8&m----------------------------------------\n&6&lBISCOB &8» &f¡El jugador &6%player% &fha subido al &e&lNivel %level%&f!\n&8&m----------------------------------------");
        if (rawMsg == null || rawMsg.isEmpty()) return;

        String formatted = Biscob.color(rawMsg
                .replace("%player%", playerName)
                .replace("%level%", String.valueOf(newLevel)));
        String[] lines = formatted.split("\n");

        for (Player online : Bukkit.getOnlinePlayers()) {
            online.getScheduler().run(plugin, t -> {
                for (String line : lines) {
                    online.sendMessage(line);
                }
                online.playSound(online.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.3f);
            }, null);
        }
    }

    public void openLevelMenu(Player player, int page) {
        int levelsPerPage = PATH_SLOTS.length;
        int maxPages = (int) Math.ceil((double) MAX_LEVEL / levelsPerPage);
        if (page < 1) page = 1;
        if (page > maxPages) page = maxPages;

        DatabaseManager.PlayerLevelData data = cache.getOrDefault(player.getUniqueId(),
                new DatabaseManager.PlayerLevelData(1, 0, 0, 1, 0L));

        String title = plugin.getConfig().getString("levels.menu-title", "&8Niveles de Jugador (Pág. %page%)")
                .replace("%page%", String.valueOf(page));

        Inventory inv = Bukkit.createInventory(new LevelMenuHolder(page), 54, Biscob.color(title));

        ItemStack blackGlass = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta glassMeta = blackGlass.getItemMeta();
        if (glassMeta != null) {
            glassMeta.setDisplayName(" ");
            blackGlass.setItemMeta(glassMeta);
        }
        for (int i = 0; i < 54; i++) {
            inv.setItem(i, blackGlass);
        }

        int startLevel = (page - 1) * levelsPerPage + 1;

        for (int i = 0; i < levelsPerPage; i++) {
            int targetLevel = startLevel + i;
            if (targetLevel > MAX_LEVEL) break;

            int slot = PATH_SLOTS[i];
            boolean unlocked = data.level >= targetLevel;
            boolean isCurrentLevel = (data.level == targetLevel);
            boolean isNextToClaim = (targetLevel == data.level + 1);
            int reqRep = getRequiredExp(targetLevel);

            Material iconMat = unlocked ? Material.PAPER : Material.MAP;
            ItemStack item = new ItemStack(iconMat);
            ItemMeta meta = item.getItemMeta();

            if (meta != null) {
                List<String> lore = new ArrayList<>();

                if (unlocked) {
                    if (isCurrentLevel) {
                        meta.setDisplayName(Biscob.color("&a&lNivel " + targetLevel + " &6(Tu Nivel Actual)"));
                    } else {
                        meta.setDisplayName(Biscob.color("&a&lNivel " + targetLevel + " &7(Completado)"));
                    }

                    if (targetLevel == 1) {
                        lore.add(Biscob.color("&7Nivel inicial de todos los jugadores."));
                    } else {
                        lore.add(Biscob.color("&7Reputación requerida: &a" + df.format(reqRep)));
                        lore.add("");
                        lore.add(Biscob.color("&6&lRecompensas obtenidas:"));
                        lore.addAll(getRewardLore(targetLevel));
                    }

                    if (isCurrentLevel) {
                        lore.add("");
                        lore.add(Biscob.color("&e▸ Tu Reputación acumulada: &f" + df.format(data.exp)));

                        int dbMult = data.getActiveMultiplier();
                        int rankMult = getRankBoost(player);
                        int globalMult = getActiveGlobalBoost();
                        int activeMult = Math.max(globalMult, Math.max(dbMult, rankMult));

                        if (activeMult > 1) {
                            String boostText;
                            if (activeMult == globalMult) {
                                boostText = " &7(Global" + (globalBoostExpires > 0 ? " - " + formatRemainingTimeRaw(globalBoostExpires) : "") + ")";
                            } else if (activeMult == dbMult && data.boostExpires > 0) {
                                boostText = formatRemainingBoost(data.boostExpires);
                            } else {
                                boostText = " &7(Por Rango)";
                            }
                            lore.add(Biscob.color("&6▸ Boost de Reputación: &ax" + activeMult + boostText));
                        }

                        if (hasAfkBypass(player)) {
                            lore.add(Biscob.color("&a▸ Beneficio AFK Activo &7(Automático)"));
                            lore.add(Biscob.color("&e▸ Próximo PayDay: &f" + (60 - data.minutesPlayed) + " min"));
                        } else {
                            lore.add(Biscob.color("&e▸ Próximo PayDay activo: &f" + (60 - data.minutesPlayed) + " min"));
                        }
                    } else {
                        lore.add("");
                        lore.add(Biscob.color("&a✔ ¡Nivel completado!"));
                    }
                } else {
                    meta.setDisplayName(Biscob.color((isNextToClaim ? "&e&l" : "&c&l") + "Nivel " + targetLevel));
                    lore.add(Biscob.color("&7Reputación necesaria: &6" + df.format(reqRep)));
                    lore.add("");
                    lore.add(Biscob.color("&6&lRecompensas al alcanzarlo:"));
                    lore.addAll(getRewardLore(targetLevel));
                    lore.add("");

                    if (isNextToClaim) {
                        if (data.exp >= reqRep) {
                            lore.add(Biscob.color("&a&l¡CLIC PARA RECLAMAR NIVEL!"));
                        } else {
                            lore.add(Biscob.color("&cAún no tienes la Reputación necesaria."));
                        }
                    } else {
                        lore.add(Biscob.color("&c🔒 Desbloquea primero el Nivel " + (targetLevel - 1)));
                    }
                }

                meta.setLore(lore);
                item.setItemMeta(meta);
            }

            inv.setItem(slot, item);
        }

        if (page > 1) {
            ItemStack prevArrow = new ItemStack(Material.ARROW);
            ItemMeta pm = prevArrow.getItemMeta();
            if (pm != null) {
                pm.setDisplayName(Biscob.color("&e« Página Anterior (" + (page - 1) + ")"));
                prevArrow.setItemMeta(pm);
            }
            inv.setItem(PREV_PAGE_SLOT, prevArrow);
        }

        if (page < maxPages) {
            ItemStack nextArrow = new ItemStack(Material.ARROW);
            ItemMeta nm = nextArrow.getItemMeta();
            if (nm != null) {
                nm.setDisplayName(Biscob.color("&eSiguiente Página (" + (page + 1) + ") »"));
                nextArrow.setItemMeta(nm);
            }
            inv.setItem(NEXT_PAGE_SLOT, nextArrow);
        }

        player.openInventory(inv);
    }

    private String formatRemainingTimeRaw(long expires) {
        long diff = expires - System.currentTimeMillis();
        if (diff <= 0) return "0m";
        long hours = TimeUnit.MILLISECONDS.toHours(diff);
        long mins = TimeUnit.MILLISECONDS.toMinutes(diff) % 60;
        return hours + "h " + mins + "m";
    }

    private String formatRemainingBoost(long expires) {
        if (expires <= 0) return " &7(Permanente)";
        long diff = expires - System.currentTimeMillis();
        if (diff <= 0) return "";
        return " &7(" + formatRemainingTimeRaw(expires) + ")";
    }

    private String getRewardConfigKey(int level) {
        if (plugin.getConfig().contains("levels.rewards." + level)) {
            return "levels.rewards." + level;
        }
        if (level > 10 && plugin.getConfig().contains("levels.rewards.10")) {
            return "levels.rewards.10";
        }
        return "levels.rewards." + level;
    }

    private List<String> getRewardLore(int level) {
        String key = getRewardConfigKey(level);
        List<String> customDesc = plugin.getConfig().getStringList(key + ".description");
        if (!customDesc.isEmpty()) {
            List<String> colored = new ArrayList<>();
            for (String line : customDesc) colored.add(Biscob.color(" &8• " + line));
            return colored;
        }
        double money = getRewardMoney(level);
        return Collections.singletonList(Biscob.color(" &8• &a+$" + df.format(money) + " Monedas"));
    }

    private double getRewardMoney(int level) {
        String key = getRewardConfigKey(level);
        return plugin.getConfig().getDouble(key + ".money", 0.0);
    }

    private List<String> getRewardCommands(int level) {
        String key = getRewardConfigKey(level);
        return plugin.getConfig().getStringList(key + ".commands");
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof LevelMenuHolder holder)) return;
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() == null || !event.getClickedInventory().equals(event.getView().getTopInventory())) return;

        int slot = event.getSlot();
        int page = holder.page;
        int maxPages = (int) Math.ceil((double) MAX_LEVEL / PATH_SLOTS.length);

        if (slot == NEXT_PAGE_SLOT && page < maxPages) {
            openLevelMenu(player, page + 1);
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1.2f);
            return;
        }

        if (slot == PREV_PAGE_SLOT && page > 1) {
            openLevelMenu(player, page - 1);
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1.2f);
            return;
        }

        int pathIndex = -1;
        for (int i = 0; i < PATH_SLOTS.length; i++) {
            if (PATH_SLOTS[i] == slot) {
                pathIndex = i;
                break;
            }
        }
        if (pathIndex == -1) return;

        int targetLevel = (page - 1) * PATH_SLOTS.length + 1 + pathIndex;
        if (targetLevel > MAX_LEVEL) return;

        DatabaseManager.PlayerLevelData data = cache.get(player.getUniqueId());
        if (data == null) return;

        if (targetLevel <= data.level) {
            player.sendMessage(Biscob.color("&6&lNIVELES &8» &eYa has alcanzado el Nivel " + targetLevel + "."));
            return;
        }

        if (targetLevel > data.level + 1) {
            player.sendMessage(Biscob.color("&6&lNIVELES &8» &cDebes reclamar el Nivel " + (data.level + 1) + " primero."));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return;
        }

        int reqRep = getRequiredExp(targetLevel);
        if (data.exp < reqRep) {
            player.sendMessage(Biscob.color("&6&lNIVELES &8» &cNecesitas &e" + df.format(reqRep) + " de Reputación &cpara subir al Nivel " + targetLevel + "."));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return;
        }

        data.exp -= reqRep;
        data.level = targetLevel;
        db.savePlayerLevelAsync(player.getUniqueId(), data);

        double rewardMoney = getRewardMoney(targetLevel);
        Economy econ = plugin.getEconomy();
        if (econ != null && rewardMoney > 0) {
            econ.depositPlayer(player, rewardMoney);
        }

        List<String> commands = getRewardCommands(targetLevel);
        if (!commands.isEmpty()) {
            Bukkit.getGlobalRegionScheduler().run(plugin, t -> {
                for (String cmd : commands) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", player.getName()));
                }
            });
        }

        updateTabList(player);
        openLevelMenu(player, page);

        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        broadcastLevelUp(player.getName(), targetLevel);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        loadPlayer(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        lastValidMoveTime.remove(uuid);
        lastMinuteLocation.remove(uuid);
        DatabaseManager.PlayerLevelData data = cache.remove(uuid);
        if (data != null) {
            db.savePlayerLevelAsync(uuid, data);
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                sendAdminHelp(sender);
                return true;
            }
            player.getScheduler().run(plugin, t -> openLevelMenu(player, 1), null);
            return true;
        }

        String sub = args[0].toLowerCase();

        if (sender instanceof Player p && !plugin.isAdmin(p) && !p.hasPermission("biscob.level.admin")) {
            p.sendMessage(Biscob.color("&6&lBISCOB &8» &cNo tienes permiso para usar comandos administrativos de niveles."));
            return true;
        }

        // Comando de Boost Global (no requiere nombre de jugador)
        if (sub.equals("globalboost") || sub.equals("boostglobal")) {
            if (args.length < 2) {
                sender.sendMessage(Biscob.color("&cUsa: /nivel globalboost <x2|x3|x5|x1> [horas]"));
                return true;
            }
            String rawMult = args[1].toLowerCase().replace("x", "");
            int mult = parsePositiveInt(sender, rawMult);
            if (mult <= 0) return true;

            long hours = 0;
            if (args.length >= 3) {
                hours = parseNonNegativeInt(sender, args[2]);
                if (hours < 0) return true;
            }

            this.globalBoostMultiplier = mult;
            this.globalBoostExpires = (hours > 0 && mult > 1) ? System.currentTimeMillis() + TimeUnit.HOURS.toMillis(hours) : 0L;
            saveGlobalBoost();

            String durationText = (mult <= 1) ? "desactivado (x1)" : (hours > 0 ? "x" + mult + " por " + hours + " horas" : "x" + mult + " permanente");
            sender.sendMessage(Biscob.color("&6&lNIVELES &8» &aBoost Global de Reputación establecido en: &e" + durationText + "&a."));

            for (Player online : Bukkit.getOnlinePlayers()) {
                online.getScheduler().run(plugin, t -> {
                    online.sendMessage(Biscob.color("&8&m----------------------------------------"));
                    if (mult > 1) {
                        online.sendMessage(Biscob.color("&6&l¡BOOST GLOBAL DE REPUTACIÓN ACTIVADO!"));
                        online.sendMessage(Biscob.color("&8» &fTodos los jugadores reciben &a" + durationText + " &fen cada PayDay."));
                        online.playSound(online.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.9f, 1.2f);
                    } else {
                        online.sendMessage(Biscob.color("&6&lBISCOB &8» &eEl Boost Global de Reputación ha finalizado."));
                    }
                    online.sendMessage(Biscob.color("&8&m----------------------------------------"));
                }, null);
            }
            return true;
        }

        if (args.length < 2) {
            sendAdminHelp(sender);
            return true;
        }

        Player onlinePlayer = Bukkit.getPlayerExact(args[1]);
        if (onlinePlayer == null) {
            onlinePlayer = Bukkit.getPlayer(args[1]);
        }

        final UUID targetId;
        final String targetName;
        final Player finalOnlineTarget = onlinePlayer;

        if (onlinePlayer != null) {
            targetId = onlinePlayer.getUniqueId();
            targetName = onlinePlayer.getName();
        } else {
            OfflinePlayer offline = Bukkit.getOfflinePlayer(args[1]);
            targetId = offline.getUniqueId();
            targetName = offline.getName() != null ? offline.getName() : args[1];
        }

        switch (sub) {
            case "reset":
            case "resetear": {
                modifyPlayerData(targetId, data -> {
                    data.level = 1;
                    data.exp = 0;
                    data.minutesPlayed = 0;
                    data.boostMultiplier = 1;
                    data.boostExpires = 0L;
                }, () -> {
                    sender.sendMessage(Biscob.color("&6&lNIVELES &8» &aNivel, Reputación y Boost de &f" + targetName + " &areseteados a Nivel 1."));
                    if (finalOnlineTarget != null) {
                        finalOnlineTarget.getScheduler().run(plugin, t -> {
                            updateTabList(finalOnlineTarget);
                            finalOnlineTarget.sendMessage(Biscob.color("&6&lNIVELES &8» &cTu nivel y reputación han sido reseteados por un Administrador."));
                        }, null);
                    }
                });
                return true;
            }

            case "darrep":
            case "addrep":
            case "darxp":
            case "addxp": {
                if (args.length < 3) {
                    sender.sendMessage(Biscob.color("&cUsa: /nivel darrep <jugador> <cantidad>"));
                    return true;
                }
                int amount = parsePositiveInt(sender, args[2]);
                if (amount <= 0) return true;

                modifyPlayerData(targetId, data -> data.exp += amount, () -> {
                    sender.sendMessage(Biscob.color("&6&lNIVELES &8» &aAñadidos &e+" + df.format(amount) + " de Reputación &aa &f" + targetName + "&a."));
                    if (finalOnlineTarget != null) {
                        finalOnlineTarget.sendMessage(Biscob.color("&6&lNIVELES &8» &aHas recibido &e+" + df.format(amount) + " de Reputación&a."));
                    }
                });
                return true;
            }

            case "quitarrep":
            case "removerep":
            case "quitarxp":
            case "removexp": {
                if (args.length < 3) {
                    sender.sendMessage(Biscob.color("&cUsa: /nivel quitarrep <jugador> <cantidad>"));
                    return true;
                }
                int amount = parsePositiveInt(sender, args[2]);
                if (amount <= 0) return true;

                modifyPlayerData(targetId, data -> data.exp = Math.max(0, data.exp - amount), () -> {
                    sender.sendMessage(Biscob.color("&6&lNIVELES &8» &eQuitados &c-" + df.format(amount) + " de Reputación &ea &f" + targetName + "&e."));
                });
                return true;
            }

            case "setrep":
            case "fijarrep":
            case "setxp":
            case "fijarxp": {
                if (args.length < 3) {
                    sender.sendMessage(Biscob.color("&cUsa: /nivel setrep <jugador> <cantidad>"));
                    return true;
                }
                int amount = parseNonNegativeInt(sender, args[2]);
                if (amount < 0) return true;

                modifyPlayerData(targetId, data -> data.exp = amount, () -> {
                    sender.sendMessage(Biscob.color("&6&lNIVELES &8» &aReputación de &f" + targetName + " &afijada en &e" + df.format(amount) + "&a."));
                    if (finalOnlineTarget != null) {
                        finalOnlineTarget.sendMessage(Biscob.color("&6&lNIVELES &8» &eTu Reputación ha sido fijada en &f" + df.format(amount) + "&e."));
                    }
                });
                return true;
            }

            case "darnivel":
            case "addlevel": {
                if (args.length < 3) {
                    sender.sendMessage(Biscob.color("&cUsa: /nivel darnivel <jugador> <cantidad>"));
                    return true;
                }
                int amount = parsePositiveInt(sender, args[2]);
                if (amount <= 0) return true;

                modifyPlayerData(targetId, data -> data.level = Math.min(MAX_LEVEL, data.level + amount), () -> {
                    int newLvl = getPlayerLevel(targetId);
                    sender.sendMessage(Biscob.color("&6&lNIVELES &8» &aAñadidos &e+" + amount + " niveles &aa &f" + targetName + "&a."));
                    broadcastLevelUp(targetName, newLvl);
                    if (finalOnlineTarget != null) {
                        finalOnlineTarget.getScheduler().run(plugin, t -> updateTabList(finalOnlineTarget), null);
                    }
                });
                return true;
            }

            case "setnivel":
            case "setlevel": {
                if (args.length < 3) {
                    sender.sendMessage(Biscob.color("&cUsa: /nivel setnivel <jugador> <1-100>"));
                    return true;
                }
                int newLvl = parsePositiveInt(sender, args[2]);
                if (newLvl <= 0) return true;
                final int clampedLvl = Math.min(MAX_LEVEL, newLvl);

                modifyPlayerData(targetId, data -> data.level = clampedLvl, () -> {
                    sender.sendMessage(Biscob.color("&6&lNIVELES &8» &aNivel de &f" + targetName + " &afijado en &eNivel " + clampedLvl + "&a."));
                    if (finalOnlineTarget != null) {
                        finalOnlineTarget.getScheduler().run(plugin, t -> updateTabList(finalOnlineTarget), null);
                    }
                });
                return true;
            }

            case "boost": {
                if (args.length < 3) {
                    sender.sendMessage(Biscob.color("&cUsa (Consola/Tebex o Admin): /nivel boost <jugador> <x2|x3|x5|1> [horas]"));
                    return true;
                }
                String rawMult = args[2].toLowerCase().replace("x", "");
                int mult = parsePositiveInt(sender, rawMult);
                if (mult <= 0) return true;

                long hours = 0;
                if (args.length >= 4) {
                    hours = parseNonNegativeInt(sender, args[3]);
                    if (hours < 0) return true;
                }
                final long finalHours = hours;
                final long expiresAt = (hours > 0 && mult > 1) ? System.currentTimeMillis() + TimeUnit.HOURS.toMillis(hours) : 0L;

                modifyPlayerData(targetId, data -> {
                    data.boostMultiplier = mult;
                    data.boostExpires = expiresAt;
                }, () -> {
                    String durationText = (mult <= 1) ? "desactivado (x1)" : (finalHours > 0 ? "x" + mult + " por " + finalHours + " horas" : "x" + mult + " permanente");
                    sender.sendMessage(Biscob.color("&6&lNIVELES &8» &aBoost de Reputación para &f" + targetName + " &aestablecido en: &e" + durationText + "&a."));
                    if (finalOnlineTarget != null) {
                        finalOnlineTarget.sendMessage(Biscob.color("&8&m----------------------------------------"));
                        finalOnlineTarget.sendMessage(Biscob.color("&6&l¡BOOST DE REPUTACIÓN ACTIVADO!"));
                        finalOnlineTarget.sendMessage(Biscob.color("&8» &fMultiplicador activo: &a" + durationText));
                        finalOnlineTarget.sendMessage(Biscob.color("&8&m----------------------------------------"));
                    }
                });
                return true;
            }

            case "info": {
                Bukkit.getAsyncScheduler().runNow(plugin, task -> {
                    DatabaseManager.PlayerLevelData data = cache.getOrDefault(targetId, db.loadPlayerLevel(targetId));
                    int dbMult = data.getActiveMultiplier();
                    int globalMult = getActiveGlobalBoost();
                    int rankMult = finalOnlineTarget != null ? getRankBoost(finalOnlineTarget) : 1;
                    int activeMult = Math.max(globalMult, Math.max(dbMult, rankMult));

                    String extra = "";
                    if (activeMult > 1) {
                        if (activeMult == globalMult) {
                            extra = " &7(Global" + (globalBoostExpires > 0 ? " - " + formatRemainingTimeRaw(globalBoostExpires) : "") + ")";
                        } else if (activeMult == dbMult && data.boostExpires > 0) {
                            extra = formatRemainingBoost(data.boostExpires);
                        } else {
                            extra = " &7(Por Rango)";
                        }
                    }

                    sender.sendMessage(Biscob.color("&8&m----------------------------------------"));
                    sender.sendMessage(Biscob.color("&6&lINFO DE NIVELES: &f" + targetName));
                    sender.sendMessage(Biscob.color("&8» &7Nivel actual: &a" + data.level + "&7/" + MAX_LEVEL));
                    sender.sendMessage(Biscob.color("&8» &7Reputación acumulada: &e" + df.format(data.exp)));

                    if (finalOnlineTarget != null && hasAfkBypass(finalOnlineTarget)) {
                        sender.sendMessage(Biscob.color("&8» &7Estado AFK: &aIgnorado (Bypass activo)"));
                    }

                    sender.sendMessage(Biscob.color("&8» &7Minutos jugados (hora actual): &f" + data.minutesPlayed + "/60 min"));
                    sender.sendMessage(Biscob.color("&8» &7Boost activo: &ax" + activeMult + extra));
                    sender.sendMessage(Biscob.color("&8&m----------------------------------------"));
                });
                return true;
            }

            default:
                sendAdminHelp(sender);
                return true;
        }
    }

    private void modifyPlayerData(UUID uuid, java.util.function.Consumer<DatabaseManager.PlayerLevelData> modifier, Runnable callback) {
        DatabaseManager.PlayerLevelData cached = cache.get(uuid);
        if (cached != null) {
            modifier.accept(cached);
            db.savePlayerLevelAsync(uuid, cached);
            callback.run();
        } else {
            Bukkit.getAsyncScheduler().runNow(plugin, task -> {
                DatabaseManager.PlayerLevelData data = db.loadPlayerLevel(uuid);
                modifier.accept(data);
                if (Bukkit.getPlayer(uuid) != null) {
                    cache.put(uuid, data);
                }
                db.savePlayerLevelSync(uuid, data.level, data.exp, data.minutesPlayed, data.boostMultiplier, data.boostExpires);
                callback.run();
            });
        }
    }

    private int parsePositiveInt(CommandSender sender, String input) {
        try {
            int val = Integer.parseInt(input);
            if (val <= 0) {
                sender.sendMessage(Biscob.color("&cEl número debe ser mayor que 0."));
                return -1;
            }
            return val;
        } catch (NumberFormatException e) {
            sender.sendMessage(Biscob.color("&cNúmero inválido: " + input));
            return -1;
        }
    }

    private int parseNonNegativeInt(CommandSender sender, String input) {
        try {
            int val = Integer.parseInt(input);
            if (val < 0) {
                sender.sendMessage(Biscob.color("&cEl número no puede ser negativo."));
                return -1;
            }
            return val;
        } catch (NumberFormatException e) {
            sender.sendMessage(Biscob.color("&cNúmero inválido: " + input));
            return -1;
        }
    }

    private void sendAdminHelp(CommandSender sender) {
        sender.sendMessage(Biscob.color("&8&m----------------------------------------"));
        sender.sendMessage(Biscob.color("&6&lCOMANDOS DE ADMINISTRACIÓN DE NIVELES"));
        sender.sendMessage(Biscob.color("&e/nivel reset <jugador> &8- &7Resetea a Nivel 1, 0 Reputación y sin boost."));
        sender.sendMessage(Biscob.color("&e/nivel darrep <jugador> <cantidad> &8- &7Suma Reputación al jugador."));
        sender.sendMessage(Biscob.color("&e/nivel quitarrep <jugador> <cantidad> &8- &7Resta Reputación al jugador."));
        sender.sendMessage(Biscob.color("&e/nivel setrep <jugador> <cantidad> &8- &7Fija la Reputación exacta."));
        sender.sendMessage(Biscob.color("&e/nivel darnivel <jugador> <cantidad> &8- &7Suma niveles al jugador."));
        sender.sendMessage(Biscob.color("&e/nivel setnivel <jugador> <1-100> &8- &7Fija el nivel exacto."));
        sender.sendMessage(Biscob.color("&e/nivel boost <jugador> <x2|x3|x5> [horas] &8- &7Da Boost personal (Tebex)."));
        sender.sendMessage(Biscob.color("&e/nivel globalboost <x2|x3|x5|x1> [horas] &8- &7Activa/Desactiva Boost Global."));
        sender.sendMessage(Biscob.color("&e/nivel info <jugador> &8- &7Consulta nivel, Reputación, tiempo y boost."));
        sender.sendMessage(Biscob.color("&8&m----------------------------------------"));
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (sender instanceof Player p && !plugin.isAdmin(p) && !p.hasPermission("biscob.level.admin")) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            return Arrays.asList("reset", "darrep", "quitarrep", "setrep", "darnivel", "setnivel", "boost", "globalboost", "info");
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("globalboost") || args[0].equalsIgnoreCase("boostglobal"))) {
            return Arrays.asList("x2", "x3", "x5", "x1");
        }
        if (args.length == 3 && (args[0].equalsIgnoreCase("globalboost") || args[0].equalsIgnoreCase("boostglobal"))) {
            return Arrays.asList("0", "1", "2", "6", "12", "24", "48");
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("boost")) {
            return Arrays.asList("x2", "x3", "x5", "x1");
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("boost")) {
            return Arrays.asList("0", "24", "72", "168", "720");
        }
        return null;
    }

    private static class LevelMenuHolder implements InventoryHolder {
        final int page;

        LevelMenuHolder(int page) {
            this.page = page;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return null;
        }
    }
}