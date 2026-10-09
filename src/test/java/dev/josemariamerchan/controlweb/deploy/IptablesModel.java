package dev.josemariamerchan.controlweb.deploy;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * A tiny evaluator of the filter-table ruleset kept by fake-iptables.sh, for the tests of the egress firewall. It understands exactly
 * the matches and targets the firewall uses and FAILS on anything else, so a new kind of rule cannot slip into the firewall without
 * the model (and therefore the tests that prove "nothing but cw-egress traffic can match") learning about it first.
 */
final class IptablesModel {

	/** A packet as the filter table sees it. */
	record Packet(String iif, String proto, String src, String dst, Integer dport, Set<String> states, String origDst, Integer origDport) {

		static Packet fresh(String iif, String proto, String dst, Integer dport) {
			return new Packet(iif, proto, "10.77.2.2", dst, dport, Set.of("NEW"), dst, dport);
		}

		static Packet established(String iif, String proto, String dst, Integer dport) {
			return new Packet(iif, proto, "10.77.2.2", dst, dport, Set.of("ESTABLISHED"), dst, dport);
		}

		/** A packet that Docker has rewritten (DNAT): it now goes to {@code dst}, but it was sent to {@code origDst}:{@code origDport}. */
		static Packet dnat(String iif, String dst, int dport, String origDst, int origDport) {
			return new Packet(iif, "tcp", "10.77.2.2", dst, dport, Set.of("NEW", "DNAT"), origDst, origDport);
		}
	}

	/** kind: PASS (fell off a built-in chain: the packet continues to whatever else is configured), ACCEPT or REJECT. */
	record Verdict(String kind, String rejectWith, List<String> visitedChains) {
		boolean touchedOurChains() {
			return visitedChains.stream().anyMatch(chain -> chain.startsWith("CW-"));
		}
	}

	private record Rule(String text, Map<String, String> options, String target) {
	}

	private enum Flow {
		RETURN, ACCEPT, REJECT
	}

	private final Map<String, List<Rule>> chains = new HashMap<>();

	static IptablesModel load(Path stateDir) throws IOException {
		IptablesModel model = new IptablesModel();
		try (Stream<Path> files = Files.list(stateDir)) {
			for (Path file : files.filter(path -> path.getFileName().toString().startsWith("chain.")).toList()) {
				String name = file.getFileName().toString().substring("chain.".length());
				List<Rule> rules = new ArrayList<>();
				for (String line : Files.readAllLines(file)) {
					if (!line.isBlank()) {
						rules.add(parse(line.strip()));
					}
				}
				model.chains.put(name, rules);
			}
		}
		return model;
	}

	private static Rule parse(String line) {
		String[] tokens = line.split(" ");
		Map<String, String> options = new HashMap<>();
		String target = null;
		for (int i = 0; i < tokens.length; i++) {
			switch (tokens[i]) {
				case "-i", "-p", "-d", "-s", "--dport", "--ctstate", "--ctorigdst", "--ctorigdstport", "--reject-with", "--limit", "--limit-burst", "--log-prefix",
						"--log-level" -> options.put(tokens[i], tokens[++i]);
				case "-m" -> {
					String module = tokens[++i];
					if (!module.equals("conntrack") && !module.equals("limit")) {
						throw new AssertionError("the model does not know the match module '" + module + "' in: " + line);
					}
				}
				case "-j" -> target = tokens[++i];
				default -> throw new AssertionError("the model does not know '" + tokens[i] + "' in: " + line);
			}
		}
		if (target == null) {
			throw new AssertionError("a rule without a target is not supported: " + line);
		}
		return new Rule(line, options, target);
	}

	Verdict evaluate(String builtinChain, Packet packet) {
		List<String> visited = new ArrayList<>();
		Object[] outcome = run(builtinChain, packet, visited);
		Flow flow = (Flow) outcome[0];
		return switch (flow) {
			case ACCEPT -> new Verdict("ACCEPT", null, visited);
			case REJECT -> new Verdict("REJECT", (String) outcome[1], visited);
			case RETURN -> new Verdict("PASS", null, visited);
		};
	}

	private Object[] run(String chain, Packet packet, List<String> visited) {
		visited.add(chain);
		List<Rule> rules = chains.get(chain);
		if (rules == null) {
			throw new AssertionError("jump to a chain that does not exist: " + chain);
		}
		for (Rule rule : rules) {
			if (!matches(rule, packet)) {
				continue;
			}
			switch (rule.target()) {
				case "RETURN" -> {
					return new Object[] { Flow.RETURN, null };
				}
				case "ACCEPT" -> {
					return new Object[] { Flow.ACCEPT, null };
				}
				case "REJECT" -> {
					return new Object[] { Flow.REJECT, rule.options().getOrDefault("--reject-with", "icmp-port-unreachable") };
				}
				case "LOG" -> {
				}
				default -> {
					Object[] sub = run(rule.target(), packet, visited);
					if (sub[0] != Flow.RETURN) {
						return sub;
					}
				}
			}
		}
		return new Object[] { Flow.RETURN, null };
	}

	private static boolean matches(Rule rule, Packet packet) {
		Map<String, String> o = rule.options();
		if (o.containsKey("-i") && !o.get("-i").equals(packet.iif())) {
			return false;
		}
		if (o.containsKey("-p") && !o.get("-p").equals(packet.proto())) {
			return false;
		}
		if (o.containsKey("-d") && !inCidr(packet.dst(), o.get("-d"))) {
			return false;
		}
		if (o.containsKey("-s") && !inCidr(packet.src(), o.get("-s"))) {
			return false;
		}
		if (o.containsKey("--dport") && (packet.dport() == null || packet.dport() != Integer.parseInt(o.get("--dport")))) {
			return false;
		}
		if (o.containsKey("--ctstate")) {
			Set<String> wanted = new HashSet<>(List.of(o.get("--ctstate").split(",")));
			if (packet.states().stream().noneMatch(wanted::contains)) {
				return false;
			}
		}
		if (o.containsKey("--ctorigdst") && !o.get("--ctorigdst").equals(packet.origDst())) {
			return false;
		}
		if (o.containsKey("--ctorigdstport") && (packet.origDport() == null || packet.origDport() != Integer.parseInt(o.get("--ctorigdstport")))) {
			return false;
		}
		return true;
	}

	static boolean inCidr(String address, String cidr) {
		try {
			String[] parts = cidr.split("/");
			int bits = parts.length == 2 ? Integer.parseInt(parts[1]) : 32;
			long a = toLong(address);
			long n = toLong(parts[0]);
			long mask = bits == 0 ? 0 : (0xFFFFFFFFL << (32 - bits)) & 0xFFFFFFFFL;
			return (a & mask) == (n & mask);
		} catch (Exception e) {
			throw new AssertionError("bad address in a rule or packet: " + address + " / " + cidr, e);
		}
	}

	private static long toLong(String address) throws Exception {
		byte[] b = InetAddress.getByName(address).getAddress();
		return ((b[0] & 0xFFL) << 24) | ((b[1] & 0xFFL) << 16) | ((b[2] & 0xFFL) << 8) | (b[3] & 0xFFL);
	}
}
