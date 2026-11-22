package city.jiujiu.eco.bukkit;

import city.jiujiu.eco.storage.Accounts;
import city.jiujiu.lib.api.JiuJiuLibApi;
import city.jiujiu.lib.message.LanguageService;
import com.google.common.collect.ImmutableMap;
import city.jiujiu.lib.scheduler.TaskScheduler;
import city.jiujiu.lib.services.PlaceholderService;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.Map;
import java.util.Collections;
import java.util.UUID;
import java.util.logging.Level;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class JiuJiuEco extends JavaPlugin {
    private LanguageService language;
    private WalletCommands commands;
    private Accounts accounts;
    private TaskScheduler tasks;
    private PlaceholderService.Registration placeholders;

    @Override public void onEnable() {
        try {
            saveDefaultConfig();
            JiuJiuLibApi library = JiuJiuLibApi.require(this);
            tasks = library.scopedScheduler(this);
            language = new LanguageService(this, tasks);
            commands = new WalletCommands(this, language, library.messages());
            applyCommandLanguage();
            accounts = WalletDatabase.open(this);
            if (accounts.sharedDatabase()) {
                java.util.concurrent.atomic.AtomicBoolean refreshing = new java.util.concurrent.atomic.AtomicBoolean();
                tasks.globalTimer(20, 20, ignored -> {
                    if (refreshing.compareAndSet(false, true)) tasks.async(() -> {
                        try { accounts.refreshDisplays(); }
                        catch (SQLException failure) { getLogger().log(Level.WARNING, "MySQL display refresh failed", failure); }
                        finally { refreshing.set(false); }
                    });
                });
            }
            placeholders = library.placeholders().register(this, "jiujiueco", this::balancePlaceholder);
            getServer().getServicesManager().register(Economy.class, new VaultWallet(this, accounts), this, ServicePriority.Normal);
            getLogger().info(language.text("log.ready", Collections.emptyMap()));
        } catch (Exception | LinkageError failure) {
            getLogger().log(Level.SEVERE, "钱包启动失败", failure);
            getServer().getPluginManager().disablePlugin(this);
        }
    }
    @Override public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);
        if (placeholders != null) placeholders.close();
        if (commands != null) commands.close();
        if (tasks != null) tasks.close();
        if (accounts != null) try { accounts.close(); }
        catch (SQLException failure) { getLogger().log(Level.SEVERE, "关闭钱包失败", failure); }
    }

    OfflinePlayer player(String name) {
        if (name == null || name.isEmpty()) throw new Accounts.Rejected("error.player", "Missing player");
        try { return getServer().getOfflinePlayer(UUID.fromString(name)); }
        catch (IllegalArgumentException notUuid) { return getServer().getOfflinePlayer(name); }
    }

    private String balancePlaceholder(OfflinePlayer player, String key) {
        boolean formatted = "balance_formatted".equals(key);
        if (player == null || (!formatted && !"balance".equals(key))) return null;
        String amount = Accounts.amount(accounts.displayBalance(player.getUniqueId())).toPlainString();
        return formatted ? amount + " J" : amount;
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName();
        if ("balance".equals(name)) {
            if (args.length > 1 || (args.length == 0 && !(sender instanceof Player))) {
                commands.usage(sender, name, label);
                return true;
            }
            String target = args.length == 0 ? ((Player)sender).getUniqueId().toString() : args[0];
            boolean seeOthers = WalletCommands.canSeeOthers(sender);
            UUID caller = sender instanceof Player ? ((Player)sender).getUniqueId() : null;
            execute(sender, () -> {
                OfflinePlayer account = player(target);
                boolean self = account.getUniqueId().equals(caller);
                if (!self && !seeOthers) { reply(sender, "error.see-others", Collections.emptyMap()); return; }
                reply(sender, self ? "balance.self" : "balance.other", ImmutableMap.of("player", displayName(account),
                        "balance", format(accounts.balance(account.getUniqueId()))));
            });
        } else if ("pay".equals(name)) {
            if (args.length != 2 || !(sender instanceof Player)) {
                commands.usage(sender, name, label);
                return true;
            }
            long value;
            try {
                value = parseAmount(args[1]);
                if (value == 0) throw new Accounts.Rejected("error.positive", "Amount must be positive");
            } catch (Accounts.Rejected invalid) {
                language.send(sender, invalid.messageKey);
                commands.usage(sender, name, label);
                return true;
            }
            UUID from = ((Player)sender).getUniqueId();
            String payer = sender.getName();
            execute(sender, () -> {
                OfflinePlayer target = player(args[0]);
                if (!target.isOnline() && !target.hasPlayedBefore() && !accounts.exists(target.getUniqueId()))
                    throw new Accounts.Rejected("error.unknown-player", "Unknown recipient");
                long remaining = accounts.transfer(from, target.getUniqueId(), value);
                Player online = target.getPlayer();
                if (online != null) reply(online, "pay.received", ImmutableMap.of("player", payer, "amount", format(value)));
                reply(sender, "pay.sent", ImmutableMap.of("player", displayName(target), "amount", format(value), "balance", format(remaining)));
            });
        } else if ("eco".equals(name)) {
            if (args.length > 0 && "reload".equalsIgnoreCase(args[0])) {
                if (args.length == 1) reloadLanguage(sender);
                else commands.usage(sender, name, label);
                return true;
            }
            Accounts.Change change = args.length == 0 ? null : WalletCommands.change(args[0]);
            if (change == null || args.length != 3) {
                commands.usage(sender, name, label);
                return true;
            }
            long value;
            try { value = parseAmount(args[2]); }
            catch (Accounts.Rejected invalid) {
                language.send(sender, invalid.messageKey);
                commands.usage(sender, name, label);
                return true;
            }
            execute(sender, () -> {
                OfflinePlayer target = player(args[1]);
                long balance = accounts.change(target.getUniqueId(), value, change);
                reply(sender, "eco." + WalletCommands.keyword(change), ImmutableMap.of("player", displayName(target),
                        "amount", format(value), "balance", format(balance)));
            });
        } else return false;
        return true;
    }

    private void applyCommandLanguage() {
        for (String name : new String[]{"balance","pay","eco"}) {
            getCommand(name).setDescription(language.text("description." + name, Collections.emptyMap()));
            getCommand(name).setUsage(language.text("usage." + name, Collections.emptyMap()));
        }
        getCommand("pay").permissionMessage(language.component("error.pay-permission", Collections.emptyMap()));
    }

    private void reloadLanguage(CommandSender sender) {
        tasks.async(() -> {
            try {
                language.reload();
                tasks.global(() -> { applyCommandLanguage(); language.send(sender, "reload.success"); });
            } catch (RuntimeException invalid) {
                getLogger().log(Level.WARNING, "Language reload failed", invalid);
                reply(sender, "reload.failed", Collections.emptyMap());
            }
        });
    }

    private interface WalletCommand { void run() throws SQLException; }
    private void execute(CommandSender sender, WalletCommand action) {
        tasks.async(() -> {
            try { action.run(); }
            catch (Accounts.Rejected invalid) {
                reply(sender, invalid.messageKey, Collections.emptyMap());
            } catch (Exception failure) {
                getLogger().log(Level.SEVERE, "钱包操作未确认", failure);
                reply(sender, "error.unconfirmed", Collections.emptyMap());
            }
        });
    }
    private void reply(CommandSender sender, String key, Map<String,?> args) {
        if (sender instanceof Player) language.send(sender, key, args);
        else tasks.global(() -> language.send(sender, key, args));
    }
    private static long parseAmount(String raw) {
        try { return Accounts.units(new BigDecimal(raw)); }
        catch (NumberFormatException | ArithmeticException invalid) {
            throw new Accounts.Rejected("error.amount", "Invalid amount");
        }
    }
    private static String displayName(OfflinePlayer player) {
        String name = player.getName();
        return name == null ? player.getUniqueId().toString() : name;
    }
    private static String format(long units) { return Accounts.amount(units).toPlainString() + " J"; }
}
