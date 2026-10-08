package dev.josemariamerchan.controlweb.monitoring;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLContext;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

/**
 * A tiny HTTP or HTTPS server (JDK built-in, no extra dependency) that records every request, used
 * as the "website" under test. Listens on the loopback address; use {@link #url(String)} to reach it.
 */
final class TestServer implements AutoCloseable {

	record RecordedRequest(String method, String path, String rawQuery, Map<String, List<String>> headers) {

		boolean hasHeader(String name) {
			return headers.containsKey(name);
		}

		String header(String name) {
			List<String> values = headers.get(name);
			return values == null || values.isEmpty() ? null : values.get(0);
		}
	}

	private final HttpServer server;
	private final ExecutorService executor = Executors.newCachedThreadPool();
	private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
	private final String scheme;

	private TestServer(HttpServer server, String scheme) {
		this.server = server;
		this.scheme = scheme;
		server.setExecutor(executor);
	}

	static TestServer http() throws IOException {
		HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		return new TestServer(server, "http").started();
	}

	static TestServer https(SSLContext sslContext) throws IOException {
		HttpsServer server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.setHttpsConfigurator(new HttpsConfigurator(sslContext));
		return new TestServer(server, "https").started();
	}

	private TestServer started() {
		server.start();
		return this;
	}

	int port() {
		return server.getAddress().getPort();
	}

	/** Always uses the host name {@code localhost}, which is what the test certificates are issued for. */
	String url(String path) {
		return scheme + "://localhost:" + port() + path;
	}

	List<RecordedRequest> requests() {
		return new ArrayList<>(requests);
	}

	void on(String path, HttpHandler handler) {
		server.createContext(path, exchange -> {
			Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
			exchange.getRequestHeaders().forEach((name, values) -> headers.put(name, List.copyOf(values)));
			requests.add(new RecordedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
					exchange.getRequestURI().getRawQuery(), headers));
			try {
				handler.handle(exchange);
			}
			finally {
				exchange.close();
			}
		});
	}

	void respond(String path, int status) {
		on(path, exchange -> exchange.sendResponseHeaders(status, -1));
	}

	void redirect(String path, int status, String location) {
		on(path, exchange -> {
			exchange.getResponseHeaders().add("Location", location);
			exchange.sendResponseHeaders(status, -1);
		});
	}

	/** Declares an unbounded chunked body and writes it until the client goes away. */
	static void streamForever(HttpExchange exchange, java.util.concurrent.atomic.AtomicLong bytesWritten,
			java.util.concurrent.atomic.AtomicBoolean clientGone) throws IOException {
		exchange.sendResponseHeaders(200, 0);
		byte[] chunk = new byte[64 * 1024];
		try (OutputStream out = exchange.getResponseBody()) {
			while (true) {
				out.write(chunk);
				out.flush();
				bytesWritten.addAndGet(chunk.length);
				try {
					Thread.sleep(1);
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
					return;
				}
			}
		}
		catch (IOException ex) {
			clientGone.set(true);
		}
	}

	@Override
	public void close() {
		server.stop(0);
		executor.shutdownNow();
	}
}
