package com.skyblockexp.ezeconomy.service.spending;
import org.junit.jupiter.api.Test;
class SqlSpendingLedgerTest {
    @Test void sqliteSettlementAndLinkedRefunds() throws Exception { LedgerRegression.main(new String[]{"sqlite"}); }
    @Test void mysqlSettlementAndLinkedRefunds() throws Exception { LedgerRegression.main(new String[]{"mysql"}); }
    @Test void sharedConnectionRollbackDoesNotLoseConcurrentChanges() throws Exception { SharedConnectionRegression.main(new String[0]); }
}
