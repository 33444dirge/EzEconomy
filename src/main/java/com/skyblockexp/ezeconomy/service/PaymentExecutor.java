package com.skyblockexp.ezeconomy.service;

import com.skyblockexp.ezeconomy.core.EzEconomyPlugin;
import com.skyblockexp.ezeconomy.util.MessageUtils;
import com.skyblockexp.ezeconomy.storage.TransferResult;
import com.skyblockexp.ezeconomy.api.events.PlayerPayPlayerEvent;
import com.skyblockexp.ezeconomy.api.storage.StorageProvider;
import com.skyblockexp.ezeconomy.util.EventDispatcher;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.util.UUID;

public class PaymentExecutor {
    private static void debug(EzEconomyPlugin plugin, String message) {
        if (plugin != null && plugin.getConfig().getBoolean("debug", false)) {
            plugin.getLogger().info(message);
        }
    }
    /**
     * Execute a payment between players. Returns true if the operation completed (success or handled failure).
     */
    public static boolean execute(EzEconomyPlugin plugin, Player from, String toName, BigDecimal amountDecimal, String currency) {
        return execute(plugin, from, toName, amountDecimal, currency, false);
    }

    public static boolean execute(EzEconomyPlugin plugin, Player from, String toName, BigDecimal amountDecimal, String currency, boolean knownOffline) {
        if (from == null || toName == null || amountDecimal == null) return false;
        double netAmount = amountDecimal.doubleValue();
        StorageProvider storage = plugin.getStorageOrWarn();
        if (storage == null) return true;
        debug(plugin, "PaymentExecutor: start execute from=" + from.getName() + " toName=" + toName + " amount=" + netAmount + " currency=" + currency + " knownOffline=" + knownOffline);

        Player online = Bukkit.getPlayerExact(toName);
        OfflinePlayer toOffline = online != null ? online : null;

        UUID fromUuid = from.getUniqueId();

        if (online == null) {
            OfflinePlayer localOffline = com.skyblockexp.ezeconomy.util.PlayerLookup.findByName(toName).orElse(null);
            boolean localKnown = localOffline != null && localOffline.hasPlayedBefore();

            if (localKnown) {
                toOffline = localOffline;
            } else if (knownOffline) {
                UUID resolvedUuid = null;
                com.skyblockexp.ezeconomy.messaging.MessagingService ms = plugin.getMessagingService();
                if (ms != null) {
                    resolvedUuid = ms.getNetworkPlayerUuid(toName);
                }
                if (resolvedUuid == null) {
                    StorageProvider storageForResolve = plugin.getStorageOrWarn();
                    if (storageForResolve != null) {
                        resolvedUuid = storageForResolve.resolvePlayerByName(toName);
                    }
                }
                if (resolvedUuid != null) {
                    toOffline = Bukkit.getOfflinePlayer(resolvedUuid);
                } else {
                    toOffline = localOffline;
                }
            }

            if (toOffline == null) {
                debug(plugin, "PaymentExecutor: recipient not found, aborting");
                MessageUtils.send(from, plugin, "player_not_found");
                return true;
            }
        }
        debug(plugin, "PaymentExecutor: executing transfer from=" + from.getName() + " to=" + toName + " online=" + (online!=null));

        if (toOffline.getUniqueId().equals(fromUuid)) {
            MessageUtils.send(from, plugin, "cannot_pay_self");
            return true;
        }

        // Fire event (must be synchronous)
        final PlayerPayPlayerEvent payEvent = new PlayerPayPlayerEvent(fromUuid, toOffline.getUniqueId(), amountDecimal);
        EventDispatcher.fireSync(plugin, payEvent);
        if (payEvent.isCancelled()) {
            String reason = payEvent.getCancelReason();
            if (reason != null && !reason.isEmpty()) {
                MessageUtils.sendRaw(from, plugin, reason);
            } else {
                MessageUtils.send(from, plugin, "payment_cancelled");
            }
            return true;
        }

        // Simple transfer
        debug(plugin, "PaymentExecutor: performing simple transfer via storage.transfer");
        TransferResult transfer = storage.transfer(fromUuid, toOffline.getUniqueId(), currency, netAmount);
        debug(plugin, "PaymentExecutor: transfer result success=" + transfer.isSuccess()
                + " debit=" + netAmount + " " + currency
                + " fromBalancePost=" + transfer.getFromBalance()
                + " toBalancePost=" + transfer.getToBalance());
        if (!transfer.isSuccess()) {
            debug(plugin, "PaymentExecutor: transfer failed, sending not_enough_money");
            MessageUtils.send(from, plugin, "not_enough_money");
            return true;
        }

        String amountWithSymbol = plugin.getCurrencyFormatter().formatPriceForMessage(netAmount, currency);
        MessageUtils.send(from, plugin, "paid", java.util.Map.of("player", toName, "amount", amountWithSymbol));
        if (toOffline.isOnline() && toOffline.getPlayer() != null) {
            MessageUtils.send(toOffline.getPlayer(), plugin, "received", java.util.Map.of("player", from.getName(), "amount", amountWithSymbol));
        } else {
            notifyCrossServer(plugin, toOffline.getUniqueId(), toOffline.getName(), from.getName(), amountWithSymbol, currency);
        }
        return true;
    }

    private static void notifyCrossServer(EzEconomyPlugin plugin, UUID recipientUuid,
                                           String recipientName, String senderName,
                                           String amount, String currency) {
        if (!plugin.getConfig().getBoolean("cross-server.enabled", false)) return;
        com.skyblockexp.ezeconomy.messaging.MessagingService ms = plugin.getMessagingService();
        if (ms == null) return;
        ms.sendPaymentNotification(recipientUuid, recipientName, senderName, amount, currency);
        debug(plugin, "PaymentExecutor: sent cross-server notification for " + recipientName);
    }
}
