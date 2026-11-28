package adris.altoclef.swarm;

import adris.altoclef.swarm.SwarmLedger.Type;
import adris.altoclef.swarm.survey.SurveyArea;
import adris.altoclef.swarm.survey.SurveyBoard;
import adris.altoclef.swarm.survey.SurveyGrid;
import adris.altoclef.swarm.survey.SurveyTask;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The swarm's decision layer. It never touches the game: it observes agents through messages,
 * decides WHAT each should do, checks the outcome against evidence, and reassigns on failure.
 * HOW a task is carried out (pathing, combat, recovery) stays with the agent and Ostinato.
 *
 * Time is passed in, so behaviour is deterministic and testable. All entry points are synchronized
 * because messages arrive off the game thread.
 */
public final class SwarmCoordinator {
    public interface Outbox {
        void send(String agentId, SwarmMessage message);
    }

    public static final class Config {
        public long heartbeatTimeoutMs = 15_000;
        public long offerTimeoutMs = 10_000;
        public long runTimeoutMs = 10 * 60_000;
        /** After accepting, the agent must keep claiming the work in heartbeats. */
        public long abandonGraceMs = 5_000;
        public int maxAttempts = 3;
        /** Wait before retrying after a situational failure (danger, death): retrying into the same threat just burns attempts. */
        public long situationalRetryDelayMs = 10_000;
        /** A held survey region with no new chunk and no movement for this long is taken back and reassigned. */
        public long surveyStallMs = 120_000;
        /** Blocks of penalty per chunk of overlap when a region would sit right next to another scout's region. */
        public double surveyCrowdWeight = 0.5;
        /** Least time between "chunks changed under you" updates to one holder. */
        public long surveyUpdMs = 5_000;
        public int surveyRegionSide = SurveyGrid.DEFAULT_SIDE;
        /** Surveys running at once; each is bounded by SurveyGrid.MAX_REGIONS regions. */
        public int maxActiveSurveys = 4;
        /** Finished or stopped surveys kept for inspection. */
        public int keptFinishedSurveys = 4;
    }

    private final Outbox outbox;
    private final Config cfg;
    private final Map<String, AgentInfo> agents = new LinkedHashMap<>();
    private final Map<String, Assignment> assignments = new LinkedHashMap<>();
    private final ReservationTable reservations = new ReservationTable();
    private final BeliefBoard beliefs = new BeliefBoard();
    private final SwarmLedger ledger = new SwarmLedger();
    private int nextId = 1;
    private int nextSurvey = 1;
    private final Map<String, SurveyBoard> surveys = new LinkedHashMap<>();
    private final Map<String, Assignment> surveyRegions = new LinkedHashMap<>(); // "sv1/3" -> its assignment
    private final Map<String, Long> lastUpd = new LinkedHashMap<>();
    private final Set<String> surveysCompleted = new LinkedHashSet<>();

    public SwarmCoordinator(Outbox outbox) { this(outbox, new Config()); }

    public SwarmCoordinator(Outbox outbox, Config cfg) {
        this.outbox = outbox;
        this.cfg = cfg;
    }

    // ---- views ----

    public synchronized AgentInfo agent(String id) { return agents.get(id); }
    public synchronized Assignment assignment(String id) { return assignments.get(id); }
    public synchronized List<AgentInfo> agents() { return new ArrayList<>(agents.values()); }
    public synchronized List<Assignment> assignments() { return new ArrayList<>(assignments.values()); }
    public ReservationTable reservations() { return reservations; }
    public BeliefBoard beliefs() { return beliefs; }
    public SwarmLedger ledger() { return ledger; }

    // ---- work in ----

    public synchronized Assignment create(Objective objective, int priority, Set<Capability> required) {
        return new Assignment("a" + nextId++, objective, priority, required);
    }

    public synchronized Assignment submit(Assignment a, long now) {
        enqueue(a, now);
        schedule(now);
        return a;
    }

