package com.huuda.orbitallaser.strike;

/**
 * Persisted states of an active strike. IDLE and COOLDOWN are not stored here:
 * IDLE means "no strike for this player" and COOLDOWN lives in {@link StrikeManager}'s cooldown map.
 */
public enum StrikeState {
    CHARGING,
    STRIKING;

    public static StrikeState byName(String name) {
        for (StrikeState s : values()) {
            if (s.name().equals(name)) {
                return s;
            }
        }
        return CHARGING;
    }
}
