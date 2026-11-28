package baritone.swarm.center.app;

import java.util.Locale;

/**
 * Ostinato: viewport-relative density policy for the Mission control wall. Chooses {@link Tier#CARDS},
 * {@link Tier#ROSTER}, or {@link Tier#CLUSTER} from the visible viewport capacity rather than hardcoded bot-count
 * cutoffs, with downward hysteresis so resizing near a boundary does not flicker.
 */
public final class DensityPolicy {

    public static final int DEFAULT_MIN_CARD_WIDTH = WallView.TILE_WIDTH;
    public static final int DEFAULT_CARD_GAP = 12;
    public static final int DEFAULT_MIN_CARD_HEIGHT = 260;
    public static final double DEFAULT_CARD_SCROLL_MULTIPLIER = 2.0;
    public static final int DEFAULT_ROSTER_ROW_HEIGHT = 36;
    public static final double DEFAULT_ROSTER_SCROLL_MULTIPLIER = 2.5;
    public static final double DEFAULT_HYSTERESIS_FRACTION = 0.15;
    public static final int DEFAULT_VIEWPORT_WIDTH = 1240;
    public static final int DEFAULT_VIEWPORT_HEIGHT = 690;

    public static final DensityPolicy DEFAULT = new DensityPolicy(
            DEFAULT_MIN_CARD_WIDTH,
            DEFAULT_CARD_GAP,
            DEFAULT_MIN_CARD_HEIGHT,
            DEFAULT_CARD_SCROLL_MULTIPLIER,
            DEFAULT_ROSTER_ROW_HEIGHT,
            DEFAULT_ROSTER_SCROLL_MULTIPLIER,
            DEFAULT_HYSTERESIS_FRACTION);

    public enum Tier {
        CARDS("cards", "Cards"),
        ROSTER("roster", "Roster"),
        CLUSTER("cluster", "Cluster");

        private final String wire;
        private final String label;

        Tier(String wire, String label) {
            this.wire = wire;
            this.label = label;
        }

        public String wire() {
            return wire;
        }

        public String label() {
            return label;
        }
    }

    public enum Override {
        AUTO("auto", "Auto", null),
        CARDS("cards", "Cards", Tier.CARDS),
        ROSTER("roster", "Roster", Tier.ROSTER),
        CLUSTER("cluster", "Cluster", Tier.CLUSTER);

        private final String wire;
        private final String label;
        private final Tier forced;

        Override(String wire, String label, Tier forced) {
            this.wire = wire;
            this.label = label;
            this.forced = forced;
        }

        public String wire() {
            return wire;
        }

        public String label() {
            return label;
        }

        public Tier forcedTier() {
            return forced;
        }

