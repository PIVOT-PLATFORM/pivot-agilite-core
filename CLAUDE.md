# CLAUDE.md — PIVOT-AGILITE-CORE

## Projet

**PIVOT-AGILITE-CORE** — backend Java/Spring Boot du domaine **Agilité** de la suite
collaborative PIVOT : capacity planning, daily standup timer, scrum poker.

Déployé dans sa propre JVM, isolée des autres modules (fault isolation — si ce module tombe,
`pivot-core` et les autres modules restent disponibles). Consomme les concepts partagés
(auth, tenant, équipes, registre de modules) exposés par **pivot-core** ; le frontend Angular
correspondant est **pivot-agilite-ui**. La documentation générale vit dans **pivot-docs**.

### Dépendance `pivot-core-starter` — état réel

`pivot-core` publie réellement `fr.pivot:pivot-core-starter` depuis la version **0.27.0**
(EN17.1, ADR-022) sur GitHub Packages du repo `pivot-core` — la note de gap ci-dessous prédatait
la complétion d'EN17.1 et est obsolète. Ce module en dépend (`pom.xml`, version épinglée
explicitement — jamais devinée) depuis Sprint 8 (US20.1.1, US09.1.1, US14.1.1), pour
`fr.pivot.core.auth.AuthenticatedPrincipal`/`AuthenticatedPrincipalResolver` (consommé par
`fr.pivot.agilite.auth.TokenValidationService`) et `fr.pivot.core.team.Team`/`TeamMember`.
**Épinglé à 0.28.0** (pas 0.27.1, la version que `pivot-collaboratif-core` avait épinglée pour
EN08.3) : 0.28.0 (`pivot-core#211`) marque les dépendances Spring Security du starter
`<optional>true</optional>`, ce qui évite complètement d'imposer l'auto-configuration Spring
Security à ce repo — **aucun `SecurityConfig` n'existe ici**, contrairement au workaround
permit-all qu'a dû ajouter `pivot-collaboratif-core` à 0.27.1 (vérifié via `mvn dependency:tree` :
aucun jar `spring-security-*` sur le classpath).

Packages restants du starter (`fr.pivot.core.modules`, `fr.pivot.core.db`) : pas encore consommés
ici — `PivotModule` et le registre de modules restent à implémenter quand une US le spécifie
explicitement.

Résolution GitHub Packages : `<repositories>` (`pom.xml`, id `pivot-core-packages`) +
`.mvn/settings.xml`/`.mvn/maven.config` (credentials `${env.GITHUB_ACTOR}`/`${env.GITHUB_TOKEN}`)
— mêmes fichiers que `pivot-collaboratif-core`. **Toujours épingler une version réelle et
publiée** — vérifier l'état de publication (`pivot-core/pom.xml`, `gh release list`) avant toute
mise à jour, jamais une version devinée.

**Architecture BDD :** schéma `agilite` (Flyway, propriétaire de ce repo). FK cross-schéma
autorisées **uniquement** vers `public.tenants(id)` et `public.teams(id)` (entités pivot-core)
— jamais vers un autre schéma module (`pilotage`, `collaboratif`).

**Port (dev) :** `8082` — routé par nginx sur `/api/agilite/*` et `/ws/agilite/*` (voir
`pivot-docs/docs/architecture/platform-overview.md`).

**Déploiement :** image Docker JRE dédiée, mêmes garde-fous que pivot-core (Trivy gate,
SLSA L3, GitHub Packages) — voir Stack technique.

---

## Communication

Concise et directe. Techniquement précise. Pas de récapitulatifs inutiles.

**Exceptions (réponses complètes et structurées) :**
- Rédaction ou revue d'US / Epics
- Décisions d'architecture (schéma BDD, contrat de module, dépendance pivot-core)
- Avis cybersécurité ou actions irréversibles — **confirmation obligatoire**
- Backlog et critères d'acceptation

---

## Stack technique

