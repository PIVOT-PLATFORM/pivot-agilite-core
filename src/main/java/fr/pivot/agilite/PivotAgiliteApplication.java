package fr.pivot.agilite;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Point d'entrée de l'application PIVOT-AGILITE-CORE. */
@SpringBootApplication(scanBasePackages = "fr.pivot.agilite")
@ConfigurationPropertiesScan("fr.pivot.agilite")
public class PivotAgiliteApplication {

    /** Démarre l'application Spring Boot. */
    public static void main(String[] args) {
        SpringApplication.run(PivotAgiliteApplication.class, args);
    }
}
