package city.jiujiu.eco.bukkit;

import city.jiujiu.eco.storage.Accounts.Change;
import city.jiujiu.lib.command.CommandRouter;
import city.jiujiu.lib.message.LanguageService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

final class WalletCommands implements AutoCloseable {
    private final CommandRouter eco, pay, balance;

    WalletCommands(JavaPlugin owner, LanguageService language, LanguageService shared) {
        CommandRouter.Suggestions names = CommandRouter.fromSender(sender -> players(sender, false));
        CommandRouter.Suggestions amounts = CommandRouter.fromSender(sender -> amounts(language));
        eco = new CommandRouter(owner, "eco", language, owner).help(shared).passthrough()
                .helpLink("/balance", "help.balance-self.description", sender -> sender instanceof Player)
                .helpLink("/balance <player>", "help.balance-other.description", WalletCommands::canSeeOthers)
                .helpLink("/pay <player> <amount>", "help.pay.description",
                        sender -> sender instanceof Player && sender.hasPermission("jiujiueco.user.pay"));
        for (Change action : Change.values()) {
            String verb = keyword(action);
            eco.route(verb, "", false, 0, verb + " <player> <amount>", names, amounts)
                    .when(verb, sender -> canChange(sender, action)).describe(verb, "help." + verb + ".description");
        }
        eco.route("reload", "jiujiueco.reload", false, 0, "reload").describe("reload", "help.reload.description");
        // The first argument of these commands is a player name, including names such as "help".
        balance = new CommandRouter(owner, "balance", language, owner).fallback(CommandRouter.fromSender(sender ->
                canSeeOthers(sender) ? players(sender, false) :
                        sender instanceof Player ? Collections.singletonList(sender.getName()) : Collections.emptyList()));
        pay = new CommandRouter(owner, "pay", language, owner).fallback(
                CommandRouter.fromSender(sender -> sender instanceof Player ? players(sender, true) : Collections.emptyList()),
                CommandRouter.fromSender(sender -> sender instanceof Player ? amounts(language) : Collections.emptyList()));
    }

    private static List<String> amounts(LanguageService language) {
        String examples = language.text("completion.amounts").trim();
        return examples.isEmpty() ? Collections.emptyList() : Arrays.asList(examples.split("\\s+"));
    }

    void usage(CommandSender sender, String name, String label) {
        String path = name.equals("eco") ? label : label.substring(0, label.lastIndexOf(':') + 1) + "eco";
        eco.showHelp(sender, "/" + path, 1);
    }

    static Change change(String word) {
        for (Change action : Change.values()) if (action.name().equalsIgnoreCase(word)) return action;
        return null;
    }

    private static boolean canChange(CommandSender sender, Change action) {
        return sender.hasPermission("jiujiueco.eco") || sender.hasPermission("jiujiueco.eco." + keyword(action));
    }
    static boolean canSeeOthers(CommandSender sender) { return sender.hasPermission("jiujiueco.user.balance.others"); }
    static String keyword(Change action) { return action.name().toLowerCase(Locale.ROOT); }

    private static List<String> players(CommandSender sender, boolean excludeSelf) {
        Player viewer = sender instanceof Player ? (Player)sender : null;
        List<String> names = new ArrayList<>();
        for (Player target : sender.getServer().getOnlinePlayers()) {
            if (viewer != null && (!viewer.canSee(target) || (excludeSelf && viewer.equals(target)))) continue;
            names.add(target.getName());
        }
        return names;
    }

    @Override public void close() { eco.close(); balance.close(); pay.close(); }
}
