package fr.pivot.agilite;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Point d'entrée de l'application PIVOT-AGILITE-CORE.
 *
 * <p>US20.1.1 — first consumer in this repo of {@code fr.pivot.core.team.Team}/{@code
 * TeamMember}/{@code TeamRepository}/{@code TeamMemberRepository}, exported as-is by {@code
 * pivot-core-starter}. {@code @SpringBootApplication(scanBasePackages = "fr.pivot.agilite")}
 * deliberately keeps general component scanning scoped to this repo's own package (the
 * starter's own beans are wired via its {@code AutoConfiguration.imports}, not regular
 * component-scan) — but Spring Data JPA's entity and repository scanning default to that same
 * narrow base package too, which would silently miss {@code fr.pivot.core.team}'s entities and
 * repositories. {@link EntityScan} and {@link EnableJpaRepositories} are declared explicitly
 * below to extend just those two scans to also cover {@code fr.pivot.core.team}, without
 * widening general component scanning.
 *
 * <p>{@link EnableScheduling} (US20.1.2a) — required for {@code RetroPhaseScheduler}'s {@code
 * @Scheduled} timer-expiry check (CONTRIBUTION → REVUE auto-transition) to actually run; no
 * scheduled task existed anywhere in this repo before this US.
 */
@SpringBootApplication(scanBasePackages = "fr.pivot.agilite")
@ConfigurationPropertiesScan("fr.pivot.agilite")
@EntityScan(basePackages = {"fr.pivot.agilite", "fr.pivot.core.team"})
@EnableJpaRepositories(basePackages = {"fr.pivot.agilite", "fr.pivot.core.team"})
@EnableScheduling
public class PivotAgiliteApplication {

    /** Démarre l'application Spring Boot. */
    public static void main(String[] args) {
        SpringApplication.run(PivotAgiliteApplication.class, args);
    }
}
