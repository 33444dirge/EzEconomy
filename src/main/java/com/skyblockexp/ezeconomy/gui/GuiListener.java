package com.skyblockexp.ezeconomy.gui;

import com.skyblockexp.ezeconomy.util.scheduler.PlatformScheduler;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.ChatColor;
import java.util.UUID;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.Bukkit;
import java.math.BigDecimal;
import com.skyblockexp.ezeconomy.core.Money;
import org.bukkit.plugin.Plugin;
import org.bukkit.entity.Player;

import com.skyblockexp.ezeconomy.core.EzEconomyPlugin;

public class GuiListener implements Listener {
    private final EzEconomyPlugin plugin;

    public GuiListener(EzEconomyPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (e.getClickedInventory() == null) return;
        String title = e.getView().getTitle();
        if (title == null) return;
        var cfg = plugin.getUserGuiConfig();
        String menuTitle = GuiUtils.formatMiniMessage(cfg.getString("title.menu", "EzEconomy - Menu"));
        String payTitle = GuiUtils.formatMiniMessage(cfg.getString("title.pay", "EzEconomy - Pay"));
        String historyTitle = GuiUtils.formatMiniMessage(cfg.getString("title.history", "EzEconomy - History"));
        String confirmPayTitle = GuiUtils.formatMiniMessage(cfg.getString("title.confirm_pay", "EzEconomy - Confirm Pay"));
        String payToPrefix = GuiUtils.formatMiniMessage(cfg.getString("title.pay_to_prefix", "EzEconomy - Pay to "));
        String balanceTitle = GuiUtils.formatMiniMessage(cfg.getString("title.balance", "\u00A7aYour Balances"));

        // allow either holder-based detection (preferred) or formatted/raw title fallbacks used in tests
        InventoryHolder topHolder = e.getView().getTopInventory() == null ? null : e.getView().getTopInventory().getHolder();
        String holderId = null;
        if (topHolder instanceof GuiInventoryHolder) holderId = ((GuiInventoryHolder) topHolder).getId();

        String rawMenu = "EzEconomy - Menu";
        String rawPay = "EzEconomy - Pay";
        String rawHistory = "EzEconomy - History";
        String rawConfirm = "EzEconomy - Confirm Pay";
        String rawPayToPrefix = "EzEconomy - Pay to ";
        String rawBalance = "Your Balances";

        boolean ok = holderId != null || title.startsWith("EzEconomy") || title.equals(menuTitle) || title.equals(payTitle) || title.equals(historyTitle) || title.equals(balanceTitle) || title.equals(confirmPayTitle) || title.startsWith(payToPrefix) || title.equals(rawMenu) || title.equals(rawPay) || title.equals(rawHistory) || title.equals(rawBalance) || title.equals(rawConfirm) || title.startsWith(rawPayToPrefix);
        if (!ok) return;
        e.setCancelled(true);
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;
        ItemStack item = e.getCurrentItem();
        if (item == null || !item.hasItemMeta() || !(e.getWhoClicked() instanceof Player player)) return;
        String actionHolder = holderId;
        ItemStack clicked = item.clone();
        PlatformScheduler.runForPlayer(plugin, player,
                () -> handleClick(player, title, actionHolder, clicked), () -> {}, 1L);
    }

