package adris.altoclef.tasks.replenish;

/**
 * Operational cost and delivery telemetry for a worksite replenishment courier.
 */
public final class ReplenishmentMetrics {

    public record Snapshot(
            int destChecks,
            int destOpens,
            int sourceOpens,
            int sourceVisits,
            int destDeliveries,
            int sourceReturns,
            int emptyTrips,
            int unnecessaryChestOpens,
            int itemsWithdrawn,
            int itemsDeposited,
            int itemsReturned,
            int completedRefills,
            int shortageReports,
            int interruptRecoveries,
            int failedAttempts) {

        public int totalChestOpens() {
            return destOpens + sourceOpens;
        }

        public String summary() {
            return "refills=" + completedRefills
                    + " withdrawn=" + itemsWithdrawn
                    + " deposited=" + itemsDeposited
                    + " returned=" + itemsReturned
                    + " destChecks=" + destChecks
                    + " opens(dest=" + destOpens + ",src=" + sourceOpens + ")"
                    + " emptyTrips=" + emptyTrips
                    + " unnecessaryOpens=" + unnecessaryChestOpens
                    + " shortages=" + shortageReports
                    + " recoveries=" + interruptRecoveries
                    + " failures=" + failedAttempts;
        }
    }

    private int destChecks;
    private int destOpens;
    private int sourceOpens;
    private int sourceVisits;
    private int destDeliveries;
    private int sourceReturns;
    private int emptyTrips;
    private int unnecessaryChestOpens;
    private int itemsWithdrawn;
    private int itemsDeposited;
    private int itemsReturned;
    private int completedRefills;
    private int shortageReports;
    private int interruptRecoveries;
    private int failedAttempts;

    public void recordDestCheck() {
        destChecks++;
        destOpens++;
    }

    public void recordSourceWithdrawVisit(int moved) {
        sourceVisits++;
        sourceOpens++;
        itemsWithdrawn += Math.max(0, moved);
        if (moved <= 0) {
            emptyTrips++;
        }
    }

    public void recordDestDeliveryVisit(int moved) {
        destDeliveries++;
        destOpens++;
        itemsDeposited += Math.max(0, moved);
        if (moved <= 0) {
            emptyTrips++;
        }
    }

    public void recordSourceReturnVisit(int moved) {
        sourceReturns++;
        sourceOpens++;
        itemsReturned += Math.max(0, moved);
        if (moved <= 0) {
            emptyTrips++;
        }
    }

    public void recordUnnecessaryChestOpen() {
        unnecessaryChestOpens++;
    }

    public void recordCompletedRefill() {
        completedRefills++;
    }

    public void recordShortageReport() {
        shortageReports++;
    }

    public void recordInterruptRecovery() {
        interruptRecoveries++;
    }

    public void recordFailedAttempt() {
        failedAttempts++;
    }

    public void restoreFrom(Snapshot s) {
        if (s == null) return;
        this.destChecks = s.destChecks();
        this.destOpens = s.destOpens();
        this.sourceOpens = s.sourceOpens();
        this.sourceVisits = s.sourceVisits();
        this.destDeliveries = s.destDeliveries();
        this.sourceReturns = s.sourceReturns();
        this.emptyTrips = s.emptyTrips();
        this.unnecessaryChestOpens = s.unnecessaryChestOpens();
        this.itemsWithdrawn = s.itemsWithdrawn();
        this.itemsDeposited = s.itemsDeposited();
        this.itemsReturned = s.itemsReturned();
        this.completedRefills = s.completedRefills();
        this.shortageReports = s.shortageReports();
        this.interruptRecoveries = s.interruptRecoveries();
        this.failedAttempts = s.failedAttempts();
    }

    public Snapshot snapshot() {
        return new Snapshot(
                destChecks,
                destOpens,
                sourceOpens,
                sourceVisits,
                destDeliveries,
                sourceReturns,
                emptyTrips,
                unnecessaryChestOpens,
                itemsWithdrawn,
                itemsDeposited,
                itemsReturned,
                completedRefills,
                shortageReports,
                interruptRecoveries,
                failedAttempts);
    }

    public String summary() {
        return snapshot().summary();
    }
}
