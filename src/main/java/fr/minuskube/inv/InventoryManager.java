/*
 * Copyright 2018-2020 Isaac Montagne
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package fr.minuskube.inv;

import fr.minuskube.inv.content.InventoryContents;
import fr.minuskube.inv.content.InventoryProvider;
import fr.minuskube.inv.content.SlotPos;
import fr.minuskube.inv.opener.ChestInventoryOpener;
import fr.minuskube.inv.opener.InventoryOpener;
import fr.minuskube.inv.opener.SpecialInventoryOpener;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

public class InventoryManager {

    private final JavaPlugin plugin;
    private final PluginManager pluginManager;

    private final Map<Player, SmartInventory> inventories;
    private final Map<Player, InventoryContents> contents;
    private final Map<Player, PlayerInvTask> updateTasks;

    private final List<InventoryOpener> defaultOpeners;
    private final List<InventoryOpener> openers;

    public InventoryManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.pluginManager = Bukkit.getPluginManager();

        this.inventories = new HashMap<>();
        this.contents = new HashMap<>();
        this.updateTasks = new HashMap<>();

        this.defaultOpeners = Arrays.asList(
                new ChestInventoryOpener(),
                new SpecialInventoryOpener()
        );

        this.openers = new ArrayList<>();
    }

    public void init() {
        pluginManager.registerEvents(new InvListener(), plugin);
    }


    public Optional<InventoryOpener> findOpener(InventoryType type) {
        Optional<InventoryOpener> opInv = this.openers.stream()
                .filter(opener -> opener.supports(type))
                .findAny();

        if (!opInv.isPresent()) {
            opInv = this.defaultOpeners.stream()
                    .filter(opener -> opener.supports(type))
                    .findAny();
        }

        return opInv;
    }

    public void registerOpeners(InventoryOpener... openers) {
        this.openers.addAll(Arrays.asList(openers));
    }

    public List<Player> getOpenedPlayers(SmartInventory inv) {
        List<Player> list = new ArrayList<>();

        this.inventories.forEach((player, playerInv) -> {
            if (inv.equals(playerInv))
                list.add(player);
        });

        return list;
    }

    public Optional<SmartInventory> getInventory(Player p) {
        return Optional.ofNullable(this.inventories.get(p));
    }

    public boolean removeCachedForPlayer(final Player viewer) {
        final boolean contents = this.contents.remove(viewer) != null;
        final boolean inventory = this.inventories.remove(viewer) != null;

        return contents && inventory;
    }

    protected void setInventory(Player p, SmartInventory inv) {
        if (inv == null)
            this.inventories.remove(p);
        else
            this.inventories.put(p, inv);
    }

    public Optional<InventoryContents> getContents(Player p) {
        return Optional.ofNullable(this.contents.get(p));
    }

    protected void setContents(Player p, InventoryContents contents) {
        if (contents == null)
            this.contents.remove(p);
        else
            this.contents.put(p, contents);
    }

    protected void scheduleUpdateTask(Player p, SmartInventory inv) {
        if (inv.getUpdateFrequency() > 0) {
            PlayerInvTask task = new PlayerInvTask(p, inv.getProvider(), contents.get(p));
            task.runTaskTimer(plugin, 1, inv.getUpdateFrequency());
            this.updateTasks.put(p, task);
        }
    }

    protected void cancelUpdateTask(Player p) {
        PlayerInvTask removed = this.updateTasks.remove(p);
        if (removed != null) {
            Bukkit.getScheduler().cancelTask(removed.getTaskId());
        }
    }

    @SuppressWarnings("unchecked")
    class InvListener implements Listener {

        @EventHandler(priority = EventPriority.LOW)
        public void onInventoryClick(InventoryClickEvent event) {
            final Player player = (Player) event.getWhoClicked();
            final SmartInventory inventory = inventories.get(player);

            if (inventory == null) {
                return;
            }

            final InventoryAction inventoryAction = event.getAction();
            switch (inventoryAction) {
                case NOTHING, COLLECT_TO_CURSOR -> {
                    event.setCancelled(true);
                    return;
                }
            }

            final ClickType clickType = event.getClick();
            final InventoryContents invContents = contents.get(player);
            if (inventoryAction == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
                if (!invContents.property("allowShift", false)) {
                    event.setCancelled(true);
                }
            }

            if (event.getClickedInventory() == player.getOpenInventory().getTopInventory()) {
                if (clickType == ClickType.NUMBER_KEY) {
                    event.setCancelled(true);
                }

                final int row = event.getSlot() / 9;
                final int column = event.getSlot() % 9;

                if (!inventory.checkBounds(row, column)) {
                    return;
                }

                final SlotPos slot = SlotPos.of(row, column);
                if (!invContents.isEditable(slot)) {
                    event.setCancelled(true);
                }

                inventory.getListeners().stream()
                        .filter(listener -> listener.getType() == InventoryClickEvent.class)
                        .forEach(listener -> ((InventoryListener<InventoryClickEvent>) listener).accept(event));

                invContents.get(slot).ifPresent(item -> item.run(new ItemClickData(event, player, event.getCurrentItem(), slot)));

                // Don't update if the clicked slot is editable - prevent item glitching
                if (!invContents.isEditable(slot)) {
                    player.updateInventory();
                }
            }
        }

        @EventHandler(priority = EventPriority.LOW)
        public void onInventoryDrag(InventoryDragEvent e) {
            Player p = (Player) e.getWhoClicked();

            SmartInventory inv = inventories.get(p);
            if (inv == null) return;
            InventoryContents content = contents.get(p);

            for (int slot : e.getRawSlots()) {
                SlotPos pos = SlotPos.of(slot / 9, slot % 9);
                if (slot >= p.getOpenInventory().getTopInventory().getSize() || content.isEditable(pos))
                    continue;

                e.setCancelled(true);
                break;
            }

            inv.getListeners().stream()
                    .filter(listener -> listener.getType() == InventoryDragEvent.class)
                    .forEach(listener -> ((InventoryListener<InventoryDragEvent>) listener).accept(e));
        }

        @EventHandler(priority = EventPriority.LOW)
        public void onInventoryOpen(InventoryOpenEvent e) {
            Player p = (Player) e.getPlayer();

            SmartInventory inv = inventories.get(p);
            if (inv == null) return;

            inv.getListeners().stream()
                    .filter(listener -> listener.getType() == InventoryOpenEvent.class)
                    .forEach(listener -> ((InventoryListener<InventoryOpenEvent>) listener).accept(e));
        }

        @EventHandler(priority = EventPriority.LOW)
        public void onInventoryClose(InventoryCloseEvent e) {
            Player p = (Player) e.getPlayer();

            SmartInventory inv = inventories.get(p);
            if (inv == null) return;

            try {
                inv.getListeners().stream()
                        .filter(listener -> listener.getType() == InventoryCloseEvent.class)
                        .forEach(listener -> ((InventoryListener<InventoryCloseEvent>) listener).accept(e));
            } finally {
                if (inv.isCloseable()) {
                    e.getInventory().clear();
                    InventoryManager.this.cancelUpdateTask(p);

                    inventories.remove(p);
                    contents.remove(p);
                } else
                    Bukkit.getScheduler().runTask(plugin, () -> p.openInventory(e.getInventory()));
            }
        }

        @EventHandler(priority = EventPriority.LOW)
        public void onPlayerQuit(PlayerQuitEvent e) {
            Player p = e.getPlayer();

            SmartInventory inv = inventories.get(p);
            if (inv == null) return;

            try {
                inv.getListeners().stream()
                        .filter(listener -> listener.getType() == PlayerQuitEvent.class)
                        .forEach(listener -> ((InventoryListener<PlayerQuitEvent>) listener).accept(e));
            } finally {
                inventories.remove(p);
                contents.remove(p);
            }
        }

        @EventHandler(priority = EventPriority.LOW)
        public void onPluginDisable(PluginDisableEvent e) {
            new HashMap<>(inventories).forEach((player, inv) -> {
                try {
                    inv.getListeners().stream()
                            .filter(listener -> listener.getType() == PluginDisableEvent.class)
                            .forEach(listener -> ((InventoryListener<PluginDisableEvent>) listener).accept(e));
                } finally {
                    inv.close(player);
                }
            });

            inventories.clear();
            contents.clear();
        }

    }

    static class PlayerInvTask extends BukkitRunnable {

        private final Player player;
        private final InventoryProvider provider;
        private final InventoryContents contents;

        public PlayerInvTask(Player player, InventoryProvider provider, InventoryContents contents) {
            this.player = Objects.requireNonNull(player);
            this.provider = Objects.requireNonNull(provider);
            this.contents = Objects.requireNonNull(contents);
        }

        @Override
        public void run() {
            provider.update(this.player, this.contents);
        }

    }

}
