package fr.pivot.agilite.auth;

import fr.pivot.agilite.auth.entity.PlatformAccessToken;
import fr.pivot.agilite.auth.entity.PlatformTenant;
import fr.pivot.agilite.auth.entity.PlatformUser;
import fr.pivot.agilite.auth.repository.AccessTokenReadRepository;
import fr.pivot.agilite.auth.repository.PlatformTenantReadRepository;
import fr.pivot.agilite.auth.repository.PlatformUserReadRepository;
import fr.pivot.core.auth.AuthenticatedPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TokenValidationService} covering every rejection branch (EN08.3,
 * ADR-022) — this is security-critical code (bearer token validation), so every collapse-to-
 * {@code Optional.empty()} path is exercised individually, not just the happy path.
 */
@ExtendWith(MockitoExtension.class)
class TokenValidationServiceTest {

    @Mock
    private AccessTokenReadRepository accessTokenRepository;

    @Mock
    private PlatformUserReadRepository userRepository;

    @Mock
    private PlatformTenantReadRepository tenantRepository;

    private TokenValidationService service;

    private static final String RAW_TOKEN = "some-raw-token";
    private static final Long USER_ID = 1L;
    private static final Long TENANT_ID = 100L;

    /** Initialises the service under test with mocked dependencies. */
    @BeforeEach
    void setUp() {
        service = new TokenValidationService(accessTokenRepository, userRepository, tenantRepository);
    }

    /**
     * Given a {@code null} raw token, when resolve() is called,
     * then it returns empty without touching any repository.
     */
    @Test
    void resolve_whenTokenNull_returnsEmpty() {
        assertThat(service.resolve(null)).isEmpty();
    }

    /**
     * Given a blank raw token, when resolve() is called,
     * then it returns empty without touching any repository.
     */
    @Test
    void resolve_whenTokenBlank_returnsEmpty() {
        assertThat(service.resolve("   ")).isEmpty();
    }

    /**
     * Given a token hash unknown to {@code public.access_tokens}, when resolve() is called,
     * then it returns empty.
     */
    @Test
    void resolve_whenTokenUnknown_returnsEmpty() {
        when(accessTokenRepository.findByTokenHashAndStatus(anyString(), eq("active")))
                .thenReturn(Optional.empty());

        assertThat(service.resolve(RAW_TOKEN)).isEmpty();
    }

    /**
     * Given a token that is already expired, when resolve() is called,
     * then it returns empty.
     */
    @Test
    void resolve_whenTokenExpired_returnsEmpty() {
        PlatformAccessToken token = tokenExpiringAt(Instant.now().minusSeconds(10));
        when(accessTokenRepository.findByTokenHashAndStatus(anyString(), eq("active")))
                .thenReturn(Optional.of(token));

        assertThat(service.resolve(RAW_TOKEN)).isEmpty();
    }

    /**
     * Given a valid token whose owning user no longer exists, when resolve() is called,
     * then it returns empty.
     */
    @Test
    void resolve_whenUserNotFound_returnsEmpty() {
        PlatformAccessToken token = tokenExpiringAt(Instant.now().plusSeconds(3600));
        when(accessTokenRepository.findByTokenHashAndStatus(anyString(), eq("active")))
                .thenReturn(Optional.of(token));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThat(service.resolve(RAW_TOKEN)).isEmpty();
    }

    /**
     * Given a valid token and user whose tenant no longer exists, when resolve() is called,
     * then it returns empty.
     */
    @Test
    void resolve_whenTenantNotFound_returnsEmpty() {
        PlatformAccessToken token = tokenExpiringAt(Instant.now().plusSeconds(3600));
        PlatformUser user = userWith(TENANT_ID, "ROLE_USER", true);
        when(accessTokenRepository.findByTokenHashAndStatus(anyString(), eq("active")))
                .thenReturn(Optional.of(token));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.empty());

