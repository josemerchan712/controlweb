package dev.josemariamerchan.controlweb.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import dev.josemariamerchan.controlweb.incident.domain.IncidentType;
import dev.josemariamerchan.controlweb.incident.rules.CheckSample;
import dev.josemariamerchan.controlweb.incident.rules.Decision;
import dev.josemariamerchan.controlweb.incident.rules.RuleConfig;
import dev.josemariamerchan.controlweb.incident.rules.RuleEngine;
import dev.josemariamerchan.controlweb.incident.rules.RuleInput;

/**
 * The rule engine is plain Java, so these are plain tests: data in, decisions out. Check histories are
 * written OLDEST to NEWEST, as a person would read them: "U" = successful check, "F" = failed check
 * (with a high latency, to prove failures never count as latency), a number = successful check with
 * that latency in ms, "-" = no checks at all.
 */
class RuleEngineTest {

	private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");

	// ---- helpers ----

	private static CheckSample sample(String token) {
		return switch (token) {
			case "U" -> new CheckSample(NOW, true, 200, 100, null, null);
			case "F" -> new CheckSample(NOW, false, 500, 5000, "HTTP 500", null);
			default -> new CheckSample(NOW, true, 200, Integer.parseInt(token), null, null);
		};
	}

	/** Parses "U F F" into a newest-first list, the order the engine expects. */
	private static List<CheckSample> history(String oldestToNewest) {
		List<CheckSample> newestFirst = new ArrayList<>();
		if (!oldestToNewest.equals("-")) {
			for (String token : oldestToNewest.trim().split("\\s+")) {
				newestFirst.add(0, sample(token));
			}
		}
		return newestFirst;
	}

	private static List<RuleConfig> only(IncidentType type, int threshold) {
		return List.of(new RuleConfig(type, threshold, true));
	}

	private static RuleInput input(List<RuleConfig> rules, List<CheckSample> checks, Set<IncidentType> open) {
		return new RuleInput(NOW, true, rules, checks, open);
	}

	private static Set<IncidentType> open(boolean isOpen, IncidentType type) {
		return isOpen ? EnumSet.of(type) : EnumSet.noneOf(IncidentType.class);
	}

	/** NONE, OPEN or CLOSE: what the engine decided for a type. */
	private static String outcome(List<Decision> decisions, IncidentType type) {
		List<String> outcomes = decisions.stream().filter(d -> d.type() == type)
				.map(d -> d instanceof Decision.Open ? "OPEN" : "CLOSE").toList();
		assertThat(outcomes).as("at most one decision per type").hasSizeLessThanOrEqualTo(1);
		return outcomes.isEmpty() ? "NONE" : outcomes.get(0);
	}

	private static Instant cert(double daysFromNow) {
		return NOW.plus(Duration.ofMinutes((long) (daysFromNow * 24 * 60)));
	}

	// ---- DOWN ----

	@ParameterizedTest(name = "DOWN: {0}")
	@CsvSource(delimiter = '|', textBlock = """
			opens after N consecutive failures                | 2 | F F       | false | OPEN
			one failure is not confirmed (fewer than N)       | 2 | F         | false | NONE
			no checks at all                                  | 2 | -         | false | NONE
			failures separated by a success are not consecutive | 3 | F F U F F | false | NONE
			only the trailing failures count                  | 3 | U F F    | false | NONE
			three in a row after a success                    | 3 | U F F F  | false | OPEN
			a threshold of 1 opens on the first failure       | 1 | F         | false | OPEN
			more failures than N still opens                  | 2 | F F F F   | false | OPEN
			already open: never opened twice                  | 2 | F F       | true  | NONE
			already open and still failing                    | 2 | F F F F   | true  | NONE
			closes at the first successful check              | 2 | F F U     | true  | CLOSE
			closes even if it just got one success            | 3 | F F F F F U | true | CLOSE
			a single check, successful, closes                | 2 | U         | true  | CLOSE
			healthy site, nothing to do                       | 2 | U U U     | false | NONE
			flapping F U F is not confirmed                   | 2 | F U F     | false | NONE
			""")
	void down(String name, int threshold, String checks, boolean alreadyOpen, String expected) {
		List<Decision> decisions = new RuleEngine(3)
				.evaluate(input(only(IncidentType.DOWN, threshold), history(checks), open(alreadyOpen, IncidentType.DOWN)));

		assertThat(outcome(decisions, IncidentType.DOWN)).isEqualTo(expected);
	}

