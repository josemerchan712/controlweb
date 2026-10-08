package dev.josemariamerchan.controlweb.publicstatus;

import java.util.Collection;

import dev.josemariamerchan.controlweb.monitoring.domain.SiteState;

/**
 * The simplified state shown to the world. It is derived from the internal {@link SiteState} (step 8) and
 * deliberately says less: no PENDING, no PAUSED.
 */
public enum PublicState {
	UP, DEGRADED, DOWN, UNKNOWN;

	/**
	 * PENDING (never checked) is UNKNOWN. PAUSED sites are never shown, so receiving one here is a programming
	 * error: it is also UNKNOWN rather than a leak of the fact that a site is paused.
	 */
	public static PublicState from(SiteState state) {
		return switch (state) {
			case UP -> UP;
			case DEGRADED -> DEGRADED;
			case DOWN -> DOWN;
			case PENDING, PAUSED -> UNKNOWN;
		};
	}

	/**
	 * The overall state of the public sites. Sites whose state is UNKNOWN are ignored if at least one site has a
	 * known state:
	 * <ul>
	 * <li>no sites, or none with a known state: UNKNOWN;</li>
	 * <li>every known site DOWN: DOWN;</li>
	 * <li>some DOWN or some DEGRADED, but not all down: DEGRADED (a partial outage);</li>
	 * <li>otherwise UP.</li>
	 * </ul>
	 */
	public static PublicState overall(Collection<PublicState> siteStates) {
		long known = siteStates.stream().filter(state -> state != UNKNOWN).count();
		if (known == 0) {
			return UNKNOWN;
		}
		long down = siteStates.stream().filter(state -> state == DOWN).count();
		if (down == known) {
			return DOWN;
		}
		boolean anyTrouble = down > 0 || siteStates.stream().anyMatch(state -> state == DEGRADED);
		return anyTrouble ? DEGRADED : UP;
	}
}