    private void handleClick(Player player, String title, String holderId, ItemStack item) {
        var cfg = plugin.getUserGuiConfig();
        String menuTitle = GuiUtils.formatMiniMessage(cfg.getString("title.menu", "EzEconomy - Menu"));
        String payTitle = GuiUtils.formatMiniMessage(cfg.getString("title.pay", "EzEconomy - Pay"));
        String historyTitle = GuiUtils.formatMiniMessage(cfg.getString("title.history", "EzEconomy - History"));
        String confirmPayTitle = GuiUtils.formatMiniMessage(cfg.getString("title.confirm_pay", "EzEconomy - Confirm Pay"));
        String payToPrefix = GuiUtils.formatMiniMessage(cfg.getString("title.pay_to_prefix", "EzEconomy - Pay to "));
        String balanceTitle = GuiUtils.formatMiniMessage(cfg.getString("title.balance", "\u00A7aYour Balances"));

        String rawMenu = "EzEconomy - Menu";
        String rawPay = "EzEconomy - Pay";
        String rawHistory = "EzEconomy - History";
        String rawConfirm = "EzEconomy - Confirm Pay";
        String rawPayToPrefix = "EzEconomy - Pay to ";
        var meta = item.getItemMeta();
        String name = meta.hasDisplayName() ? meta.getDisplayName() : "";
        // detect GUI action stored in PDC or legacy lore marker
        java.util.Optional<String> maybeAction = GuiUtils.getGuiAction(meta, plugin);
        if (maybeAction.isPresent()) {
            String action = maybeAction.get();
            if (action.equals("back")) {
                player.closeInventory();
                MainGui.open(plugin, player);
                return;
            }
            if (action.startsWith("page:")) {
                try {
                    int target = Integer.parseInt(action.substring("page:".length()));
                    player.closeInventory();
                    PayPlayerSelectionGui.open(plugin, player, target);
                    return;
                } catch (NumberFormatException ex) {
                    // ignore malformed
                }
            }
            // new: direct-pay action carries the UUID of the target so we never pass a raw UUID as a "name"
            if (action.startsWith("payto:")) {
                String uuidStr = action.substring("payto:".length());
                player.closeInventory();
                PayAmountGui.open(plugin, player, uuidStr);
                return;
            }

            // open currency selector from pay amount GUI
            if (action.startsWith("open_currency:")) {
                String targetName = action.substring("open_currency:".length());
                player.closeInventory();
                PayCurrencySelectionGui.open(plugin, player, targetName);
                return;
            }

            // back from currency selector to the pay amount GUI
            if (action.startsWith("back_to_pay:")) {
                String targetName = action.substring("back_to_pay:".length());
                player.closeInventory();
                PayAmountGui.open(plugin, player, targetName);
                return;
            }

            // currency select action from currency selection GUI
            if (action.startsWith("select_currency:")) {
                String key = action.substring("select_currency:".length());
                // set chosen currency for the pay flow and return to PayAmountGui
                plugin.getPayFlowManager().setCurrency(player.getUniqueId(), key);
                // determine return target from title (if present)
                String target = "";
                if (title.startsWith(payToPrefix)) target = title.substring(payToPrefix.length());
                else if (title.startsWith(rawPayToPrefix)) target = title.substring(rawPayToPrefix.length());
                player.closeInventory();
                PayAmountGui.open(plugin, player, target);
                return;
            }

            // amount action: handle preset click (only valid within the pay_to GUI)
            if (action.startsWith("amount:")) {
                if ("pay_to".equals(holderId) || title.startsWith(payToPrefix) || title.startsWith(rawPayToPrefix)) {
                    String amtStr = action.substring("amount:".length());
                    try {
                        java.math.BigDecimal bd = new java.math.BigDecimal(amtStr);
                        String chosen = plugin.getPayFlowManager().getCurrency(player.getUniqueId());
                        String currency = chosen == null ? plugin.getDefaultCurrency() : chosen;
                        String target;
                        if (title.startsWith(payToPrefix)) target = title.substring(payToPrefix.length());
                        else target = title.substring(rawPayToPrefix.length());
                        UUID targetUuid = getPlayerUuidByName(target);
                        int timeoutSeconds = plugin.getConfig().getInt("pay.confirmation.timeout_seconds", 30);
                        long expiresAt = System.currentTimeMillis() + (timeoutSeconds * 1000L);
                        com.skyblockexp.ezeconomy.core.Money money = com.skyblockexp.ezeconomy.core.Money.of(bd, currency);
                        plugin.getPayFlowManager().createPendingTransfer(player.getUniqueId(), targetUuid, target, money, currency, expiresAt);
                        player.closeInventory();
                        PayConfirmGui.open(plugin, player, target, bd.toPlainString(), currency);
                        PlatformScheduler.runTaskLater(plugin, expiryCleanup(player.getUniqueId()), timeoutSeconds * 20L);
                        return;
                    } catch (NumberFormatException ex) {
                        player.sendMessage(org.bukkit.ChatColor.RED + "Invalid preset amount.");
                        return;
                    }
                }
            }
        }

        if ("menu".equals(holderId) || title.equals(menuTitle) || title.equals(rawMenu)) {
            java.util.Optional<String> maybe = GuiUtils.getGuiAction(meta, plugin);
            if (maybe.isPresent()) {
                String key = maybe.get();
                switch (key) {
                    case "balance": new BalanceAction().open(plugin, player); return;
                    case "pay": new PayAction().open(plugin, player); return;
                    case "history": new HistoryAction().open(plugin, player); return;
                }
            }
        } else if ("pay".equals(holderId) || title.equals(payTitle) || title.equals(rawPay)) {
            // clicked a player to pay
            String target = ChatColor.stripColor(name);
            if (!target.isBlank()) {
                player.closeInventory();
                PayAmountGui.open(plugin, player, target);
            }
        } else if ("pay_to".equals(holderId) || title.startsWith(payToPrefix) || title.startsWith(rawPayToPrefix)) {
            // amount selection for a specific target
            String target;
            if (title.startsWith(payToPrefix)) target = title.substring(payToPrefix.length());
            else target = title.substring(rawPayToPrefix.length());
            String currencyLabel = plugin.getUserGuiConfig().getString("pay.currency.label", "Currency: ");
            if (name.contains(currencyLabel)) {
                // pick currency
                String key = ChatColor.stripColor(name).replace(currencyLabel, "").split(" ")[0];
                plugin.getPayFlowManager().setCurrency(player.getUniqueId(), key);
                player.sendMessage(ChatColor.AQUA + "Selected currency: " + key);
                // reopen the GUI to reflect selection (simple UX)
                player.closeInventory();
                PayAmountGui.open(plugin, player, target);
                return;
            }
            String customDisplay = plugin.getUserGuiConfig().getString("pay.custom.display-name", "Custom Amount");
            if (name.contains(GuiUtils.formatMiniMessage(customDisplay)) || name.contains("Custom Amount")) {
                // start awaiting custom amount via chat
                player.closeInventory();
                UUID targetUuid = getPlayerUuidByName(target);
                plugin.getPayFlowManager().startAwaiting(player.getUniqueId(), targetUuid, target);
                player.sendMessage(ChatColor.AQUA + "Enter the amount to pay " + target + " in chat.");
            } else if (name.contains("Pay ")) {
                // preset amount selected
                String amt = name.replaceAll("[^0-9.]", "");
                player.closeInventory();
                // create pending transfer and open confirm GUI
                try {
                    BigDecimal bd = new BigDecimal(amt);
                    String chosen = plugin.getPayFlowManager().getCurrency(player.getUniqueId());
                    String currency = chosen == null ? plugin.getDefaultCurrency() : chosen;
                    Money money = Money.of(bd, currency);
                    UUID targetUuid = getPlayerUuidByName(target);
                    int timeoutSeconds = plugin.getConfig().getInt("pay.confirmation.timeout_seconds", 30);
                    long expiresAt = System.currentTimeMillis() + (timeoutSeconds * 1000L);
                    plugin.getPayFlowManager().createPendingTransfer(player.getUniqueId(), targetUuid, target, money, currency, expiresAt);
                    PayConfirmGui.open(plugin, player, target, amt, currency);
                    PlatformScheduler.runTaskLater(plugin, expiryCleanup(player.getUniqueId()), timeoutSeconds * 20L);
                } catch (NumberFormatException ex) {
                    player.sendMessage(ChatColor.RED + "Invalid preset amount.");
                }
            }
        } else if ("confirm_pay".equals(holderId) || title.equals(confirmPayTitle) || title.equals(rawConfirm)) {
            if ("confirm_pay".equals(maybeAction.orElse("")) || name.contains("Confirm")) {
                // Execute the stored pending transfer for this player
                var pt = plugin.getPayFlowManager().pollPendingTransfer(player.getUniqueId());
                if (pt == null) {
                    player.sendMessage(ChatColor.RED + "No pending payment found.");
                } else {
                    player.closeInventory();
                    java.math.BigDecimal bd = pt.getAmount().getAmount();
                    com.skyblockexp.ezeconomy.service.PaymentExecutor.execute(plugin, player, pt.getToName(), bd, pt.getCurrency());
                }
            } else if ("cancel_pay".equals(maybeAction.orElse("")) || name.contains("Cancel")) {
                player.closeInventory();
                plugin.getPayFlowManager().stopAwaiting(player.getUniqueId());
                player.sendMessage(ChatColor.RED + "Payment cancelled.");
            }
        }
    }

