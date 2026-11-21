package com.biscob.core.levelsystem;

import com.biscob.core.Biscob;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class BiscobPlaceholder extends PlaceholderExpansion {

    private final Biscob plugin;
    private final LevelSystem levelSystem;

    public BiscobPlaceholder(Biscob plugin, LevelSystem levelSystem) {
        this.plugin = plugin;
        this.levelSystem = levelSystem;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "biscob";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Biscob";
    }

    @Override
    public @NotNull String getVersion() {
        return "1.0";
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public boolean canRegister() {
        return true;
    }

    @Override
    public @Nullable String onPlaceholderRequest(Player player, @NotNull String params) {
        return onRequest(player, params);
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) return "1";

        switch (params.toLowerCase()) {
            case "level":
            case "nivel":
                return String.valueOf(levelSystem.getPlayerLevel(player.getUniqueId()));

            case "level_prefix":
            case "prefix":
                int lvl = levelSystem.getPlayerLevel(player.getUniqueId());
                String format = plugin.getConfig().getString("levels.tab-prefix", "&8[&6%level%&8] &r");
                return Biscob.color(format
                        .replace("%level%", String.valueOf(lvl))
                        .replace("%biscob_level%", String.valueOf(lvl)));

            case "exp":
            case "xp":
                return String.valueOf(levelSystem.getPlayerExp(player.getUniqueId()));

            case "exp_required":
            case "xp_required":
                int nextLvl = levelSystem.getPlayerLevel(player.getUniqueId()) + 1;
                if (nextLvl > LevelSystem.MAX_LEVEL) return "MAX";
                return String.valueOf(levelSystem.getRequiredExp(nextLvl));

            case "boost":
                return "x" + levelSystem.getPlayerBoost(player.getUniqueId());

            case "payday_minutes":
            case "minutos_restantes":
                return String.valueOf(60 - levelSystem.getPlayerMinutes(player.getUniqueId()));

            default:
                return null;
        }
    }
}