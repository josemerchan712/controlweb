package dev.josemariamerchan.controlweb.publicstatus;

import java.time.Instant;
import java.util.List;

/**
 * The public status page data.
 *
 * @param status      the overall state, computed ONLY from the public sites listed below
 * @param generatedAt when this data was read from the database (it can be a few seconds old: it is cached)
 * @param sites       the public, active sites; empty when there are none
 */
public record PublicStatusResponse(PublicState status, Instant generatedAt, List<PublicSiteView> sites) {
}
