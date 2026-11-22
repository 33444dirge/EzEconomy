package city.jiujiu.eco.bukkit;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import city.jiujiu.lib.message.LanguageService;
import city.jiujiu.lib.scheduler.TaskScheduler;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import java.util.Map;
import java.util.HashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WalletCommandsTest {
    @TempDir Path dir;
    private LanguageService language;
    private WalletCommands commands;
    private final Map<String,TabCompleter> routers = new HashMap<>();
    private final Map<String,PluginCommand> registered = new HashMap<>();
    @BeforeEach void languageFiles() throws Exception {
        JavaPlugin owner = mock(JavaPlugin.class);
        when(owner.getName()).thenReturn("JiuJiuEco");
        for(String name : Arrays.asList("eco","pay","balance")){
            PluginCommand command = mock(PluginCommand.class);
            registered.put(name,command);
            when(owner.getCommand(name)).thenReturn(command);
            when(command.testPermissionSilent(any())).thenReturn(true);
            doAnswer(call -> { routers.put(name,call.getArgument(0));return null; }).when(command).setTabCompleter(any());
        }
        when(owner.getDataFolder()).thenReturn(dir.toFile());
        when(owner.getResource("lang/zh_CN.yml")).thenAnswer(call -> getClass().getResourceAsStream("/lang/zh_CN.yml"));
        Files.createDirectories(dir.resolve("lang"));
        Files.copy(getClass().getResourceAsStream("/language.yml"), dir.resolve("language.yml"));
        Files.copy(getClass().getResourceAsStream("/lang/zh_CN.yml"), dir.resolve("lang/zh_CN.yml"));
        language = new LanguageService(owner, mock(TaskScheduler.class));
        LanguageService shared = mock(LanguageService.class);
        when(shared.text(eq("command.help.page-size"), anyMap())).thenReturn("8");
        when(shared.text(eq("command.help.description"), anyMap())).thenReturn("Help");
        when(shared.component(anyString(), anyMap())).thenAnswer(call -> Component.text((String)call.getArgument(0)));
        commands = new WalletCommands(owner,language,shared);
    }
    private static Player player(String name) {
        Player player = mock(Player.class);
        when(player.getName()).thenReturn(name);
        return player;
    }

    @Test void paySuggestsVisibleRecipientsAndAmountsAtTheirOwnPositions() {
        Player viewer = player("Alex"), visible = player("Amber"), hidden = player("Ash");
        Server server = mock(Server.class);
        when(viewer.getServer()).thenReturn(server);
        doReturn(Arrays.asList(viewer, visible, hidden)).when(server).getOnlinePlayers();
        when(viewer.hasPermission("jiujiueco.user.pay")).thenReturn(true);
        when(viewer.canSee(viewer)).thenReturn(true);
        when(viewer.canSee(visible)).thenReturn(true);
        assertEquals(Collections.singletonList("Amber"), complete(viewer, "pay", "a"));
        assertEquals(Arrays.asList("10", "100"), complete(viewer, "pay", "Amber", "10"));
        assertTrue(complete(viewer, "pay", "Amber", "1", "").isEmpty());
        assertTrue(complete(mock(CommandSender.class), "pay", "").isEmpty());
        assertEquals(Collections.singletonList("Alex"), complete(viewer, "balance", "al"));
        assertTrue(complete(viewer, "balance", "Am").isEmpty());
        when(viewer.hasPermission("jiujiueco.user.balance.others")).thenReturn(true);
        assertEquals(Arrays.asList("Alex", "Amber"), complete(viewer, "balance", "A"));
    }

    @Test void ecoHelpAndCompletionUseTheSamePermissionAndRespectTheTypedAlias() {
        CommandSender admin = mock(CommandSender.class);
        when(admin.hasPermission("jiujiueco.eco.give")).thenReturn(true);
        assertEquals(Collections.singletonList("give"), complete(admin, "eco", "G"));
        assertTrue(complete(admin, "eco", "take", "Alex", "").isEmpty());
        assertEquals(Collections.singletonList("100"), complete(admin, "eco", "GiVe", "Alex", "100"));
        commands.usage(admin, "eco", "jiujiueco:eco");
        ArgumentCaptor<Component> line = ArgumentCaptor.forClass(Component.class);
        verify(admin,times(4)).sendMessage(line.capture());
        assertEquals(ClickEvent.suggestCommand("/jiujiueco:eco give <player> <amount>"), line.getAllValues().get(1).clickEvent());
    }

    @Test void localeReloadChangesAmountSuggestions() throws Exception {
        CommandSender admin = mock(CommandSender.class);
        when(admin.hasPermission("jiujiueco.eco.give")).thenReturn(true);
        Files.write(dir.resolve("language.yml"), "locale: custom\n".getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("lang/custom.yml"),
                "completion.amounts: '5 50'\n".getBytes(StandardCharsets.UTF_8));
        language.reload();
        assertEquals(Arrays.asList("5", "50"), complete(admin, "eco", "give", "Steve", ""));
    }

    private List<String> complete(CommandSender sender, String command, String... args) {
        return routers.get(command).onTabComplete(sender,registered.get(command),command,args);
    }
}
