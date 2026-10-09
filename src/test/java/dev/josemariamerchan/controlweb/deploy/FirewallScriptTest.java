package dev.josemariamerchan.controlweb.deploy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.josemariamerchan.controlweb.deploy.IptablesModel.Packet;
import dev.josemariamerchan.controlweb.deploy.IptablesModel.Verdict;

/**
 * deploy/firewall/controlweb-firewall.sh against a stateful fake iptables (src/test/resources/fakedocker/fake-iptables.sh) that keeps
 * the ruleset in files, plus a small evaluator of that ruleset (IptablesModel). What is proved:
 * (1) no rule of the firewall can match anything that does not enter through the bridge cw-egress: Rotten Odds' bridges, cw-proxy,
 * cw-db, the network card and loopback never even visit a CW-* chain; (2) what cw-egress traffic is allowed or rejected, by the plan's
 * table; (3) applying twice is the same as once; (4) stop removes every rule and chain of ours and leaves everything else byte-identical;
 * (5) a failure at ANY step leaves no active rule behind. REJECT, never DROP.
 */
@Tag("slow-scripts")
class FirewallScriptTest {

	private static final String BASH = bash();
	private static final String PUBLIC_IP = "93.184.216.34";
	private static final Path SCRIPT = Path.of("deploy/firewall/controlweb-firewall.sh");

	@TempDir
	Path temp;

	private Path state;
	private Path conf;
	private Path dockerLog;
	private Path fakeIptables;

	private record Result(int exit, String output) {
	}

	private static String bash() {
		for (String candidate : List.of("C:/Program Files/Git/bin/bash.exe", "/bin/bash", "/usr/bin/bash")) {
			if (Files.exists(Path.of(candidate))) {
				return candidate;
			}
		}
		return null;
	}

	private static String slashes(Path path) {
		return path.toAbsolutePath().toString().replace('\\', '/');
	}

	@BeforeEach
	void world() throws IOException {
		assumeTrue(BASH != null, "bash is not available");
		state = Files.createDirectories(temp.resolve("ipt"));
		conf = temp.resolve("firewall.conf");
		dockerLog = temp.resolve("docker.log");
		fakeIptables = ScriptFixtures.copyFile(Path.of("src/test/resources/fakedocker/fake-iptables.sh"), temp.resolve("bin"), "iptables");
		Files.writeString(conf, "# comment\nPUBLIC_IP=" + PUBLIC_IP + "\n");
		seedOtherPeoplesRules();
	}

	/** What a server shared with Rotten Odds looks like before our firewall: Docker's chains and some of the owner's own rules. */
	private void seedOtherPeoplesRules() throws IOException {
		Files.writeString(state.resolve("chain.INPUT"), "-i lo -j ACCEPT\n-m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT\n-p tcp --dport 22 -j ACCEPT\n-p tcp --dport 443 -j ACCEPT\n");
		Files.writeString(state.resolve("chain.FORWARD"), "-j DOCKER-USER\n-i br-0123456789ab -j ACCEPT\n");
		Files.writeString(state.resolve("chain.DOCKER-USER"), "-j RETURN\n");
	}

	private Result firewall(Map<String, String> extra, String... arguments) throws Exception {
		List<String> command = new ArrayList<>(List.of(BASH, SCRIPT.toString()));
		command.addAll(List.of(arguments));
		ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
		builder.directory(Path.of("").toAbsolutePath().toFile());
		builder.environment().put("IPTABLES", slashes(fakeIptables));
		builder.environment().put("FAKE_IPT_STATE", slashes(state));
		builder.environment().put("CONF_FILE", slashes(conf));
		builder.environment().put("ALLOW_NON_ROOT", "1");
		builder.environment().putAll(extra);
		Process process = builder.start();
		process.getOutputStream().close();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertThat(process.waitFor(120, TimeUnit.SECONDS)).isTrue();
		return new Result(process.exitValue(), output);
	}

	private Result start() throws Exception {
		return firewall(Map.of(), "start");
	}

	/** The script without its comment lines: the rules about what it must not do apply to the code, not to the explanations. */
	private static String code() throws IOException {
		return Files.readAllLines(SCRIPT).stream().filter(line -> !line.strip().startsWith("#")).collect(java.util.stream.Collectors.joining("\n"));
	}

