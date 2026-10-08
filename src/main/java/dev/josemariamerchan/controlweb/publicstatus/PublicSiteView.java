package dev.josemariamerchan.controlweb.publicstatus;

import java.math.BigDecimal;

/**
 * A site on the public page. THREE fields and nothing else: no URL, no id, no latency, no incident text, no
 * certificate dates. A test pins the exact JSON keys, so adding a field here breaks the build.
 *
 * @param name      the name the owner gave the site
 * @param status    UP, DEGRADED, DOWN or UNKNOWN
 * @param uptime30d uptime percent of the last 30 days with two decimals; null when there are no checks (never 100 %)
 */
public record PublicSiteView(String name, PublicState status, BigDecimal uptime30d) {
}
