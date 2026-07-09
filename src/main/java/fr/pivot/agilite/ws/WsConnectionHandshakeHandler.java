package fr.pivot.agilite.ws;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import java.security.Principal;
import java.util.Map;
import java.util.UUID;

/**
 * Handshake handler that unconditionally assigns every WebSocket connection on
 * {@code /ws/agilite} a random {@link WsConnectionPrincipal}.
 *
 * <p>Unlike a typical {@link DefaultHandshakeHandler}, this never inspects the HTTP upgrade
 * request at all — no header, cookie, or query parameter is read. The assigned identity is
 * purely a per-session correlation handle (see {@link WsConnectionPrincipal}'s JavaDoc for why),
 * so every handshake succeeds and receives a principal, regardless of caller.
 */
public class WsConnectionHandshakeHandler extends DefaultHandshakeHandler {

    /**
     * Builds a fresh, random {@link WsConnectionPrincipal} for the new session.
     *
     * @param request    the HTTP upgrade request (not used — see class JavaDoc)
     * @param wsHandler  the target WebSocket handler (not used)
     * @param attributes the HTTP session attributes (not used)
     * @return a new {@link WsConnectionPrincipal}, never {@code null}
     */
    @Override
    protected Principal determineUser(
            final ServerHttpRequest request,
            final WebSocketHandler wsHandler,
            final Map<String, Object> attributes) {
        return new WsConnectionPrincipal(UUID.randomUUID().toString());
    }
}