| Couche | Technologie |
|--------|-------------|
| Backend | Java 25 · Spring Boot 4.x · Maven · `--release 24` (pas de preview features) |
| BDD | PostgreSQL 18 (instance partagée) · Spring Data JPA · Flyway · schéma `agilite` |
| Cache / Temps réel | Redis (partagé) · Spring WebSocket (STOMP) — capacity planning, standup timer, scrum poker sont tous trois collaboratifs temps réel |
| Tests | JUnit 5 · Mockito · Testcontainers (TI) |
| Observabilité | Spring Actuator · Micrometer · Prometheus |
| CI/CD | GitHub Actions · SonarCloud · Semantic Release · Plumber |
| Déploiement | Docker · Docker Compose · JVM dédiée (fault isolation) |
| Frontend | → **pivot-agilite-ui** (Angular 22 · lazy-loaded dans pivot-ui) |
| Auth / Tenant / Modules | → **pivot-core** (voir section dépendance `pivot-core-starter` ci-dessus — `fr.pivot.core.auth`/`fr.pivot.core.team` consommés depuis Sprint 8) |

---

## Structure du dépôt

```
pivot-agilite-core/
├── src/
│   ├── main/java/fr/pivot/agilite/
│   │   ├── PivotAgiliteApplication.java
│   │   ├── auth/, context/               # bearer-token auth (pattern EN08.3, Sprint 8)
│   │   ├── config/, exception/           # WebSocketConfig, WebMvcConfig, ClockConfig, GlobalExceptionHandler
│   │   ├── poker/, poker/ws/             # US09.1.1 (rooms) + EN09.1 (isolation WebSocket)
│   │   ├── retro/                        # US20.1.1 (rétrospectives)
│   │   ├── wheel/, team/                 # US14.1.1 (module La Roue)
│   │   └── ws/                           # EN09.1 — plumbing WebSocket partagé
│   ├── main/resources/
│   │   ├── application.yml, application-test.yml
│   │   └── db/migration/      # Flyway schéma agilite — voir règle V1 unique ci-dessous
│   └── test/java/
├── .github/
│   ├── workflows/
│   └── ISSUE_TEMPLATE/
├── .mvn/settings.xml, .mvn/maven.config  # credentials GitHub Packages (pivot-core-starter)
├── .plumber.yaml
└── Dockerfile
```

**Maven :** projet single-module.

**Migrations Flyway — fichier V1 unique avant la BETA :** tant que le schéma `agilite` n'est
pas stabilisé (avant la première BETA du produit), tout changement de schéma est plié dans
l'unique `V1__schema_init.sql` plutôt que d'ajouter un `V2__`/`V3__…` séparé — pas d'historique
de migrations incrémentales à maintenir tant que rien n'est en prod. Ne pas créer de nouveau
fichier de migration numéroté sans feu vert explicite du mainteneur (déclenché au démarrage de
la BETA). `V1__schema_init.sql` contient désormais, en plus du schéma : `agilite.retro_sessions`/
`retro_cards` (US20.1.1), `agilite.poker_rooms` (US09.1.1), `agilite.wheel`/`wheel_entry`
(US14.1.1), `agilite.retro_formats`/`retro_format_columns` + `retro_sessions.custom_format_id`
(US20.2.1), et `agilite.wheel_draw` (US14.2.1).

Frontend Angular → **pivot-agilite-ui**. Documentation → **pivot-docs**. Auth/tenant/modules
partagés → **pivot-core**.

---

## Équipe experte

Toute contribution mobilise les experts concernés — les mentionner explicitement dans la
réponse.

| Expert | Domaine |
|--------|---------|
| **Architecte Java / Spring** | Architecture Spring Boot, patterns (Repository, Service, DTO), SOLID |
| **Architecte BDD PostgreSQL** | Schéma `agilite`, migrations Flyway, index, performances, intégrité référentielle |
| **Architecte Agilité / Scrum** | Sémantique métier capacity planning (vélocité, sprints, charge), daily standup timer, scrum poker (estimation, cartes, consensus) — cohérence fonctionnelle du domaine |
| **Expert DevSecOps** | CI/CD GitHub Actions, SonarCloud, Semgrep, Gitleaks, Plumber, SBOM, Semantic Release |
| **Expert Red Team** | OWASP Top 10, injection SQL, CSRF, IDOR, sécurité WebSocket/STOMP |
| **Expert Blue Team** | Hardening Spring Security côté module, CORS, audit log, réponse aux rapports Red Team |
| **Expert QA** | Stratégie TU/TI, Testcontainers, coverage ≥ 85 %, non-régression |
| **Expert RGPD** | Conformité RGPD/CNIL si données personnelles traitées (ex. historique de vélocité par utilisateur) |
| **Product Owner** | Backlog markdown pivot-docs, Epics, US, critères d'acceptation, priorisation |
| **Scrum Master** | Coordination, sprints, impediments, backlog consistency |
| **Expert PR Review** | Relecture croisée neutre : cohérence architecture, lisibilité, dette technique, respect des standards PIVOT |
| **Experts Angular / UX/UI** | → **pivot-agilite-ui** |

