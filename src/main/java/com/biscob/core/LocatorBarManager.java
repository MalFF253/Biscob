package com.biscob.core;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class LocatorBarManager implements Listener, CommandExecutor, TabCompleter {

    private final Biscob plugin;
    private Attribute transmitAttr;
    private Attribute receiveAttr;

    public LocatorBarManager(Biscob plugin) {
        this.plugin = plugin;
        ensureConfigDefaults();
        resolveAttributes();
        applyToAllOnline();
    }

    private void ensureConfigDefaults() {
        boolean changed = false;
        if (!plugin.getConfig().contains("locator-bar.enabled")) {
            plugin.getConfig().set("locator-bar.enabled", true);
            changed = true;
        }
        if (!plugin.getConfig().contains("locator-bar.range")) {
            plugin.getConfig().set("locator-bar.range", 200.0);
            changed = true;
        }
        if (changed) {
            plugin.saveConfig();
        }
    }

    // Busca los atributos de la 1.21.6 / 26.3 dinámicamente sin romper la compilación en 1.21.1
    private void resolveAttributes() {
        try {
            transmitAttr = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("waypoint_transmit_range"));
            receiveAttr = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("waypoint_receive_range"));
        } catch (Exception ignored) {}

        // Fallback por reflexión por si la versión de Registry cambia el nombre interno
        if (transmitAttr == null) {
            try {
                transmitAttr = (Attribute) Attribute.class.getField("WAYPOINT_TRANSMIT_RANGE").get(null);
                receiveAttr = (Attribute) Attribute.class.getField("WAYPOINT_RECEIVE_RANGE").get(null);
            } catch (Exception ignored) {}
        }

        if (transmitAttr == null || receiveAttr == null) {
            plugin.getLogger().warning("No se detectaron los atributos de LocatorBar en esta version.");
        } else {
            plugin.getLogger().info("Control de LocatorBar activado (Rango: " + getTargetRange() + " bloques).");
        }
    }

    public double getTargetRange() {
        if (!plugin.getConfig().getBoolean("locator-bar.enabled", true)) {
            return 0.0; // Si se apaga, rango 0 = nadie ve a nadie en la barra
        }
        return plugin.getConfig().getDouble("locator-bar.range", 200.0);
    }

    public void applyLocatorRange(Player player) {
        if (transmitAttr == null || receiveAttr == null) return;

        double range = getTargetRange();

        // Ejecutar de forma segura en el hilo de la región del jugador en Folia
        player.getScheduler().run(plugin, task -> {
            AttributeInstance tx = player.getAttribute(transmitAttr);
            if (tx != null) {
                tx.setBaseValue(range);
            }
            AttributeInstance rx = player.getAttribute(receiveAttr);
            if (rx != null) {
                rx.setBaseValue(range);
            }
        }, null);
    }

    public void applyToAllOnline() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            applyLocatorRange(player);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        applyLocatorRange(event.getPlayer());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        // Al morir y reaparecer, Minecraft resetea los atributos base; lo volvemos a aplicar
        Player player = event.getPlayer();
        player.getScheduler().runDelayed(plugin, task -> applyLocatorRange(player), null, 5L);
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        applyLocatorRange(event.getPlayer());
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (sender instanceof Player player && !plugin.isAdmin(player)) {
            player.sendMessage(Biscob.color("&6&lBISCOB &8» &cNo tienes permiso para usar este comando."));
            return true;
        }

        if (args.length == 0) {
            boolean enabled = plugin.getConfig().getBoolean("locator-bar.enabled", true);
            double range = plugin.getConfig().getDouble("locator-bar.range", 200.0);
            sender.sendMessage(Biscob.color("&6&lBISCOB &8» &7LocatorBar: " + (enabled ? "&aON &7(&e" + (int) range + " bloques&7)" : "&cOFF (0 bloques)")));
            sender.sendMessage(Biscob.color("&6&lBISCOB &8» &7Usa: &6/locatorbar <bloques|on|off>"));
            return true;
        }

        String arg = args[0].toLowerCase();
        if (arg.equals("off")) {
            plugin.getConfig().set("locator-bar.enabled", false);
            plugin.saveConfig();
            applyToAllOnline();
            sender.sendMessage(Biscob.color("&6&lBISCOB &8» &cLocatorBar desactivada para todos los jugadores (0 bloques)."));
            return true;
        }

        if (arg.equals("on")) {
            plugin.getConfig().set("locator-bar.enabled", true);
            plugin.saveConfig();
            applyToAllOnline();
            double range = plugin.getConfig().getDouble("locator-bar.range", 200.0);
            sender.sendMessage(Biscob.color("&6&lBISCOB &8» &aLocatorBar activada a &e" + (int) range + " bloques&a."));
            return true;
        }

        try {
            double newRange = Double.parseDouble(arg);
            if (newRange < 0) newRange = 0;

            plugin.getConfig().set("locator-bar.enabled", newRange > 0);
            plugin.getConfig().set("locator-bar.range", newRange);
            plugin.saveConfig();
            applyToAllOnline();

            sender.sendMessage(Biscob.color("&6&lBISCOB &8» &aRango de la LocatorBar actualizado a &e" + (int) newRange + " bloques &apara todos."));
        } catch (NumberFormatException e) {
            sender.sendMessage(Biscob.color("&6&lBISCOB &8» &cValor inválido. Usa un número (ej. 200) o on/off."));
        }

        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (sender instanceof Player player && !plugin.isAdmin(player)) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            return Arrays.asList("200", "100", "50", "on", "off");
        }
        return Collections.emptyList();
    }
}