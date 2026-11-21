package com.skyblockexp.ezeconomy.command;

import com.skyblockexp.ezeconomy.api.storage.StorageProvider;
import com.skyblockexp.ezeconomy.core.EzEconomyPlugin;
import com.skyblockexp.ezeconomy.util.MessageUtils;
import com.skyblockexp.ezeconomy.util.PlayerLookup;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.server.PluginDisableEvent;

/** Bounded asynchronous balance command; no balance mutations. */
public final class BalanceCommand implements CommandExecutor, Listener, AutoCloseable {
    private final EzEconomyPlugin plugin;
    private final BalanceScheduler scheduler;
    private final ThreadPoolExecutor readers;
    private final ConcurrentHashMap<Object,Query> pending=new ConcurrentHashMap<>();
    private final AtomicBoolean closed=new AtomicBoolean();
    private final String busyMessage,failureMessage;
    public BalanceCommand(EzEconomyPlugin plugin){
        this.plugin=plugin;this.scheduler=new BalanceScheduler(plugin);
        var config=plugin.getConfig();
        int threads=Math.max(1,Math.min(16,config.getInt("performance.balance-command.workers",2)));
        int capacity=Math.max(1,Math.min(4096,config.getInt("performance.balance-command.queue-capacity",128)));
        busyMessage=color(config.getString("performance.balance-command.messages.busy","&e余额查询正在处理中，请稍候再试。"));
        failureMessage=color(config.getString("performance.balance-command.messages.failure","&c余额暂时无法查询，请稍后重试。"));
        readers=new ThreadPoolExecutor(threads,threads,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(capacity),r->{
            Thread t=new Thread(r,"EzEconomy-Balance-Reader");t.setDaemon(true);return t;
        },new ThreadPoolExecutor.AbortPolicy());
        readers.allowCoreThreadTimeOut(true);
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
    }
    private static String color(String value){return ChatColor.translateAlternateColorCodes('&',value);}
    private boolean otherPermission(CommandSender sender){return sender.hasPermission("ezeconomy.user.balance.others")||sender.hasPermission("ezeconomy.balance.others");}
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){
        if(closed.get()||!plugin.isEnabled())return true;
        if(args.length>2){MessageUtils.send(sender,plugin,"usage_balance");return true;}
        var section=plugin.getConfig().getConfigurationSection("multi-currency.currencies");
        Map<String,Object> currencies=section==null?Map.of():section.getValues(false);
        if(args.length==0){
            if(!(sender instanceof Player player)){MessageUtils.send(sender,plugin,"only_players");return true;}
            query(sender,player.getUniqueId(),player.getName(),plugin.getCurrencyPreferenceManager().getPreferredCurrency(player.getUniqueId()),false);
            return true;
        }
        String lower=args[0].toLowerCase(Locale.ROOT);
        if(args.length==1&&(currencies.containsKey(lower)||currencies.containsKey(args[0]))){
            if(!(sender instanceof Player player)){MessageUtils.send(sender,plugin,"only_players");return true;}
            query(sender,player.getUniqueId(),player.getName(),currencies.containsKey(lower)?lower:args[0],false);return true;
        }
        if(!otherPermission(sender)){MessageUtils.send(sender,plugin,"no_permission_others_balance");return true;}
        OfflinePlayer target=PlayerLookup.findByName(args[0]).orElse(null);
        if(target==null||(args.length==1&&!target.hasPlayedBefore()&&!target.isOnline())){
            MessageUtils.send(sender,plugin,"player_not_found");return true;
        }
        String currency=args.length==2?args[1].toLowerCase(Locale.ROOT):plugin.getDefaultCurrency();
        if(args.length==2&&!currencies.containsKey(currency)){
            MessageUtils.send(sender,plugin,"unknown_currency",Map.of("currency",currency));return true;
        }
        query(sender,target.getUniqueId(),Objects.requireNonNullElse(target.getName(),args[0]),currency,true);return true;
    }
    private void query(CommandSender sender,UUID target,String targetName,String currency,boolean other){
        StorageProvider storage=plugin.getStorageOrWarn();
        if(storage==null){sender.sendMessage(failureMessage);return;}
        Object key=sender instanceof Player player?player.getUniqueId():sender.getClass().getName()+":"+sender.getName();
        Query query=new Query(key,new WeakReference<>(sender),target,targetName,currency,other);
        if(pending.putIfAbsent(key,query)!=null){sender.sendMessage(busyMessage);return;}
        try{readers.execute(()->read(storage,query));}
        catch(RejectedExecutionException rejected){pending.remove(key,query);if(!closed.get())sender.sendMessage(busyMessage);}
    }
    private void read(StorageProvider storage,Query query){
        if(closed.get()){pending.remove(query.key,query);return;}
        double balance=0;boolean success=false;
        try{balance=storage.getBalance(query.target,query.currency);success=Double.isFinite(balance)&&balance>=0;}
        catch(RuntimeException failure){plugin.getLogger().log(java.util.logging.Level.WARNING,"余额查询失败，未修改任何余额",failure);}
        final double result=balance;final boolean valid=success;
        CommandSender recipient=query.sender.get();
        if(closed.get()||recipient==null){pending.remove(query.key,query);return;}
        scheduler.reply(recipient,()->{
            try{
                if(closed.get()||pending.get(query.key)!=query||!plugin.isEnabled())return;
                if(query.other&&!otherPermission(recipient)){MessageUtils.send(recipient,plugin,"no_permission_others_balance");return;}
                if(!valid){recipient.sendMessage(failureMessage);return;}
                var values=new HashMap<String,String>();values.put("currency",query.currency);
                values.put("balance",plugin.getCurrencyFormatter().formatPriceForMessage(result,query.currency));
                if(query.other)values.put("player",query.targetName);
                MessageUtils.send(recipient,plugin,query.other?"others_balance":"your_balance",values);
            }finally{pending.remove(query.key,query);}
        },()->pending.remove(query.key,query));
    }
    private record Query(Object key,WeakReference<CommandSender> sender,UUID target,String targetName,String currency,boolean other){}
    @EventHandler public void disabled(PluginDisableEvent event){if(event.getPlugin()==plugin)close();}
    @Override public void close(){if(closed.compareAndSet(false,true)){pending.clear();readers.shutdownNow();HandlerList.unregisterAll(this);}}
}