	private Map<String, String> snapshot() throws IOException {
		Map<String, String> chains = new TreeMap<>();
		try (Stream<Path> files = Files.list(state)) {
			for (Path file : files.filter(path -> path.getFileName().toString().startsWith("chain.")).toList()) {
				chains.put(file.getFileName().toString(), Files.readString(file));
			}
		}
		return chains;
	}

	private Map<String, String> others(Map<String, String> snapshot) {
		Map<String, String> result = new TreeMap<>(snapshot);
		result.keySet().removeIf(name -> name.startsWith("chain.CW-"));
		return result;
	}

	private IptablesModel model() throws IOException {
		return IptablesModel.load(state);
	}

	private static final List<String> NOT_OURS = List.of("eth0", "lo", "docker0", "br-0123456789ab", "br-edgeedgeedge", "cw-proxy", "cw-db", "cw-egress2", "xcw-egress", "cw-egres", "veth1234");
	private static final List<String> DESTINATIONS = List.of("169.254.169.254", "10.77.0.10", "10.77.1.2", "172.18.0.2", "172.28.0.10", "192.168.1.1", "127.0.0.1", "100.64.0.1",
			PUBLIC_IP, "8.8.8.8", "1.1.1.1", "224.0.0.1", "255.255.255.255", "93.184.216.35");

	// ---- (1) nothing but cw-egress traffic can ever match ------------------------------------------------------------------------

	@Test
	@DisplayName("no packet from Rotten Odds' bridges, cw-proxy, cw-db, the network card or loopback ever visits a CW-* chain, in FORWARD or INPUT")
	void onlyTheEgressBridgeIsAffected() throws Exception {
		start();
		IptablesModel model = model();
		IptablesModel before = null;
		int checked = 0;
		for (String iif : NOT_OURS) {
			for (String dst : DESTINATIONS) {
				for (int port : new int[] { 22, 53, 80, 443, 5432, 8080 }) {
					for (String proto : List.of("tcp", "udp", "icmp")) {
						for (Packet packet : List.of(Packet.fresh(iif, proto, dst, port), Packet.established(iif, proto, dst, port),
								Packet.dnat(iif, "172.18.0.2", 443, PUBLIC_IP, 443), Packet.dnat(iif, dst, port, PUBLIC_IP, 22))) {
							for (String chain : List.of("DOCKER-USER", "INPUT")) {
								Verdict verdict = model.evaluate(chain, packet);
								assertThat(verdict.touchedOurChains()).as("%s %s -> %s:%d in %s visited %s", iif, proto, dst, port, chain, verdict.visitedChains()).isFalse();
								checked++;
							}
						}
					}
				}
			}
		}
		assertThat(checked).isGreaterThan(10_000);
	}

	@Test
	@DisplayName("for traffic that is not cw-egress the verdict is exactly what it was before the firewall existed (Rotten Odds' own rules still decide)")
	void otherTrafficIsDecidedByTheOldRules() throws Exception {
		Path before = temp.resolve("before");
		Files.createDirectories(before);
		for (Map.Entry<String, String> entry : snapshot().entrySet()) {
			Files.writeString(before.resolve(entry.getKey()), entry.getValue());
		}
		IptablesModel without = IptablesModel.load(before);
		start();
		IptablesModel with = model();

		for (String iif : NOT_OURS) {
			for (String dst : DESTINATIONS) {
				for (int port : new int[] { 22, 443, 5432 }) {
					Packet packet = Packet.fresh(iif, "tcp", dst, port);
					for (String chain : List.of("DOCKER-USER", "INPUT")) {
						Verdict a = without.evaluate(chain, packet);
						Verdict b = with.evaluate(chain, packet);
						assertThat(b.kind()).as("%s -> %s:%d in %s", iif, dst, port, chain).isEqualTo(a.kind());
						assertThat(b.rejectWith()).isEqualTo(a.rejectWith());
					}
				}
			}
		}
		assertThat(with.evaluate("INPUT", Packet.fresh("eth0", "tcp", "203.0.113.5", 22)).kind()).as("SSH from outside still works").isEqualTo("ACCEPT");
	}

