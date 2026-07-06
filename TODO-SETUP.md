# TODO — Setup humain requis avant branch protection stricte

Ce repo vient d'être bootstrappé (skeleton + CI/CD + sécurité). La branch protection actuelle
sur `main` est volontairement **minimale** : elle ne requiert que les checks qui peuvent
réellement passer sans configuration supplémentaire. Voici ce qui manque avant d'aligner ce
repo sur le niveau de protection strict de `pivot-core` (ruleset `main-protection`, 10 checks
requis).

## 1. Créer le projet SonarCloud

- Clé de projet attendue (déjà dans `sonar-project.properties`) : `PIVOT-PLATFORM_pivot-agilite-core`
- Organisation SonarCloud : `pivot-platform`
- `SONAR_TOKEN` est **déjà disponible** en secret GitHub Actions au niveau de l'organisation
  `PIVOT-PLATFORM` (vérifié : visible par ce repo via
  `gh api repos/PIVOT-PLATFORM/pivot-agilite-core/actions/organization-secrets`) — **aucune
  action secret requise**, seule la création du projet côté sonarcloud.io est nécessaire.
- Une fois le projet créé et un premier run vert sur `main`, les jobs "SonarCloud Analysis" et
  "SonarCloud Code Analysis" (Sonar's own PR decoration check) peuvent être ajoutés aux checks
  requis.

## 2. Secrets déjà disponibles (organisation) — aucune action requise

Vérifié identique sur `pivot-core`/`pivot-ui`/`pivot-agilite-core`/`pivot-agilite-ui` :
`GITLEAKS_LICENCE_KEY`, `PLUMBER_TOKEN`, `SEMANTIC_RELEASE_TOKEN`, `SONAR_TOKEN`. Ces 4 secrets
sont au niveau organisation et automatiquement hérités par tout nouveau repo — rien à faire ici.

## 3. Secrets optionnels, non bloquants

- `SEMGREP_APP_TOKEN` : n'existe **nulle part** actuellement (ni org, ni repo, y compris sur
  pivot-core/pivot-ui). Le job "Semgrep - SAST" tourne quand même (règles publiques
  `p/java`, `p/spring`, `p/owasp-top-ten`, etc. en local, sans token) — ce secret ne fait
  qu'activer l'intégration au dashboard Semgrep AppSec. Optionnel.
- `PLUMBER_METADATA_TOKEN` : absent également (ni org ni repo). D'après le commentaire du
  workflow (`security.yml`, job `plumber`), son absence fait juste "skipped" la vérification de
  version des actions tierces — compliance attendue ~93%, au-dessus du seuil de 90% requis par
  le gate. **Ne bloque pas** le job "Plumber - CI/CD Compliance". `PLUMBER_TOKEN` (org secret,
  différent de `PLUMBER_METADATA_TOKEN`) est lui bien présent et donne au job l'accès en
  lecture à la config de branch protection.

## 4. Gap `fr.pivot:pivot-core-starter` — non consommable actuellement

Ce module devrait à terme dépendre de `fr.pivot:pivot-core-starter` (GitHub Packages) pour
`TenantContext`, `@TenantAware`, l'interface `PivotModule`, etc. Vérifié au bootstrap
(2026-07) :

- `pivot-core/pom.xml` ne définit ni module Maven séparé, ni profil `release` (contrairement à
  ce que dit le CLAUDE.md de pivot-core : "pivot-core-starter = artifact publié depuis ce même
  pom.xml via profil release" — cette phrase semble obsolète).
- `pivot-core/.github/workflows/release.yml` publie en réalité
  `fr.pivot:pivot-core:<version>` (ex. `fr/pivot/pivot-core/0.19.1/pivot-core-0.19.1.jar` sur
  GitHub Packages) — le JAR applicatif complet, pas une librairie séparée.
- Ce JAR est de toute façon re-packagé en exécutable par `spring-boot-maven-plugin` (classes
  sous `BOOT-INF/classes/`), donc **inutilisable comme dépendance de compilation standard**
  même sous ses coordonnées réelles.

Ce module ne déclare donc aucune dépendance vers pivot-core pour l'instant (voir CLAUDE.md).
À corriger côté pivot-core avant que ce module (ou pivot-pilotage-core/pivot-collaboratif-core)
puisse réellement consommer du code partagé — cf. Enabler `EN17.1` (backlog pivot-docs,
"Librairies partagées non publiées").

## 5. Une fois 1–4 réglés : passer à la branch protection stricte

Remplacer la branch protection classique actuelle (contexts limités à "Code Quality - Java",
"Tests Backend (TU + TI)", "Maven deploy preview (PR)", "Docker preview image (PR)") et créer
un ruleset `main-protection` équivalent à celui de pivot-core, avec ces 10 checks requis :

- Code Quality - Java
- Tests Backend (TU + TI)
- SCA - Dependency Audit
- Mutation Testing (PITest)
- SonarCloud Analysis
- SonarCloud Code Analysis
- Gitleaks - Secret Scan
- CodeQL - SAST
- Semgrep - SAST
- Plumber - CI/CD Compliance

Référence exacte : `gh api repos/PIVOT-PLATFORM/pivot-core/rulesets/17948736`.