    private void enqueue(Assignment a, long now) {
        assignments.put(a.id, a);
        a.move(AssignmentState.UNASSIGNED, now);
        ledger.add(now, Type.TASK_SUBMITTED, null, a.id, a.objective.describe() + " needs " + a.required() + " prio " + a.priority);
    }

    // ---- surveys ----

    /**
     * Starts surveying a marked area. The area is cut into bounded regions, each one an ordinary assignment that goes
     * through the same scheduler, offer, accept, heartbeat, timeout and reassignment path as any other work.
     *
     * @throws IllegalArgumentException if the area is larger than one survey may be
     * @throws IllegalStateException    if too many surveys are already running
     */
    public synchronized SurveyBoard startSurvey(SurveyArea area, long now) {
        pruneSurveys();
        int active = 0;
        for (SurveyBoard b : surveys.values()) if (!b.closed() && !b.done()) active++;
        if (active >= cfg.maxActiveSurveys) throw new IllegalStateException("already running " + active + " surveys; stop one first");
        String id = "sv" + nextSurvey++;
        SurveyBoard b = new SurveyBoard(id, area, cfg.surveyRegionSide, now);
        surveys.put(id, b);
        SurveyGrid g = b.grid();
        ledger.add(now, Type.SURVEY_STARTED, null, null, id + " " + area + " as " + b.regionCount() + " regions of up to " + g.side() + "x" + g.side());
        for (int r = 0; r < b.regionCount(); r++) {
            SurveyTask t = new SurveyTask(id, r, area, g.side(), b.todo(r));
            Assignment a = create(Objective.survey(t), 5, EnumSet.noneOf(Capability.class));
            a.target(g.centerX(r), 64, g.centerZ(r));
            surveyRegions.put(id + "/" + r, a);
            enqueue(a, now);
        }
        schedule(now);
        return b;
    }

    public synchronized SurveyBoard survey(String id) { return surveys.get(id); }
    public synchronized List<SurveyBoard> surveys() { return new ArrayList<>(surveys.values()); }

    /** Calls off every unfinished region of a survey. What was observed stays observed. */
    public synchronized boolean stopSurvey(String id, long now) {
        SurveyBoard b = surveys.get(id);
        if (b == null || b.closed()) return false;
        for (int r = 0; r < b.regionCount(); r++) {
            Assignment a = surveyRegions.get(id + "/" + r);
            if (a != null && !a.state().isTerminal()) cancel(a.id, now);
        }
        b.close();
        ledger.add(now, Type.SURVEY_STOPPED, null, null, id + " stopped at " + b.snapshot().observed() + "/" + b.snapshot().total() + " observed");
        return true;
    }

    private void pruneSurveys() {
        List<String> finished = new ArrayList<>();
        for (SurveyBoard b : surveys.values()) if (b.closed() || b.done()) finished.add(b.id());
        for (int i = 0; i < finished.size() - cfg.keptFinishedSurveys; i++) {
            String id = finished.get(i);
            SurveyBoard b = surveys.remove(id);
            for (int r = 0; r < b.regionCount(); r++) {
                Assignment a = surveyRegions.remove(id + "/" + r);
                if (a != null && a.state().isTerminal()) assignments.remove(a.id);
                lastUpd.remove(id + "/" + r);
            }
            surveysCompleted.remove(id);
        }
    }

    private SurveyBoard boardOf(Assignment a) {
        return a.objective.isSurvey() ? surveys.get(a.objective.survey().surveyId()) : null;
    }

    public synchronized boolean cancel(String id, long now) {
        Assignment a = assignments.get(id);
        if (a == null || a.state().isTerminal()) return false;
        String agent = a.agentId();
        SurveyBoard sb = boardOf(a);
        if (sb != null) sb.release(a.objective.survey().region());
        if (agent != null) {
            outbox.send(agent, SwarmMessage.of("cancel", "a", a.id));
            freeAgent(agent, a);
        }
        a.release(now);
        a.move(AssignmentState.CANCELLED, now);
        ledger.add(now, Type.TASK_CANCELLED, agent, a.id, "cancelled by leader");
        return true;
    }

