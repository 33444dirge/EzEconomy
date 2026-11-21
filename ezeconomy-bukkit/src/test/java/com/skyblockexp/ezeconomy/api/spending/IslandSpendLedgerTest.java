package com.skyblockexp.ezeconomy.api.spending;
import org.junit.jupiter.api.Test;
class IslandSpendLedgerTest {
    @Test void sqliteSettlementAndLinkedRefunds() throws Exception { LedgerRegression.main(new String[]{"sqlite"}); }
    @Test void mysqlSettlementAndLinkedRefunds() throws Exception { LedgerRegression.main(new String[]{"mysql"}); }
    @Test void sharedConnectionRollbackDoesNotLoseConcurrentChanges() throws Exception { SharedConnectionRegression.main(new String[0]); }
}
