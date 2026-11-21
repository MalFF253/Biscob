package com.biscob.core;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class TradeManager implements CommandExecutor, Listener {

    private final Biscob plugin;
    private final Map<UUID, UUID> tradeRequests = new ConcurrentHashMap<>();
    private final Map<UUID, TradeSession> activeTrades = new ConcurrentHashMap<>();
    private final DecimalFormat df = new DecimalFormat("#,###.##");

    private static final int[] LEFT_SLOTS = {
            0, 1, 2, 3,
            9, 10, 11, 12,
            18, 19, 20, 21,
            27, 28, 29, 30,
            36, 37, 38, 39
    };

    private static final int[] RIGHT_SLOTS = {
            5, 6, 7, 8,
            14, 15, 16, 17,
            23, 24, 25, 26,
            32, 33, 34, 35,
            41, 42, 43, 44
    };

    private static final int[] DIVIDER_SLOTS = {4, 13, 22, 31, 40, 49};
    private static final int SELF_MONEY_SLOT = 45;
    private static final int SELF_BUTTON_SLOT = 47;
    private static final int SELF_STATUS_SLOT = 48;
    private static final int OTHER_STATUS_SLOT = 50;
    private static final int OTHER_MONEY_SLOT = 53;

    public TradeManager(Biscob plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Solo jugadores pueden comerciar.");
            return true;
        }

        if (args.length == 0) {
            player.sendMessage(Biscob.color("&6&lTRADE &8» &fUsa &6/trade <jugador> &fpara enviar solicitud o &6/trade aceptar &fpara aceptar."));
            return true;
        }

        if (args[0].equalsIgnoreCase("aceptar") || args[0].equalsIgnoreCase("accept")) {
            UUID requesterId = tradeRequests.remove(player.getUniqueId());
            if (requesterId == null) {
                player.sendMessage(Biscob.color("&6&lTRADE &8» &cNo tienes solicitudes de intercambio pendientes."));
                return true;
            }

            Player requester = Bukkit.getPlayer(requesterId);
            if (requester == null || !requester.isOnline()) {
                player.sendMessage(Biscob.color("&6&lTRADE &8» &cEl jugador ya no está conectado."));
                return true;
            }

            if (activeTrades.containsKey(player.getUniqueId()) || activeTrades.containsKey(requester.getUniqueId())) {
                player.sendMessage(Biscob.color("&6&lTRADE &8» &cUno de los jugadores ya está en otro intercambio."));
                return true;
            }

            if (!checkDistance(player, requester)) return true;

            startTradeSession(requester, player);
            return true;
        }

        Player target = Bukkit.getPlayer(args[0]);
        if (target == null || !target.isOnline()) {
            player.sendMessage(Biscob.color("&6&lTRADE &8» &cEse jugador no está conectado."));
            return true;
        }

        if (target.getUniqueId().equals(player.getUniqueId())) {
            player.sendMessage(Biscob.color("&6&lTRADE &8» &cNo puedes comerciar contigo mismo."));
            return true;
        }

        if (!checkDistance(player, target)) return true;

        tradeRequests.put(target.getUniqueId(), player.getUniqueId());
        player.sendMessage(Biscob.color("&6&lTRADE &8» &aSolicitud de intercambio enviada a &f" + target.getName() + "&a."));

        target.getScheduler().run(plugin, t -> {
            target.sendMessage(Biscob.color("&8&m----------------------------------------"));
            target.sendMessage(Biscob.color("&6&lTRADE &8» &f" + player.getName() + " &7quiere intercambiar contigo."));
            target.sendMessage(Biscob.color("&8» &fEscribe &6/trade aceptar &fpara abrir el menú seguro."));
            target.sendMessage(Biscob.color("&8&m----------------------------------------"));
            target.playSound(target.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
        }, null);

        return true;
    }

    private boolean checkDistance(Player p1, Player p2) {
        if (!p1.getWorld().equals(p2.getWorld())) {
            p1.sendMessage(Biscob.color("&6&lTRADE &8» &cDeben estar en el mismo mundo para comerciar."));
            return false;
        }
        double maxDist = plugin.getConfig().getDouble("trade.max-distance", 25.0);
        if (maxDist > 0 && p1.getLocation().distance(p2.getLocation()) > maxDist) {
            p1.sendMessage(Biscob.color("&6&lTRADE &8» &cEstás demasiado lejos de &f" + p2.getName() + " &c(Máx. " + (int) maxDist + " bloques)."));
            return false;
        }
        return true;
    }

    private void startTradeSession(Player p1, Player p2) {
        TradeSession session = new TradeSession(p1, p2);
        activeTrades.put(p1.getUniqueId(), session);
        activeTrades.put(p2.getUniqueId(), session);

        p1.getScheduler().run(plugin, t -> p1.openInventory(session.inv1), null);
        p2.getScheduler().run(plugin, t -> p2.openInventory(session.inv2), null);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof TradeHolder holder)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        TradeSession session = holder.session;
        if (session == null || session.completed) {
            event.setCancelled(true);
            return;
        }

        Inventory clickedInv = event.getClickedInventory();
        if (clickedInv == null) return;

        // Permitir Shift-Click SOLO en el botón de dinero
        if (clickedInv.equals(event.getView().getTopInventory()) && event.getSlot() == SELF_MONEY_SLOT) {
            event.setCancelled(true);
            session.handleMoneyClick(player, event.getClick());
            return;
        }

        // Bloquear Shift-Click o Doble Click para los ítems normales
        if (event.getClick().isShiftClick() || event.getClick().name().contains("DOUBLE")) {
            event.setCancelled(true);
            return;
        }

        // Permitir agarrar objetos de su propio inventario inferior
        if (clickedInv.equals(event.getView().getBottomInventory())) {
            if (session.isReady(player)) {
                event.setCancelled(true);
                player.sendMessage(Biscob.color("&6&lTRADE &8» &cDesmarca tu confirmación antes de modificar tus ítems."));
            }
            return;
        }

        int slot = event.getSlot();

        if (slot == SELF_BUTTON_SLOT) {
            event.setCancelled(true);
            session.toggleReady(player);
            return;
        }

        if (isLeftSlot(slot)) {
            session.resetReadyStates();
            player.getScheduler().run(plugin, t -> session.syncItems(), null);
        } else {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof TradeHolder holder)) return;
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < 54 && !isLeftSlot(rawSlot)) {
                event.setCancelled(true);
                return;
            }
        }
        TradeSession session = holder.session;
        session.resetReadyStates();
        event.getWhoClicked().getScheduler().run(plugin, t -> session.syncItems(), null);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof TradeHolder holder)) return;
        TradeSession session = holder.session;
        if (session != null && !session.completed) {
            session.cancelTrade((Player) event.getPlayer());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        tradeRequests.remove(uuid);
        TradeSession session = activeTrades.get(uuid);
        if (session != null && !session.completed) {
            session.cancelTrade(event.getPlayer());
        }
    }

    public void cancelAllTrades() {
        for (TradeSession session : new HashSet<>(activeTrades.values())) {
            if (!session.completed) {
                session.cancelTrade(null);
            }
        }
        activeTrades.clear();
    }

    private boolean isLeftSlot(int slot) {
        for (int s : LEFT_SLOTS) {
            if (s == slot) return true;
        }
        return false;
    }

    private ItemStack createGuiItem(Material mat, String name, String... lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(Biscob.color(name));
            if (lore.length > 0) {
                List<String> coloredLore = new ArrayList<>();
                for (String l : lore) coloredLore.add(Biscob.color(l));
                meta.setLore(coloredLore);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private class TradeSession {
        final Player p1;
        final Player p2;
        final Inventory inv1;
        final Inventory inv2;
        double money1 = 0.0;
        double money2 = 0.0;
        boolean ready1 = false;
        boolean ready2 = false;
        boolean completed = false;

        TradeSession(Player p1, Player p2) {
            this.p1 = p1;
            this.p2 = p2;
            this.inv1 = Bukkit.createInventory(new TradeHolder(this), 54, Biscob.color("&8Tú &0| &8" + p2.getName()));
            this.inv2 = Bukkit.createInventory(new TradeHolder(this), 54, Biscob.color("&8Tú &0| &8" + p1.getName()));
            setupBorders(inv1);
            setupBorders(inv2);
            updateButtons();
        }

        void setupBorders(Inventory inv) {
            ItemStack divider = createGuiItem(Material.ORANGE_STAINED_GLASS_PANE, "&6&lBISCOB TRADE");
            for (int slot : DIVIDER_SLOTS) inv.setItem(slot, divider);

            ItemStack bottom = createGuiItem(Material.BLACK_STAINED_GLASS_PANE, " ");
            for (int i = 45; i < 54; i++) {
                if (i != SELF_MONEY_SLOT && i != SELF_BUTTON_SLOT && i != SELF_STATUS_SLOT
                        && i != OTHER_STATUS_SLOT && i != OTHER_MONEY_SLOT && i != 49) {
                    inv.setItem(i, bottom);
                }
            }
        }

        boolean isReady(Player p) {
            return p.equals(p1) ? ready1 : ready2;
        }

        void handleMoneyClick(Player p, ClickType click) {
            Economy econ = plugin.getEconomy();
            if (econ == null) {
                p.sendMessage(Biscob.color("&6&lTRADE &8» &cEl sistema de economía (Vault) no está disponible."));
                return;
            }

            boolean isP1 = p.equals(p1);
            double currentOffer = isP1 ? money1 : money2;
            double balance = econ.getBalance(p);
            double newOffer = currentOffer;

            if (click == ClickType.LEFT) {
                newOffer += 100;
            } else if (click == ClickType.SHIFT_LEFT) {
                newOffer += 1000;
            } else if (click == ClickType.RIGHT) {
                newOffer -= 100;
            } else if (click == ClickType.SHIFT_RIGHT) {
                newOffer -= 1000;
            } else if (click == ClickType.DROP || click == ClickType.CONTROL_DROP || click == ClickType.MIDDLE) {
                newOffer = 0;
            } else {
                return;
            }

            if (newOffer < 0) newOffer = 0;
            if (newOffer > balance) {
                newOffer = balance;
                p.sendMessage(Biscob.color("&6&lTRADE &8» &eHas alcanzado el máximo de tu saldo disponible (&a$" + df.format(balance) + "&e)."));
            }

            if (newOffer == currentOffer) return;

            if (isP1) money1 = newOffer;
            else money2 = newOffer;

            // Desmarcar confirmación por seguridad Anti-Scam al cambiar el dinero
            resetReadyStates();
            updateButtons();
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.4f);
        }

        void toggleReady(Player p) {
            if (p.equals(p1)) ready1 = !ready1;
            else ready2 = !ready2;

            updateButtons();
            p1.playSound(p1.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1.2f);
            p2.playSound(p2.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1.2f);

            if (ready1 && ready2) {
                completeTrade();
            }
        }

        void resetReadyStates() {
            if (ready1 || ready2) {
                ready1 = false;
                ready2 = false;
            }
            updateButtons();
        }

        void updateButtons() {
            // Botones de dinero para P1
            inv1.setItem(SELF_MONEY_SLOT, createGuiItem(
                    Material.GOLD_NUGGET,
                    "&6&lTu Oferta de Dinero: &e$" + df.format(money1),
                    "",
                    "&a▸ Clic Izquierdo: &f+$100",
                    "&a▸ Shift + Clic Izquierdo: &f+$1,000",
                    "&c▸ Clic Derecho: &f-$100",
                    "&c▸ Shift + Clic Derecho: &f-$1,000",
                    "&7▸ Soltar (Q): &fResetear a $0"
            ));
            inv1.setItem(OTHER_MONEY_SLOT, createGuiItem(
                    Material.GOLD_INGOT,
                    "&6&lDinero de " + p2.getName() + ": &e$" + df.format(money2),
                    "&7Recibirás esta cantidad al confirmar."
            ));

            // Botones de dinero para P2
            inv2.setItem(SELF_MONEY_SLOT, createGuiItem(
                    Material.GOLD_NUGGET,
                    "&6&lTu Oferta de Dinero: &e$" + df.format(money2),
                    "",
                    "&a▸ Clic Izquierdo: &f+$100",
                    "&a▸ Shift + Clic Izquierdo: &f+$1,000",
                    "&c▸ Clic Derecho: &f-$100",
                    "&c▸ Shift + Clic Derecho: &f-$1,000",
                    "&7▸ Soltar (Q): &fResetear a $0"
            ));
            inv2.setItem(OTHER_MONEY_SLOT, createGuiItem(
                    Material.GOLD_INGOT,
                    "&6&lDinero de " + p1.getName() + ": &e$" + df.format(money1),
                    "&7Recibirás esta cantidad al confirmar."
            ));

            // Botones de estado P1
            inv1.setItem(SELF_BUTTON_SLOT, createGuiItem(
                    ready1 ? Material.LIME_TERRACOTTA : Material.ORANGE_TERRACOTTA,
                    ready1 ? "&a&lCONFIRMAR (Listo)" : "&6&lCLIC PARA ACEPTAR",
                    "&7Haz clic para cambiar tu estado."
            ));
            inv1.setItem(SELF_STATUS_SLOT, createGuiItem(
                    ready1 ? Material.LIME_DYE : Material.GRAY_DYE,
                    ready1 ? "&aTu estado: &lLISTO" : "&7Tu estado: &eEsperando..."
            ));
            inv1.setItem(OTHER_STATUS_SLOT, createGuiItem(
                    ready2 ? Material.LIME_DYE : Material.GRAY_DYE,
                    ready2 ? "&a" + p2.getName() + ": &lLISTO" : "&7" + p2.getName() + ": &eEsperando..."
            ));

            // Botones de estado P2
            inv2.setItem(SELF_BUTTON_SLOT, createGuiItem(
                    ready2 ? Material.LIME_TERRACOTTA : Material.ORANGE_TERRACOTTA,
                    ready2 ? "&a&lCONFIRMAR (Listo)" : "&6&lCLIC PARA ACEPTAR",
                    "&7Haz clic para cambiar tu estado."
            ));
            inv2.setItem(SELF_STATUS_SLOT, createGuiItem(
                    ready2 ? Material.LIME_DYE : Material.GRAY_DYE,
                    ready2 ? "&aTu estado: &lLISTO" : "&7Tu estado: &eEsperando..."
            ));
            inv2.setItem(OTHER_STATUS_SLOT, createGuiItem(
                    ready1 ? Material.LIME_DYE : Material.GRAY_DYE,
                    ready1 ? "&a" + p1.getName() + ": &lLISTO" : "&7" + p1.getName() + ": &eEsperando..."
            ));
        }

        void syncItems() {
            if (completed) return;
            for (int i = 0; i < LEFT_SLOTS.length; i++) {
                ItemStack item1 = inv1.getItem(LEFT_SLOTS[i]);
                inv2.setItem(RIGHT_SLOTS[i], item1 != null ? item1.clone() : null);

                ItemStack item2 = inv2.getItem(LEFT_SLOTS[i]);
                inv1.setItem(RIGHT_SLOTS[i], item2 != null ? item2.clone() : null);
            }
        }

        void completeTrade() {
            Economy econ = plugin.getEconomy();
            if (econ != null) {
                if (money1 > 0 && !econ.has(p1, money1)) {
                    p1.sendMessage(Biscob.color("&6&lTRADE &8» &cNo tienes suficiente dinero para completar el intercambio."));
                    cancelTrade(null);
                    return;
                }
                if (money2 > 0 && !econ.has(p2, money2)) {
                    p2.sendMessage(Biscob.color("&6&lTRADE &8» &cNo tienes suficiente dinero para completar el intercambio."));
                    cancelTrade(null);
                    return;
                }
            }

            completed = true;
            activeTrades.remove(p1.getUniqueId());
            activeTrades.remove(p2.getUniqueId());

            // Transferir dinero en Vault
            if (econ != null) {
                if (money1 > 0) {
                    econ.withdrawPlayer(p1, money1);
                    econ.depositPlayer(p2, money1);
                }
                if (money2 > 0) {
                    econ.withdrawPlayer(p2, money2);
                    econ.depositPlayer(p1, money2);
                }
            }

            List<ItemStack> itemsFrom1 = new ArrayList<>();
            List<ItemStack> itemsFrom2 = new ArrayList<>();

            for (int slot : LEFT_SLOTS) {
                ItemStack i1 = inv1.getItem(slot);
                if (i1 != null && i1.getType() != Material.AIR) itemsFrom1.add(i1.clone());

                ItemStack i2 = inv2.getItem(slot);
                if (i2 != null && i2.getType() != Material.AIR) itemsFrom2.add(i2.clone());
            }

            inv1.clear();
            inv2.clear();

            final double rec1 = money2;
            final double rec2 = money1;

            p1.getScheduler().run(plugin, t -> {
                p1.closeInventory();
                for (ItemStack item : itemsFrom2) {
                    Map<Integer, ItemStack> leftovers = p1.getInventory().addItem(item);
                    for (ItemStack drop : leftovers.values()) {
                        p1.getWorld().dropItemNaturally(p1.getLocation(), drop);
                    }
                }
                p1.sendMessage(Biscob.color("&6&lTRADE &8» &a¡Intercambio completado con &f" + p2.getName() + "&a!"));
                if (rec1 > 0) p1.sendMessage(Biscob.color("&6&lTRADE &8» &eHas recibido &a+$" + df.format(rec1) + " &ede &f" + p2.getName() + "&e."));
                p1.playSound(p1.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
            }, null);

            p2.getScheduler().run(plugin, t -> {
                p2.closeInventory();
                for (ItemStack item : itemsFrom1) {
                    Map<Integer, ItemStack> leftovers = p2.getInventory().addItem(item);
                    for (ItemStack drop : leftovers.values()) {
                        p2.getWorld().dropItemNaturally(p2.getLocation(), drop);
                    }
                }
                p2.sendMessage(Biscob.color("&6&lTRADE &8» &a¡Intercambio completado con &f" + p1.getName() + "&a!"));
                if (rec2 > 0) p2.sendMessage(Biscob.color("&6&lTRADE &8» &eHas recibido &a+$" + df.format(rec2) + " &ede &f" + p1.getName() + "&e."));
                p2.playSound(p2.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
            }, null);
        }

        void cancelTrade(Player closer) {
            if (completed) return;
            completed = true;
            activeTrades.remove(p1.getUniqueId());
            activeTrades.remove(p2.getUniqueId());

            List<ItemStack> returnTo1 = new ArrayList<>();
            List<ItemStack> returnTo2 = new ArrayList<>();

            for (int slot : LEFT_SLOTS) {
                ItemStack i1 = inv1.getItem(slot);
                if (i1 != null && i1.getType() != Material.AIR) returnTo1.add(i1.clone());

                ItemStack i2 = inv2.getItem(slot);
                if (i2 != null && i2.getType() != Material.AIR) returnTo2.add(i2.clone());
            }

            inv1.clear();
            inv2.clear();

            p1.getScheduler().run(plugin, t -> {
                if (closer == null || !closer.equals(p1)) p1.closeInventory();
                for (ItemStack item : returnTo1) {
                    Map<Integer, ItemStack> leftovers = p1.getInventory().addItem(item);
                    for (ItemStack drop : leftovers.values()) {
                        p1.getWorld().dropItemNaturally(p1.getLocation(), drop);
                    }
                }
                p1.sendMessage(Biscob.color("&6&lTRADE &8» &cEl intercambio fue cancelado. Tus ítems y dinero no han sido tocados."));
                p1.playSound(p1.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            }, null);

            p2.getScheduler().run(plugin, t -> {
                if (closer == null || !closer.equals(p2)) p2.closeInventory();
                for (ItemStack item : returnTo2) {
                    Map<Integer, ItemStack> leftovers = p2.getInventory().addItem(item);
                    for (ItemStack drop : leftovers.values()) {
                        p2.getWorld().dropItemNaturally(p2.getLocation(), drop);
                    }
                }
                p2.sendMessage(Biscob.color("&6&lTRADE &8» &cEl intercambio fue cancelado. Tus ítems y dinero no han sido tocados."));
                p2.playSound(p2.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            }, null);
        }
    }

    private static class TradeHolder implements InventoryHolder {
        final TradeSession session;

        TradeHolder(TradeSession session) {
            this.session = session;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return null;
        }
    }
}