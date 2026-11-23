package com.biscob.core;

import com.biscob.core.endreset.EndResetManager;
import com.biscob.core.levelsystem.BiscobPlaceholder;
import com.biscob.core.levelsystem.LevelSystem;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.InheritanceNode;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class Biscob extends JavaPlugin implements Listener {

    private LuckPerms luckPerms;
    private Economy econ;
    private AnnouncerTask announcerTask;
    private TradeManager tradeManager;
    private LocatorBarManager locatorBarManager;
    private LevelSystem levelSystem;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        if (Bukkit.getPluginManager().getPlugin("LuckPerms") != null) {
            RegisteredServiceProvider<LuckPerms> provider = Bukkit.getServicesManager().getRegistration(LuckPerms.class);
            if (provider != null) {
                luckPerms = provider.getProvider();
            }
        }

        setupEconomy();

        DatabaseManager databaseManager = new DatabaseManager(this);

        announcerTask = new AnnouncerTask(this);
        announcerTask.start();

        SupportManager supportManager = new SupportManager(this);
        StaffChatManager staffChatManager = new StaffChatManager(this);
        tradeManager = new TradeManager(this);
        DimensionManager dimensionManager = new DimensionManager(this, databaseManager);
        locatorBarManager = new LocatorBarManager(this);
        BlockManager blockManager = new BlockManager(this);
        CraftingRestrictionManager craftingRestrictionManager = new CraftingRestrictionManager(this);
        levelSystem = new LevelSystem(this, databaseManager);

        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(supportManager, this);
        Bukkit.getPluginManager().registerEvents(tradeManager, this);
        Bukkit.getPluginManager().registerEvents(dimensionManager, this);
        Bukkit.getPluginManager().registerEvents(locatorBarManager, this);
        Bukkit.getPluginManager().registerEvents(blockManager, this);
        Bukkit.getPluginManager().registerEvents(craftingRestrictionManager, this);
        Bukkit.getPluginManager().registerEvents(levelSystem, this);
        Bukkit.getPluginManager().registerEvents(staffChatManager, this);

        EndResetManager endResetManager = new EndResetManager(this);
        endResetManager.start();
        getServer().getPluginManager().registerEvents(endResetManager, this);
        getCommand("resetend").setExecutor(endResetManager);

        PvpToggleCommand pvpCommand = new PvpToggleCommand();
        getCommand("pvp").setExecutor(pvpCommand);
        getServer().getPluginManager().registerEvents(pvpCommand, this);

        // Intento 1: Registrar PlaceholderAPI inmediatamente si ya está activo
        registerPlaceholders();

        // Intento 2: Volver a verificar tras 1 segundo (20 ticks) cuando Folia termine de cargar todos los plugins
        Bukkit.getGlobalRegionScheduler().runDelayed(this, task -> registerPlaceholders(), 20L);

        getCommand("soporte").setExecutor(supportManager);
        getCommand("trade").setExecutor(tradeManager);

        getCommand("end").setExecutor(dimensionManager);
        getCommand("end").setTabCompleter(dimensionManager);

        getCommand("nether").setExecutor(dimensionManager);
        getCommand("nether").setTabCompleter(dimensionManager);

        getCommand("locatorbar").setExecutor(locatorBarManager);
        getCommand("locatorbar").setTabCompleter(locatorBarManager);

        getCommand("nivel").setExecutor(levelSystem);
        getCommand("nivel").setTabCompleter(levelSystem);

        getCommand("biscobreload").setExecutor((sender, command, label, args) -> {
            if (sender instanceof Player p && !isAdmin(p)) {
                sender.sendMessage(color("&6&lBISCOB &8» &cNo tienes permiso para usar este comando."));
                return true;
            }
            reloadConfig();
            announcerTask.restart();
            locatorBarManager.applyToAllOnline();
            registerPlaceholders();
            for (Player online : Bukkit.getOnlinePlayers()) {
                levelSystem.updateTabList(online);
            }
            sender.sendMessage(color("&6&lBISCOB &8» &aConfiguracion y Placeholders recargados correctamente."));
            return true;
        });

        getLogger().info("Plugin Biscob activado correctamente en Folia!");
    }

    // Intento 3: Si PlaceholderAPI se enciende después que Biscob, lo detecta automáticamente
    @EventHandler
    public void onPluginEnable(PluginEnableEvent event) {
        if (event.getPlugin().getName().equalsIgnoreCase("PlaceholderAPI")) {
            registerPlaceholders();
        }
    }

    public void registerPlaceholders() {
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI") && levelSystem != null) {
            boolean success = new BiscobPlaceholder(this, levelSystem).register();
            if (success) {
                getLogger().info("Expansion de PlaceholderAPI 'biscob' (%biscob_level%) registrada con exito!");
            }
        }
    }

    private void setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) return;
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp != null) {
            econ = rsp.getProvider();
        }
    }

    public Economy getEconomy() {
        return econ;
    }

    @Override
    public void onDisable() {
        if (announcerTask != null) announcerTask.stop();
        if (tradeManager != null) tradeManager.cancelAllTrades();
        if (levelSystem != null) levelSystem.stopTimer();
    }

    public String getLuckPermsPrefix(Player player) {
        if (luckPerms == null) return "";
        User user = luckPerms.getUserManager().getUser(player.getUniqueId());
        if (user == null) return "";
        String prefix = user.getCachedData().getMetaData().getPrefix();
        return prefix != null ? prefix : "";
    }

    public String getLuckPermsSuffix(Player player) {
        if (luckPerms == null) return "";
        User user = luckPerms.getUserManager().getUser(player.getUniqueId());
        if (user == null) return "";
        String suffix = user.getCachedData().getMetaData().getSuffix();
        return suffix != null ? suffix : "";
    }

    public boolean isAdmin(Player player) {
        if (player == null) return false;
        if (player.isOp() || player.hasPermission("biscob.admin")) return true;
        return hasLuckPermsGroup(player, Arrays.asList("admin", "owner"));
    }

    public boolean isStaff(Player player) {
        if (player == null) return false;
        if (isAdmin(player) || player.hasPermission("biscob.staff")) return true;
        List<String> staffGroups = getConfig().getStringList("support.staff-groups");
        return hasLuckPermsGroup(player, staffGroups);
    }

    private boolean hasLuckPermsGroup(Player player, List<String> groups) {
        if (luckPerms != null) {
            User user = luckPerms.getUserManager().getUser(player.getUniqueId());
            if (user != null) {
                String primary = user.getPrimaryGroup().toLowerCase();
                for (String g : groups) {
                    if (primary.equalsIgnoreCase(g)) return true;
                }

                Set<String> inherited = user.getNodes(NodeType.INHERITANCE).stream()
                        .map(InheritanceNode::getGroupName)
                        .map(String::toLowerCase)
                        .collect(Collectors.toSet());

                for (String g : groups) {
                    if (inherited.contains(g.toLowerCase())) return true;
                }
            }
        }

        for (String g : groups) {
            if (player.hasPermission("group." + g.toLowerCase())) return true;
        }
        return false;
    }

    public static String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }
}