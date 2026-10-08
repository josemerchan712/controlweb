package dev.josemariamerchan.controlweb.read;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import dev.josemariamerchan.controlweb.TestcontainersConfiguration;
import dev.josemariamerchan.controlweb.site.repository.SiteRepository;

/**
 * Does the database have the indexes the read API needs? The plans come from the real PostgreSQL over a realistic
 * volume (40,000 incidents, 30,000 checks). To prove that migration V2 is NEEDED, each query is also explained
 * with the index dropped inside a transaction that is rolled back (PostgreSQL DDL is transactional): the plan
 * gets dramatically worse without it.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ReadIndexesIntegrationTest {

	private static final String NEWEST_INCIDENTS = "select * from incidents order by opened_at desc, id desc limit 50";
	private static final String NEWEST_OPEN_INCIDENTS = "select * from incidents where resolved_at is null order by opened_at desc, id desc limit 50";
	private static final String NEWEST_RESOLVED_INCIDENTS = "select * from incidents where resolved_at is not null order by opened_at desc, id desc limit 50";
	private static final String INCIDENTS_OF_A_SITE = "select * from incidents where site_id = %d order by opened_at desc, id desc limit 50";
	private static final String CHECK_HISTORY = "select * from checks where site_id = %d and checked_at >= now() - interval '10 days' "
			+ "order by checked_at desc, id desc limit 50";
	private static final Pattern TOTAL_COST = Pattern.compile("cost=\\d+\\.\\d+\\.\\.(\\d+\\.\\d+)");

	@Autowired
	private JdbcClient jdbc;
	@Autowired
	private PlatformTransactionManager transactionManager;
	@Autowired
	private SiteRepository sites;

	private long aSiteId;

	@BeforeEach
	void seedAVolumeOfData() {
		jdbc.sql("""
				insert into sites (name, url, check_interval_seconds, active, public_visible, created_at, updated_at)
				select 'idx-' || g, 'https://idx-' || g || '.example.com', 60, true, false, now(), now()
				from generate_series(1, 50) g
				""").update();
		// 40,000 resolved incidents spread over the 50 sites and the 3 types (many per site and type is allowed
		// once they are resolved) ...
		jdbc.sql("""
				insert into incidents (site_id, type, opened_at, resolved_at, details)
				select s.id, (array['DOWN', 'HIGH_LATENCY', 'SSL_EXPIRING'])[1 + (g % 3)],
				       now() - g * interval '1 minute', now() - g * interval '1 minute' + interval '30 seconds', 'x'
				from generate_series(1, 40000) g
				join (select id, row_number() over (order by id) rn from sites) s on s.rn = 1 + (g % 50)
				""").update();
		// ... and only a handful of open ones (the unique index allows one open per site and type). They are OLD:
		// long outages stay open for longer than the thousands of short incidents that opened after them, which is
		// exactly where scanning the full history to find the open rows becomes expensive.
		jdbc.sql("""
				insert into incidents (site_id, type, opened_at, details)
				select s.id, t.type, now() - interval '60 days' - s.rn * interval '7 seconds' - length(t.type) * interval '1 second', 'open'
				from (select id, row_number() over (order by id) rn from sites) s
				cross join (values ('DOWN'), ('SSL_EXPIRING')) as t(type)
				""").update();
		jdbc.sql("""
				insert into checks (site_id, checked_at, up, status_code, latency_ms)
				select s.id, now() - g * interval '1 minute', g % 10 <> 0, 200, 100 + g % 50
				from generate_series(1, 30000) g
				join (select id, row_number() over (order by id) rn from sites where name in ('idx-1', 'idx-2', 'idx-3')) s on s.rn = 1 + (g % 3)
				""").update();
		aSiteId = jdbc.sql("select id from sites where name = 'idx-1'").query(Long.class).single();
		jdbc.sql("analyze").update();
	}

	@AfterEach
	void cleanUp() {
		sites.deleteAll();
	}

	private String explain(String sql) {
		return String.join("\n", jdbc.sql("explain " + sql).query(String.class).list());
	}

	private static double cost(String plan) {
		Matcher matcher = TOTAL_COST.matcher(plan);
		assertThat(matcher.find()).as("a cost in the plan:%n%s", plan).isTrue();
		return Double.parseDouble(matcher.group(1));
	}

	/** Explains {@code sql} with the given indexes dropped, then rolls everything back. */
	private String explainWithout(String sql, String... indexes) {
		String[] plan = new String[1];
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			for (String index : indexes) {
				jdbc.sql("drop index " + index).update();
			}
			plan[0] = explain(sql);
			status.setRollbackOnly();
		});
		return plan[0];
	}

	// ---- what exists ----

	@Test
	@DisplayName("the indexes the read API relies on exist with the right columns, order and predicate")
	void indexCatalog() {
		List<String> definitions = jdbc.sql("select indexdef from pg_indexes where tablename in ('checks', 'incidents')")
				.query(String.class).list();
		String all = String.join("\n", definitions);

		assertThat(all).as("history, stats and latest-check queries").contains("idx_checks_site_checked_at")
				.contains("(site_id, checked_at DESC)");
		assertThat(all).as("incidents of one site").contains("idx_incidents_site_opened_at").contains("(site_id, opened_at DESC)");
		assertThat(all).as("whole incident list").contains("idx_incidents_opened_at ON public.incidents USING btree (opened_at DESC, id DESC)");
		assertThat(all).as("open incidents are found through the partial unique index from V1")
				.contains("uq_incidents_one_open_per_site_type").contains("(site_id, type) WHERE (resolved_at IS NULL)");
		assertThat(all).as("no dedicated open-incidents index: the plans below show it is not needed")
				.doesNotContain("idx_incidents_open_opened_at");
	}

	// ---- queries that must use an index ----

	@Test
	@DisplayName("the check history of a site reads its first page from idx_checks_site_checked_at")
	void checkHistoryUsesItsIndex() {
		String plan = explain(CHECK_HISTORY.formatted(aSiteId));

		assertThat(plan).contains("idx_checks_site_checked_at").doesNotContain("Seq Scan");
	}

	@Test
	@DisplayName("the incidents of one site read from idx_incidents_site_opened_at")
	void incidentsOfOneSiteUseTheirIndex() {
		assertThat(explain(INCIDENTS_OF_A_SITE.formatted(aSiteId))).contains("idx_incidents_site_opened_at").doesNotContain("Seq Scan");
	}

	@Test
	@DisplayName("the global incident list (newest first) reads straight from idx_incidents_opened_at, with no sort")
	void globalListUsesV2Index() {
		String plan = explain(NEWEST_INCIDENTS);

		assertThat(plan).contains("idx_incidents_opened_at").doesNotContain("Seq Scan").doesNotContain("Sort");
	}

	@Test
	@DisplayName("status=open is served by the partial unique index that V1 already has: only the few open rows are read")
	void openListUsesTheExistingPartialIndex() {
		String plan = explain(NEWEST_OPEN_INCIDENTS);

		assertThat(plan).contains("uq_incidents_one_open_per_site_type").doesNotContain("Seq Scan");
		assertThat(cost(plan)).as("a cheap plan (about a hundred open rows among 40,000):%n%s", plan).isLessThan(1000);
	}

	@Test
	@DisplayName("status=resolved is served by the global index (skipping the few open rows)")
	void resolvedListUsesGlobalIndex() {
		assertThat(explain(NEWEST_RESOLVED_INCIDENTS)).contains("idx_incidents_opened_at").doesNotContain("Seq Scan");
	}

	// ---- proof that V2 was necessary ----

	@Test
	@DisplayName("WITHOUT idx_incidents_opened_at the global list needs a full scan and a sort: far more expensive")
	void globalListNeedsTheV2Index() {
		String with = explain(NEWEST_INCIDENTS);
		String without = explainWithout(NEWEST_INCIDENTS, "idx_incidents_opened_at");

		assertThat(without).as("plan without the index:%n%s", without).contains("Seq Scan on incidents").contains("Sort");
		assertThat(cost(without)).as("cost with: %s / without: %s%n%s%n%s", cost(with), cost(without), with, without)
				.isGreaterThan(cost(with) * 20);
	}

	@Test
	@DisplayName("the rollback leaves the indexes in place")
	void ddlWasRolledBack() {
		explainWithout(NEWEST_INCIDENTS, "idx_incidents_opened_at");

		assertThat(explain(NEWEST_INCIDENTS)).contains("idx_incidents_opened_at");
	}
}
