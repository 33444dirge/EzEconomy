package com.skyblockexp.ezeconomy.command;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import com.skyblockexp.ezeconomy.core.EzEconomyPlugin;
import com.skyblockexp.ezeconomy.util.scheduler.PlatformScheduler;

/** Reuses the plugin's cached platform detection; live messages always return to their owner. */
final class BalanceScheduler {
    private final EzEconomyPlugin plugin;
    private final boolean folia;
    BalanceScheduler(EzEconomyPlugin plugin){this.plugin=plugin;this.folia=PlatformScheduler.isFolia();}
    void reply(CommandSender sender,Runnable message,Runnable retired){
        try {
            if(sender instanceof Player player){
                if(folia){
                    var task=player.getScheduler().runDelayed(plugin,t->{if(player.isOnline())message.run();else retired.run();},retired,1);
                    if(task==null)retired.run();
                }else Bukkit.getScheduler().runTaskLater(plugin,()->{if(player.isOnline())message.run();else retired.run();},1);
            }else PlatformScheduler.runTask(plugin,message);
        }catch(RuntimeException failure){retired.run();}
    }
}
