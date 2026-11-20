package com.pessimisticit.scarcity.controller;

import tools.jackson.databind.JsonNode;
import com.pessimisticit.scarcity.world.model.WorldJson;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletionStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One socket's JDK callbacks, turned into whole JSON frames for a {@link Handler}: text
 * parts are joined until the last one, and a frame that isn't JSON is logged and skipped.
 * The JDK calls these one at a time.
 */
final class SocketListener implements WebSocket.Listener {

    /** What a connection does with its socket's events. */
    interface Handler {
        void opened(WebSocket ws);

        /** Anything from the server, a ping included. */
        void heard(WebSocket ws);

        void frame(WebSocket ws, JsonNode frame);

        /** The socket is gone; {@code code} is the close code, null on a failure. */
        void lost(Integer code);
    }

    private static final Logger LOG = LoggerFactory.getLogger(SocketListener.class);

    private final Handler handler;
    private final StringBuilder text;

    SocketListener(Handler handler) {
        this.text = new StringBuilder();
        this.handler = handler;
    }

    @Override
    public void onOpen(WebSocket ws) {
        handler.opened(ws);
        ws.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
        text.append(data);
        if (last) {
            String whole = text.toString();
            text.setLength(0);
            handler.heard(ws);
            JsonNode frame = null;
            try {
                frame = WorldJson.fromJson(whole, JsonNode.class);
            } catch (RuntimeException e) {
                LOG.warn("unreadable frame from subscriptions: {}", e.toString());
            }
            if (frame != null) {
                handler.frame(ws, frame);
            }
        }
        ws.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onPing(WebSocket ws, ByteBuffer message) {
        handler.heard(ws);
        return WebSocket.Listener.super.onPing(ws, message);
    }

    @Override
    public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
        LOG.info("socket closed: {} {}", code, reason);
        handler.lost(code);
        return null;
    }

    @Override
    public void onError(WebSocket ws, Throwable error) {
        LOG.warn("socket failed: {}", TurnSocket.rootMessage(error));
        handler.lost(null);
    }
}