	@Test
	@DisplayName("DOWN details say how many checks failed and the last error")
	void downDetails() {
		List<CheckSample> checks = List.of(
				new CheckSample(NOW, false, null, null, "Connection failed: refused or unreachable", null),
				new CheckSample(NOW, false, null, null, "Request timed out after 10s", null));

		Decision.Open open = (Decision.Open) new RuleEngine(3)
				.evaluate(input(only(IncidentType.DOWN, 2), checks, open(false, IncidentType.DOWN))).get(0);

		assertThat(open.details()).contains("2 consecutive failed checks")
				.contains("Last error: Connection failed: refused or unreachable");
	}

	@Test
	@DisplayName("DOWN recovery reason mentions the successful check")
	void downRecoveryReason() {
		Decision.Close close = (Decision.Close) new RuleEngine(3)
				.evaluate(input(only(IncidentType.DOWN, 2), history("F F U"), open(true, IncidentType.DOWN))).get(0);

		assertThat(close.reason()).containsIgnoringCase("recovered").contains("HTTP 200");
	}

	// ---- HIGH_LATENCY ----

	@ParameterizedTest(name = "HIGH_LATENCY: {0}")
	@CsvSource(delimiter = '|', textBlock = """
			opens when the last 3 successful checks are all slow    | 2000 | 3 | 2500 2600 2700            | false | OPEN
			two slow checks are not enough                          | 2000 | 3 | 2500 2600                 | false | NONE
			a single spike never opens                              | 2000 | 3 | 100 100 5000              | false | NONE
			a spike in the middle breaks the run                    | 2000 | 3 | 2500 100 2600 2700       | false | NONE
			slow checks after a fast one                            | 2000 | 3 | 100 2500 2600 2700       | false | OPEN
			failed checks are not latency (skipped)                 | 2000 | 3 | 2500 2600 F 2700         | false | OPEN
			failures with huge latency never open it                | 2000 | 3 | F F F                     | false | NONE
			one success among failures is not enough                | 2000 | 3 | 2500 F F F                | false | NONE
			exactly at the threshold is not slow                    | 2000 | 3 | 2000 2000 2000           | false | NONE
			one millisecond over is slow                            | 2000 | 3 | 2001 2001 2001           | false | OPEN
			already open: never opened twice                        | 2000 | 3 | 2500 2600 2700           | true  | NONE
			already open and still slow                             | 2000 | 3 | 2500 2600 2700 2800      | true  | NONE
			closes when a successful check is below the threshold   | 2000 | 3 | 2500 2600 2700 500       | true  | CLOSE
			closes when a successful check is exactly at it         | 2000 | 3 | 2500 2600 2700 2000      | true  | CLOSE
			stays open at 1 ms over                                 | 2000 | 3 | 2500 2600 2700 2001      | true  | NONE
			a failed check does not close it                        | 2000 | 3 | 2500 2600 2700 F         | true  | NONE
			closes on the latest successful check despite a failure | 2000 | 3 | 2500 2600 2700 500 F     | true  | CLOSE
			no successful checks at all                             | 2000 | 3 | -                         | false | NONE
			the number of samples is configurable (2)               | 2000 | 2 | 2500 2600                 | false | OPEN
			the number of samples is configurable (5)               | 2000 | 5 | 2500 2600 2700 2800       | false | NONE
			five samples all slow                                   | 2000 | 5 | 2500 2600 2700 2800 2900 | false | OPEN
			""")
	void highLatency(String name, int threshold, int samples, String checks, boolean alreadyOpen, String expected) {
		List<Decision> decisions = new RuleEngine(samples).evaluate(
				input(only(IncidentType.HIGH_LATENCY, threshold), history(checks), open(alreadyOpen, IncidentType.HIGH_LATENCY)));

		assertThat(outcome(decisions, IncidentType.HIGH_LATENCY)).isEqualTo(expected);
	}