    // ---- messages in ----

    public synchronized void onMessage(String from, SwarmMessage m, long now) {
        switch (m.op) {
            case "reg" -> register(from, m, now);
            case "bye" -> loseAgent(from, AgentLifecycle.DISCONNECTED, "said goodbye", now);
            case "hb" -> heartbeat(from, m, now);
            case "accept" -> accepted(from, m, now);
            case "reject" -> rejected(from, m, now);
            case "prog" -> progress(from, m, now);
            case "result" -> result(from, m, now);
            case "obs" -> observed(from, m, now);
            case "cov" -> covered(from, m, now);
            default -> { /* unknown ops from newer agents are ignored */ }
        }
        schedule(now);
    }

    private void register(String id, SwarmMessage m, long now) {
        Set<Capability> caps = Capability.decode(m.get("caps", ""));
        AgentInfo old = agents.get(id);
        if (old != null && old.assignmentId() != null) {
            // A fresh registration means the agent restarted; whatever it was doing is gone.
            failHeld(old, FailureDiagnosis.agentLost(AgentLifecycle.DISCONNECTED), now);
        }
        AgentInfo a = new AgentInfo(id, caps, now);
        if (m.get("x") != null) a.setPosition(m.getDouble("x", 0), m.getDouble("y", 0), m.getDouble("z", 0));
        agents.put(id, a);
        ledger.add(now, Type.AGENT_REGISTERED, id, null, "caps " + caps);
        a.setLifecycle(AgentLifecycle.READY);
        ledger.add(now, Type.AGENT_READY, id, null, "");
    }

    private void heartbeat(String id, SwarmMessage m, long now) {
        AgentInfo a = agents.get(id);
        if (a == null) {
            // It joined a leader that has since restarted and does not know to register again.
            outbox.send(id, SwarmMessage.of("who"));
            return;
        }
        a.touch(now);
        if (m.get("x") != null) a.setPosition(m.getDouble("x", 0), m.getDouble("y", 0), m.getDouble("z", 0));
        Assignment holding = a.assignmentId() == null ? null : assignments.get(a.assignmentId());
        SurveyBoard hb = holding == null ? null : boardOf(holding);
        if (hb != null && a.hasPosition() && holding.state().isHeld()) hb.touch(holding.objective.survey().region(), id, a.x(), a.z(), now);
        a.setHealth((float) m.getDouble("hp", a.health()));
        a.setRisk(m.get("risk", ""));
        boolean alive = !"0".equals(m.get("alive", "1"));
        if (!alive) {
            loseAgent(id, AgentLifecycle.DEAD, "reported dead", now);
            return;
        }
        if (a.lifecycle().isGone()) {
            a.setLifecycle(AgentLifecycle.READY);
            ledger.add(now, Type.AGENT_READY, id, null, "back after " + "silence");
        }
        String claimed = m.get("a");
        if (claimed != null) {
            Assignment c = assignments.get(claimed);
            // Still at work on something that is over or is now someone else's (it was written off while silent).
            // Work that is merely waiting for an agent is left alone: it may be handed straight back.
            if (c == null || c.state().isTerminal() || (c.agentId() != null && !id.equals(c.agentId()))) {
                outbox.send(id, SwarmMessage.of("cancel", "a", claimed));
            }
        }
        Assignment held = a.assignmentId() == null ? null : assignments.get(a.assignmentId());
        if (held != null && (held.state() == AssignmentState.ACCEPTED || held.state() == AssignmentState.RUNNING)
                && !held.id.equals(claimed) && now - held.stateSinceMs() > cfg.abandonGraceMs) {
            failHeld(a, FailureDiagnosis.abandoned(), now);
        }
    }

    private void accepted(String id, SwarmMessage m, long now) {
        Assignment a = ownedBy(id, m.get("a"), now, "accept");
        if (a == null || a.state() != AssignmentState.ASSIGNED) return;
        a.move(AssignmentState.ACCEPTED, now);
        ledger.add(now, Type.TASK_ACCEPTED, id, a.id, "");
    }

