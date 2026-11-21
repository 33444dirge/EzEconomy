package com.skyblockexp.ezeconomy.gui;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import com.skyblockexp.ezeconomy.core.Money;

/** Immutable flow snapshots shared by entity ticks and asynchronous chat callbacks. */
public class PayFlowManager {
    private record Flow(UUID targetUuid, String targetName, Money amount, String currency,
                        long expiresAtMillis, boolean awaitingAmount) {}
    private final Map<UUID, Flow> flows = new ConcurrentHashMap<>();

    public static class PendingTransfer {
        private final UUID toUuid;
        private final String toName;
        private final Money amount;
        private final String currency;
        private final long expiresAtMillis;
        public PendingTransfer(UUID toUuid, String toName, Money amount, String currency, long expiresAtMillis) {
            this.toUuid = toUuid; this.toName = toName; this.amount = amount;
            this.currency = currency; this.expiresAtMillis = expiresAtMillis;
        }
        public UUID getToUuid() { return toUuid; }
        public String getToName() { return toName; }
        public Money getAmount() { return amount; }
        public String getCurrency() { return currency; }
        public long getExpiresAtMillis() { return expiresAtMillis; }
    }

    public void startAwaiting(UUID source, UUID targetUuid, String targetName) {
        startAwaiting(source, targetUuid, targetName, System.currentTimeMillis() + 30_000L);
    }
    public void startAwaiting(UUID source, UUID targetUuid, String targetName, long expiresAt) {
        flows.compute(source, (id, old) -> new Flow(targetUuid, targetName, null,
                old == null ? null : old.currency(), expiresAt, true));
    }
    public void setCurrency(UUID source, String currency) {
        flows.compute(source, (id, old) -> old == null
                ? new Flow(null, null, null, currency, System.currentTimeMillis() + 30_000L, false)
                : new Flow(old.targetUuid(), old.targetName(), old.amount(), currency, old.expiresAtMillis(), old.awaitingAmount()));
    }
    public String getCurrency(UUID source) { Flow flow = current(source); return flow == null ? null : flow.currency(); }
    public UUID getTarget(UUID source) { Flow flow = current(source); return flow == null ? null : flow.targetUuid(); }
    public String getTargetName(UUID source) { Flow flow = current(source); return flow == null ? null : flow.targetName(); }
    public void createPendingTransfer(UUID source, UUID toUuid, String toName, Money amount, String currency, long expiresAt) {
        flows.put(source, new Flow(toUuid, toName, amount, currency, expiresAt, false));
    }
    public PendingTransfer pollPendingTransfer(UUID source) { return pending(flows.remove(source)); }
    public PendingTransfer getPendingTransfer(UUID source) { return pending(current(source)); }
    private PendingTransfer pending(Flow flow) {
        return flow == null || flow.amount() == null || flow.expiresAtMillis() <= System.currentTimeMillis() ? null
                : new PendingTransfer(flow.targetUuid(), flow.targetName(), flow.amount(), flow.currency(), flow.expiresAtMillis());
    }
    public void stopAwaiting(UUID source) { flows.remove(source); }
    public boolean isAwaiting(UUID source) { Flow flow = current(source); return flow != null && flow.awaitingAmount(); }
    public boolean isPendingConfirm(UUID source) { return getPendingTransfer(source) != null; }
    public void removeIfExpired(UUID source) { current(source); }
    public void clear() { flows.clear(); }
    public void cleanUp() { flows.forEach((id, flow) -> { if (expired(flow)) flows.remove(id, flow); }); }
    private boolean expired(Flow flow) { return flow.expiresAtMillis() <= System.currentTimeMillis(); }
    private Flow current(UUID source) {
        Flow flow = flows.get(source);
        if (flow != null && expired(flow)) { flows.remove(source, flow); return null; }
        return flow;
    }
}
