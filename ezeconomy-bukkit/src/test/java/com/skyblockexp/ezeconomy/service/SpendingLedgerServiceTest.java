package com.skyblockexp.ezeconomy.service;

import com.skyblockexp.ezeconomy.service.spending.SqlSpendingLedger;
import com.skyblockexp.ezeconomy.api.storage.EconomyMutationResult;
import com.skyblockexp.ezeconomy.api.storage.StorageProvider;
import com.skyblockexp.ezeconomy.core.EzEconomyPlugin;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SpendingLedgerServiceTest {
    private EzEconomyPlugin plugin;
    private StorageProvider storage;
    private SpendingLedgerService service;
    private final UUID payer = UUID.randomUUID();

    @BeforeEach void setup() {
        plugin = mock(EzEconomyPlugin.class);
        storage = mock(StorageProvider.class);
        var config = new YamlConfiguration();
        config.set("multi-currency.enabled", true);
        config.set("multi-currency.currencies.j.name", "J");
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getStorage()).thenReturn(storage);
        service = new SpendingLedgerService(plugin);
    }

    @Test void membershipComesOnlyFromCallerAndClosingDetachesWithoutDeletingHistory() throws Exception {
        Object owner = new Object();
        Function<UUID, UUID> resolver = id -> UUID.randomUUID();
        AutoCloseable registration = mock(AutoCloseable.class);
        try (var ledger = mockStatic(SqlSpendingLedger.class)) {
            ledger.when(() -> SqlSpendingLedger.attach(owner, resolver, "j")).thenReturn(registration);
            var handle = service.attach(owner, resolver, "j");
            ledger.verify(() -> SqlSpendingLedger.attach(owner, resolver, "j"));
            assertThrows(IllegalStateException.class, () -> service.attach(new Object(), resolver, "j"));
            handle.close();
            verify(registration).close();
            ledger.verifyNoMoreInteractions();
        }
    }

    @Test void snapshotPreservesCallerSuppliedSubjectsWithoutSortingAndIsImmutable() throws Exception {
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        var rows = new ArrayList<>(List.of(new SqlSpendingLedger.Row(first, payer, new BigDecimal("2")),
                new SqlSpendingLedger.Row(second, payer, new BigDecimal("100"))));
        try (var ledger = mockStatic(SqlSpendingLedger.class)) {
            ledger.when(() -> SqlSpendingLedger.snapshot(17)).thenReturn(new SqlSpendingLedger.Snapshot(42, rows));
            var snapshot = service.snapshot(17);
            rows.clear();
            assertEquals(42, snapshot.since());
            assertEquals(List.of(first, second), snapshot.rows().stream().map(row -> row.subject()).toList());
            assertEquals(new BigDecimal("2"), snapshot.rows().get(0).amount());
            assertThrows(UnsupportedOperationException.class, () -> snapshot.rows().clear());
        }
    }

    @Test void snapshotLimitFailureIsPropagatedRatherThanPublishingPartialTotals() {
        try (var ledger = mockStatic(SqlSpendingLedger.class)) {
            ledger.when(() -> SqlSpendingLedger.snapshot(2)).thenThrow(new SQLException("limit"));
            assertEquals("limit", assertThrows(SQLException.class, () -> service.snapshot(2)).getMessage());
        }
    }

    @Test void debitUsesOneExistingSettlementAndAlwaysClosesTheOrderScope() throws Exception {
        var result = EconomyMutationResult.success(75);
        var scope = mock(SqlSpendingLedger.Scope.class);
        when(storage.withdrawAndGetBalance(payer, "j", 25)).thenReturn(result);
        try (var ledger = mockStatic(SqlSpendingLedger.class)) {
            ledger.when(() -> SqlSpendingLedger.order("subject/order")).thenReturn(scope);
            assertSame(result, service.withdraw("subject/order", payer, "j", new BigDecimal("25")));
            verify(storage).withdrawAndGetBalance(payer, "j", 25);
            ledger.verify(() -> SqlSpendingLedger.validateSettlement(plugin));
            verify(scope).close();
        }
    }

    @Test void invalidAmountsAndUnknownCurrencyCannotMutateBalances() {
        for (String invalid : List.of("0", "-1", "0.000000001", "123456789012345678.01")) {
            assertThrows(RuntimeException.class, () -> service.withdraw("order", payer, "j", new BigDecimal(invalid)));
        }
        assertThrows(IllegalArgumentException.class, () -> service.withdraw("order", payer, "unknown", BigDecimal.ONE));
        verifyNoInteractions(storage);
    }

    @Test void failedBackendValidationCannotDebit() {
        try (var ledger = mockStatic(SqlSpendingLedger.class)) {
            ledger.when(() -> SqlSpendingLedger.validateSettlement(plugin)).thenThrow(new SQLException("unsafe backend"));
            assertThrows(SQLException.class, () -> service.withdraw("order", payer, "j", BigDecimal.ONE));
            verifyNoInteractions(storage);
        }
    }

    @Test void explicitSubjectIsSuppliedByTheCallerAndDoesNotDependOnAReader() throws Exception {
        UUID subject = UUID.randomUUID();
        var result = EconomyMutationResult.success(99);
        var scope = mock(SqlSpendingLedger.Scope.class);
        try (var ledger = mockStatic(SqlSpendingLedger.class)) {
            ledger.when(() -> SqlSpendingLedger.order("payment/1")).thenReturn(scope);
            ledger.when(() -> SqlSpendingLedger.withdrawForSubject(subject, payer, "j", 1)).thenReturn(result);
            assertSame(result, service.withdraw("payment/1", subject, payer, "j", BigDecimal.ONE));
            ledger.verify(() -> SqlSpendingLedger.withdrawForSubject(subject, payer, "j", 1));
            verify(scope).close();
            verifyNoInteractions(storage);
        }
    }

    @Test void refundKeepsOriginalPaymentReferencesInsteadOfLookingUpNewMembership() throws Exception {
        var result = EconomyMutationResult.success(90);
        var sources = List.of("original/a", "original/b");
        try (var ledger = mockStatic(SqlSpendingLedger.class)) {
            ledger.when(() -> SqlSpendingLedger.refund("refund/1", sources, new BigDecimal("100"), payer, "j", 10)).thenReturn(result);
            assertSame(result, service.refund("refund/1", sources, new BigDecimal("100"), payer, "j", BigDecimal.TEN));
            ledger.verify(() -> SqlSpendingLedger.refund("refund/1", sources, new BigDecimal("100"), payer, "j", 10));
            verifyNoInteractions(storage);
        }
    }

    @Test void shutdownReleasesMembershipAndRejectsCallsFromStaleConsumers() throws Exception {
        Object owner = new Object();
        Function<UUID, UUID> resolver = id -> null;
        var registration = mock(AutoCloseable.class);
        try (var ledger = mockStatic(SqlSpendingLedger.class)) {
            ledger.when(() -> SqlSpendingLedger.attach(owner, resolver, "j")).thenReturn(registration);
            service.attach(owner, resolver, "j");
            service.close();
            service.close();
            verify(registration).close();
            assertFalse(service.available());
            assertThrows(SQLException.class, () -> service.snapshot(10));
            assertThrows(SQLException.class, () -> service.balance(payer, "j"));
            assertThrows(SQLException.class, () -> service.withdraw("order", payer, "j", BigDecimal.ONE));
            verifyNoInteractions(storage);
        }
    }

    @Test void invalidBalanceFailsInsteadOfBeingReportedAsZero() {
        when(storage.getBalance(payer, "j")).thenReturn(Double.NaN);
        assertThrows(SQLException.class, () -> service.balance(payer, "j"));
    }
}