    private void rejected(String id, SwarmMessage m, long now) {
        Assignment a = ownedBy(id, m.get("a"), now, "reject");
        if (a == null) return;
        ledger.add(now, Type.TASK_REJECTED, id, a.id, m.get("why", ""));
        fail(a, FailureDiagnosis.rejected(m.get("why", "no reason")), now);
    }

    private void progress(String id, SwarmMessage m, long now) {
        Assignment a = ownedBy(id, m.get("a"), now, "prog");
        if (a == null) return;
        if (a.state() == AssignmentState.ASSIGNED) {
            // Progress implies acceptance; the accept may simply have been lost.
            a.move(AssignmentState.ACCEPTED, now);
            ledger.add(now, Type.TASK_ACCEPTED, id, a.id, "implied by progress");
        }
        if (a.state() == AssignmentState.ACCEPTED) {
            a.move(AssignmentState.RUNNING, now);
            ledger.add(now, Type.TASK_STARTED, id, a.id, "");
        }
        int p = m.getInt("pct", a.progress());
        if (p != a.progress()) {
            a.setProgress(p);
            ledger.add(now, Type.TASK_PROGRESS, id, a.id, p + "%");
        }
    }

    private void result(String id, SwarmMessage m, long now) {
        Assignment a = ownedBy(id, m.get("a"), now, "result");
        if (a == null) return;
        String status = m.get("status", "FAILED");
        SurveyBoard sb = boardOf(a);
        if (status.equals("SUCCEEDED") && sb != null) {
            // The scout's word is not enough: the region must be settled in the merged observations.
            int r = a.objective.survey().region();
            if (sb.todo(r) == 0) {
                freeAgent(id, a);
                sb.release(r);
                a.setProgress(100);
                a.move(AssignmentState.SUCCEEDED, now);
                ledger.add(now, Type.TASK_SUCCEEDED, id, a.id, "verified, region " + r + " settled in merged observations");
                checkSurveyComplete(sb, now);
            } else {
                ledger.add(now, Type.TASK_UNVERIFIED, id, a.id, "region " + r + " still has " + Long.bitCount(sb.todo(r)) + " unobserved chunks");
                fail(a, FailureDiagnosis.unverified(Long.bitCount(sb.todo(r)) + " chunks of region " + r + " unobserved"), now);
            }
            return;
        }
        if (status.equals("SUCCEEDED")) {
            if (a.objective.verify(m.fields())) {
                freeAgent(id, a);
                a.setProgress(100);
                a.move(AssignmentState.SUCCEEDED, now);
                ledger.add(now, Type.TASK_SUCCEEDED, id, a.id, "verified, have=" + m.get("have"));
                reservations.releaseAll(id).forEach(r -> ledger.add(now, Type.RESOURCE_RELEASED, id, a.id, r));
            } else {
                ledger.add(now, Type.TASK_UNVERIFIED, id, a.id, "have=" + m.get("have") + " need=" + a.objective.count);
                fail(a, FailureDiagnosis.unverified("have=" + m.get("have")), now);
            }
        } else if (status.equals("CANCELLED")) {
            fail(a, FailureDiagnosis.reported("CANCELLED_BY_AGENT"), now);
        } else {
            fail(a, FailureDiagnosis.reported(m.get("reason")), now);
        }
    }

    private void observed(String from, SwarmMessage m, long now) {
        String key = m.get("key");
        String value = m.get("value");
        if (key == null || value == null) return;
        if (beliefs.observe(key, value, m.getDouble("conf", 0.7), now, from)) {
            ledger.add(now, Type.WORLD_BELIEF_UPDATED, from, null, key + "=" + value);
        }
    }

