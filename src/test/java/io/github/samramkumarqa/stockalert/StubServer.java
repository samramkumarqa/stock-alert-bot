package io.github.samramkumarqa.stockalert;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

import com.sun.net.httpserver.HttpServer;

/**
 * Tiny in-process HTTP stub (JDK only, no WireMock needed).
 * Queue responses with {@link #enqueue}; inspect what the client sent via {@link #requests()}.
 */
public final class StubServer implements AutoCloseable {

    public record Request(String method, String pathAndQuery, String body) {
    }

    private record Response(int status, String body) {
    }

    private final HttpServer server;
    private final Deque<Response> queue = new ArrayDeque<>();
    private final List<Request> requests = Collections.synchronizedList(new ArrayList<>());

    public StubServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", exchange -> {
            String body;
            try (InputStream in = exchange.getRequestBody()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().toString(), body));
            Response r;
            synchronized (queue) {
                r = queue.isEmpty() ? new Response(500, "no stub queued") : queue.poll();
            }
            byte[] bytes = r.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(r.status(), bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    public StubServer enqueue(int status, String body) {
        synchronized (queue) {
            queue.add(new Response(status, body));
        }
        return this;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public List<Request> requests() {
        return List.copyOf(requests);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    public static String fixture(String name) {
        try (InputStream in = StubServer.class.getResourceAsStream("/fixtures/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("missing fixture " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
