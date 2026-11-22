package city.jiujiu.eco.bukkit;

import city.jiujiu.eco.storage.Accounts;
import java.sql.*;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class WalletTest {
    @Test void freshWalletPersistsFractionalTransfersAcrossRestart(@TempDir Path directory) throws Exception {
        String url = "jdbc:sqlite:" + directory.resolve("jiujiueco.db");
        UUID payer = UUID.randomUUID(), recipient = UUID.randomUUID();
        try (Accounts wallet = new Accounts(DriverManager.getConnection(url), false)) {
            assertEquals(0, wallet.balance(payer));
            wallet.change(payer, Accounts.units(new BigDecimal("3.00000001")), Accounts.Change.SET);
            wallet.transfer(payer, recipient, Accounts.units(new BigDecimal("2.00000001")));
        }
        try (Accounts wallet = new Accounts(DriverManager.getConnection(url), false)) {
            assertEquals(100000000L, wallet.displayBalance(payer));
            assertEquals(200000001L, wallet.displayBalance(recipient));
            assertEquals("1", Accounts.amount(wallet.balance(payer)).toPlainString());
            assertEquals("2.00000001", Accounts.amount(wallet.balance(recipient)).toPlainString());
        }
    }
    @Test void failedCreditRollsBackDebitAndFailedDebitCannotPay() throws Exception {
        try(Connection c=DriverManager.getConnection("jdbc:sqlite::memory:");Accounts a=new Accounts(c,false)){
            UUID from=UUID.randomUUID(), to=UUID.randomUUID();
            a.change(from,100,Accounts.Change.GIVE); a.change(to,Long.MAX_VALUE,Accounts.Change.SET);
            assertThrows(IllegalArgumentException.class,()->a.transfer(from,to,1));
            assertEquals(100,a.displayBalance(from));assertEquals(Long.MAX_VALUE,a.displayBalance(to));
            assertEquals(100,a.balance(from));assertEquals(Long.MAX_VALUE,a.balance(to));
            a.change(to,0,Accounts.Change.SET);assertEquals(75,a.transfer(from,to,25));
            assertThrows(IllegalArgumentException.class,()->a.transfer(from,to,76));
            assertEquals(25,a.balance(to));assertEquals(75,a.balance(from));
            assertEquals(25,a.displayBalance(to));assertEquals(75,a.displayBalance(from));
        }
    }

    @Test void sharedWalletRefreshesExternallyChangedAndDeletedDisplayedAccounts(@TempDir Path directory) throws Exception {
        String url = "jdbc:sqlite:" + directory.resolve("shared.db");
        UUID id = UUID.randomUUID();
        try (Accounts reader = new Accounts(DriverManager.getConnection(url), true);
             Accounts writer = new Accounts(DriverManager.getConnection(url), false);
             Connection external = DriverManager.getConnection(url)) {
            assertEquals(0, reader.displayBalance(id));
            writer.change(id, 120, Accounts.Change.SET);
            reader.refreshDisplays();
            assertEquals(120, reader.displayBalance(id));
            writer.change(id, 50, Accounts.Change.TAKE);
            reader.refreshDisplays();
            assertEquals(70, reader.displayBalance(id));
            try (PreparedStatement delete = external.prepareStatement("DELETE FROM jiujiueco_wallet WHERE player=?")) {
                delete.setString(1, id.toString());
                delete.executeUpdate();
            }
            reader.refreshDisplays();
            assertEquals(0, reader.displayBalance(id));
        }
    }
}