    /**
     * Merges one scout's chunk observations. Observations are facts about that client's world, so they are accepted from any
     * live agent for a running survey (even for chunks outside its own region); only the dimension has to match.
     */
    private void covered(String from, SwarmMessage m, long now) {
        AgentInfo ag = agents.get(from);
        SurveyBoard b = surveys.get(m.get("sv", ""));
        if (ag == null || ag.lifecycle().isGone() || b == null || b.closed()) return;
        ag.touch(now);
        if (m.get("x") != null) ag.setPosition(m.getDouble("x", 0), ag.y(), m.getDouble("z", 0));
        Map<Integer, Long> masks = new LinkedHashMap<>();
        String raw = m.get("m", "");
        if (!raw.isEmpty()) {
            for (String part : raw.split(",")) {
                int colon = part.indexOf(':');
                if (colon <= 0) continue;
                try {
                    masks.merge(Integer.parseInt(part.substring(0, colon)), Long.parseUnsignedLong(part.substring(colon + 1), 16), (x, y) -> x | y);
                } catch (NumberFormatException ignored) {
                    // a garbled entry is dropped, the rest still counts
                }
            }
        }
        SurveyBoard.MergeResult r = b.merge(from, m.get("dim", ""), masks, now);
        if (r.rejected()) {
            ledger.add(now, Type.SURVEY_REJECTED, from, null, b.id() + ": observation in dimension '" + m.get("dim", "") + "' ignored, survey is " + b.area().dimension());
            return;
        }
        if (ag.hasPosition()) b.metrics().leg(b.legSince(from, ag.x(), ag.z()), r.fresh(), r.duplicate());
        if (r.fresh() > 0 || r.duplicate() > 0) {
            ledger.add(now, Type.SURVEY_COVERAGE, from, null, b.id() + " +" + r.fresh() + " new, " + r.duplicate() + " already seen; "
                    + b.snapshot().observed() + "/" + b.snapshot().total() + " observed");
        }
        for (int region : r.touchedHeld()) {
            Assignment held = surveyRegions.get(b.id() + "/" + region);
            String key = b.id() + "/" + region;
            if (held == null || held.agentId() == null || !held.state().isHeld()) continue;
            if (now - lastUpd.getOrDefault(key, Long.MIN_VALUE / 2) < cfg.surveyUpdMs && b.todo(region) != 0) continue;
            lastUpd.put(key, now);
            outbox.send(held.agentId(), SwarmMessage.of("upd", "a", held.id, "todo", Long.toHexString(b.todo(region))));
        }
        checkSurveyComplete(b, now);
    }

    private void checkSurveyComplete(SurveyBoard b, long now) {
        if (!b.done() || !surveysCompleted.add(b.id())) return;
        SurveyBoard.Snapshot s = b.snapshot();
        ledger.add(now, Type.SURVEY_COMPLETE, null, null, b.id() + " observed " + s.observed() + "/" + s.total()
                + (s.unreachable() > 0 ? ", " + s.unreachable() + " unreachable" : "") + "; " + b.metrics().summary());
    }

    /** The message must come from the agent that currently holds the assignment; stale ones are dropped. */
    private Assignment ownedBy(String agent, String assignmentId, long now, String op) {
        Assignment a = assignmentId == null ? null : assignments.get(assignmentId);
        if (a == null || a.state().isTerminal() || !agent.equals(a.agentId())) return null;
        agents.computeIfPresent(agent, (k, v) -> { v.touch(now); return v; });
        return a;
    }

    // ---- time ----