	@Test
	@DisplayName("HIGH_LATENCY details list the slow measurements and the threshold")
	void highLatencyDetails() {
		Decision.Open open = (Decision.Open) new RuleEngine(3).evaluate(input(only(IncidentType.HIGH_LATENCY, 2000),
				history("2500 2600 2700"), open(false, IncidentType.HIGH_LATENCY))).get(0);

		assertThat(open.details()).contains("last 3 successful checks").contains("2700, 2600, 2500").contains("threshold 2000 ms");
	}

	// ---- SSL_EXPIRING ----

	@ParameterizedTest(name = "SSL_EXPIRING: {0}")
	@CsvSource(delimiter = '|', textBlock = """
			opens when fewer days than the threshold are left | 14 | 10     | false | OPEN
			just under the threshold opens                    | 14 | 13.99  | false | OPEN
			exactly the threshold does not open               | 14 | 14     | false | NONE
			plenty of time                                    | 14 | 90     | false | NONE
			an already expired certificate opens              | 14 | -2     | false | OPEN
			expires within the hour                           | 14 | 0.02   | false | OPEN
			already open: never opened twice                  | 14 | 10     | true  | NONE
			still expiring and open                           | 14 | 1      | true  | NONE
			closes when the certificate is renewed            | 14 | 90     | true  | CLOSE
			closes exactly at the threshold                   | 14 | 14     | true  | CLOSE
			an expired certificate keeps it open              | 14 | -2     | true  | NONE
			no certificate information: no guess              | 14 | none   | false | NONE
			no certificate information does not close it      | 14 | none   | true  | NONE
			a different threshold is respected                | 30 | 20     | false | OPEN
			""")
	void sslExpiring(String name, int thresholdDays, String daysLeft, boolean alreadyOpen, String expected) {
		Instant expiry = daysLeft.equals("none") ? null : cert(Double.parseDouble(daysLeft));
		List<CheckSample> checks = List.of(new CheckSample(NOW, true, 200, 100, null, expiry));

		List<Decision> decisions = new RuleEngine(3)
				.evaluate(input(only(IncidentType.SSL_EXPIRING, thresholdDays), checks, open(alreadyOpen, IncidentType.SSL_EXPIRING)));

		assertThat(outcome(decisions, IncidentType.SSL_EXPIRING)).isEqualTo(expected);
	}

	@Test
	@DisplayName("SSL uses the latest check that HAS a certificate date, skipping newer checks without one")
	void sslUsesLatestCheckWithCertificate() {
		List<CheckSample> checks = List.of(
				new CheckSample(NOW, false, null, null, "Request timed out after 10s", null), // newest: no cert
				new CheckSample(NOW, true, 200, 100, null, cert(5)), // latest with a cert: 5 days left
				new CheckSample(NOW, true, 200, 100, null, cert(90)));

		assertThat(outcome(new RuleEngine(3).evaluate(input(only(IncidentType.SSL_EXPIRING, 14), checks,
				open(false, IncidentType.SSL_EXPIRING))), IncidentType.SSL_EXPIRING)).isEqualTo("OPEN");
	}

	@Test
	@DisplayName("SSL: a renewed certificate in a newer check wins over an older, expiring one")
	void sslNewestCertificateWins() {
		List<CheckSample> checks = List.of(
				new CheckSample(NOW, true, 200, 100, null, cert(90)), // newest: renewed
				new CheckSample(NOW, true, 200, 100, null, cert(2)));

		assertThat(outcome(new RuleEngine(3).evaluate(input(only(IncidentType.SSL_EXPIRING, 14), checks,
				open(true, IncidentType.SSL_EXPIRING))), IncidentType.SSL_EXPIRING)).isEqualTo("CLOSE");
	}

	@Test
	@DisplayName("an expired certificate that makes checks fail raises BOTH SSL_EXPIRING and DOWN (and that is correct)")
	void expiredCertificateRaisesSslAndDown() {
		List<CheckSample> checks = List.of(
				new CheckSample(NOW, false, null, null, "SSL error: the certificate has expired", null),
				new CheckSample(NOW, false, null, null, "SSL error: the certificate has expired", null),
				new CheckSample(NOW, true, 200, 100, null, cert(-0.5))); // last good handshake: it expired half a day ago
		List<RuleConfig> rules = List.of(new RuleConfig(IncidentType.DOWN, 2, true),
				new RuleConfig(IncidentType.SSL_EXPIRING, 14, true));

		List<Decision> decisions = new RuleEngine(3).evaluate(input(rules, checks, EnumSet.noneOf(IncidentType.class)));

		assertThat(decisions).extracting(Decision::type).containsExactly(IncidentType.DOWN, IncidentType.SSL_EXPIRING);
		assertThat(decisions).allMatch(d -> d instanceof Decision.Open);
		assertThat(((Decision.Open) decisions.get(0)).details()).contains("SSL error: the certificate has expired");
		assertThat(((Decision.Open) decisions.get(1)).details()).contains("expired");
	}

