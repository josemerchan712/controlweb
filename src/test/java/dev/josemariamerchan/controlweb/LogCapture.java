package dev.josemariamerchan.controlweb;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;

/** Captures what a logger (or ALL loggers) writes while a test runs, including exception text and stack traces. */
public final class LogCapture implements AutoCloseable {

	private final Logger logger;
	private final Level previousLevel;
	private final boolean levelChanged;
	private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

	private LogCapture(Logger logger, Level level) {
		this.logger = logger;
		this.previousLevel = logger.getLevel();
		this.levelChanged = level != null;
		if (level != null) {
			logger.setLevel(level);
		}
		appender.start();
		logger.addAppender(appender);
	}

	public static LogCapture of(Class<?> type, Level level) {
		return new LogCapture((Logger) LoggerFactory.getLogger(type), level);
	}

	/** The root logger: sees every log line of every library, at the given level. */
	public static LogCapture everything(Level level) {
		return new LogCapture((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME), level);
	}

	/**
	 * Sees what the application REALLY logs: attaches to the root logger without touching any level, so the levels
	 * configured by the active profile (for example application-prod.yml) decide what reaches the appender.
	 */
	public static LogCapture atConfiguredLevels() {
		return new LogCapture((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME), null);
	}

	public List<ILoggingEvent> events() {
		return new ArrayList<>(appender.list);
	}

	/** Formatted messages only. */
	public List<String> messages() {
		return events().stream().map(ILoggingEvent::getFormattedMessage).toList();
	}

	/** Everything a log line could show: message, exception messages AND stack traces. */
	public List<String> fullText() {
		return fullTextExcluding();
	}

	/**
	 * Like {@link #fullText()} but ignoring loggers whose name starts with any given prefix. Tests that run a
	 * fake server inside the same JVM use it to leave out the SERVER's own logging (the JDK's built-in HTTP
	 * server traces every request line), so only the code under test is judged.
	 */
	public List<String> fullTextExcluding(String... loggerPrefixes) {
		return events().stream()
				.filter(event -> Arrays.stream(loggerPrefixes).noneMatch(event.getLoggerName()::startsWith))
				.map(event -> event.getFormattedMessage() + (event.getThrowableProxy() == null ? ""
						: "\n" + ThrowableProxyUtil.asString(event.getThrowableProxy())))
				.toList();
	}

	@Override
	public void close() {
		logger.detachAppender(appender);
		if (levelChanged) {
			logger.setLevel(previousLevel);
		}
	}
}