        assertThat(service.resolve(RAW_TOKEN)).isEmpty();
    }

    /**
     * Given a token issued before its tenant's last invalidation timestamp, when resolve() is
     * called, then it returns empty (bulk tenant-deactivation revocation).
     */
    @Test
    void resolve_whenTenantInvalidatedAfterTokenIssued_returnsEmpty() {
        Instant createdAt = Instant.now().minusSeconds(3600);
        Instant invalidatedAt = Instant.now().minusSeconds(60);
        PlatformAccessToken token = tokenWith(createdAt, Instant.now().plusSeconds(3600));
        PlatformUser user = userWith(TENANT_ID, "ROLE_USER", true);
        PlatformTenant tenant = tenantWith(invalidatedAt);
        when(accessTokenRepository.findByTokenHashAndStatus(anyString(), eq("active")))
                .thenReturn(Optional.of(token));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.of(tenant));

        assertThat(service.resolve(RAW_TOKEN)).isEmpty();
    }

    /**
     * Given a token issued after its tenant's last invalidation timestamp, when resolve() is
     * called, then the tenant-invalidation check passes (only earlier-issued tokens are
     * rejected).
     */
    @Test
    void resolve_whenTokenIssuedAfterTenantInvalidation_isNotRejectedByThatCheck() {
        Instant invalidatedAt = Instant.now().minusSeconds(3600);
        Instant createdAt = Instant.now().minusSeconds(60);
        PlatformAccessToken token = tokenWith(createdAt, Instant.now().plusSeconds(3600));
        PlatformUser user = userWith(TENANT_ID, "ROLE_USER", true);
        PlatformTenant tenant = tenantWith(invalidatedAt);
        when(accessTokenRepository.findByTokenHashAndStatus(anyString(), eq("active")))
                .thenReturn(Optional.of(token));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.of(tenant));

        assertThat(service.resolve(RAW_TOKEN)).isPresent();
    }

    /**
     * Given a tenant that was never invalidated ({@code null} timestamp), when resolve() is
     * called, then the tenant-invalidation check passes.
     */
    @Test
    void resolve_whenTenantNeverInvalidated_isNotRejectedByThatCheck() {
        PlatformAccessToken token = tokenExpiringAt(Instant.now().plusSeconds(3600));
        PlatformUser user = userWith(TENANT_ID, "ROLE_USER", true);
        PlatformTenant tenant = tenantWith(null);
        when(accessTokenRepository.findByTokenHashAndStatus(anyString(), eq("active")))
                .thenReturn(Optional.of(token));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.of(tenant));

        assertThat(service.resolve(RAW_TOKEN)).isPresent();
    }

    /**
     * Given a valid token whose user has been deactivated, when resolve() is called,
     * then it returns empty.
     */
    @Test
    void resolve_whenUserInactive_returnsEmpty() {
        PlatformAccessToken token = tokenExpiringAt(Instant.now().plusSeconds(3600));
        PlatformUser user = userWith(TENANT_ID, "ROLE_USER", false);
        PlatformTenant tenant = tenantWith(null);
        when(accessTokenRepository.findByTokenHashAndStatus(anyString(), eq("active")))
                .thenReturn(Optional.of(token));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.of(tenant));

        assertThat(service.resolve(RAW_TOKEN)).isEmpty();
    }

    /**
     * Given a fully valid token/user/tenant, when resolve() is called,
     * then it returns the resolved {@link AuthenticatedPrincipal} with the correct fields.
     */
    @Test
    void resolve_whenAllValid_returnsPrincipal() {
        PlatformAccessToken token = tokenExpiringAt(Instant.now().plusSeconds(3600));
        PlatformUser user = userWith(TENANT_ID, "ROLE_ADMIN", true);
        PlatformTenant tenant = tenantWith(null);
        when(accessTokenRepository.findByTokenHashAndStatus(anyString(), eq("active")))
                .thenReturn(Optional.of(token));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.of(tenant));

        Optional<AuthenticatedPrincipal> result = service.resolve(RAW_TOKEN);

        assertThat(result).isPresent();
        assertThat(result.get().userId()).isEqualTo(USER_ID);
        assertThat(result.get().tenantId()).isEqualTo(TENANT_ID);
        assertThat(result.get().role()).isEqualTo("ROLE_ADMIN");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private PlatformAccessToken tokenExpiringAt(final Instant expiresAt) {
        return tokenWith(Instant.now().minusSeconds(3600), expiresAt);
    }

    private PlatformAccessToken tokenWith(final Instant createdAt, final Instant expiresAt) {
        PlatformAccessToken token = newInstance(PlatformAccessToken.class);
        setField(token, "id", 1L);
        setField(token, "userId", USER_ID);
        setField(token, "tokenHash", "hash");
        setField(token, "status", "active");
        setField(token, "expiresAt", expiresAt);
        setField(token, "createdAt", createdAt);
        return token;
    }

    private PlatformUser userWith(final Long tenantId, final String role, final boolean active) {
        PlatformUser user = newInstance(PlatformUser.class);
        setField(user, "id", USER_ID);
        setField(user, "tenantId", tenantId);
        setField(user, "role", role);
        setField(user, "active", active);
        return user;
    }

    private PlatformTenant tenantWith(final Instant invalidationTimestamp) {
        PlatformTenant tenant = newInstance(PlatformTenant.class);
        setField(tenant, "id", TENANT_ID);
        setField(tenant, "tenantInvalidationTimestamp", invalidationTimestamp);
        return tenant;
    }

    /**
     * Instantiates {@code type} via its protected no-arg JPA constructor — these read-only
     * mirror entities live in a different package ({@code fr.pivot.agilite.auth.entity}) than
     * this test, so the constructor is not otherwise accessible.
     */
    private static <T> T newInstance(final Class<T> type) {
        try {
            var constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Failed to instantiate " + type + " in test", ex);
        }
    }

    private static void setField(final Object target, final String fieldName, final Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Failed to set field " + fieldName + " in test", ex);
        }
    }
}
