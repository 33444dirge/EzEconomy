package com.skyblockexp.ezeconomy.service.spending;

import com.skyblockexp.ezeconomy.storage.jaloquent.model.BalanceModel;
import java.math.BigDecimal;
import java.sql.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExplicitSettlementTest {
    @Test void sqliteSettlesBeforeAnyReaderAttaches()throws Exception{settle(false);}
    @Test void mysqlSettlesBeforeAnyReaderAttaches()throws Exception{settle(true);}

    private void settle(boolean mysql)throws Exception{
        UUID payer=UUID.randomUUID(),subject=UUID.randomUUID();
        try(var c=DriverManager.getConnection(mysql?"jdbc:h2:mem:explicit-"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE":"jdbc:sqlite::memory:")){
            c.createStatement().execute("CREATE TABLE balances(id VARCHAR(69) PRIMARY KEY,uuid VARCHAR(36),currency VARCHAR(32),balance DOUBLE)");
            try(var s=c.prepareStatement("INSERT INTO balances VALUES(?,?,?,?)")){
                s.setString(1,BalanceModel.idFor(payer,"j"));s.setString(2,payer.toString());s.setString(3,"j");s.setDouble(4,100);s.executeUpdate();
            }
            if(mysql)SqlSpendingLedger.mysql(null,null,c,"balances");else SqlSpendingLedger.sqlite(null,c,c,"balances");
            assertFalse(SqlSpendingLedger.ready());
            try(var scope=SqlSpendingLedger.order("payment/1")){
                assertEquals(75,SqlSpendingLedger.withdrawForSubject(subject,payer,"j",25).getBalance());
            }
            try(var scope=SqlSpendingLedger.order("payment/1")){
                assertEquals(75,SqlSpendingLedger.withdrawForSubject(UUID.randomUUID(),payer,"j",25).getBalance());
            }
            SqlSpendingLedger.refund("refund/1",List.of("payment/1"),new BigDecimal("25"),payer,"j",10);
            SqlSpendingLedger.refund("refund/1",List.of("payment/1"),new BigDecimal("25"),payer,"j",10);
            try(var s=c.createStatement();var r=s.executeQuery("SELECT subject,amount FROM ezeconomy_spend")){
                assertTrue(r.next());assertEquals(subject.toString(),r.getString(1));
                assertEquals(0,new BigDecimal("15").compareTo(r.getBigDecimal(2)));assertFalse(r.next());
            }
            try(var s=c.createStatement();var r=s.executeQuery("SELECT balance FROM balances")){
                assertTrue(r.next());assertEquals(85,r.getDouble(1));
            }
        }finally{SqlSpendingLedger.close();}
    }
}