	@Test
	@DisplayName("SSL details tell how many days are left")
	void sslDetails() {
		List<CheckSample> checks = List.of(new CheckSample(NOW, true, 200, 100, null, cert(9.5)));

		Decision.Open open = (Decision.Open) new RuleEngine(3)
				.evaluate(input(only(IncidentType.SSL_EXPIRING, 14), checks, open(false, IncidentType.SSL_EXPIRING))).get(0);

		assertThat(open.details()).contains("expires in 9 days").contains("threshold 14 days");
	}

	// ---- disabled rules, inactive sites ----

	@ParameterizedTest(name = "{0}")
	@CsvSource({ "DOWN", "HIGH_LATENCY", "SSL_EXPIRING" })
	@DisplayName("a disabled rule opens nothing")
	void disabledRuleOpensNothing(IncidentType type) {
		List<CheckSample> everythingIsWrong = List.of(
				new CheckSample(NOW, false, 500, 9000, "HTTP 500", cert(-3)),
				new CheckSample(NOW, false, 500, 9000, "HTTP 500", cert(-3)),
				new CheckSample(NOW, false, 500, 9000, "HTTP 500", cert(-3)));
		List<RuleConfig> rules = List.of(new RuleConfig(type, 1, false));

		assertThat(new RuleEngine(1).evaluate(input(rules, everythingIsWrong, EnumSet.noneOf(IncidentType.class)))).isEmpty();
	}

	@ParameterizedTest(name = "{0}")
	@CsvSource({ "DOWN", "HIGH_LATENCY", "SSL_EXPIRING" })
	@DisplayName("a disabled rule closes the open incident of its type so it is not left hanging")
	void disabledRuleClosesOpenIncident(IncidentType type) {
		List<RuleConfig> rules = List.of(new RuleConfig(type, 2, false));

		List<Decision> decisions = new RuleEngine(3).evaluate(input(rules, history("F F"), EnumSet.of(type)));

		assertThat(decisions).containsExactly(new Decision.Close(type, RuleEngine.RULE_DISABLED));
	}

	@Test
	@DisplayName("a disabled rule affects only its own type")
	void disabledRuleAffectsOnlyItsType() {
		List<RuleConfig> rules = List.of(new RuleConfig(IncidentType.DOWN, 2, true),
				new RuleConfig(IncidentType.HIGH_LATENCY, 2000, false));

		List<Decision> decisions = new RuleEngine(3).evaluate(
				input(rules, history("F F"), EnumSet.of(IncidentType.HIGH_LATENCY)));

		assertThat(decisions).hasSize(2);
		assertThat(outcome(decisions, IncidentType.DOWN)).isEqualTo("OPEN");
		assertThat(decisions).contains(new Decision.Close(IncidentType.HIGH_LATENCY, RuleEngine.RULE_DISABLED));
	}

	@Test
	@DisplayName("a missing rule is treated like a disabled one")
	void missingRuleIsLikeDisabled() {
		assertThat(new RuleEngine(3).evaluate(input(List.of(), history("F F F"), EnumSet.noneOf(IncidentType.class)))).isEmpty();
		assertThat(new RuleEngine(3).evaluate(input(List.of(), history("F F F"), EnumSet.of(IncidentType.DOWN))))
				.containsExactly(new Decision.Close(IncidentType.DOWN, RuleEngine.RULE_DISABLED));
	}