    public synchronized void tick(long now) {
        for (AgentInfo a : new ArrayList<>(agents.values())) {
            if (!a.lifecycle().isGone() && now - a.lastSeenMs() > cfg.heartbeatTimeoutMs) {
                loseAgent(a.id, AgentLifecycle.DISCONNECTED, "heartbeat silent " + (now - a.lastSeenMs()) + "ms", now);
            }
        }
        for (Assignment a : new ArrayList<>(assignments.values())) {
            long held = now - a.stateSinceMs();
            if (a.state() == AssignmentState.ASSIGNED && held > cfg.offerTimeoutMs) {
                // The agent may have taken the offer and only the accept was lost; do not leave it working unowned.
                if (a.agentId() != null) outbox.send(a.agentId(), SwarmMessage.of("cancel", "a", a.id));
                fail(a, FailureDiagnosis.offerTimeout(), now);
            } else if ((a.state() == AssignmentState.ACCEPTED || a.state() == AssignmentState.RUNNING)
                    && now - startOfHold(a) > cfg.runTimeoutMs) {
                if (a.agentId() != null) outbox.send(a.agentId(), SwarmMessage.of("cancel", "a", a.id));
                fail(a, FailureDiagnosis.runTimeout(), now);
            }
        }
        for (SurveyBoard b : new ArrayList<>(surveys.values())) {
            if (b.closed() || b.done()) continue;
            for (int r : b.stale(now, cfg.surveyStallMs)) {
                Assignment a = surveyRegions.get(b.id() + "/" + r);
                if (a == null || !a.state().isHeld()) continue;
                ledger.add(now, Type.SURVEY_CLAIM_EXPIRED, a.agentId(), a.id, b.id() + " region " + r + " claim expired: no new chunk and no movement");
                if (a.agentId() != null) outbox.send(a.agentId(), SwarmMessage.of("cancel", "a", a.id));
                fail(a, FailureDiagnosis.stalled(cfg.surveyStallMs), now);
            }
        }
        schedule(now);
    }

    private final Map<String, Long> heldSince = new LinkedHashMap<>();

    private long startOfHold(Assignment a) { return heldSince.getOrDefault(a.id, a.stateSinceMs()); }

    // ---- failure and recovery ----

    private void loseAgent(String id, AgentLifecycle how, String why, long now) {
        AgentInfo a = agents.get(id);
        if (a == null || a.lifecycle().isGone()) return;
        a.setLifecycle(how);
        ledger.add(now, how == AgentLifecycle.DEAD ? Type.AGENT_DIED : Type.AGENT_DISCONNECTED, id, a.assignmentId(), why);
        if (a.assignmentId() != null) failHeld(a, FailureDiagnosis.agentLost(how), now);
        for (String r : reservations.releaseAll(id)) ledger.add(now, Type.RESOURCE_RELEASED, id, null, r + " (agent gone)");
    }

    private void failHeld(AgentInfo a, FailureDiagnosis d, long now) {
        Assignment as = assignments.get(a.assignmentId());
        if (as != null) fail(as, d, now);
    }

    /** Records the diagnosis, frees the agent and resources, and either requeues or ends the assignment. */
    private void fail(Assignment a, FailureDiagnosis d, long now) {
        String agent = a.agentId();
        SurveyBoard sb = boardOf(a);
        if (sb != null) {
            // The claim ends with the attempt; what the scout already saw stays observed, only the rest is reassigned.
            sb.release(a.objective.survey().region());
            sb.recordFailure(a.objective.survey().region(), agent == null ? "-" : agent, d.kind() + ": " + d.detail());
        }
        if (agent != null) freeAgent(agent, a);
        ledger.add(now, Type.TASK_FAILED, agent, a.id, d.kind() + ": " + d.detail());
        ledger.add(now, Type.FAILURE_DIAGNOSED, agent, a.id,
                (d.blameAgent() ? "agent excluded from this work" : "agent not blamed")
                        + (d.restart() ? ", restart from scratch" : ", resume"));
        a.setFailure(d.kind() + ": " + d.detail());
        if (d.blameAgent() && agent != null) a.excluded().add(agent);
        a.release(now);
        if (d.kind() == FailureDiagnosis.Kind.REPORTED && !d.blameAgent()) a.setNotBefore(now + cfg.situationalRetryDelayMs);

        if (a.attempts() >= cfg.maxAttempts) {
            a.move(AssignmentState.FAILED, now);
            ledger.add(now, Type.TASK_FAILED, null, a.id, "gave up after " + a.attempts() + " attempts");
            if (sb != null) giveUpRegion(a, sb, "gave up after " + a.attempts() + " attempts", now);
            return;
        }
        boolean anyone = false;
        for (AgentInfo x : agents.values()) if (Scheduler.capable(x, a)) { anyone = true; break; }
        if (!anyone && d.blameAgent()) {
            a.move(AssignmentState.FAILED, now);
            ledger.add(now, Type.TASK_FAILED, null, a.id, "no remaining capable agent");
            if (sb != null) giveUpRegion(a, sb, "no capable scout left to try it", now);
            return;
        }
        ledger.add(now, Type.TASK_REASSIGNED, agent, a.id, "requeued, " + (anyone ? "candidates remain" : "waiting for an agent"));
    }