	@Test
	@DisplayName("the only rules added outside our CW-* chains are the two jumps '-i cw-egress -j CW-...', inserted FIRST; everything else is untouched")
	void onlyTwoJumpsAreAdded() throws Exception {
		Map<String, String> seed = snapshot();
		Result result = start();

		assertThat(result.exit()).as(result.output()).isZero();
		Map<String, String> after = others(snapshot());
		assertThat(after.get("chain.DOCKER-USER")).isEqualTo("-i cw-egress -j CW-EGRESS\n" + seed.get("chain.DOCKER-USER"));
		assertThat(after.get("chain.INPUT")).isEqualTo("-i cw-egress -j CW-INPUT\n" + seed.get("chain.INPUT"));
		assertThat(after.get("chain.FORWARD")).isEqualTo(seed.get("chain.FORWARD"));
		assertThat(after.keySet()).isEqualTo(seed.keySet());
		// every call made to the fake iptables, one by one
		for (String call : Files.readAllLines(state.resolve(".log"))) {
			String[] words = call.split(" ");
			String op = words[0].equals("-w") ? words[2] : words[0];
			String chain = words[0].equals("-w") ? words[3] : words[1];
			assertThat(call).as("no other table, no policy, no zeroing, no save/restore").doesNotContain("-t ").doesNotContain("-P ").doesNotContain("-Z").doesNotContain("-R ").doesNotContain("-E ");
			switch (op) {
				case "-N", "-F", "-X", "-A" -> assertThat(chain).as(call).startsWith("CW-");
				case "-I", "-D", "-C" -> {
					if (!chain.startsWith("CW-")) {
						assertThat(chain).as(call).isIn("INPUT", "DOCKER-USER");
						assertThat(call).as(call).containsPattern("-i cw-egress -j CW-(EGRESS|INPUT)$");
					}
				}
				case "-S" -> {
				}
				default -> throw new AssertionError("unexpected iptables operation: " + call);
			}
		}
	}

	// ---- (2) what cw-egress traffic may and may not do ---------------------------------------------------------------------------

	private Verdict forward(Packet packet) throws IOException {
		return model().evaluate("DOCKER-USER", packet);
	}

	@Test
	@DisplayName("cw-egress -> the public Internet and DNS: allowed")
	void publicInternetIsAllowed() throws Exception {
		start();
		for (String dst : List.of("93.184.216.35", "8.8.8.8", "1.1.1.1", "142.250.0.1", "104.16.0.1")) {
			assertThat(forward(Packet.fresh("cw-egress", "tcp", dst, 443)).kind()).as(dst).isEqualTo("PASS");
			assertThat(forward(Packet.fresh("cw-egress", "udp", dst, 53)).kind()).as(dst).isEqualTo("PASS");
		}
		assertThat(forward(Packet.established("cw-egress", "tcp", "10.77.0.10", 443)).kind()).as("replies of allowed connections").isEqualTo("PASS");
	}

	@Test
	@DisplayName("cw-egress -> every private, reserved or special-use range (metadata, Caddy, other bridges, the host): REJECTED at once, tcp-reset or icmp-admin-prohibited, never dropped")
	void privateRangesAreRejected() throws Exception {
		start();
		for (String dst : List.of("169.254.169.254", "169.254.1.1", "10.0.0.1", "10.77.0.10", "10.77.1.2", "10.77.2.1", "172.16.0.1", "172.18.0.2", "172.28.0.10", "172.31.255.255",
				"192.168.0.1", "127.0.0.1", "100.64.0.1", "100.127.255.255", "192.0.0.1", "192.0.2.1", "198.18.0.1", "198.19.255.255", "198.51.100.1", "203.0.113.1", "224.0.0.1",
				"239.255.255.250", "240.0.0.1", "255.255.255.255", "0.0.0.0", "0.1.2.3")) {
			Verdict tcp = forward(Packet.fresh("cw-egress", "tcp", dst, 443));
			Verdict udp = forward(Packet.fresh("cw-egress", "udp", dst, 53));
			Verdict icmp = forward(Packet.fresh("cw-egress", "icmp", dst, null));
			assertThat(tcp.kind()).as(dst).isEqualTo("REJECT");
			assertThat(tcp.rejectWith()).isEqualTo("tcp-reset");
			assertThat(udp.kind()).as(dst).isEqualTo("REJECT");
			assertThat(udp.rejectWith()).isEqualTo("icmp-admin-prohibited");
			assertThat(icmp.rejectWith()).as(dst).isEqualTo("icmp-admin-prohibited");
		}
		// the edges of the ranges: the first public address on each side is allowed
		for (String dst : List.of("9.255.255.255", "11.0.0.0", "100.63.255.255", "100.128.0.0", "126.255.255.255", "128.0.0.1", "169.253.255.255", "169.255.0.0", "172.15.255.255", "172.32.0.0",
				"192.167.255.255", "192.169.0.0", "198.17.255.255", "198.20.0.0", "223.255.255.255")) {
			assertThat(forward(Packet.fresh("cw-egress", "tcp", dst, 443)).kind()).as("just outside a range: " + dst).isEqualTo("PASS");
		}
	}

