package dev.josemariamerchan.controlweb.publicstatus;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import dev.josemariamerchan.controlweb.monitoring.domain.SiteState;

class PublicStateTest {

	@ParameterizedTest(name = "{0} -> {1}")
	@CsvSource({ "UP,UP", "DEGRADED,DEGRADED", "DOWN,DOWN", "PENDING,UNKNOWN", "PAUSED,UNKNOWN" })
	@DisplayName("the internal state collapses to the public one (PENDING is UNKNOWN; PAUSED never gets here)")
	void collapse(SiteState internal, PublicState expected) {
		assertThat(PublicState.from(internal)).isEqualTo(expected);
	}

	private static PublicState overall(String csv) {
		List<PublicState> states = csv.isBlank() ? List.of()
				: Arrays.stream(csv.split(",")).map(String::trim).map(PublicState::valueOf).toList();
		return PublicState.overall(states);
	}

	@ParameterizedTest(name = "{0}")
	@CsvSource(delimiter = '|', textBlock = """
			no public sites                                  |                       | UNKNOWN
			a single healthy site                            | UP                    | UP
			a single site that is down                       | DOWN                  | DOWN
			a single degraded site                           | DEGRADED              | DEGRADED
			a single site not checked yet                    | UNKNOWN               | UNKNOWN
			only sites not checked yet                       | UNKNOWN,UNKNOWN       | UNKNOWN
			all healthy                                      | UP,UP,UP              | UP
			healthy sites ignore the ones not checked yet    | UP,UNKNOWN,UP         | UP
			one degraded among healthy ones                  | UP,DEGRADED,UP        | DEGRADED
			one down among healthy ones is a partial outage  | UP,DOWN,UP            | DEGRADED
			one down and one degraded                        | DOWN,DEGRADED         | DEGRADED
			every known site is down                         | DOWN,DOWN             | DOWN
			all known sites down, unknown ones are ignored   | DOWN,UNKNOWN,DOWN     | DOWN
			a down site next to an unchecked one             | DOWN,UNKNOWN          | DOWN
			""")
	void overallState(String name, String states, PublicState expected) {
		assertThat(overall(states == null ? "" : states)).isEqualTo(expected);
	}

	@Test
	@DisplayName("the public enum has exactly UP, DEGRADED, DOWN and UNKNOWN: nothing about pending/paused can be expressed")
	void onlyFourStates() {
		assertThat(PublicState.values()).containsExactly(PublicState.UP, PublicState.DEGRADED, PublicState.DOWN, PublicState.UNKNOWN);
	}
}
