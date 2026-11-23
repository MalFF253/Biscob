package com.biscob.core;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.jetbrains.annotations.NotNull;

public class StaffChatManager implements Listener {

    private final Biscob plugin;

    public StaffChatManager(@NotNull Biscob plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        String message = event.getMessage();
        if (!message.startsWith("*")) return;

        event.setCancelled(true);
        String staffMessage = message.substring(1).trim();
        Player sender = event.getPlayer();

        Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
            if (!sender.isOnline()) return;

            if (!plugin.isStaff(sender)) {
                sender.getScheduler().run(plugin, playerTask ->
                        sender.sendMessage(Biscob.color("&6&lSTAFF &8» &cNo tienes permiso para usar el chat de Staff.")), null);
                return;
            }

            if (staffMessage.isEmpty()) {
                sender.getScheduler().run(plugin, playerTask ->
                        sender.sendMessage(Biscob.color("&6&lSTAFF &8» &cUso: ! <mensaje>")), null);
                return;
            }

            String rank = plugin.getLuckPermsPrefix(sender).trim();
            if (rank.isEmpty()) {
                rank = "&c[Staff]";
            } else {
                String plainRank = ChatColor.stripColor(Biscob.color(rank)).trim();
                if (!plainRank.startsWith("[") || !plainRank.endsWith("]")) {
                    rank = "&8[&r" + rank + "&8]";
                }
            }
            String formatted = Biscob.color(rank + " &7- &f" + sender.getName() + "&8: &f") + staffMessage;

            for (Player online : Bukkit.getOnlinePlayers()) {
                if (plugin.isStaff(online)) {
                    online.getScheduler().run(plugin, playerTask -> online.sendMessage(formatted), null);
                }
            }
        });
    }
}