	@Test
	@DisplayName("hairpin: a connection to the VPS's own public address on 80/443 that Docker rewrote to Caddy is allowed; any other port, address or non-rewritten packet to a private range is rejected")
	void hairpin() throws Exception {
		start();
		assertThat(forward(Packet.dnat("cw-egress", "172.18.0.2", 443, PUBLIC_IP, 443)).kind()).isEqualTo("PASS");
		assertThat(forward(Packet.dnat("cw-egress", "172.18.0.2", 80, PUBLIC_IP, 80)).kind()).isEqualTo("PASS");
		assertThat(forward(Packet.dnat("cw-egress", "172.18.0.2", 22, PUBLIC_IP, 22)).kind()).as("another port of the public address").isEqualTo("REJECT");
		assertThat(forward(Packet.dnat("cw-egress", "172.18.0.2", 443, "93.184.216.99", 443)).kind()).as("rewritten, but not from OUR public address").isEqualTo("REJECT");
		assertThat(forward(Packet.dnat("cw-egress", "172.18.0.2", 5432, PUBLIC_IP, 443)).kind()).as("rewritten to another port").isEqualTo("PASS"); // allowed by origdst; the port after DNAT is Docker's business
		assertThat(forward(Packet.fresh("cw-egress", "tcp", "172.18.0.2", 443)).kind()).as("directly to Caddy's private address").isEqualTo("REJECT");
		assertThat(forward(Packet.fresh("cw-egress", "udp", "172.18.0.2", 443)).kind()).isEqualTo("REJECT");
		assertThat(forward(new Packet("cw-egress", "udp", "10.77.2.2", "172.18.0.2", 443, java.util.Set.of("NEW", "DNAT"), PUBLIC_IP, 443)).kind()).as("hairpin is tcp only").isEqualTo("REJECT");
	}

	@Test
	@DisplayName("a resolver on a private address is reachable only if ALLOW_DNS lists it, and only on port 53")
	void privateResolver() throws Exception {
		Files.writeString(conf, "PUBLIC_IP=" + PUBLIC_IP + "\nALLOW_DNS=10.0.0.2 10.0.0.3\n");
		start();

		assertThat(forward(Packet.fresh("cw-egress", "udp", "10.0.0.2", 53)).kind()).isEqualTo("PASS");
		assertThat(forward(Packet.fresh("cw-egress", "tcp", "10.0.0.3", 53)).kind()).isEqualTo("PASS");
		assertThat(forward(Packet.fresh("cw-egress", "tcp", "10.0.0.2", 22)).kind()).isEqualTo("REJECT");
		assertThat(forward(Packet.fresh("cw-egress", "udp", "10.0.0.4", 53)).kind()).isEqualTo("REJECT");
	}

