package dev.josemariamerchan.controlweb.incident.rules;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import dev.josemariamerchan.controlweb.incident.domain.IncidentType;

/**
 * Decides which incidents to open and close for a site. Plain Java: no Spring, no database, no clock
 * and no memory between calls. Everything it needs comes in a {@link RuleInput} (itself read from the
 * database), which has two consequences:
 * <ul>
 * <li>a restart changes nothing: the state of the world IS the database;</li>
 * <li>it is idempotent: the open incidents are part of the input, so once a decision has been applied
 * the same evaluation produces no decision, and evaluating the same data twice never duplicates
 * anything.</li>
 * </ul>
 *
 * <p>Rules, per type:
 * <ul>
 * <li><b>DOWN</b>: opens after {@code threshold} consecutive failed checks (fewer checks than that never
 * confirm it); closes at the first successful check.</li>
 * <li><b>HIGH_LATENCY</b>: opens when the last {@code latencySamples} SUCCESSFUL checks all take more than
 * {@code threshold} ms (a single spike never opens it; failed checks are not latency); closes when the
 * latest successful check is at or below the threshold.</li>
 * <li><b>SSL_EXPIRING</b>: looks at the certificate expiry of the latest check that has one; opens when fewer
 * than {@code threshold} days are left (an already expired certificate included); closes when the
 * certificate is renewed. Without any certificate information nothing happens. A certificate that has
 * expired also makes the checks fail, which opens a DOWN incident as well: that is expected.</li>
 * <li>A disabled (or missing) rule never opens anything and closes its open incident, so none is left hanging.</li>
 * <li>An inactive site is not evaluated: it opens nothing and its open incidents are closed.</li>
 * </ul>
 */
public class RuleEngine {

	/** Number of recent checks the caller should load, at least. */
	public static final int MIN_WINDOW = 50;

	public static final String SITE_DEACTIVATED = "Site deactivated";
	public static final String RULE_DISABLED = "Rule disabled";

	private final int latencySamples;

	/** @param latencySamples how many consecutive successful checks must be slow before HIGH_LATENCY opens */
	public RuleEngine(int latencySamples) {
		if (latencySamples < 1) {
			throw new IllegalArgumentException("latencySamples must be at least 1");
		}
		this.latencySamples = latencySamples;
	}

	/** How many recent checks to load for these rules: enough for the DOWN threshold, and never fewer than 50. */
	public int windowSize(List<RuleConfig> rules) {
		int downThreshold = rules.stream().filter(rule -> rule.type() == IncidentType.DOWN)
				.mapToInt(RuleConfig::threshold).max().orElse(0);
		return Math.max(MIN_WINDOW, Math.max(downThreshold, latencySamples));
	}

	public List<Decision> evaluate(RuleInput input) {
		List<Decision> decisions = new ArrayList<>();

		if (!input.siteActive()) {
			input.openIncidents().stream().sorted().forEach(type -> decisions.add(new Decision.Close(type, SITE_DEACTIVATED)));
			return decisions;
		}

		for (IncidentType type : IncidentType.values()) {
			boolean open = input.isOpen(type);
			Optional<RuleConfig> rule = input.ruleFor(type).filter(RuleConfig::enabled);
			if (rule.isEmpty()) {
				if (open) {
					decisions.add(new Decision.Close(type, RULE_DISABLED));
				}
				continue;
			}
			Optional<Decision> decision = switch (type) {
				case DOWN -> down(rule.get(), open, input.checks());
				case HIGH_LATENCY -> highLatency(rule.get(), open, input.checks());
				case SSL_EXPIRING -> sslExpiring(rule.get(), open, input.checks(), input.now());
			};
			decision.ifPresent(decisions::add);
		}
		return decisions;
	}

	private Optional<Decision> down(RuleConfig rule, boolean open, List<CheckSample> checks) {
		if (checks.isEmpty()) {
			return Optional.empty();
		}
		CheckSample latest = checks.get(0);
		if (open) {
			return latest.up()
					? Optional.of(new Decision.Close(IncidentType.DOWN, "Site recovered: the latest check succeeded"
							+ (latest.statusCode() == null ? "" : " (HTTP " + latest.statusCode() + ")")))
					: Optional.empty();
		}
		int consecutiveFailures = 0;
		for (CheckSample check : checks) {
			if (check.up()) {
				break;
			}
			consecutiveFailures++;
		}
		if (consecutiveFailures < rule.threshold()) {
			return Optional.empty();
		}
		String lastError = latest.errorMessage() == null ? "" : " Last error: " + latest.errorMessage();
		return Optional.of(new Decision.Open(IncidentType.DOWN,
				"Site is down: %d consecutive failed checks (threshold %d).%s".formatted(consecutiveFailures,
						rule.threshold(), lastError)));
	}

	private Optional<Decision> highLatency(RuleConfig rule, boolean open, List<CheckSample> checks) {
		List<CheckSample> successes = checks.stream().filter(check -> check.up() && check.latencyMs() != null).toList();
		if (successes.isEmpty()) {
			return Optional.empty();
		}
		int threshold = rule.threshold();
		if (open) {
			int latest = successes.get(0).latencyMs();
			return latest <= threshold
					? Optional.of(new Decision.Close(IncidentType.HIGH_LATENCY,
							"Latency back to normal: %d ms (threshold %d ms)".formatted(latest, threshold)))
					: Optional.empty();
		}
		if (successes.size() < latencySamples) {
			return Optional.empty();
		}
		List<CheckSample> recent = successes.subList(0, latencySamples);
		if (!recent.stream().allMatch(check -> check.latencyMs() > threshold)) {
			return Optional.empty();
		}
		String values = recent.stream().map(check -> String.valueOf(check.latencyMs())).collect(Collectors.joining(", "));
		return Optional.of(new Decision.Open(IncidentType.HIGH_LATENCY,
				"High latency: the last %d successful checks took %s ms (threshold %d ms)".formatted(latencySamples,
						values, threshold)));
	}

	private Optional<Decision> sslExpiring(RuleConfig rule, boolean open, List<CheckSample> checks, Instant now) {
		Optional<Instant> expiry = checks.stream().map(CheckSample::certExpiresAt).filter(date -> date != null).findFirst();
		if (expiry.isEmpty()) {
			return Optional.empty(); // no certificate information: neither open nor close on a guess
		}
		Duration remaining = Duration.between(now, expiry.get());
		boolean expiring = remaining.compareTo(Duration.ofDays(rule.threshold())) < 0;
		if (open) {
			return expiring ? Optional.empty()
					: Optional.of(new Decision.Close(IncidentType.SSL_EXPIRING,
							"Certificate renewed: it now expires on %s (%d days left)".formatted(expiry.get(),
									remaining.toDays())));
		}
		if (!expiring) {
			return Optional.empty();
		}
		String when = remaining.isNegative()
				? "TLS certificate expired on %s".formatted(expiry.get())
				: "TLS certificate expires in %d days, on %s (threshold %d days)".formatted(remaining.toDays(),
						expiry.get(), rule.threshold());
		return Optional.of(new Decision.Open(IncidentType.SSL_EXPIRING, when));
	}
}
