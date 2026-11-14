# Technology baseline and update policy

**Verified against upstream release pages on 2026-11-13; scaffolding resolution verified on 2026-11-14 (see “Scaffolding verification” below).** This is a planning baseline, not a dependency lockfile. Recheck before each release. Use the latest **stable/GA** releases; exclude snapshots, milestones, release candidates, and early access.

Canonical source repository: [patbaumgartner/social-cross-poster](https://github.com/patbaumgartner/social-cross-poster). Planned image location: `ghcr.io/patbaumgartner/social-cross-poster`; no published image is claimed.

## Current release baseline

| Technology | Stable release observed | Source / implementation rule |
|---|---|---|
| Spring Boot + Maven plugin | 4.1.2 | [Upstream release](https://github.com/spring-projects/spring-boot/releases/tag/v4.1.2); align plugin and dependency-management versions |
| Java | Java 27; use latest security patch for selected distribution | [Java releases](https://www.oracle.com/java/technologies/downloads/); [Boot compatibility](https://docs.spring.io/spring-boot/system-requirements.html) includes Java 27; do not default to an older LTS |
| Maven | 3.10.0 | [Official download](https://maven.apache.org/download.cgi); pin through Maven Wrapper with checksum |
| Thymeleaf | 3.1.7.RELEASE | [Official download](https://www.thymeleaf.org/download.html); use the current Boot integration and verify compatibility |
| HTMX | 4.0.1 | [Upstream release](https://github.com/bigskysoftware/htmx/releases/tag/v4.0.1); use [v4 documentation](https://four.htmx.org/docs/), not v2 snippets or an npm dist-tag |
| PostgreSQL | 18.7 | [Official announcement](https://www.postgresql.org/about/news/postgresql-187-1712-1616-1520-1425-and-19-rc-2-released-3442/); PostgreSQL 19 RC2 is a prerelease |
| Flyway Engine | 13.12.2 | [Release notes](https://documentation.red-gate.com/fd/release-notes-for-flyway-engine-179732572.html); align engine and PostgreSQL module, test migrations |
| Paketo Noble Java Tiny builder | 0.0.212 | [Builder release](https://github.com/paketo-buildpacks/builder-noble-java-tiny/releases/tag/v0.0.212); candidate until Java/image-processing/architecture probes pass |
| Paketo Java buildpack | 22.7.3 | [Buildpack release](https://github.com/paketo-buildpacks/java/releases/tag/v22.7.3); inspect what the selected builder actually includes |
| Docker Engine | 29.9.3 | [Engine release notes](https://docs.docker.com/engine/release-notes/29/); upstream build/CI baseline; the owner's NAS runs 24.0.2 and needs separate deployment compatibility tests |
| Docker Compose plugin | 5.7.1 | [Upstream release](https://github.com/docker/compose/releases/tag/v5.7.1); use the `docker compose` command; deployment must also work with the owner's 2.20.1 |
| WireMock | 3.13.2 | [Upstream release](https://github.com/wiremock/wiremock/releases/tag/3.13.2); isolate mock-server runtime dependencies from application runtime |
| Testcontainers Java | 2.0.5 | [Upstream release](https://github.com/testcontainers/testcontainers-java/releases/tag/2.0.5); test with the chosen Docker API/runtime |

Java patch numbers differ by distribution. The Java download source identifies Oracle's latest patch; it does not establish the exact runtime supplied by Paketo. Select the latest patched supported buildpack JVM distribution, set the required major explicitly, and record the resolved vendor/version from the image SBOM. Missing current JVM/architecture support is an explicit compatibility gap to resolve, not permission to silently ship an older runtime.

## All dependencies, not just this table

- Start from the latest stable Spring Boot BOM for Spring Framework/Security, Hibernate, Jackson, logging, JDBC, validation, connection pool, and test libraries. Inventory resolved versions and compare them with current upstream stable releases.
- If a managed dependency trails a stable upstream release, validate a deliberate override or update the compatible stack. Record any unresolved incompatibility, owner, and recheck date; do not claim “all latest” while hiding older managed versions.
- Pin direct libraries, Maven plugins/wrapper, local frontend assets, GitHub Actions, builder/buildpacks/run images, PostgreSQL images, and optional bridge versions. Choose current stable versions first, then pin exact versions/digests/commit SHAs for reproducibility.
- SDKs are optional; Java HTTP integration can avoid unnecessary provider SDK dependencies. Any introduced SDK follows the same inventory/update rules.
- Automated update PRs cover Maven, actions, frontend assets, and image digests. A release check refreshes this baseline, resolves the dependency tree/SBOM, tests the resulting combination, and surfaces exceptions.
- “Latest” is a maintenance policy, not a floating production tag. Build once, promote the tested immutable image, and regularly rebuild on new patched builder/run images.
- The NAS OS/proxy/container runtime is operator-managed. The owner reports Docker Engine 24.0.2, Compose 2.20.1 and Traefik on the DS918+; test this reference deployment independently from the latest upstream build/CI baseline. Hostnames, external origin and trusted proxies are installation configuration. No exact Traefik version or product-design hostname is required; extra host/proxy version details help diagnose installation-specific issues. No untested NAS runtime upgrade is implied by the application technology policy.

## Buildpacks first

Use the Spring Boot Maven plugin's [Cloud Native Buildpacks support](https://docs.spring.io/spring-boot/maven-plugin/build-image.html) with a current Paketo builder and matching run image. Configure `BP_JVM_VERSION=27` for this baseline and inspect the resulting runtime; never rely on the builder's default Java version. See [Paketo Java configuration](https://paketo.io/docs/howto/java/).

Build/test separately for `linux/amd64` and `linux/arm64`, preferably on native runners. The plugin's image-platform setting selects one platform; it does not create a multi-platform manifest. Combine successfully tested architecture images into one OCI index and record all digests.

No application Dockerfile is planned by default. First select a maintained builder that supplies the required JDK, CA certificates, fonts/image libraries, and architecture. A custom Dockerfile is an exception only after a demonstrated buildpack limitation, with a documented reason and equivalent maintenance/testing requirements. Existing third-party service images are consumed upstream; the app does not rebuild PostgreSQL with buildpacks.

## Scaffolding verification (2026-11-14)

The P0 scaffold was resolved and built on 2026-11-14; `./mvnw clean verify` is green (10/10 tests on H2 in PostgreSQL mode). All artifacts below were verified on Maven Central / upstream with HTTP 200 on 2026-11-14:

| Input | Resolved value | Pin |
|---|---|---|
| Spring Boot parent + BOM + plugins | 4.1.2 | `pom.xml` parent version |
| Java | 27 (27.0.1 BellSoft locally; CI uses Temurin 27) | `maven.compiler.release=27`, `JAVA_VERSION: "27"` in CI |
| Maven | 3.10.0 | Maven Wrapper `.mvn/wrapper/maven-wrapper.properties`, `distributionSha256Sum=1f6d9909266510f039f59aa0e13dcd2c66da85f043e56276e41f2918f8bddaff` |
| Thymeleaf (+ Spring integration) | 3.1.7.RELEASE | **Deliberate override**: Boot 4.1.2 manages 3.1.5.RELEASE; the pom sets `<thymeleaf.version>3.1.7.RELEASE</thymeleaf.version>` per this baseline's latest-stable rule |
| Flyway core + PostgreSQL module | 13.12.2 | **Deliberate override**: Boot 4.1.2 manages Flyway 12.4.0; the pom sets `<flyway.version>13.12.2</flyway.version>` |
| spring-javaformat-maven-plugin | 0.0.48 | `pom.xml` build plugin; `validate` runs in `verify` and CI |
| HTMX | 4.0.1 | Pinned local asset `src/main/resources/static/assets/htmx-4.0.1.min.js`, sha256 `a0abb04f2f45a1f89d1d4844023245537e5e6014c87b144553e0607b4cc32130` (37448 bytes, from the upstream v4.0.1 release) |
| PostgreSQL image | 18.7 | `docker-compose.yml` pins `postgres:18.7@sha256:9f4fd4aa918f52ae3d6f3b687f1de90d8f867cd4d520021545a9a50c120eae3b` (tag digest from Docker Hub, 2026-11-14) |
| Image (planned) | `ghcr.io/patbaumgartner/social-cross-poster` | Buildpacks via `spring-boot:build-image`, `BP_JVM_VERSION=27`; **not published yet** |

Spring Boot 4 API drift encountered during scaffolding and resolved: `@AutoConfigureMockMvc` lives in `org.springframework.boot.webmvc.test.autoconfigure` (starter `spring-boot-starter-webmvc-test`), `formLogin()` is `SecurityMockMvcRequestBuilders.formLogin` (Spring Security 7.1.1), actuator's `EndpointRequest` is `org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest`, and the `RestClient.Builder` auto-configuration moved into the dedicated `spring-boot-starter-restclient` starter (added to the pom for the UC-02 provider probes, 2026-11-14).
