package com.biscob.core;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPortalEnterEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class DimensionManager implements CommandExecutor, TabCompleter, Listener {

    private final Biscob plugin;
    private final DatabaseManager db;
    private final Map<UUID, Long> messageCooldown = new ConcurrentHashMap<>();

    public DimensionManager(Biscob plugin, DatabaseManager db) {
        this.plugin = plugin;
        this.db = db;
        ensureConfigMessages();
    }

    public void ensureConfigMessages() {
        boolean changed = false;
        if (!plugin.getConfig().contains("dimensions.end.deny-message")) {
            plugin.getConfig().set("dimensions.end.deny-message", "&6&lBISCOB &8» &c¡El acceso al End se encuentra cerrado temporalmente!");
            plugin.getConfig().set("dimensions.end.broadcast-on", "&8&m----------------------------------------\n&6&lBISCOB &8» &a¡El portal al &5&lEND &aha sido &lABIERTO&a!\n&8&m----------------------------------------");
            plugin.getConfig().set("dimensions.end.broadcast-off", "&8&m----------------------------------------\n&6&lBISCOB &8» &c¡El acceso al &5&lEND &cha sido &lCERRADO&c!\n&8&m----------------------------------------");
            changed = true;
        }
        if (!plugin.getConfig().contains("dimensions.nether.deny-message")) {
            plugin.getConfig().set("dimensions.nether.deny-message", "&6&lBISCOB &8» &c¡El acceso al Nether se encuentra cerrado temporalmente!");
            plugin.getConfig().set("dimensions.nether.broadcast-on", "&8&m----------------------------------------\n&6&lBISCOB &8» &a¡El acceso al &4&lNETHER &aha sido &lABIERTO&a!\n&8&m----------------------------------------");
            plugin.getConfig().set("dimensions.nether.broadcast-off", "&8&m----------------------------------------\n&6&lBISCOB &8» &c¡El acceso al &4&lNETHER &cha sido &lCERRADO&c!\n&8&m----------------------------------------");
            changed = true;
        }
        if (changed) {
            plugin.saveConfig();
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (sender instanceof Player player && !plugin.isAdmin(player)) {
            player.sendMessage(Biscob.color("&6&lBISCOB &8» &cNo tienes permiso para usar este comando."));
            return true;
        }

        String cmd = command.getName().toLowerCase(); // "end" o "nether"

        if (args.length == 0 || (!args[0].equalsIgnoreCase("on") && !args[0].equalsIgnoreCase("off"))) {
            boolean currentState = db.isDimensionEnabled(cmd);
            String status = currentState ? "&aACTIVADO (ON)" : "&cDESACTIVADO (OFF)";
            sender.sendMessage(Biscob.color("&6&lBISCOB &8» &7Estado actual de &e" + cmd.toUpperCase() + " &7(SQLite): " + status));
            sender.sendMessage(Biscob.color("&6&lBISCOB &8» &7Usa: &6/" + cmd + " <on|off>"));
            return true;
        }

        boolean enable = args[0].equalsIgnoreCase("on");
        // Guardar en SQLite
        db.setDimensionEnabled(cmd, enable);

        String defaultBroadcast = enable
                ? "&8&m----------------------------------------\n&6&lBISCOB &8» &a¡El acceso a &e&l" + cmd.toUpperCase() + " &aha sido &lABIERTO&a!\n&8&m----------------------------------------"
                : "&8&m----------------------------------------\n&6&lBISCOB &8» &c¡El acceso a &e&l" + cmd.toUpperCase() + " &cha sido &lCERRADO&c!\n&8&m----------------------------------------";

        String broadcastPath = "dimensions." + cmd + "." + (enable ? "broadcast-on" : "broadcast-off");
        String broadcastMsg = plugin.getConfig().getString(broadcastPath, defaultBroadcast);

        String[] lines = Biscob.color(broadcastMsg).split("\n");
        for (Player online : Bukkit.getOnlinePlayers()) {
            online.getScheduler().run(plugin, t -> {
                for (String line : lines) {
                    online.sendMessage(line);
                }
                online.playSound(online.getLocation(),
                        enable ? Sound.ENTITY_ENDER_DRAGON_GROWL : Sound.BLOCK_ANVIL_LAND,
                        0.6f, 1.0f);
            }, null);
        }

        if (!(sender instanceof Player)) {
            sender.sendMessage(Biscob.color("&6[Biscob] &fDimension " + cmd.toUpperCase() + " guardada en SQLite como: " + (enable ? "&aON" : "&cOFF")));
        }

        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (sender instanceof Player player && !plugin.isAdmin(player)) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            return Arrays.asList("on", "off");
        }
        return Collections.emptyList();
    }

    private void sendDenyMessage(Player player, String dimension) {
        long now = System.currentTimeMillis();
        long last = messageCooldown.getOrDefault(player.getUniqueId(), 0L);
        if (now - last < 2000) return;
        messageCooldown.put(player.getUniqueId(), now);

        String defaultMsg = "&6&lBISCOB &8» &c¡El acceso al " + dimension.toUpperCase() + " se encuentra cerrado temporalmente!";
        String msg = plugin.getConfig().getString("dimensions." + dimension + ".deny-message", defaultMsg);
        player.sendMessage(Biscob.color(msg));
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
    }

    // 1. Cancelar colocar el Ojo de Ender en el marco del portal cuando el End está en OFF
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlaceEnderEye(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getClickedBlock() == null || event.getClickedBlock().getType() != Material.END_PORTAL_FRAME) return;
        if (event.getItem() == null || event.getItem().getType() != Material.ENDER_EYE) return;

        if (!db.isDimensionEnabled("end")) {
            event.setCancelled(true);
            sendDenyMessage(event.getPlayer(), "end");
        }
    }

    // 2. Cancelar encender portales del Nether cuando el Nether está en OFF
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPortalCreate(PortalCreateEvent event) {
        if (event.getReason() == PortalCreateEvent.CreateReason.FIRE) {
            if (!db.isDimensionEnabled("nether")) {
                event.setCancelled(true);
                if (event.getEntity() instanceof Player player) {
                    sendDenyMessage(player, "nether");
                }
            }
        }
    }

    // 3. Bloquear PlayerPortalEvent
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerPortal(PlayerPortalEvent event) {
        Player player = event.getPlayer();
        PlayerTeleportEvent.TeleportCause cause = event.getCause();

        if (cause == PlayerTeleportEvent.TeleportCause.NETHER_PORTAL) {
            if (!db.isDimensionEnabled("nether")) {
                event.setCancelled(true);
                sendDenyMessage(player, "nether");
            }
        } else if (cause == PlayerTeleportEvent.TeleportCause.END_PORTAL) {
            if (!db.isDimensionEnabled("end")) {
                event.setCancelled(true);
                sendDenyMessage(player, "end");
            }
        }
    }

    // 4. Bloquear PlayerTeleportEvent (Respaldo específico para Folia 26.3)
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        PlayerTeleportEvent.TeleportCause cause = event.getCause();

        if (cause == PlayerTeleportEvent.TeleportCause.NETHER_PORTAL) {
            if (!db.isDimensionEnabled("nether")) {
                event.setCancelled(true);
                sendDenyMessage(player, "nether");
            }
        } else if (cause == PlayerTeleportEvent.TeleportCause.END_PORTAL) {
            if (!db.isDimensionEnabled("end")) {
                event.setCancelled(true);
                sendDenyMessage(player, "end");
            }
        }
    }

    // 5. Detectar entrada física al bloque de portal en Folia
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPortalEnter(EntityPortalEnterEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        Material blockType = event.getLocation().getBlock().getType();
        if (blockType == Material.NETHER_PORTAL) {
            if (!db.isDimensionEnabled("nether")) {
                sendDenyMessage(player, "nether");
            }
        } else if (blockType == Material.END_PORTAL) {
            if (!db.isDimensionEnabled("end")) {
                sendDenyMessage(player, "end");
                player.getScheduler().run(plugin, t -> {
                    player.setVelocity(player.getLocation().getDirection().multiply(-0.8).setY(0.4));
                }, null);
            }
        }
    }
}