    /** Bounded retries are spent: what is still unknown in the region is marked unreachable, with the reason. */
    private void giveUpRegion(Assignment a, SurveyBoard sb, String why, long now) {
        int r = a.objective.survey().region();
        List<String> tries = sb.failures(r);
        String last = tries.isEmpty() ? "no report" : tries.get(tries.size() - 1);
        String reason = last + " (" + why + "; " + tries.size() + " failed tries: " + String.join(" | ", tries) + ")";
        int n = sb.markUnreachable(r, reason);
        ledger.add(now, Type.SURVEY_UNREACHABLE, null, a.id, sb.id() + " region " + r + ": " + n + " chunks unreachable, " + reason);
        checkSurveyComplete(sb, now);
    }

    private void freeAgent(String agentId, Assignment a) {
        AgentInfo ag = agents.get(agentId);
        if (ag != null) {
            ag.setAssignment(null);
            if (!ag.lifecycle().isGone()) ag.setLifecycle(AgentLifecycle.READY);
        }
        for (String r : a.reservations()) reservations.release(r, agentId);
    }

    // ---- assignment ----

    private void schedule(long now) {
        List<Assignment> pending = new ArrayList<>();
        for (Assignment a : assignments.values()) if (a.state() == AssignmentState.UNASSIGNED) pending.add(a);
        pending.sort(Comparator.comparingInt((Assignment a) -> -a.priority));
        for (Assignment a : pending) {
            if (a.objective.isSurvey()) continue; // matched below, jointly with every idle scout
            if (now < a.notBeforeMs()) continue;
            Scheduler.Choice c = Scheduler.choose(agents.values(), a);
            if (c == null) continue;
            AgentInfo ag = c.agent();
            if (!reserveFor(a, ag, now)) continue;
            ag.setAssignment(a.id);
            ag.setLifecycle(AgentLifecycle.BUSY);
            a.assign(ag.id, now);
            heldSince.put(a.id, now);
            ledger.add(now, Type.TASK_ASSIGNED, ag.id, a.id, c.why());
            outbox.send(ag.id, SwarmMessage.of("offer", "a", a.id, "item", a.objective.item, "n", String.valueOf(a.objective.count)));
        }
        scheduleSurveys(now);
    }