    @EventHandler
    public void onDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        var top = event.getView().getTopInventory();
        if (top.getHolder() instanceof GuiInventoryHolder
                && event.getRawSlots().stream().anyMatch(slot -> slot < top.getSize())) event.setCancelled(true);
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent e) {
        Player player = e.getPlayer();
        UUID uuid = player.getUniqueId();
        if (!plugin.getPayFlowManager().isAwaiting(uuid)) return;
        e.setCancelled(true);
        String input = e.getMessage().trim();
        PlatformScheduler.runForPlayer(plugin, player, () -> acceptAmount(player, input),
                () -> plugin.getPayFlowManager().stopAwaiting(uuid), 1L);
    }

    private void acceptAmount(Player player, String input) {
        UUID uuid = player.getUniqueId();
        var flows = plugin.getPayFlowManager();
        if (!flows.isAwaiting(uuid)) return;
        String currency = flows.getCurrency(uuid);
        if (currency == null) currency = plugin.getDefaultCurrency();
        var money = com.skyblockexp.ezeconomy.util.NumberUtil.parseMoney(input, currency);
        if (money == null || money.getAmount().signum() <= 0) {
            player.sendMessage(ChatColor.RED + "Invalid number. Please enter a positive amount.");
            return;
        }
        UUID target = flows.getTarget(uuid);
        String targetName = flows.getTargetName(uuid);
        if (target == null || targetName == null) {
            player.sendMessage(ChatColor.RED + "Target not found or expired.");
            flows.stopAwaiting(uuid);
            return;
        }
        int timeout = Math.max(1, plugin.getConfig().getInt("pay.confirmation.timeout_seconds", 30));
        flows.createPendingTransfer(uuid, target, targetName, money, money.getCurrencyId(),
                System.currentTimeMillis() + timeout * 1000L);
        PayConfirmGui.open(plugin, player, targetName, money.getAmount().toPlainString(), money.getCurrencyId());
        PlatformScheduler.runTaskLater(plugin, expiryCleanup(uuid), timeout * 20L);
    }

    private Runnable expiryCleanup(UUID uuid) {
        var flows = plugin.getPayFlowManager();
        return () -> flows.removeIfExpired(uuid);
    }

    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        plugin.getPayFlowManager().stopAwaiting(event.getPlayer().getUniqueId());
    }

    private java.util.UUID getPlayerUuidByName(String name) {
        // allow passing a UUID string directly (we open PayAmountGui with UUID when needed)
        if (name != null) {
            try {
                return java.util.UUID.fromString(name);
            } catch (IllegalArgumentException ignored) {
            }
        }
        var p = plugin.getServer().getPlayerExact(name);
        return p == null ? null : p.getUniqueId();
    }
}