### Faire appel aux experts

| Type de tâche | Expert(s) |
|---------------|-----------|
| Controller, Service, Repository Java | **Architecte Java / Spring** |
| Schéma BDD, migration Flyway, requête @Query | **Architecte BDD PostgreSQL** |
| Règles métier capacity/standup/poker | **Architecte Agilité / Scrum** |
| Tests TU/TI, Testcontainers, couverture | **Expert QA** |
| CI/CD, GitHub Actions, Plumber, SBOM | **Expert DevSecOps** |
| Vulnérabilité sécurité, vecteur d'attaque | **Expert Red Team** → **Expert Blue Team** |
| RGPD, données personnelles | **Expert RGPD** |
| Backlog, US, acceptance criteria | **Product Owner** |
| Review finale PR (après "prêt pour review") | **Expert PR Review** |
| Bug inexpliqué | **Architecte Java** en premier, puis **Expert Red Team** si suspicion sécurité |
| Frontend Angular, SCSS, composants | → **pivot-agilite-ui** |

**Règles :**
- Mentionner l'expert explicitement quand son domaine est engagé.
- Toute faille Red Team = correction Blue Team **avant** tout merge.
- Changement touchant le contrat `PivotModule` ou une dépendance pivot-core = coordination
  **pivot-core ↔ pivot-agilite-core ↔ pivot-agilite-ui obligatoire**.

---

## Backlog — Fichiers markdown

> **Sources de vérité :**
> - Hiérarchie backlog + conventions : `pivot-docs/docs/backlog/README.md`
> - Sprints, assignation US, état avancement : **`pivot-docs/docs/backlog/sprints/`** (un fichier par sprint, index dans `sprints/README.md`)
> - Backlog opérationnel : **fichiers markdown dans `pivot-docs/docs/backlog/`** — un fichier par US/Enabler avec frontmatter (`Stage`, `Priority`, `Phase`, `Module: agilite`).

### Hiérarchie
`EPIC → FEATURE (valeur) / ENABLER (technique) → US` · clé `E01 → F01.1 / EN01.1 → US01.1.1`.

### Champs du Project

| Champ | Valeurs |
|-------|---------|
| Item Type | Epic / Feature / Enabler / US |
| Parent | clé du parent (ex. `E01`, `F01.1`) |
| Stage | ⬜ (pas encore terminé) / ✅ (Done — recette mainteneur). États intermédiaires internes, non persistés → pivot-docs/docs/backlog/README.md §2/§5 |
| Priority | Critical / High / Medium / Low |
| Module | core / auth / admin / oidc / pilotage / **agilite** / collaboratif |
| Phase | Socle / v1-enterprise / phase-3 |
| Sprint | Sprint 1…N |
| Size | XS / S / M / L / XL |

### Template US, Definition of Ready, vagues → `pivot-docs/docs/backlog/README.md`.

---

## Breaking Points

### Step 0 — Challenge PO avant implémentation

Avant tout code, le **PO Agent** challenge les ACs de l'US :

1. Vérifier DoR — story complète, ACs Given/When/Then, AC erreur + sécurité
2. Calculer Gate 1 : **= 100** → procéder · **< 100** → PO Agent réécrit ACs → recalculer
3. AC ambigus à l'implémentation → PO Agent clarifie, jamais d'interprétation unilatérale

Pas de blocage humain — Claude autonome de A à Z sur la validation des ACs.

### Breaking Point 2 : Gate 4 MERGE < 60 ou hard block

Tout PR avec :
- Label `security` ou `breaking-change`
- Gitleaks secret détecté
- Modification du contrat de module (`PivotModule`) sans PRs coordonnées
- Ajout/modification d'une dépendance vers pivot-core-starter

