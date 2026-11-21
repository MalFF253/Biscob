package com.biscob.core;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;

public class BlockManager implements Listener {

    private final Biscob plugin;

    public BlockManager(Biscob plugin) {
        this.plugin = plugin;
        ensureConfigDefaults();
    }

    private void ensureConfigDefaults() {
        boolean changed = false;
        if (!plugin.getConfig().contains("restrictions.disable-placing-crystals")) {
            plugin.getConfig().set("restrictions.disable-placing-crystals", true);
            changed = true;
        }
        if (!plugin.getConfig().contains("restrictions.disable-placing-anchors")) {
            plugin.getConfig().set("restrictions.disable-placing-anchors", true);
            changed = true;
        }
        if (changed) {
            plugin.saveConfig();
        }
    }

    // 1. Cancelar colocar Cristales del End sobre Obsidiana o Bedrock
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlaceCrystal(PlayerInteractEvent event) {
        if (!plugin.getConfig().getBoolean("restrictions.disable-placing-crystals", true)) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getItem() == null || event.getItem().getType() != Material.END_CRYSTAL) return;
        if (event.getClickedBlock() == null) return;

        Material clickedType = event.getClickedBlock().getType();
        if (clickedType == Material.OBSIDIAN || clickedType == Material.BEDROCK) {
            event.setCancelled(true);
            Player player = event.getPlayer();
            player.sendMessage(Biscob.color("&6&lBISCOB &8» &c¡Colocar Cristales del End está desactivado!"));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
        }
    }

    // 2. Cancelar colocar Anclas de Reaparición (Respawn Anchors)
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlaceAnchor(BlockPlaceEvent event) {
        if (!plugin.getConfig().getBoolean("restrictions.disable-placing-anchors", true)) return;
        if (event.getBlockPlaced().getType() == Material.RESPAWN_ANCHOR) {
            event.setCancelled(true);
            Player player = event.getPlayer();
            player.sendMessage(Biscob.color("&6&lBISCOB &8» &c¡Colocar Anclas de Reaparición está desactivado!"));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
        }
    }
}