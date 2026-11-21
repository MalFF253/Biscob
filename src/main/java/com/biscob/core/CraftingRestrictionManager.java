package com.biscob.core;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;

import java.util.Iterator;

public class CraftingRestrictionManager implements Listener {

    private final Biscob plugin;

    public CraftingRestrictionManager(Biscob plugin) {
        this.plugin = plugin;
        ensureConfigDefaults();
        removeRecipes();
    }

    private void ensureConfigDefaults() {
        boolean changed = false;
        if (!plugin.getConfig().contains("restrictions.disable-ender-crystals")) {
            plugin.getConfig().set("restrictions.disable-ender-crystals", true);
            changed = true;
        }
        if (!plugin.getConfig().contains("restrictions.disable-respawn-anchors")) {
            plugin.getConfig().set("restrictions.disable-respawn-anchors", true);
            changed = true;
        }
        if (changed) {
            plugin.saveConfig();
        }
    }

    // Capa 1: Elimina las recetas del registro del servidor al arrancar
    public void removeRecipes() {
        boolean disableCrystals = plugin.getConfig().getBoolean("restrictions.disable-ender-crystals", true);
        boolean disableAnchors = plugin.getConfig().getBoolean("restrictions.disable-respawn-anchors", true);

        Iterator<Recipe> iterator = Bukkit.recipeIterator();
        while (iterator.hasNext()) {
            Recipe recipe = iterator.next();
            if (recipe != null) {
                Material resultType = recipe.getResult().getType();
                if (disableCrystals && resultType == Material.END_CRYSTAL) {
                    iterator.remove();
                }
                if (disableAnchors && resultType == Material.RESPAWN_ANCHOR) {
                    iterator.remove();
                }
            }
        }
    }

    // Capa 2: Limpia el slot de resultado en la mesa de crafteo
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        ItemStack result = event.getInventory().getResult();
        if (result == null || result.getType() == Material.AIR) return;

        Material type = result.getType();
        boolean disableCrystals = plugin.getConfig().getBoolean("restrictions.disable-ender-crystals", true);
        boolean disableAnchors = plugin.getConfig().getBoolean("restrictions.disable-respawn-anchors", true);

        if ((disableCrystals && type == Material.END_CRYSTAL) || (disableAnchors && type == Material.RESPAWN_ANCHOR)) {
            event.getInventory().setResult(new ItemStack(Material.AIR));
        }
    }

    // Capa 3: Bloquea físicamente la extracción si intentan pulsar el objeto
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCraftItem(CraftItemEvent event) {
        ItemStack result = event.getRecipe().getResult();
        if (result == null) return;

        Material type = result.getType();
        boolean disableCrystals = plugin.getConfig().getBoolean("restrictions.disable-ender-crystals", true);
        boolean disableAnchors = plugin.getConfig().getBoolean("restrictions.disable-respawn-anchors", true);

        if ((disableCrystals && type == Material.END_CRYSTAL) || (disableAnchors && type == Material.RESPAWN_ANCHOR)) {
            event.setCancelled(true);
        }
    }
}