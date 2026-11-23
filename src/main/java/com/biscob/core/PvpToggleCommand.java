package com.biscob.core; // Cambia esto por tu paquete real

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

import java.util.concurrent.atomic.AtomicBoolean;

public class PvpToggleCommand implements CommandExecutor, Listener {

    // true = PvP activado | false = PvP desactivado
    private final AtomicBoolean pvpEnabled = new AtomicBoolean(true);

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("biscob.pvp.toggle")) {
            sender.sendMessage("§cNo tienes permiso para usar este comando.");
            return true;
        }

        boolean nuevoEstado;
        if (args.length > 0 && args[0].equalsIgnoreCase("on")) {
            nuevoEstado = true;
        } else if (args.length > 0 && args[0].equalsIgnoreCase("off")) {
            nuevoEstado = false;
        } else {
            nuevoEstado = !pvpEnabled.get();
        }

        pvpEnabled.set(nuevoEstado);

        if (nuevoEstado) {
            Bukkit.broadcastMessage("§6[Biscob] §aEl PvP ha sido ACTIVADO en el servidor.");
        } else {
            Bukkit.broadcastMessage("§6[Biscob] §cEl PvP ha sido DESACTIVADO temporalmente.");
        }

        return true;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerDamage(EntityDamageByEntityEvent event) {
        if (pvpEnabled.get()) return;
        if (!(event.getEntity() instanceof Player)) return;

        Player attacker = null;
        if (event.getDamager() instanceof Player p) {
            attacker = p;
        } else if (event.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player p) {
            attacker = p;
        }

        // Cancelar el daño si el atacante es otro jugador (permite autodaño con perlas/flechas)
        if (attacker != null && !attacker.equals(event.getEntity())) {
            event.setCancelled(true);
            attacker.sendActionBar(net.kyori.adventure.text.Component.text("§cEl PvP está desactivado temporalmente."));
        }
    }
}