	@Test
	@DisplayName("an inactive site is not evaluated: nothing opens and every open incident closes as 'Site deactivated'")
	void inactiveSite() {
		List<RuleConfig> rules = List.of(new RuleConfig(IncidentType.DOWN, 2, true),
				new RuleConfig(IncidentType.HIGH_LATENCY, 1, true), new RuleConfig(IncidentType.SSL_EXPIRING, 14, true));
		List<CheckSample> failing = history("F F F");

		List<Decision> nothingOpen = new RuleEngine(3)
				.evaluate(new RuleInput(NOW, false, rules, failing, EnumSet.noneOf(IncidentType.class)));
		List<Decision> twoOpen = new RuleEngine(3)
				.evaluate(new RuleInput(NOW, false, rules, failing, EnumSet.of(IncidentType.SSL_EXPIRING, IncidentType.DOWN)));

		assertThat(nothingOpen).isEmpty();
		assertThat(twoOpen).containsExactly(new Decision.Close(IncidentType.DOWN, "Site deactivated"),
				new Decision.Close(IncidentType.SSL_EXPIRING, "Site deactivated"));
	}

	// ---- independence, no duplicates, idempotence ----

	@Test
	@DisplayName("types are independent: several incidents can open at once, in a stable order")
	void typesAreIndependent() {
		List<RuleConfig> rules = List.of(new RuleConfig(IncidentType.SSL_EXPIRING, 14, true),
				new RuleConfig(IncidentType.HIGH_LATENCY, 100, true), new RuleConfig(IncidentType.DOWN, 1, true));
		List<CheckSample> checks = List.of(new CheckSample(NOW, false, 503, 3000, "HTTP 503", cert(3)));
		List<CheckSample> slow = List.of(
				new CheckSample(NOW, true, 200, 500, null, cert(3)),
				new CheckSample(NOW, true, 200, 500, null, cert(3)),
				new CheckSample(NOW, true, 200, 500, null, cert(3)));

		assertThat(new RuleEngine(3).evaluate(input(rules, checks, EnumSet.noneOf(IncidentType.class))))
				.extracting(Decision::type).containsExactly(IncidentType.DOWN, IncidentType.SSL_EXPIRING);
		assertThat(new RuleEngine(3).evaluate(input(rules, slow, EnumSet.noneOf(IncidentType.class))))
				.extracting(Decision::type).containsExactly(IncidentType.HIGH_LATENCY, IncidentType.SSL_EXPIRING);
	}

	@Test
	@DisplayName("evaluating the same data twice gives the same answer (no hidden state)")
	void sameInputSameDecisions() {
		RuleEngine engine = new RuleEngine(3);
		RuleInput input = input(only(IncidentType.DOWN, 2), history("F F"), EnumSet.noneOf(IncidentType.class));

		assertThat(engine.evaluate(input)).isEqualTo(engine.evaluate(input)).isEqualTo(new RuleEngine(3).evaluate(input));
	}

	@Test
	@DisplayName("once a decision is applied, evaluating again decides nothing (idempotent): no duplicates")
	void idempotentOnceApplied() {
		RuleEngine engine = new RuleEngine(3);
		List<RuleConfig> rules = List.of(new RuleConfig(IncidentType.DOWN, 2, true));
		List<CheckSample> failing = history("F F");

		List<Decision> first = engine.evaluate(input(rules, failing, EnumSet.noneOf(IncidentType.class)));
		List<Decision> again = engine.evaluate(input(rules, failing, EnumSet.of(IncidentType.DOWN))); // as the database now says

		assertThat(first).hasSize(1).first().isInstanceOf(Decision.Open.class);
		assertThat(again).isEmpty();
	}

	/** Replays a history one check at a time like the real system does, applying every decision. */
	private List<String> replay(RuleEngine engine, List<RuleConfig> rules, String oldestToNewest) {
		Set<IncidentType> open = EnumSet.noneOf(IncidentType.class);
		List<CheckSample> newestFirst = new ArrayList<>();
		List<String> log = new ArrayList<>();
		for (String token : oldestToNewest.trim().split("\\s+")) {
			newestFirst.add(0, sample(token));
			for (Decision decision : engine.evaluate(input(rules, newestFirst, open))) {
				if (decision instanceof Decision.Open) {
					open.add(decision.type());
					log.add("OPEN " + decision.type());
				}
				else {
					open.remove(decision.type());
					log.add("CLOSE " + decision.type());
				}
			}
			assertThat(engine.evaluate(input(rules, newestFirst, open))).as("after applying, nothing more to decide at '%s'", token).isEmpty();
		}
		return log;
	}

