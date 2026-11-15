package com.pessimisticit.scarcity.orbital;

import jakarta.servlet.http.HttpServlet;
import jakarta.websocket.CloseReason;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.websocket.server.WsSci;

/**
 * A stand-in for subscriptions' WebSocket (docs/websocket.md) on its own Tomcat: {@code auth}
 * with an accepted token gets {@code ready}, another closes {@code 4401}; {@code subscribe}
 * gets {@code subscribed} and a {@code digest}. Tests send {@code turn} frames with
 * {@link #send}. One at a time per JVM (the endpoint finds it through a static).
 */
final class StubSubscriptions implements AutoCloseable {

    private static volatile StubSubscriptions current;

    final List<String> received = new CopyOnWriteArrayList<>();
    final List<Session> sessions = new CopyOnWriteArrayList<>();
    private volatile Predicate<String> accepted = token -> true;
    private final Tomcat tomcat = new Tomcat();

    StubSubscriptions() {
        current = this;
        try {
            tomcat.setBaseDir(Files.createTempDirectory("stub-subscriptions").toString());
            tomcat.setPort(0);
            tomcat.getConnector();
            Context context = tomcat.addContext("", null);
            context.addServletContainerInitializer(new WsSci(), Set.of(Endpoint.class));
            // The upgrade is a filter: a request must map to some servlet to reach it.
            Tomcat.addServlet(context, "none", new HttpServlet() {
                private static final long serialVersionUID = 1L;
            });
            context.addServletMappingDecoded("/", "none");
            tomcat.start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (LifecycleException e) {
            throw new IllegalStateException(e);
        }
    }

    String url() {
        return "ws://127.0.0.1:" + tomcat.getConnector().getLocalPort() + "/api/subscriptions/ws";
    }

    /** Which tokens {@code auth} accepts; the others close 4401. */
    void accept(Predicate<String> tokens) {
        this.accepted = tokens;
    }

    /** Every connection that has been opened, closed or not. */
    int connections() {
        return sessions.size();
    }

    List<Session> open() {
        return sessions.stream().filter(Session::isOpen).toList();
    }

    void send(String frame) {
        for (Session s : open()) {
            synchronized (s) {
                try {
                    s.getBasicRemote().sendText(frame);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
    }

    void sendTurn(String entity, long version) {
        send("{\"type\":\"turn\",\"area\":\"" + StubWorld.AREA + "\",\"entity\":\"" + entity + "\",\"version\":"
                + version + ",\"since\":0.0}");
    }

    void closeAll(int code) {
        for (Session s : open()) {
            try {
                s.close(new CloseReason(CloseReason.CloseCodes.getCloseCode(code), "closed by the test"));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    @Override
    public void close() throws LifecycleException {
        tomcat.stop();
        tomcat.destroy();
    }

    /** The endpoint; the container makes one per connection. */
    @ServerEndpoint("/api/subscriptions/ws")
    public static final class Endpoint {

        @OnOpen
        public void open(Session session) {
            current.sessions.add(session);
        }

        @OnMessage
        public void message(Session session, String text) throws IOException {
            synchronized (session) {
                reply(session, text);
            }
        }

        private static void reply(Session session, String text) throws IOException {
            StubSubscriptions stub = current;
            stub.received.add(text);
            if (text.contains("\"type\":\"auth\"")) {
                String token = text.replaceAll(".*\"token\":\"([^\"]*)\".*", "$1");
                if (stub.accepted.test(token)) {
                    session.getBasicRemote().sendText("{\"type\":\"ready\",\"user\":\"orbital\",\"username\":null}");
                } else {
                    session.close(new CloseReason(CloseReason.CloseCodes.getCloseCode(TurnSocket.UNAUTHORIZED),
                            "invalid token"));
                }
            } else if (text.contains("\"type\":\"subscribe\"")) {
                session.getBasicRemote().sendText("{\"type\":\"subscribed\",\"area\":\"" + StubWorld.AREA + "\"}");
                session.getBasicRemote().sendText("{\"type\":\"digest\",\"area\":\"" + StubWorld.AREA
                        + "\",\"versions\":{}}");
            }
        }

        @OnClose
        public void close(Session session) {
            // kept in sessions: connections() counts every one
        }
    }
}
