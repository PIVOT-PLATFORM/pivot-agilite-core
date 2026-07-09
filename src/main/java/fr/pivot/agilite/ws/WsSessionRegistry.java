package fr.pivot.agilite.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks every currently open {@code /ws/agilite} {@link WebSocketSession} by its session ID so
 * that server-side components can force-close a specific session on demand.
 *
 * <p>Backs the EN09.1 rate-limit enforcement in
 * {@link fr.pivot.agilite.poker.ws.PokerChannelInterceptor}: sending a STOMP error frame alone
 * does not close the underlying transport — Spring's {@code StompSubProtocolHandler} only ever
 * writes the frame, it never closes the session on its own. The interceptor calls {@link #close}
 * after repeated rate-limit violations to actually terminate the connection.
 *
 * <p>Populated by {@link WsSessionTrackingHandlerDecoratorFactory}, which decorates the STOMP
 * {@code WebSocketHandler} to observe every connection open/close at the transport level. Shared
 * across every sub-domain of this module using the {@code /ws/agilite} endpoint (capacity,
 * standup, poker) — not poker-specific despite currently only being exercised by poker traffic.
 */
@Component
public class WsSessionRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(WsSessionRegistry.class);

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    /**
     * Registers a newly established session so it can later be force-closed by ID.
     *
     * @param session the newly opened WebSocket session
     */
    void register(final WebSocketSession session) {
        sessions.put(session.getId(), session);
    }

    /**
     * Removes a session from the registry once its transport connection has closed.
     *
     * @param session the closed WebSocket session
     */
    void unregister(final WebSocketSession session) {
        sessions.remove(session.getId());
    }

    /**
     * Forcibly closes the session identified by {@code sessionId}, if it is still open and
     * still tracked.
     *
     * <p>Best-effort: if the session is unknown (already closed, or never registered — e.g. a
     * race with a client-initiated disconnect) or the close attempt itself fails, the failure
     * is logged at WARN and swallowed. The caller has no further recourse in either case — the
     * client connection is gone or going regardless.
     *
     * @param sessionId the STOMP/WebSocket session ID to close
     * @param status    the close status sent to the client as part of the close handshake
     */
    public void close(final String sessionId, final CloseStatus status) {
        if (sessionId == null) {
            return;
        }
        WebSocketSession session = sessions.get(sessionId);
        if (session == null) {
            LOG.debug("Cannot force-close session={}: not (or no longer) tracked", sessionId);
            return;
        }
        try {
            if (session.isOpen()) {
                session.close(status);
                LOG.info("Session={} force-closed: {}", sessionId, status);
            }
        } catch (IOException e) {
            LOG.warn("Failed to force-close session={}: {}", sessionId, e.getMessage());
        }
    }
}