	@Test
	@DisplayName("cw-egress -> the VPS itself (INPUT): SSH and everything else rejected, replies to its own connections accepted; other interfaces keep their rules")
	void inputFromTheEgressBridge() throws Exception {
		start();
		IptablesModel model = model();

		for (String dst : List.of("10.77.2.1", PUBLIC_IP, "172.18.0.1", "127.0.0.1")) {
			for (int port : new int[] { 22, 443, 5432 }) {
				Verdict verdict = model.evaluate("INPUT", Packet.fresh("cw-egress", "tcp", dst, port));
				assertThat(verdict.kind()).as(dst + ":" + port).isEqualTo("REJECT");
				assertThat(verdict.rejectWith()).isEqualTo("tcp-reset");
			}
		}
		assertThat(model.evaluate("INPUT", Packet.fresh("cw-egress", "udp", "10.77.2.1", 53)).rejectWith()).isEqualTo("icmp-admin-prohibited");
		assertThat(model.evaluate("INPUT", Packet.established("cw-egress", "tcp", "10.77.2.1", 40000)).kind()).isEqualTo("ACCEPT");
		assertThat(model.evaluate("INPUT", Packet.fresh("eth0", "tcp", PUBLIC_IP, 22)).kind()).as("SSH from the Internet is not our business").isEqualTo("ACCEPT");
	}

	@Test
	@DisplayName("nothing is ever DROPped, and the interface of every jump is exactly cw-egress")
	void noDropAndOneBridge() throws Exception {
		start();
		for (String content : snapshot().values()) {
			assertThat(content).doesNotContain("DROP");
		}
		assertThat(code()).doesNotContain("DROP").doesNotContain("-P ").doesNotContain("iptables-save").doesNotContain("iptables-restore").doesNotContain("nat");
		assertThat(Files.readString(state.resolve("chain.INPUT")).lines().filter(line -> line.contains("CW-")).toList()).containsExactly("-i cw-egress -j CW-INPUT");
		assertThat(Files.readString(state.resolve("chain.DOCKER-USER")).lines().filter(line -> line.contains("CW-")).toList()).containsExactly("-i cw-egress -j CW-EGRESS");
	}

	// ---- (3) idempotent, (4) reversible -------------------------------------------------------------------------------------------

	@Test
	@DisplayName("applying twice or three times gives the same rules as once (no duplicate jumps, no duplicate rules)")
	void idempotent() throws Exception {
		start();
		Map<String, String> once = snapshot();

		Result second = start();
		Map<String, String> twice = snapshot();
		start();
		Map<String, String> thrice = snapshot();

		assertThat(second.exit()).as(second.output()).isZero();
		assertThat(twice).isEqualTo(once);
		assertThat(thrice).isEqualTo(once);
	}

	@Test
	@DisplayName("applying again repairs a missing jump or a tampered chain")
	void repairs() throws Exception {
		start();
		Map<String, String> good = snapshot();
		Files.writeString(state.resolve("chain.INPUT"), Files.readString(state.resolve("chain.INPUT")).replace("-i cw-egress -j CW-INPUT\n", ""));
		Files.writeString(state.resolve("chain.CW-EGRESS"), "-j RETURN\n");

		start();

		assertThat(snapshot()).isEqualTo(good);
	}

	@Test
	@DisplayName("stop removes every rule and chain of ours and leaves everything else byte-identical to before; stopping twice, or never started, is fine")
	void reversible() throws Exception {
		Map<String, String> seed = snapshot();
		start();

		Result stop = firewall(Map.of(), "stop");
		Result again = firewall(Map.of(), "stop");

		assertThat(stop.exit()).as(stop.output()).isZero();
		assertThat(again.exit()).as(again.output()).isZero();
		assertThat(snapshot()).isEqualTo(seed);
		Path clean = temp.resolve("clean");
		state = Files.createDirectories(clean);
		seedOtherPeoplesRules();
		assertThat(firewall(Map.of(), "stop").exit()).as("stop on a server that never had the firewall").isZero();
	}

