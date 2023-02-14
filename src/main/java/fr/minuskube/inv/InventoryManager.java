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

    private final Map<UUID, SmartInventory> inventories;
    private final Map<UUID, InventoryContents> contents;
    private final Map<UUID, BukkitRunnable> updateTasks;

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

        if (opInv.isEmpty()) {
            opInv = this.defaultOpeners.stream()
                    .filter(opener -> opener.supports(type))
                    .findAny();
        }

        return opInv;
    }

    public void registerOpeners(InventoryOpener... openers) {
        this.openers.addAll(Arrays.asList(openers));
    }

    public List<UUID> getOpenedPlayers(SmartInventory inv) {
        List<UUID> list = new ArrayList<>();

        this.inventories.forEach((uuid, playerInv) -> {
            if (inv.equals(playerInv)) list.add(uuid);
        });

        return list;
    }

    public Optional<SmartInventory> getInventory(final Player player) {
        return Optional.ofNullable(this.inventories.get(player.getUniqueId()));
    }

    protected void setInventory(final Player player, SmartInventory inv) {
        if (inv == null)
            this.inventories.remove(player.getUniqueId());
        else
            this.inventories.put(player.getUniqueId(), inv);
    }

    public Optional<InventoryContents> getContents(final Player player) {
        return Optional.ofNullable(this.contents.get(player.getUniqueId()));
    }

    protected void setContents(final Player player, InventoryContents contents) {
        if (contents == null)
            this.contents.remove(player.getUniqueId());
        else
            this.contents.put(player.getUniqueId(), contents);
    }

    protected void scheduleUpdateTask(final Player player, SmartInventory inv) {
        PlayerInvTask task = new PlayerInvTask(player, inv.getProvider(), contents.get(player.getUniqueId()));
        task.runTaskTimer(plugin, 1, inv.getUpdateFrequency());
        this.updateTasks.put(player.getUniqueId(), task);
    }

    protected void cancelUpdateTask(final Player player) {
        if (updateTasks.containsKey(player.getUniqueId())) {
            int bukkitTaskId = this.updateTasks.get(player.getUniqueId()).getTaskId();
            Bukkit.getScheduler().cancelTask(bukkitTaskId);
            this.updateTasks.remove(player.getUniqueId());
        }
    }

    @SuppressWarnings("unchecked")
    class InvListener implements Listener {

        @EventHandler(priority = EventPriority.LOW)
        public void onInventoryClick(final InventoryClickEvent event) {
            final Player player = (Player) event.getWhoClicked();
            final SmartInventory inv = inventories.get(player.getUniqueId());
            if (inv == null) return;

            if (event.getAction() == InventoryAction.COLLECT_TO_CURSOR || event.getAction() == InventoryAction.NOTHING) {
                event.setCancelled(true);
                return;
            }

            if (event.getClickedInventory() != player.getOpenInventory().getTopInventory()) {
                return;
            }

            final int row = event.getSlot() / 9;
            final int column = event.getSlot() % 9;

            if (!inv.checkBounds(row, column)) return;

            if (event.getClick() == ClickType.NUMBER_KEY) {
                event.setCancelled(true);
                return;
            }

            final InventoryContents invContents = contents.get(player.getUniqueId());
            if (event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY && invContents.property("allowShift", false)) {
                event.setCancelled(true);
            }

            final SlotPos slot = SlotPos.of(row, column);
            if (!invContents.isEditable(slot)) {
                event.setCancelled(true);
            }

            inv.getListeners().stream()
                    .filter(listener -> listener.getType() == InventoryClickEvent.class)
                    .forEach(listener -> ((InventoryListener<InventoryClickEvent>) listener).accept(event));

            invContents.get(slot).ifPresent(item -> item.run(new ItemClickData(event, player, event.getCurrentItem(), slot)));

            // Don't update if the clicked slot is editable - prevent item glitching
            if (!invContents.isEditable(slot)) {
                player.updateInventory();
            }
        }

        @EventHandler(priority = EventPriority.LOW)
        public void onInventoryDrag(final InventoryDragEvent event) {
            final Player player = (Player) event.getWhoClicked();

            final SmartInventory inv = inventories.get(player.getUniqueId());
            if (inv == null) {
                return;
            }

            final InventoryContents content = contents.get(player.getUniqueId());
            for (final int slot : event.getRawSlots()) {
                final SlotPos pos = SlotPos.of(slot / 9, slot % 9);
                if (slot >= player.getOpenInventory().getTopInventory().getSize() || content.isEditable(pos))
                    continue;

                event.setCancelled(true);
                break;
            }

            inv.getListeners().stream()
                    .filter(listener -> listener.getType() == InventoryDragEvent.class)
                    .forEach(listener -> ((InventoryListener<InventoryDragEvent>) listener).accept(event));
        }

        @EventHandler(priority = EventPriority.LOW)
        public void onInventoryOpen(final InventoryOpenEvent event) {
            final Player player = (Player) event.getPlayer();
            final SmartInventory inv = inventories.get(player.getUniqueId());
            if (inv == null) {
                return;
            }

            inv.getListeners().stream()
                    .filter(listener -> listener.getType() == InventoryOpenEvent.class)
                    .forEach(listener -> ((InventoryListener<InventoryOpenEvent>) listener).accept(event));
        }

        @EventHandler(priority = EventPriority.LOW)
        public void onInventoryClose(final InventoryCloseEvent event) {
            final Player player = (Player) event.getPlayer();
            final SmartInventory inv = inventories.get(player.getUniqueId());
            if (inv == null) {
                return;
            }

            try {
                inv.getListeners().stream()
                        .filter(listener -> listener.getType() == InventoryCloseEvent.class)
                        .forEach(listener -> ((InventoryListener<InventoryCloseEvent>) listener).accept(event));
            } finally {
                if (inv.isCloseable()) {
                    event.getInventory().clear();
                    InventoryManager.this.cancelUpdateTask(player);

                    inventories.remove(player.getUniqueId());
                    contents.remove(player.getUniqueId());
                } else
                    Bukkit.getScheduler().runTask(plugin, () -> player.openInventory(event.getInventory()));
            }
        }

        @EventHandler(priority = EventPriority.LOW)
        public void onPlayerQuit(final PlayerQuitEvent event) {
            final Player player = event.getPlayer();
            final SmartInventory inv = inventories.get(player.getUniqueId());
            if (inv == null) {
                return;
            }

            try {
                inv.getListeners().stream()
                        .filter(listener -> listener.getType() == PlayerQuitEvent.class)
                        .forEach(listener -> ((InventoryListener<PlayerQuitEvent>) listener).accept(event));
            } finally {
                inventories.remove(player.getUniqueId());
                contents.remove(player.getUniqueId());
            }
        }

        @EventHandler(priority = EventPriority.LOW)
        public void onPluginDisable(final PluginDisableEvent event) {
            new HashMap<>(inventories).forEach((uuid, inv) -> {
                try {
                    inv.getListeners().stream()
                            .filter(listener -> listener.getType() == PluginDisableEvent.class)
                            .forEach(listener -> ((InventoryListener<PluginDisableEvent>) listener).accept(event));
                } finally {
                    inv.close(Bukkit.getPlayer(uuid));
                }
            });

            inventories.clear();
            contents.clear();
        }

    }

    class InvTask extends BukkitRunnable {

        @Override
        public void run() {
            new HashMap<>(inventories).forEach((uuid, inv) -> inv.getProvider().update(Bukkit.getPlayer(uuid), contents.get(uuid)));
        }

    }

    class PlayerInvTask extends BukkitRunnable {

        private final Player player;
        private final InventoryProvider provider;
        private final InventoryContents contents;

        public PlayerInvTask(final Player player, final InventoryProvider provider, final InventoryContents contents) {
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