→ Label `needs-human-review` + score breakdown + attendre le mainteneur.

---

## Workflow — Organisation par sprint

Travail organisé par sprint. Référence : **`pivot-docs/docs/backlog/sprints/`** (un fichier par sprint).

**Principes :**
- **Une branche par US / Enabler** — `feat/{us-id}-{slug}` (ex. `feat/us-agilite-1-1-scrum-poker-vote`)
- **Agents en parallèle** — un agent par item du sprint, branches séparées
- **Backlog pivot-docs sur la branche courante** — `sprints/sprint-{N}.md` committé sur la branche de l'item (pas de branche docs séparée)
- **Issue GitHub liée** — avant de démarrer un item, vérifier qu'une issue existe dans **ce repo** pour cet US/Enabler (recherche par id/titre). Absente → la créer (titre `{id} — {titre US}`, corps = lien vers le fichier backlog pivot-docs + AC). **Déjà assignée** (humain ou agent en cours) → item déjà pris, ne pas démarrer, passer au suivant. Sinon → se l'auto-assigner immédiatement (`gh issue edit {N} --add-assignee @me`) avant le premier commit — verrouille l'item, empêche qu'un autre agent ou une autre personne ne le reprenne en parallèle. Référencer l'issue dans la PR (`Closes #N`) — fermeture automatique à la fusion, jamais de fermeture manuelle en double.

## Workflow — Merge séquentiel autonome (plusieurs PR)

Quand plusieurs PR sont ouvertes/en attente sur ce repo (ex. plusieurs items d'un même sprint),
Claude détermine seul l'ordre de fusion et l'exécute de bout en bout, sans confirmation par PR :

1. **Ordre** — dépendances fonctionnelles entre items d'abord, puis fichiers partagés
   (migrations Flyway, config Spring commune) pour minimiser les rebases en cascade.
2. **Par PR, dans cet ordre :**
   - Rebase sur `main` à jour (jamais de merge commit)
   - Conflit → résolution manuelle réelle (jamais `--theirs`/`--ours` aveugle) : lire les deux
     côtés, comprendre l'intention de chacun, fusionner le contenu
   - Rebase sans conflit mais fichier partagé → vérifier quand même qu'aucune régression
     sémantique silencieuse ne s'est introduite (ex. une clé de config écrasée par l'auto-merge git)
   - `mvn verify -q` local avant push (ou vérification équivalente si Docker indisponible en
     sandbox — s'appuyer sur la CI réelle pour la partie Testcontainers)
   - Push, attendre la CI réelle en boucle synchrone (jamais d'attente passive d'une notification)
   - Gate 4 selon les seuils déjà définis ci-dessous → squash-merge dès convergence
3. **Dernier item du sprint courant** (vérifier `pivot-docs/docs/backlog/sprints/sprint-{N}.md`)
   → le commit de squash-merge porte le marqueur de release (voir *Workflow — Release*
   ci-dessous), tous les autres non.
4. Incident CI rencontré en cours de route → diagnostiquer et corriger avant de continuer la
   séquence, pas de contournement silencieux.

## Workflow — Release

Le déclenchement d'une release (`release.yml` : version, publish Maven/Docker, tag, changelog)
n'a lieu **qu'en fin de sprint**, jamais à chaque merge — un merge ordinaire ne doit ni bumper de
version ni publier quoi que ce soit.

- **Déclencheur** : le commit du squash-merge du **dernier item d'un sprint** porte le trailer
  `Release-Trigger: true` **sur sa propre ligne, seul, rien d'autre** (`grep -qxE` — match exact
  de ligne entière, jamais une simple sous-chaîne — cf. incident réel documenté sur
  `pivot-core/CLAUDE.md` et `pivot-ui/CLAUDE.md`, section Workflow — Release).
- **Pourquoi** : sans cette règle, chaque merge déclenche `release.yml` — plusieurs merges
  rapprochés calculeraient tous la même "prochaine version" (aucun tag encore créé entre eux) et
  le second à publier échouerait en conflit sur GitHub Packages.