	@Test
	@DisplayName("stop also works when DOCKER-USER does not exist yet (Docker not started) and never removes a chain that is not ours")
	void stopWithoutDockerUser() throws Exception {
		Files.delete(state.resolve("chain.DOCKER-USER"));
		Files.writeString(state.resolve("chain.FORWARD"), "-i br-0123456789ab -j ACCEPT\n");
		Result started = start();
		assertThat(started.exit()).as(started.output()).isZero();
		assertThat(state.resolve("chain.DOCKER-USER")).as("created by us because Docker had not started; Docker keeps it").exists();

		firewall(Map.of(), "stop");

		assertThat(state.resolve("chain.DOCKER-USER")).as("DOCKER-USER stays: it is Docker's chain").exists();
		assertThat(Files.readString(state.resolve("chain.DOCKER-USER"))).isEmpty();
		assertThat(state.resolve("chain.CW-EGRESS")).doesNotExist();
	}

	// ---- (5) failure at any step --------------------------------------------------------------------------------------------------

	@Test
	@DisplayName("whichever iptables call fails, start exits with an error and leaves NO jump and no CW chain behind (never a half-built firewall)")
	void failureAtAnyStep() throws Exception {
		start();
		int calls = Integer.parseInt(Files.readString(state.resolve(".calls")).strip());
		firewall(Map.of(), "stop");
		Map<String, String> seed = snapshot();
		assertThat(calls).isGreaterThan(30);

		// every one of the last calls (where the jumps go in) and every 3rd of the rest: each run starts bash many times, which is slow on Windows
		for (int failAt = 1; failAt <= calls; failAt += (failAt > calls - 10 ? 1 : 3)) {
			Files.deleteIfExists(state.resolve(".calls"));
			Result result = firewall(Map.of("FAKE_IPT_FAIL_AT", String.valueOf(failAt)), "start");

			assertThat(result.exit()).as("call %d failing: %s", failAt, result.output()).isNotZero();
			Map<String, String> after = snapshot();
			assertThat(after.keySet()).as("call %d: no CW chain left", failAt).noneMatch(name -> name.startsWith("chain.CW-"));
			assertThat(after.get("chain.INPUT")).as("call %d", failAt).isEqualTo(seed.get("chain.INPUT"));
			assertThat(after.get("chain.DOCKER-USER")).as("call %d", failAt).isEqualTo(seed.get("chain.DOCKER-USER"));
		}
	}

	// ---- configuration ----------------------------------------------------------------------------------------------------------

	@Test
	@DisplayName("without a valid PUBLIC_IP nothing is touched: no file, a private/special address, junk, shell code, an unknown key, or the metadata address as DNS")
	void configurationIsValidated() throws Exception {
		Files.delete(conf);
		Result missing = start();
		assertThat(missing.exit()).isEqualTo(1);
		assertThat(missing.output()).contains("does not exist");

		for (String content : List.of("PUBLIC_IP=10.1.2.3\n", "PUBLIC_IP=169.254.169.254\n", "PUBLIC_IP=203.0.113.5\n", "PUBLIC_IP=127.0.0.1\n", "PUBLIC_IP=224.0.0.5\n", "PUBLIC_IP=0.0.0.0\n",
				"PUBLIC_IP=256.1.1.1\n", "PUBLIC_IP=1.2.3\n", "PUBLIC_IP=example.com\n", "PUBLIC_IP=1.1.1.1; touch /tmp/pwned\n", "PUBLIC_IP=$(id)\n", "PUBLIC_IP=1.1.1.1\nPATH=/tmp\n",
				"\n# only comments\n", "PUBLIC_IP=1.1.1.1\nALLOW_DNS=169.254.169.254\n", "PUBLIC_IP=1.1.1.1\nALLOW_DNS=10.0.0.2 evil\n", "PUBLIC_IP=1.1.1.1\nALLOW_DNS=999.0.0.1\n")) {
			Files.writeString(conf, content);
			Result result = start();
			assertThat(result.exit()).as(content + " -> " + result.output()).isEqualTo(1);
			assertThat(Files.exists(state.resolve(".log")) ? Files.readString(state.resolve(".log")) : "").as("iptables must not be called: " + content).isEmpty();
			assertThat(snapshot().keySet()).noneMatch(name -> name.startsWith("chain.CW-"));
		}
		Files.writeString(conf, "PUBLIC_IP=" + PUBLIC_IP + "\r\n# crlf\r\n");
		assertThat(start().exit()).as("a configuration written on Windows is fine").isZero();
	}