        public static Override parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return AUTO;
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "cards", "card" -> CARDS;
                case "roster", "table" -> ROSTER;
                case "cluster", "heatmap" -> CLUSTER;
                default -> AUTO;
            };
        }
    }

    private final int minCardWidth;
    private final int cardGap;
    private final int minCardHeight;
    private final double cardScrollMultiplier;
    private final int rosterRowHeight;
    private final double rosterScrollMultiplier;
    private final double hysteresisFraction;

    public DensityPolicy(int minCardWidth, int cardGap, int minCardHeight, double cardScrollMultiplier,
                         int rosterRowHeight, double rosterScrollMultiplier, double hysteresisFraction) {
        if (minCardWidth <= 0 || minCardHeight <= 0 || rosterRowHeight <= 0) {
            throw new IllegalArgumentException("cell dimensions must be positive");
        }
        if (cardGap < 0 || cardScrollMultiplier <= 0.0 || rosterScrollMultiplier <= 0.0) {
            throw new IllegalArgumentException("gap must be >= 0 and multipliers must be positive");
        }
        if (hysteresisFraction < 0.0 || hysteresisFraction >= 0.5) {
            throw new IllegalArgumentException("hysteresisFraction must be in [0.0, 0.5)");
        }
        this.minCardWidth = minCardWidth;
        this.cardGap = cardGap;
        this.minCardHeight = minCardHeight;
        this.cardScrollMultiplier = cardScrollMultiplier;
        this.rosterRowHeight = rosterRowHeight;
        this.rosterScrollMultiplier = rosterScrollMultiplier;
        this.hysteresisFraction = hysteresisFraction;
    }

    public int minCardWidth() {
        return minCardWidth;
    }

    public int minCardHeight() {
        return minCardHeight;
    }

    public int rosterRowHeight() {
        return rosterRowHeight;
    }

    public int visibleCardColumns(int viewportWidth) {
        int w = effectiveWidth(viewportWidth);
        return Math.max(1, (w + cardGap) / (minCardWidth + cardGap));
    }

    public int visibleCardRows(int viewportHeight) {
        int h = effectiveHeight(viewportHeight);
        return Math.max(1, h / minCardHeight);
    }

    /** Maximum number of bots shown in {@link Tier#CARDS} for this viewport before transitioning to {@link Tier#ROSTER}. */
    public int cardsCapacity(int viewportWidth, int viewportHeight) {
        int cols = visibleCardColumns(viewportWidth);
        int rows = visibleCardRows(viewportHeight);
        return Math.max(1, (int) Math.floor(cols * rows * cardScrollMultiplier));
    }

    public int visibleRosterRows(int viewportHeight) {
        int h = effectiveHeight(viewportHeight);
        return Math.max(1, h / rosterRowHeight);
    }

    /** Maximum number of bots shown in {@link Tier#ROSTER} for this viewport before transitioning to {@link Tier#CLUSTER}. */
    public int rosterCapacity(int viewportWidth, int viewportHeight) {
        int cardsCap = cardsCapacity(viewportWidth, viewportHeight);
        int visibleRows = visibleRosterRows(viewportHeight);
        int fromRows = (int) Math.floor(visibleRows * rosterScrollMultiplier);
        int minRosterBand = cardsCap + Math.max(4, visibleCardColumns(viewportWidth));
        return Math.max(minRosterBand, fromRows);
    }

    public int hysteresisMargin(int capacity) {
        if (capacity <= 1 || hysteresisFraction <= 0.0) {
            return 0;
        }
        return Math.max(1, (int) Math.ceil(capacity * hysteresisFraction));
    }

    /** Evaluates the active tier without hysteresis (initial layout or stateless query). */
    public Tier evaluate(int botCount, int viewportWidth, int viewportHeight, Override override) {
        return evaluate(botCount, viewportWidth, viewportHeight, override, null);
    }

    /**
     * Evaluates the active tier from {@code (botCount, viewportWidth, viewportHeight, override)} using
     * {@code currentTier} to apply downward hysteresis near capacity boundaries.
     */
    public Tier evaluate(int botCount, int viewportWidth, int viewportHeight, Override override, Tier currentTier) {
        if (override != null && override.forcedTier() != null) {
            return override.forcedTier();
        }
        if (botCount <= 0) {
            return Tier.CARDS;
        }
        int cardsCap = cardsCapacity(viewportWidth, viewportHeight);
        int rosterCap = rosterCapacity(viewportWidth, viewportHeight);
        if (currentTier == null) {
            if (botCount <= cardsCap) {
                return Tier.CARDS;
            }
            return botCount <= rosterCap ? Tier.ROSTER : Tier.CLUSTER;
        }
        int cardsDown = Math.max(1, cardsCap - hysteresisMargin(cardsCap));
        int rosterDown = Math.max(cardsCap, rosterCap - hysteresisMargin(rosterCap));
        return switch (currentTier) {
            case CARDS -> {
                if (botCount > rosterCap) {
                    yield Tier.CLUSTER;
                }
                yield botCount > cardsCap ? Tier.ROSTER : Tier.CARDS;
            }
            case ROSTER -> {
                if (botCount > rosterCap) {
                    yield Tier.CLUSTER;
                }
                yield botCount <= cardsDown ? Tier.CARDS : Tier.ROSTER;
            }
            case CLUSTER -> {
                if (botCount <= cardsDown) {
                    yield Tier.CARDS;
                }
                yield botCount <= rosterDown ? Tier.ROSTER : Tier.CLUSTER;
            }
        };
    }

    private static int effectiveWidth(int viewportWidth) {
        return viewportWidth > 0 ? viewportWidth : DEFAULT_VIEWPORT_WIDTH;
    }

    private static int effectiveHeight(int viewportHeight) {
        return viewportHeight > 0 ? viewportHeight : DEFAULT_VIEWPORT_HEIGHT;
    }
}
