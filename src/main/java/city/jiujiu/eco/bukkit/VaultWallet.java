package city.jiujiu.eco.bukkit;

import city.jiujiu.eco.storage.Accounts;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import net.milkbowl.vault.economy.AbstractEconomy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;

public final class VaultWallet extends AbstractEconomy {
    private final JiuJiuEco plugin;
    private final Accounts accounts;
    VaultWallet(JiuJiuEco plugin,Accounts accounts){this.plugin=plugin;this.accounts=accounts;}
    public String getName(){return "JiuJiuEco";}
    public boolean isEnabled(){return plugin.isEnabled();}
    public boolean hasBankSupport(){return false;}
    public int fractionalDigits(){return 8;}
    public String currencyNameSingular(){return "J";}
    public String currencyNamePlural(){return "J";}
    public String format(double amount){return BigDecimal.valueOf(amount).stripTrailingZeros().toPlainString()+" J";}
    public List<String> getBanks(){return Collections.emptyList();}
    private EconomyResponse mutate(OfflinePlayer player,double value,Accounts.Change change){
        try {
            long balance=accounts.change(player.getUniqueId(),Accounts.units(BigDecimal.valueOf(value)),change);
            return new EconomyResponse(value,Accounts.amount(balance).doubleValue(),EconomyResponse.ResponseType.SUCCESS,null);
        } catch (ArithmeticException | IllegalArgumentException rejected) {
            return new EconomyResponse(0,getBalance(player),EconomyResponse.ResponseType.FAILURE,rejected.getMessage());
        } catch (SQLException failure) {
            plugin.getLogger().log(Level.SEVERE,"Vault 交易结果未确认",failure);
            return new EconomyResponse(0,0,EconomyResponse.ResponseType.FAILURE,"Storage failure / review required");
        }
    }
    private static EconomyResponse unsupported(){return new EconomyResponse(0,0,EconomyResponse.ResponseType.NOT_IMPLEMENTED,"Bank accounts unsupported");}
    public boolean hasAccount(OfflinePlayer p) { try { return accounts.exists(p.getUniqueId()); } catch (SQLException e) { throw new IllegalStateException(e); } }
    public boolean hasAccount(String p) { return hasAccount(plugin.player(p)); }
    public boolean hasAccount(String p, String world) { return hasAccount(plugin.player(p)); }
    public boolean hasAccount(OfflinePlayer p, String world) { return hasAccount(p); }
    public boolean createPlayerAccount(OfflinePlayer p) { try { return accounts.create(p.getUniqueId()); } catch (SQLException e) { throw new IllegalStateException(e); } }
    public boolean createPlayerAccount(String p) { return createPlayerAccount(plugin.player(p)); }
    public boolean createPlayerAccount(String p, String world) { return createPlayerAccount(plugin.player(p)); }
    public boolean createPlayerAccount(OfflinePlayer p, String world) { return createPlayerAccount(p); }
    public double getBalance(OfflinePlayer p) { try { return Accounts.amount(accounts.balance(p.getUniqueId())).doubleValue(); } catch (SQLException e) { throw new IllegalStateException(e); } }
    public double getBalance(String p) { return getBalance(plugin.player(p)); }
    public double getBalance(String p, String world) { return getBalance(plugin.player(p)); }
    public double getBalance(OfflinePlayer p, String world) { return getBalance(p); }
    public boolean has(OfflinePlayer p, double amount) { return Double.isFinite(amount) && amount >= 0 && getBalance(p)>=amount; }
    public boolean has(String p, double amount) { return has(plugin.player(p),amount); }
    public boolean has(String p, String world, double amount) { return has(plugin.player(p),amount); }
    public boolean has(OfflinePlayer p, String world, double amount) { return has(p,amount); }
    public EconomyResponse withdrawPlayer(OfflinePlayer p, double amount) { return mutate(p,amount,Accounts.Change.TAKE); }
    public EconomyResponse withdrawPlayer(String p, double amount) { return withdrawPlayer(plugin.player(p),amount); }
    public EconomyResponse withdrawPlayer(String p, String world, double amount) { return withdrawPlayer(plugin.player(p),amount); }
    public EconomyResponse withdrawPlayer(OfflinePlayer p, String world, double amount) { return withdrawPlayer(p,amount); }
    public EconomyResponse depositPlayer(OfflinePlayer p, double amount) { return mutate(p,amount,Accounts.Change.GIVE); }
    public EconomyResponse depositPlayer(String p, double amount) { return depositPlayer(plugin.player(p),amount); }
    public EconomyResponse depositPlayer(String p, String world, double amount) { return depositPlayer(plugin.player(p),amount); }
    public EconomyResponse depositPlayer(OfflinePlayer p, String world, double amount) { return depositPlayer(p,amount); }
    public EconomyResponse createBank(String name,String player) { return unsupported(); }
    public EconomyResponse createBank(String name,OfflinePlayer player) { return unsupported(); }
    public EconomyResponse bankBalance(String name) { return unsupported(); }
    public EconomyResponse deleteBank(String name) { return unsupported(); }
    public EconomyResponse bankHas(String name,double amount) { return unsupported(); }
    public EconomyResponse bankWithdraw(String name,double amount) { return unsupported(); }
    public EconomyResponse bankDeposit(String name,double amount) { return unsupported(); }
    public EconomyResponse isBankOwner(String name,String player) { return unsupported(); }
    public EconomyResponse isBankOwner(String name,OfflinePlayer player) { return unsupported(); }
    public EconomyResponse isBankMember(String name,String player) { return unsupported(); }
    public EconomyResponse isBankMember(String name,OfflinePlayer player) { return unsupported(); }
}