	@ParameterizedTest(name = "{0}")
	@CsvSource(delimiter = '|', textBlock = """
			alternating F U never opens DOWN (threshold 2)  | F U F U F U F U         |
			one outage = one incident                       | U F F F F U             | OPEN DOWN,CLOSE DOWN
			flapping outages open and close each time       | F F U F F U F F         | OPEN DOWN,CLOSE DOWN,OPEN DOWN,CLOSE DOWN,OPEN DOWN
			a long outage never reopens                     | F F F F F F F F F F     | OPEN DOWN
			recovery then immediate single failure          | F F U F                 | OPEN DOWN,CLOSE DOWN
			""")
	void downFlappingReplay(String name, String checks, String expectedLog) {
		List<String> expected = expectedLog == null ? List.of() : Arrays.asList(expectedLog.split(","));

		assertThat(replay(new RuleEngine(3), only(IncidentType.DOWN, 2), checks)).isEqualTo(expected);
	}

	@Test
	@DisplayName("latency replay: one incident for a slow period, a new one for the next slow period, spikes ignored")
	void latencyReplay() {
		List<String> log = replay(new RuleEngine(3), only(IncidentType.HIGH_LATENCY, 2000),
				"100 3000 100 3000 3000 3000 3000 100 3000 3000 3000");

		assertThat(log).containsExactly("OPEN HIGH_LATENCY", "CLOSE HIGH_LATENCY", "OPEN HIGH_LATENCY");
	}

	@Test
	@DisplayName("a restart changes nothing: a brand new engine continues exactly where the old one was")
	void freshEngineGivesSameDecisions() {
		List<RuleConfig> rules = only(IncidentType.DOWN, 2);
		List<CheckSample> checks = history("U F F F");

		assertThat(new RuleEngine(3).evaluate(input(rules, checks, EnumSet.of(IncidentType.DOWN)))).isEmpty();
		assertThat(new RuleEngine(3).evaluate(input(rules, history("U F F F U"), EnumSet.of(IncidentType.DOWN))))
				.hasSize(1).first().isInstanceOf(Decision.Close.class);
	}

	// ---- window, configuration, purity ----

	@Test
	@DisplayName("the window of recent checks covers the DOWN threshold and is never smaller than 50")
	void windowSize() {
		RuleEngine engine = new RuleEngine(3);

		assertThat(engine.windowSize(List.of())).isEqualTo(50);
		assertThat(engine.windowSize(only(IncidentType.DOWN, 2))).isEqualTo(50);
		assertThat(engine.windowSize(only(IncidentType.DOWN, 80))).isEqualTo(80);
		assertThat(engine.windowSize(only(IncidentType.HIGH_LATENCY, 5000))).isEqualTo(50);
	}

	@Test
	@DisplayName("at least one latency sample is required")
	void latencySamplesMustBePositive() {
		assertThatThrownBy(() -> new RuleEngine(0)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("the engine is stateless and framework-free: only a final int field, no Spring/JPA/data types anywhere")
	void engineIsPureJava() {
		for (Field field : RuleEngine.class.getDeclaredFields()) {
			if (Modifier.isStatic(field.getModifiers())) {
				continue;
			}
			assertThat(Modifier.isFinal(field.getModifiers())).as("field %s is final", field.getName()).isTrue();
			assertThat(field.getType().isPrimitive()).as("field %s holds no collaborator or mutable state", field.getName()).isTrue();
		}
		assertThat(RuleEngine.class.getDeclaredAnnotations()).isEmpty();
		List<Class<?>> typesUsed = new ArrayList<>();
		for (Method method : RuleEngine.class.getDeclaredMethods()) {
			typesUsed.add(method.getReturnType());
			typesUsed.addAll(Arrays.asList(method.getParameterTypes()));
		}
		for (Class<?> type : Arrays.asList(RuleInput.class, RuleConfig.class, CheckSample.class, Decision.class)) {
			typesUsed.add(type);
			for (var component : type.isRecord() ? type.getRecordComponents() : new java.lang.reflect.RecordComponent[0]) {
				typesUsed.add(component.getType());
			}
		}
		assertThat(typesUsed).extracting(Class::getName)
				.noneMatch(name -> name.startsWith("org.springframework") || name.startsWith("jakarta.persistence")
						|| name.startsWith("jakarta.transaction") || name.contains(".repository.")
						|| name.contains(".domain.") && !name.endsWith("IncidentType"));
	}
}
