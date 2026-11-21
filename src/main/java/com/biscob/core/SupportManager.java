package com.biscob.core;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SupportManager implements CommandExecutor, Listener {

    private final Biscob plugin;
    // Solicitudes pendientes: UUID del jugador -> Motivo
    private final Map<UUID, String> pendingTickets = new ConcurrentHashMap<>();
    // Canales privados activos: UUID participante -> UUID del otro participante
    private final Map<UUID, UUID> activeSessions = new ConcurrentHashMap<>();

    public SupportManager(Biscob plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Solo jugadores pueden usar soporte.");
            return true;
        }

        if (args.length == 0) {
            if (plugin.isStaff(player)) {
                player.sendMessage(Biscob.color("&6&lSOPORTE &8» &7Comandos de Staff:"));
                player.sendMessage(Biscob.color("&e/soporte atender <jugador> &8- &fAbre un canal privado con el usuario."));
                player.sendMessage(Biscob.color("&e/soporte cerrar &8- &fCierra el canal de soporte actual."));
            } else {
                player.sendMessage(Biscob.color("&6&lSOPORTE &8» &fUsa &6/soporte <mensaje> &fpara pedir ayuda al Staff, o &6/soporte cerrar &fpara finalizar."));
            }
            return true;
        }

        String sub = args[0].toLowerCase();

        // Cerrar sesión o solicitud
        if (sub.equals("cerrar") || sub.equals("close")) {
            if (activeSessions.containsKey(player.getUniqueId())) {
                UUID partnerId = activeSessions.remove(player.getUniqueId());
                if (partnerId != null) {
                    activeSessions.remove(partnerId);
                    Player partner = Bukkit.getPlayer(partnerId);
                    if (partner != null) {
                        partner.sendMessage(Biscob.color("&6&lSOPORTE &8» &cEl canal de soporte ha sido cerrado por &f" + player.getName() + "&c."));
                    }
                }
                player.sendMessage(Biscob.color("&6&lSOPORTE &8» &aHas salido del canal de soporte. Tu chat vuelve a ser público."));
                return true;
            }
            if (pendingTickets.remove(player.getUniqueId()) != null) {
                player.sendMessage(Biscob.color("&6&lSOPORTE &8» &cHas cancelado tu solicitud de soporte."));
                return true;
            }
            player.sendMessage(Biscob.color("&6&lSOPORTE &8» &cNo tienes ningún canal de soporte activo."));
            return true;
        }

        // Staff atendiendo a un usuario
        if (sub.equals("atender") && plugin.isStaff(player)) {
            if (args.length < 2) {
                player.sendMessage(Biscob.color("&6&lSOPORTE &8» &cUsa: /soporte atender <jugador>"));
                return true;
            }
            if (activeSessions.containsKey(player.getUniqueId())) {
                player.sendMessage(Biscob.color("&6&lSOPORTE &8» &cYa estás en un canal de soporte. Usa &e/soporte cerrar &cprimero."));
                return true;
            }

            Player target = Bukkit.getPlayer(args[1]);
            if (target == null || !target.isOnline()) {
                player.sendMessage(Biscob.color("&6&lSOPORTE &8» &cEse jugador no está conectado."));
                return true;
            }
            if (target.getUniqueId().equals(player.getUniqueId())) {
                player.sendMessage(Biscob.color("&6&lSOPORTE &8» &cNo puedes atenderte a ti mismo."));
                return true;
            }
            if (activeSessions.containsKey(target.getUniqueId())) {
                player.sendMessage(Biscob.color("&6&lSOPORTE &8» &cEste jugador ya está siendo atendido por otro miembro del Staff."));
                return true;
            }

            pendingTickets.remove(target.getUniqueId());
            activeSessions.put(player.getUniqueId(), target.getUniqueId());
            activeSessions.put(target.getUniqueId(), player.getUniqueId());

            player.sendMessage(Biscob.color("&8&m----------------------------------------"));
            player.sendMessage(Biscob.color("&6&lSOPORTE &8» &aCanal privado conectado con &f" + target.getName() + "&a."));
            player.sendMessage(Biscob.color("&7Todo lo que escribas en el chat normal solo lo verá este jugador."));
            player.sendMessage(Biscob.color("&7Usa &e/soporte cerrar &7cuando termines."));
            player.sendMessage(Biscob.color("&8&m----------------------------------------"));

            target.getScheduler().run(plugin, t -> {
                target.sendMessage(Biscob.color("&8&m----------------------------------------"));
                target.sendMessage(Biscob.color("&6&lSOPORTE &8» &fEl miembro del Staff &6" + player.getName() + " &fha tomado tu ticket."));
                target.sendMessage(Biscob.color("&a¡Canal privado activado! &7Escribe directamente en el chat para hablar con el Staff."));
                target.sendMessage(Biscob.color("&7Usa &e/soporte cerrar &7para finalizar."));
                target.sendMessage(Biscob.color("&8&m----------------------------------------"));
                target.playSound(target.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
            }, null);

            for (Player online : Bukkit.getOnlinePlayers()) {
                if (plugin.isStaff(online) && !online.getUniqueId().equals(player.getUniqueId())) {
                    online.sendMessage(Biscob.color("&6&lSOPORTE &8» &e" + player.getName() + " &7está atendiendo a &f" + target.getName() + "&7."));
                }
            }
            return true;
        }

        // Si ya está en un canal activo y usa /soporte <texto>, enviarlo también por el canal
        if (activeSessions.containsKey(player.getUniqueId())) {
            player.sendMessage(Biscob.color("&6&lSOPORTE &8» &7Ya estás en un canal de soporte activo. Escribe directamente en el chat sin comandos o usa &e/soporte cerrar&7."));
            return true;
        }

        // Crear nueva solicitud de soporte
        String reason = String.join(" ", args);
        pendingTickets.put(player.getUniqueId(), reason);

        player.sendMessage(Biscob.color("&6&lSOPORTE &8» &aTu solicitud ha sido enviada al Staff. Espera a que un miembro te atienda."));

        for (Player online : Bukkit.getOnlinePlayers()) {
            if (plugin.isStaff(online)) {
                online.getScheduler().run(plugin, t -> {
                    online.sendMessage(Biscob.color("&8&m----------------------------------------"));
                    online.sendMessage(Biscob.color("&6&l NUEVA SOLICITUD DE SOPORTE"));
                    online.sendMessage(Biscob.color("&8» &7Usuario: &f" + player.getName()));
                    online.sendMessage(Biscob.color("&8» &7Mensaje: &e" + reason));
                    online.sendMessage(Biscob.color("&8» &7Para abrir chat privado usa: &6/soporte atender " + player.getName()));
                    online.sendMessage(Biscob.color("&8&m----------------------------------------"));
                    online.playSound(online.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1f, 1.2f);
                }, null);
            }
        }
        return true;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent event) {
        Player sender = event.getPlayer();
        UUID partnerId = activeSessions.get(sender.getUniqueId());
        if (partnerId == null) return;

        // Cancelamos el mensaje para el resto del servidor (Canal privado Usuario-Staff)
        event.setCancelled(true);

        Player partner = Bukkit.getPlayer(partnerId);
        if (partner == null || !partner.isOnline()) {
            activeSessions.remove(sender.getUniqueId());
            activeSessions.remove(partnerId);
            sender.sendMessage(Biscob.color("&6&lSOPORTE &8» &cLa otra persona se ha desconectado. Canal cerrado."));
            return;
        }

        String roleTag = plugin.isStaff(sender) ? "&6[Staff] " : "&e[Usuario] ";
        String formatted = Biscob.color("&6&lSOPORTE &8| " + roleTag + "&f" + sender.getName() + " &8» &7") + event.getMessage();

        sender.sendMessage(formatted);
        partner.sendMessage(formatted);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        pendingTickets.remove(uuid);
        UUID partnerId = activeSessions.remove(uuid);
        if (partnerId != null) {
            activeSessions.remove(partnerId);
            Player partner = Bukkit.getPlayer(partnerId);
            if (partner != null) {
                partner.sendMessage(Biscob.color("&6&lSOPORTE &8» &c" + event.getPlayer().getName() + " se ha desconectado. El canal de soporte se ha cerrado."));
            }
        }
    }
}