- **Effet** : la release qui finit par se déclencher regroupe automatiquement, dans une seule
  entrée de changelog, tous les commits accumulés depuis le dernier tag — comportement natif de
  semantic-release, pas une fonctionnalité à coder.
- **Ajout du trailer** : `gh pr merge --squash --body "...

Release-Trigger: true"` — trailer sur sa propre ligne finale, précédée d'une ligne vide, jamais
  intégré dans une phrase. Uniquement sur le merge identifié comme dernier item du sprint courant.

## Workflow — Autoloop PR

Après toute modification sur **toute branche de travail** — US/Enabler (`feat/{us-id}-{slug}`)
ou hors sprint (`fix/`, `refactor/`, `chore/`, `docs/`) — **sans exception** :

1. Ouvrir une PR (draft) vers `main`
2. **Autoloop** (20 itérations max) :
   - **En parallèle :**
     - **Review neutre** — Expert PR Review : architecture, AC, sécurité, dette
     - **CI** — `mvn verify -q` = 0 erreur/warning · Gitleaks clean · Gate 3 hard blocks
   - **Corrections** — tous les findings résolus, commit `fix({scope}): ...`
   - **Convergence** — Gate 4 = 100/100 (ou convergence confirmée sans finding restant) ET CI verte → sortir
3. Gate 4 = 100/100 (ou convergence confirmée sans finding restant) :
   - Sortir la PR du mode draft (`gh pr ready`)
   - État interne Review (Stage frontmatter reste ⬜) + mise à jour de `sprints/sprint-{N}.md` (backlog pivot-docs sur la branche courante — pas de branche docs séparée)
   - **Gate 5** — générer/mettre à jour la spec fonctionnelle et technique figée `pivot-docs/docs/specs/{EPIC}/{us-id}-{slug}.md` (branche/PR `pivot-docs` dédiée — jamais de commit cross-repo)
   - Signal mainteneur
4. Blocage 20 boucles → Breaking Point 2

## Workflow — Ordre d'exécution par US (dans un sprint)

| Étape | Contenu |
|-------|---------|
| **1. Code** | Java + JavaDoc |
| **2. Tests** | JUnit 5 TU + Testcontainers TI — **dans le même commit** |
| **3. Qualité** | Checkstyle · SpotBugs verts |
| **4. Gate 2** | Coverage check : ≥ 85 % → continuer · 70–84 % → compléter · < 70 % → stop |
| **5. Backlog** | Mise à jour `sprints/sprint-{N}.md` + statut US **obligatoire avant commit** |
| **6. E2E** | — (délégué à pivot-agilite-ui) |
| **7. Commit** | `git add` fichier par fichier · commits atomiques sur branche `feat/{us-id}-{slug}` |

> **E2E délégué à pivot-agilite-ui.** Étapes 5 et 7 non différables (Backlog et Commit).

### Approche tests

Écrire le code d'abord, puis les tests couvrant toutes les branches et conditions limites.
TDD strict non utilisé.

**Exception :** quand le contrat d'un service temps réel (standup timer, scrum poker) est
flou — écrire les tests en premier pour forcer la clarification.

---

## Workflow — Vérifications avant push autonome

**Condition absolue avant tout push autonome : 0 erreur, 0 warning.**

```bash
mvn verify -q        # compile + tests + Checkstyle + SpotBugs
```

Rapporter ✅ ou stderr complet. Toute erreur ou warning non justifié = **stop, corriger avant push**.

---

## Workflow — Branches

| Préfixe | Usage | Exemple |
|---------|-------|---------|
| `feat/{us-id}-{slug}` | Implémentation d'une US | `feat/us-agilite-1-1-scrum-poker-vote` |
| `feat/{en-id}-{slug}` | Implémentation d'un Enabler | `feat/en-agilite-1-capacity-schema` |
| `fix/{id}-{slug}` | Correction bug hors sprint | `fix/12-standup-timer-drift` |
| `refactor/{id}-{slug}` | Refactoring hors sprint | `refactor/18-poker-session-service` |
| `chore/{slug}` | CI, deps, config | `chore/plumber-config` |
| `docs/{slug}` | Documentation hors sprint | `docs/adr-capacity-model` |

