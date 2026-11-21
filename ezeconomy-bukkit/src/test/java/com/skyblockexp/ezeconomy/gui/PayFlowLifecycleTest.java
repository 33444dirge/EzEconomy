package com.skyblockexp.ezeconomy.gui;

import com.skyblockexp.ezeconomy.core.Money;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PayFlowLifecycleTest {
    @Test void expiredConfirmationCannotBePaidEvenBeforeCleanupRuns() {
        PayFlowManager flows = new PayFlowManager();
        UUID source = UUID.randomUUID();
        flows.createPendingTransfer(source, UUID.randomUUID(), "recipient", Money.of(BigDecimal.TEN, "j"), "j", 1L);
        assertNull(flows.pollPendingTransfer(source));
        assertFalse(flows.isPendingConfirm(source));
    }

    @Test void customAmountKeepsSelectedCurrencyAndKnownOfflineName() {
        PayFlowManager flows = new PayFlowManager();
        UUID source = UUID.randomUUID(), target = UUID.randomUUID();
        flows.setCurrency(source, "j");
        flows.startAwaiting(source, target, "OfflineRecipient");
        assertEquals("j", flows.getCurrency(source));
        assertEquals(target, flows.getTarget(source));
        assertEquals("OfflineRecipient", flows.getTargetName(source));
        flows.clear();
        assertFalse(flows.isAwaiting(source));
    }

    @Test void cleanupDoesNotRemoveAReplacementConfirmation() {
        PayFlowManager flows = new PayFlowManager();
        UUID source = UUID.randomUUID();
        flows.startAwaiting(source, UUID.randomUUID(), "old", 1L);
        flows.createPendingTransfer(source, UUID.randomUUID(), "new", Money.of(BigDecimal.ONE, "j"), "j", Long.MAX_VALUE);
        flows.removeIfExpired(source);
        assertEquals("new", flows.pollPendingTransfer(source).getToName());
        assertNull(flows.pollPendingTransfer(source));
    }
}
