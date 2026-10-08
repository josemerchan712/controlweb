package dev.josemariamerchan.controlweb.monitoring.dto;

import java.time.Instant;
import java.util.List;

public record StatusResponse(Instant generatedAt, StatusSummary summary, List<SiteStatusView> sites) {
}