**Règles :**
- Jamais de travail direct sur `main` (sauf le commit initial de bootstrap de ce repo, déjà effectué — cf. `TODO-SETUP.md` pour le contexte)
- **Une branche = un item de sprint** (US ou Enabler)
- **Backlog pivot-docs committé sur la branche de l'item courant**
- Rebase avant merge → squash WIP
- `git push --force-with-lease` uniquement sur branches de travail

**Création de branche item — procédure obligatoire :**
```bash
git checkout main
git pull origin main
git checkout -b feat/{us-id}-{slug}
```
Branche existante → `git checkout feat/{us-id}-{slug}` directement.

---

## Workflow — Commits

Format **Conventional Commits** (`type(scope): message`) — alimente Semantic Release pour le
versioning automatique (`feat` → minor, `fix` → patch, `feat!` / `BREAKING CHANGE` → major).

| Commit | Contenu typique |
|--------|----------------|
| `feat(db):` | nouvelle migration Flyway (table, colonne, contrainte) → minor bump |
| `fix(db):` | correction migration Flyway existante → patch bump |
| `chore(db):` | seeds test, commentaires schéma (sans impact utilisateur) |
| `feat(backend):` | service, repository, controller |
| `fix(backend):` | correction bug backend |
| `feat(api):` | endpoint REST, DTO |
| `fix(api):` | correction endpoint ou contrat API |
| `test:` | ajout ou correction de tests (TU, TI) sans changement de code prod |
| `feat(capacity):` / `feat(standup):` / `feat(poker):` | feature métier du sous-domaine concerné |
| `feat(ws):` | WebSocket, STOMP handlers (timer standup, session poker temps réel) |
| `fix(ws):` | correction bug WebSocket / STOMP |
| `ci:` | GitHub Actions workflows, Plumber |
| `docs:` | README, CLAUDE.md, ADR |
| `security:` | correctif sécurité — **hard block Gate 4, review humaine** · label `security` posé automatiquement |

Co-author sur chaque commit : `Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>`

---

## Gates ACDD — Confidence Gates

Score 0–100, jamais booléen. Scores/décisions consignés en **commentaire de PR** (plus de
dossier `gates/`). Le statut vit dans le champ **Stage** du frontmatter US (pivot-docs).

| Gate | Moment | Seuils |
|------|--------|--------|
| **1 — READINESS** | Avant implémentation | PO Agent self-challenge · = 100 → état interne Ready → procéder (Stage frontmatter reste ⬜) · < 100 → PO Agent réécrit ACs |
| **2 — COVERAGE** | Par commit | ≥ 85 → continuer · 70–84 → compléter tests · < 70 → stop |
| **3 — QUALITY** | Après CI verte | Hard blocks : secret Gitleaks, label `security`/`breaking-change`, modif contrat module |
| **4 — MERGE CONFIDENCE** | Avant merge | = 100/100 → sortie du mode draft (merge autonome) · 60–99 → merge documenté · < 60 → Breaking Point 2 |

**Checks Gate 1 :** AC testables (40) · dépendances résolues (20) · impact contrat module (15) · AC sécurité ≥ 1 (15) · pas de cycle (10)

**Checks Gate 2 :** AC couverts (50) · pas de code non testé (30) · tests non triviaux (20)

**Checks Gate 3 :** SonarCloud ≥ 80 % (25) · zéro finding critique/high (25) · linters clean (20) · Gitleaks clean (20) · build Docker (10)

**Format du commentaire de PR (gate)** : `gate` (READINESS | COVERAGE | QUALITY | MERGE_CONFIDENCE), `score`, `decision`, `breakdown`, `notes`.

---

## Standards de code

### Java (backend)

- JavaDoc sur toutes les classes et méthodes publiques
- Checkstyle (config projet — voir `checkstyle.xml`)
- SpotBugs — zéro warning ignoré · aucune suppression inline (`@SuppressFBWarnings`) sans validation explicite du mainteneur
- Pas de logique dans les contrôleurs — déléguer aux services
- DTOs pour toutes les entrées/sorties API — **jamais les entités JPA directement**
- Pas de `@Transactional` sur les contrôleurs — uniquement sur les services

### Général

