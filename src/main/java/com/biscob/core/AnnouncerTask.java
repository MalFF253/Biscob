package com.biscob.core;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class AnnouncerTask {

    private final Biscob plugin;
    private ScheduledTask task;
    private final AtomicInteger index = new AtomicInteger(0);

    public AnnouncerTask(Biscob plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (!plugin.getConfig().getBoolean("announcer.enabled", true)) return;

        long interval = plugin.getConfig().getLong("announcer.interval-seconds", 300);
        if (interval <= 0) interval = 60;

        task = Bukkit.getAsyncScheduler().runAtFixedRate(plugin, scheduledTask -> {
            List<String> messages = plugin.getConfig().getStringList("announcer.messages");
            if (messages.isEmpty()) return;

            int current = index.getAndUpdate(i -> (i + 1) % messages.size());
            if (current >= messages.size()) current = 0;

            String rawMessage = messages.get(current);
            String[] lines = Biscob.color(rawMessage).split("\n");
            boolean playSound = plugin.getConfig().getBoolean("announcer.sound", true);

            for (Player player : Bukkit.getOnlinePlayers()) {
                player.getScheduler().run(plugin, t -> {
                    for (String line : lines) {
                        player.sendMessage(line);
                    }
                    if (playSound) {
                        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, 1.5f);
                    }
                }, null);
            }
        }, interval, interval, TimeUnit.SECONDS);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    public void restart() {
        stop();
        start();
    }
}