	@Test
	@DisplayName("with no arguments or an unknown one it prints the usage, exit 2, and does nothing")
	void usage() throws Exception {
		Result none = firewall(Map.of());
		Result bad = firewall(Map.of(), "flush");

		assertThat(none.exit()).isEqualTo(2);
		assertThat(bad.exit()).isEqualTo(2);
		assertThat(state.resolve(".log")).doesNotExist();
	}

	// ---- status and guard -------------------------------------------------------------------------------------------------------

	@Test
	@DisplayName("status: 0 when the jumps and chains are in place, 1 when a jump or a chain is missing")
	void status() throws Exception {
		assertThat(firewall(Map.of(), "status").exit()).isEqualTo(1);
		start();
		assertThat(firewall(Map.of(), "status").exit()).isZero();
		Files.writeString(state.resolve("chain.DOCKER-USER"), "-j RETURN\n");
		assertThat(firewall(Map.of(), "status").exit()).isEqualTo(1);
	}

	/** A docker that answers `ps -q` with the given container ids (one per line, may be empty) and logs every call. */
	private Path fakeDocker(String ids, boolean stopFails) throws IOException {
		return ScriptFixtures.write(temp.resolve("docker"), "#!/bin/sh\necho \"$*\" >> '" + slashes(dockerLog) + "'\n"
				+ "case \"$1\" in ps) printf '%s' '" + ids + "' ;; stop) " + (stopFails ? "exit 1" : "exit 0") + " ;; esac\n");
	}

	private String dockerCalls() throws IOException {
		return Files.exists(dockerLog) ? Files.readString(dockerLog) : "";
	}

	@Test
	@DisplayName("guard: firewall active -> touches nothing; inactive -> stops ONLY the containers of the service controlweb-app of the compose project controlweb (found by labels)")
	void guard() throws Exception {
		Path docker = fakeDocker("1a2b3c4d5e6f", false);
		start();
		assertThat(firewall(Map.of("DOCKER", slashes(docker)), "guard").exit()).isZero();
		assertThat(dockerCalls()).as("active firewall: Docker is not even asked").isEmpty();

		firewall(Map.of(), "stop");
		Result inactive = firewall(Map.of("DOCKER", slashes(docker)), "guard");
		assertThat(inactive.exit()).isZero();
		assertThat(dockerCalls().strip().lines().toList()).containsExactly("ps -q --filter label=com.docker.compose.project=controlweb --filter label=com.docker.compose.service=controlweb-app", "stop 1a2b3c4d5e6f");
		assertThat(inactive.output()).contains("was stopped");
	}

	@Test
	@DisplayName("guard: no matching container, several matching containers, or a failing docker stop: nothing else is touched and it never fails")
	void guardIsCareful() throws Exception {
		Files.deleteIfExists(dockerLog);
		Result several = firewall(Map.of("DOCKER", slashes(fakeDocker("aaa111\nbbb222\n", false))), "guard");
		assertThat(several.exit()).isZero();
		assertThat(dockerCalls().lines().filter(line -> line.startsWith("stop")).toList()).containsExactly("stop aaa111", "stop bbb222");
		assertThat(dockerCalls().lines().filter(line -> line.startsWith("ps")).toList()).containsExactly("ps -q --filter label=com.docker.compose.project=controlweb --filter label=com.docker.compose.service=controlweb-app");

		Files.deleteIfExists(dockerLog);
		Result none = firewall(Map.of("DOCKER", slashes(fakeDocker("", false))), "guard");
		assertThat(none.exit()).isZero();
		assertThat(dockerCalls()).doesNotContain("stop");

		Files.deleteIfExists(dockerLog);
		Result failing = firewall(Map.of("DOCKER", slashes(fakeDocker("1a2b3c4d5e6f", true))), "guard");
		assertThat(failing.exit()).as("the guard never fails the boot").isZero();
		assertThat(failing.output()).contains("could not stop");
		assertThat(dockerCalls().lines().filter(line -> line.startsWith("stop")).toList()).containsExactly("stop 1a2b3c4d5e6f");
		assertThat(code()).doesNotContain("rm ").doesNotContain("kill").doesNotContain("docker compose").doesNotContain("\"$DOCKER\" rm");
	}
}