- Pas de secrets dans le code — variables d'environnement
- Toute action state-changing → log structuré JSON (backend)
- **`// NOSONAR` : zéro, jamais.** Tout faux positif Sonar se marque côté SonarCloud (UI "Won't fix" / "False positive", ou exclusion centralisée) — aucune exception.
- **`// nosemgrep` : interdit par défaut**, autorisé **uniquement avec la validation explicite du mainteneur**. Sans validation, exclusion côté config Semgrep (`.semgrepignore` / `--exclude-rule`), jamais en commentaire inline.

---

## Règle transversale sécurité — Isolation tenant

**Tout endpoint `/api/agilite/*` :**
- Extrait le `tenantId`/`userId` **exclusivement** du `RequestPrincipal` résolu depuis le token
  porteur (`fr.pivot.agilite.context.RequestPrincipalResolver` — délègue à
  `fr.pivot.core.auth.AuthenticatedPrincipalResolver` du starter, implémenté par
  `TokenValidationService`)
- N'accepte **jamais** un `tenantId` ou `userId` venant du body JSON, d'un query param ou d'un header custom
- Si `{resourceId}` dans le path → vérifier l'appartenance au tenant courant **avant** tout traitement
- Appartenance invalide → **404** (pas 403 — ne pas confirmer l'existence de la ressource cross-tenant)
- Test TI cross-tenant **obligatoire** sur chaque endpoint

---

## Règles absolues

| Interdit | Raison |
|----------|--------|
| `--no-verify` | Contourne les hooks qualité |
| `git push origin main` (push direct) | Jamais, hors bootstrap initial de ce repo (déjà effectué) — tout code passe par PR + review |
| `git push --force` sur `main` | Jamais — le mainteneur uniquement si nécessaire |
| `git add .` en bloc | Risque d'inclure `.env`, clés, binaires |
| Merger avec label `security` sans revue humaine | Hard block Gate 4 |
| Commiter `.env`, tokens, secrets, certificats | Exposition définitive |
| Entités JPA exposées directement en API | Fuite de schéma, IDOR |
| Logique métier dans les contrôleurs | Viole la séparation des couches |
| FK vers un schéma module autre que `public` | Viole l'isolation multi-schéma de la plateforme |
| Implémenter sans US tracée dans les fichiers markdown backlog | Perte de traçabilité |
| `tenantId` extrait du body / header dans un endpoint `/api/agilite/*` | IDOR cross-tenant — extrait exclusivement du `TenantContext` du token porteur |

---

## Boucles de problèmes — règle d'escalade

### Limite 10 commandes en échec successif

Si **10 commandes consécutives échouent** (toute combinaison : build, test, lint, push, CI) sur une tâche :
1. **Stopper la tâche courante** — ne pas impacter les agents parallèles sur d'autres US
2. **Poster un commentaire de gate** avec `decision: ESCALATED`, liste des 10 échecs, contexte
3. **Label `needs-human-review`** + signal mainteneur
4. **Proposer une alternative** (approche différente, découpage)

Le compteur se remet à zéro dès qu'une commande réussit.

### Limite 20 push — autoloop PR Review

Voir section **Workflow — Autoloop PR** — au-delà de 20 push correctifs → Breaking Point 2 automatique.

### Règle 2 tentatives (stratégie identique)

Après **2 tentatives** (même stratégie ou variantes proches) :
1. **Stopper** — ne pas continuer à boucler
2. **Poster un commentaire de gate sur la PR** avec `decision: ESCALATED`, contexte complet, tentatives effectuées — **jamais committer un fichier de gate**
3. **Signaler** au mainteneur : blocage, tentatives, raison de l'échec — label `needs-human-review`
4. **Proposer** une alternative : approche différente, outil différent, contournement

Ne jamais enchaîner plus de 2 tentatives sans informer le mainteneur.

---

## Parallélisation

Lancer un maximum d'actions en parallèle dans chaque message :

| Actions parallélisables | Exemples |
|------------------------|---------|
| Lectures indépendantes | Plusieurs `Read` / `Grep` / `Glob` |
| Linters | Checkstyle + SpotBugs lancés simultanément |
| Créations de fichiers indépendants | TU + TI d'une même feature |
| Recherches codebase | Plusieurs `Grep` sur cibles différentes |

Ne séquencer que ce qui dépend du résultat d'une étape précédente.
