package com.skyblockexp.ezeconomy.service.spending;

import java.sql.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LedgerMigrationTest {
    @Test void sqliteImportsHistoryExactlyOnce() throws Exception { migration(false); }
    @Test void mysqlImportsHistoryExactlyOnce() throws Exception { migration(true); }

    private Connection open(boolean mysql)throws Exception {
        return DriverManager.getConnection(mysql?"jdbc:h2:mem:migration-"+java.util.UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE":"jdbc:sqlite::memory:");
    }

    private void legacy(Connection c)throws Exception {
        try(var s=c.createStatement()){
            s.execute("CREATE TABLE old_spend(group_id VARCHAR(36),payer VARCHAR(36),currency VARCHAR(32),amount VARCHAR(64))");
            s.execute("CREATE TABLE old_spend_orders(id VARCHAR(96),group_id VARCHAR(36),payer VARCHAR(36),currency VARCHAR(32),paid VARCHAR(64),refunded VARCHAR(64))");
            s.execute("CREATE TABLE old_spend_refunds(id VARCHAR(96),payer VARCHAR(36),currency VARCHAR(32),amount VARCHAR(64),fingerprint VARCHAR(64))");
            s.execute("CREATE TABLE old_spend_meta(id INTEGER,started BIGINT)");
            s.execute("INSERT INTO old_spend VALUES('00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000002','j','75')");
            s.execute("INSERT INTO old_spend_orders VALUES('payment/1','00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000002','j','100','25')");
            s.execute("INSERT INTO old_spend_refunds VALUES('refund/1','00000000-0000-0000-0000-000000000002','j','25','original-fingerprint')");
            s.execute("INSERT INTO old_spend_meta VALUES(1,123456)");
        }
    }

    private String value(Connection c,String sql)throws Exception {
        try(var s=c.createStatement();var r=s.executeQuery(sql)){assertTrue(r.next());return r.getString(1);}
    }

    private void migration(boolean mysql)throws Exception {
        try(var c=open(mysql)){
            legacy(c);
            if(mysql)SqlSpendingLedger.mysql(null,null,c,"balances");else SqlSpendingLedger.sqlite(null,c,c,"balances");
            SqlSpendingLedger.importLegacy("old_spend","group_id");
            assertEquals(0,new java.math.BigDecimal(value(c,"SELECT amount FROM ezeconomy_spend")).compareTo(new java.math.BigDecimal("75")));
            assertEquals("00000000-0000-0000-0000-000000000001",value(c,"SELECT subject FROM ezeconomy_spend"));
            assertEquals("25",value(c,"SELECT refunded FROM ezeconomy_spend_orders"));
            assertEquals("original-fingerprint",value(c,"SELECT fingerprint FROM ezeconomy_spend_refunds"));
            assertEquals("123456",value(c,"SELECT started FROM ezeconomy_spend_meta WHERE id=1"));
            SqlSpendingLedger.importLegacy("old_spend","group_id");
            assertEquals("1",value(c,"SELECT COUNT(*) FROM ezeconomy_spend_orders"));
            assertEquals("75",value(c,"SELECT amount FROM old_spend"));
        }finally{SqlSpendingLedger.close();}
    }

    @Test void failedImportRollsBackEveryCopiedRow()throws Exception {
        try(var c=open(false)){
            legacy(c);c.createStatement().execute("DROP TABLE old_spend_refunds");
            SqlSpendingLedger.sqlite(null,c,c,"balances");
            assertThrows(SQLException.class,()->SqlSpendingLedger.importLegacy("old_spend","group_id"));
            assertEquals("0",value(c,"SELECT COUNT(*) FROM ezeconomy_spend"));
            assertEquals("0",value(c,"SELECT COUNT(*) FROM ezeconomy_spend_orders"));
            assertEquals("0",value(c,"SELECT COUNT(*) FROM ezeconomy_spend_imports"));
            assertTrue(c.getAutoCommit());
        }finally{SqlSpendingLedger.close();}
    }

    @Test void newInstallAndInvalidIdentifiersDoNotTouchUnrelatedTables()throws Exception {
        try(var c=open(false)){
            SqlSpendingLedger.sqlite(null,c,c,"balances");
            assertDoesNotThrow(()->SqlSpendingLedger.importLegacy("absent_spend","group_id"));
            assertThrows(IllegalArgumentException.class,()->SqlSpendingLedger.importLegacy("old_spend;DROP TABLE balances","group_id"));
            assertThrows(IllegalArgumentException.class,()->SqlSpendingLedger.importLegacy("old_spend","group_id;DROP TABLE balances"));
            assertEquals("0",value(c,"SELECT COUNT(*) FROM ezeconomy_spend"));
        }finally{SqlSpendingLedger.close();}
    }
}
