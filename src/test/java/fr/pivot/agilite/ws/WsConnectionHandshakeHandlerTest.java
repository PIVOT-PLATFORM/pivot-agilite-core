package fr.pivot.agilite.ws;

import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.socket.WebSocketHandler;

import java.security.Principal;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link WsConnectionHandshakeHandler}.
 */
class WsConnectionHandshakeHandlerTest {

    private final WsConnectionHandshakeHandler handler = new WsConnectionHandshakeHandler();

    /**
     * Given any HTTP upgrade request, when the handshake handler determines the user, then a
     * non-null {@link WsConnectionPrincipal} is always assigned — no request is ever rejected
     * for lack of identity, since this is not an authentication mechanism (see class JavaDoc).
     */
    @Test
    void alwaysAssignsAPrincipal() {
        Principal principal = determineUser();

        assertThat(principal).isInstanceOf(WsConnectionPrincipal.class);
        assertThat(principal.getName()).isNotBlank();
    }

    /**
     * Given two separate handshakes, when each determines its user, then each receives a
     * distinct correlation id — connections must never share an addressing identity.
     */
    @Test
    void distinctHandshakesGetDistinctPrincipals() {
        Principal first = determineUser();
        Principal second = determineUser();

        assertThat(first.getName()).isNotEqualTo(second.getName());
    }

    /**
     * Invokes the protected {@link WsConnectionHandshakeHandler#determineUser} via a real HTTP
     * request with no identifying headers at all — proving the assigned identity does not
     * depend on request content.
     *
     * @return the resulting principal
     */
    private Principal determineUser() {
        ServerHttpRequest request = new ServletServerHttpRequest(new MockHttpServletRequest());
        WebSocketHandler wsHandler = mock(WebSocketHandler.class);
        Map<String, Object> attributes = new HashMap<>();
        return handler.determineUser(request, wsHandler, attributes);
    }
}