    /**
     * Pairs idle scouts with open regions one best pair at a time: the scout nearest to unobserved ground of a region,
     * less a penalty for sitting beside another scout's region. Recomputed after every assignment (offers can re-enter),
     * and it does nothing unless a scout is idle and a region is open, so the cost follows active regions, not time.
     */
    private void scheduleSurveys(long now) {
        closeCoveredRegions(now);
        for (int guard = 0; guard < SurveyGrid.MAX_REGIONS * cfg.maxActiveSurveys; guard++) {
            Assignment bestA = null;
            AgentInfo bestG = null;
            double bestCost = Double.POSITIVE_INFINITY;
            for (Assignment a : assignments.values()) {
                if (!a.objective.isSurvey() || a.state() != AssignmentState.UNASSIGNED || now < a.notBeforeMs()) continue;
                SurveyBoard b = boardOf(a);
                if (b == null || b.closed()) continue;
                int r = a.objective.survey().region();
                for (AgentInfo g : agents.values()) {
                    if (!Scheduler.eligible(g, a)) continue;
                    double cost = (g.hasPosition() ? b.distanceToUnknown(r, g.x(), g.z()) : 0) + b.crowding(r, g.id, cfg.surveyCrowdWeight);
                    boolean better = cost < bestCost
                            || (cost == bestCost && bestA != null && (g.id.compareTo(bestG.id) < 0 || (g.id.equals(bestG.id) && r < bestA.objective.survey().region())));
                    if (better) {
                        bestA = a;
                        bestG = g;
                        bestCost = cost;
                    }
                }
            }
            if (bestA == null) return;
            SurveyBoard b = boardOf(bestA);
            int r = bestA.objective.survey().region();
            if (!b.claim(r, bestG.id, now)) return; // cannot happen for an unassigned region with work left; never spin
            bestG.setAssignment(bestA.id);
            bestG.setLifecycle(AgentLifecycle.BUSY);
            bestA.assign(bestG.id, now);
            heldSince.put(bestA.id, now);
            ledger.add(now, Type.TASK_ASSIGNED, bestG.id, bestA.id, String.format(java.util.Locale.ROOT, "%s region %d: %d chunks left, cost %.0f",
                    b.id(), r, Long.bitCount(b.todo(r)), bestCost));
            SwarmMessage offer = SwarmMessage.of("offer", "a", bestA.id);
            bestA.objective.survey().withTodo(b.todo(r)).writeTo(offer); // only what is still unknown, not the original region
            outbox.send(bestG.id, offer);
        }
    }

    /** An open region that other scouts already saw in full needs no visit. */
    private void closeCoveredRegions(long now) {
        for (Assignment a : assignments.values()) {
            if (!a.objective.isSurvey() || a.state() != AssignmentState.UNASSIGNED) continue;
            SurveyBoard b = boardOf(a);
            if (b == null || b.closed()) continue;
            int r = a.objective.survey().region();
            if (b.todo(r) == 0 && b.state(r) == SurveyBoard.RegionState.OBSERVED) {
                a.setProgress(100);
                a.move(AssignmentState.SUCCEEDED, now);
                ledger.add(now, Type.TASK_SUCCEEDED, null, a.id, "region " + r + " already fully observed by other scouts");
                checkSurveyComplete(b, now);
            }
        }
    }

    private boolean reserveFor(Assignment a, AgentInfo ag, long now) {
        List<String> taken = new ArrayList<>();
        for (String r : a.reservations()) {
            if (!reservations.reserve(r, ag.id, a.objective.describe())) {
                ledger.add(now, Type.RESOURCE_CONTENDED, ag.id, a.id, r + " held by " + reservations.holder(r));
                for (String t : taken) reservations.release(t, ag.id);
                return false;
            }
            taken.add(r);
            ledger.add(now, Type.RESOURCE_RESERVED, ag.id, a.id, r);
        }
        return true;
    }

    /** One line per agent and assignment, for @swarm status. */
    public synchronized List<String> status() {
        List<String> out = new ArrayList<>();
        for (AgentInfo a : agents.values()) out.add("agent " + a);
        for (SurveyBoard b : surveys.values()) {
            SurveyBoard.Snapshot s = b.snapshot();
            out.add("survey " + b.id() + " " + b.area() + ": observed " + s.observed() + " claimed " + s.claimed() + " unknown " + s.unknown()
                    + " unreachable " + s.unreachable() + (b.closed() ? " [stopped]" : b.done() ? " [complete]" : "") + " (@swarm survey status " + b.id() + ")");
        }
        for (Assignment a : assignments.values()) {
            if (a.objective.isSurvey()) continue; // one line per survey above, not one per region
            out.add("task " + a + (a.failure().isEmpty() ? "" : " last failure: " + a.failure())
                    + " attempts=" + a.attempts() + " progress=" + a.progress() + "%");
        }
        for (ReservationTable.Claim c : reservations.all()) out.add("reserved " + c.resource() + " by " + c.owner() + " for " + c.purpose());
        return out;
    }
}
