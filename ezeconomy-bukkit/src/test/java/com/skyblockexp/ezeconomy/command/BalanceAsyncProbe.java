package com.skyblockexp.ezeconomy.command;
import static org.mockito.Mockito.*;
import com.skyblockexp.ezeconomy.core.EzEconomyPlugin;
import com.skyblockexp.ezeconomy.api.storage.StorageProvider;
import com.skyblockexp.ezeconomy.manager.CurrencyPreferenceManager;
import com.skyblockexp.ezeconomy.service.format.CurrencyFormatter;
import com.skyblockexp.ezeconomy.util.MessageUtils;
import com.skyblockexp.ezeconomy.util.scheduler.PlatformScheduler;
import io.papermc.paper.threadedregions.scheduler.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;

public final class BalanceAsyncProbe {
    static int checks;
    static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);checks++;System.out.println("PASS "+label);}
    public static void main(String[] args)throws Exception{
        var plugin=mock(EzEconomyPlugin.class);var server=mock(Server.class);var manager=mock(PluginManager.class);
        when(plugin.getServer()).thenReturn(server);when(server.getPluginManager()).thenReturn(manager);
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());when(plugin.isEnabled()).thenReturn(true);
        var config=new YamlConfiguration();config.set("multi-currency.currencies.j",Map.of());config.set("performance.balance-command.workers",1);config.set("performance.balance-command.queue-capacity",1);
        when(plugin.getConfig()).thenReturn(config);
        var prefs=mock(CurrencyPreferenceManager.class);when(plugin.getCurrencyPreferenceManager()).thenReturn(prefs);when(prefs.getPreferredCurrency(any())).thenReturn("j");
        var formatter=mock(CurrencyFormatter.class);when(plugin.getCurrencyFormatter()).thenReturn(formatter);when(formatter.formatPriceForMessage(anyDouble(),anyString())).thenReturn("42.50");
        var storage=mock(StorageProvider.class);when(plugin.getStorageOrWarn()).thenReturn(storage);
        var player=mock(Player.class);UUID id=UUID.randomUUID();when(player.getUniqueId()).thenReturn(id);when(player.getName()).thenReturn("Krypton_QG");when(player.isOnline()).thenReturn(true);
        var scheduler=mock(EntityScheduler.class);when(player.getScheduler()).thenReturn(scheduler);
        BlockingQueue<Consumer<ScheduledTask>> callbacks=new LinkedBlockingQueue<>();ScheduledTask nativeTask=mock(ScheduledTask.class);
        when(scheduler.runDelayed(eq(plugin),any(),any(),eq(1L))).thenAnswer(c->{callbacks.add(c.getArgument(1));return nativeTask;});
        var messages=new ArrayList<String>();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicReference<Thread> readThread=new AtomicReference<>();
        when(storage.getBalance(id,"j")).thenAnswer(c->{readThread.set(Thread.currentThread());entered.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("test timeout");return 42.5;});
        try(var platform=mockStatic(PlatformScheduler.class, CALLS_REAL_METHODS);var output=mockStatic(MessageUtils.class)){
            platform.when(PlatformScheduler::isFolia).thenReturn(true);
            output.when(()->MessageUtils.send(any(CommandSender.class),eq(plugin),anyString(),anyMap())).thenAnswer(c->{messages.add(c.getArgument(2));return null;});
            output.when(()->MessageUtils.send(any(CommandSender.class),eq(plugin),anyString())).thenAnswer(c->{messages.add(c.getArgument(2));return null;});
            var command=new BalanceCommand(plugin);
            long start=System.nanoTime();command.onCommand(player,null,"bal",new String[0]);
            check(System.nanoTime()-start<TimeUnit.MILLISECONDS.toNanos(200),"command returns while database is still blocked");
            check(entered.await(2,TimeUnit.SECONDS)&&readThread.get()!=Thread.currentThread(),"database read runs on dedicated worker");
            command.onCommand(player,null,"bal",new String[0]);verify(storage,times(1)).getBalance(id,"j");verify(player).sendMessage(contains("处理中"));check(true,"repeat request cannot schedule a duplicate read");
            release.countDown();var callback=callbacks.poll(2,TimeUnit.SECONDS);check(callback!=null,"reply returns through one-tick entity scheduler");
            check(messages.isEmpty(),"worker does not send player messages");callback.accept(nativeTask);check(messages.equals(List.of("your_balance")),"one success message after owner callback");
            messages.clear();command.onCommand(player,null,"bal",new String[]{"AnotherPlayer"});check(messages.equals(List.of("no_permission_others_balance")),"other-player permissions checked before database query");
            when(storage.getBalance(id,"j")).thenThrow(new IllegalStateException("injected DB failure"));
            command.onCommand(player,null,"bal",new String[0]);callback=callbacks.poll(2,TimeUnit.SECONDS);check(callback!=null,"error completes on owner");messages.clear();callback.accept(nativeTask);
            check(messages.isEmpty(),"thrown DB failure never produces balance success message");verify(player).sendMessage(contains("无法查询"));
            doReturn(7.0).when(storage).getBalance(id,"j");
            command.onCommand(player,null,"bal",new String[0]);callback=callbacks.poll(2,TimeUnit.SECONDS);check(callback!=null,"third query scheduled");
            command.close();messages.clear();callback.accept(nativeTask);check(messages.isEmpty(),"late completion after disable is ignored");
            var pending=BalanceCommand.class.getDeclaredField("pending");pending.setAccessible(true);check(((Map<?,?>)pending.get(command)).isEmpty(),"pending references cleared on disable");
            command.onCommand(player,null,"bal",new String[0]);verify(storage,times(3)).getBalance(id,"j");check(true,"closed command cannot start more IO");
            var bounded=new BalanceCommand(plugin);var blocked=new CountDownLatch(1);var began=new CountDownLatch(1);
            doAnswer(c->{began.countDown();blocked.await(3,TimeUnit.SECONDS);return 7.0;}).when(storage).getBalance(id,"j");
            var second=mock(Player.class);var third=mock(Player.class);
            when(second.getUniqueId()).thenReturn(UUID.randomUUID());when(second.getName()).thenReturn("Second");when(second.getScheduler()).thenReturn(scheduler);
            when(third.getUniqueId()).thenReturn(UUID.randomUUID());when(third.getName()).thenReturn("Third");when(third.getScheduler()).thenReturn(scheduler);
            try{
                bounded.onCommand(player,null,"bal",new String[]{"j"});check(began.await(2,TimeUnit.SECONDS),"explicit currency uses bounded reader");
                bounded.onCommand(second,null,"bal",new String[0]);bounded.onCommand(third,null,"bal",new String[0]);
                verify(third).sendMessage(contains("处理中"));verify(storage,never()).getBalance(third.getUniqueId(),"j");check(true,"full queue rejects extra query without caller-thread IO");
                bounded.close();check(((Map<?,?>)pending.get(bounded)).isEmpty(),"shutdown clears running and queued requests");
            }finally{blocked.countDown();bounded.close();}
            var offline=new BalanceCommand(plugin);doReturn(7.0).when(storage).getBalance(id,"j");
            when(player.isOnline()).thenReturn(false);messages.clear();offline.onCommand(player,null,"bal",new String[0]);
            callback=callbacks.poll(2,TimeUnit.SECONDS);check(callback!=null,"offline completion scheduled safely");callback.accept(nativeTask);
            check(messages.isEmpty()&&((Map<?,?>)pending.get(offline)).isEmpty(),"offline reply skips messages and clears pending");offline.close();

        }finally{release.countDown();}
        System.out.println("BALANCE ASYNC PASSED "+checks);
    }
}
