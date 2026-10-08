package dev.josemariamerchan.controlweb;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;

/**
 * Starting an application applies the {@code logging.level.*} of its profile to the one global logging system of the
 * JVM. A test that starts the {@code prod} profile (quiet levels) would otherwise leave those levels behind and quietly
 * weaken every later test that relies on seeing DEBUG/TRACE output (the ones that hunt for leaked secrets in logs).
 * This extension takes a snapshot before the class and puts every level back after it.
 */
public class RestoreLoggingLevels implements BeforeAllCallback, AfterAllCallback {

	private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(RestoreLoggingLevels.class);

	@Override
	public void beforeAll(ExtensionContext context) {
		LoggerContext loggers = loggerContext();
		Map<String, Level> levels = new HashMap<>();
		for (Logger logger : loggers.getLoggerList()) {
			levels.put(logger.getName(), logger.getLevel());
		}
		context.getStore(NAMESPACE).put("levels", levels);
	}

	@Override
	@SuppressWarnings("unchecked")
	public void afterAll(ExtensionContext context) {
		Map<String, Level> levels = context.getStore(NAMESPACE).remove("levels", Map.class);
		if (levels == null) {
			return;
		}
		for (Logger logger : loggerContext().getLoggerList()) {
			boolean isRoot = logger.getName().equals(Logger.ROOT_LOGGER_NAME);
			Level before = levels.get(logger.getName());
			if (isRoot && before == null) {
				continue;
			}
			// Loggers created meanwhile had no explicit level before: back to "inherit" (the root keeps its own).
			logger.setLevel(before != null || isRoot ? before : null);
		}
	}

	private static LoggerContext loggerContext() {
		ILoggerFactory factory = LoggerFactory.getILoggerFactory();
		return (LoggerContext) factory;
	}
}
