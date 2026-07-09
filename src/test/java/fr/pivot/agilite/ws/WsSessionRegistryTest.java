package fr.pivot.agilite.ws;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WsSessionRegistry}.
 */
class WsSessionRegistryTest {

    private final WsSessionRegistry registry = new WsSessionRegistry();

    /**
     * Given a registered, open session, when {@link WsSessionRegistry#close} is called with its
     * ID, then the session is actually closed with the given status.
     */
    @Test
    void closesARegisteredOpenSession() throws IOException {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("session-1");
        when(session.isOpen()).thenReturn(true);
        registry.register(session);

        registry.close("session-1", CloseStatus.POLICY_VIOLATION);

        verify(session).close(eq(CloseStatus.POLICY_VIOLATION));
    }

    /**
     * Given a session that has already been unregistered (e.g. closed by the client first),
     * when {@link WsSessionRegistry#close} is called with its former ID, then nothing happens —
     * no exception, no interaction with any session.
     */
    @Test
    void doesNothingForAnUnregisteredSessionId() {
        registry.close("unknown-session", CloseStatus.POLICY_VIOLATION);
        // No exception thrown is the assertion; nothing to verify against, no session exists.
    }

    /**
     * Given a session that was registered then unregistered, when {@link WsSessionRegistry#close}
     * is called afterward, then the session is no longer tracked and is not closed again.
     */
    @Test
    void unregisteredSessionIsNoLongerClosable() throws IOException {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("session-2");
        registry.register(session);
        registry.unregister(session);

        registry.close("session-2", CloseStatus.POLICY_VIOLATION);

        verify(session, never()).close(any());
    }

    /**
     * Given a registered session that is already closed (e.g. a race with a client-initiated
     * disconnect), when {@link WsSessionRegistry#close} is called, then {@code close} is not
     * invoked again on it.
     */
    @Test
    void doesNotReCloseAnAlreadyClosedSession() throws IOException {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("session-3");
        when(session.isOpen()).thenReturn(false);
        registry.register(session);

        registry.close("session-3", CloseStatus.POLICY_VIOLATION);

        verify(session, never()).close(any());
    }

    /**
     * Given a registered session whose {@code close} call itself throws, when
     * {@link WsSessionRegistry#close} is invoked, then the failure is swallowed — the caller has
     * no further recourse and must not be disrupted by it.
     */
    @Test
    void swallowsIoExceptionFromCloseAttempt() throws IOException {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("session-4");
        when(session.isOpen()).thenReturn(true);
        doThrow(new IOException("boom")).when(session).close(any());
        registry.register(session);

        registry.close("session-4", CloseStatus.POLICY_VIOLATION);
        // No exception propagating out of close() is the assertion.
    }

    /**
     * Given a {@code null} session id, when {@link WsSessionRegistry#close} is called, then
     * nothing happens.
     */
    @Test
    void doesNothingForANullSessionId() {
        registry.close(null, CloseStatus.POLICY_VIOLATION);
    }
}
