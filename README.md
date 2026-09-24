# hotel

Project for hotel management

## Running the tests

There are two developer modes.

### Fast / default mode

```bash
mvn test
```

This is the convenient day-to-day command. PostgreSQL/Testcontainers integration tests are annotated
`@Testcontainers(disabledWithoutDocker = true)`, so if Docker is not reachable they are reported as **skipped**,
not failed, and the build still reports `BUILD SUCCESS`. This is intentional: a contributor without Docker can
still run the fast unit/MVC tests.

No environment variables are required for this mode. Test-only fallback values for the few properties that have
no default and are read while any `@SpringBootTest` context boots (`security.jwt-secret`,
`security.bootstrap-admin-username`, `security.bootstrap-admin-password`) live in
`src/test/resources/application.properties`; they are placeholder strings, never real credentials, and never
reach production (`src/main/resources/application.yml` is unchanged and still requires the real
`JWT_SECRET`/`BOOTSTRAP_ADMIN_USERNAME`/`BOOTSTRAP_ADMIN_PASSWORD` environment variables to run the application
itself). `DATABASE_URL`/`DATABASE_USERNAME`/`DATABASE_PASSWORD` are not needed for tests either: every
PostgreSQL integration test supplies its own Testcontainers connection via `@DynamicPropertySource`, which
overrides those properties before the Spring context starts.

### FULL verification mode

```bash
mvn verify -Pintegration
```

Use this before merging a change that touches business logic, migrations, or anything the PostgreSQL
integration suite covers. Unlike the fast mode, it **requires** the PostgreSQL/Testcontainers suite to actually
execute: it fails the build loudly if Docker is unreachable or if any test is skipped for any other reason,
instead of allowing a silently-incomplete `BUILD SUCCESS`.

Mechanically, this activates the `integration` Maven profile, which binds Maven Failsafe's
`integration-test`/`verify` goals. Failsafe runs `PostgresIntegrationSuiteCompletenessIT`
(`src/test/java/com/example/hotel/PostgresIntegrationSuiteCompletenessIT.java`) after the `test` phase's Surefire
reports already exist; that class sums every report's test/skipped/failure/error counts and fails if anything
was skipped, if any failure/error slipped through, or if far fewer tests ran than expected. See its Javadoc for
details. This class is never picked up by plain `mvn test` (Surefire's default include patterns do not match
`*IT.java`), so fast mode is unaffected.

Requirements for this mode:

- **A working Docker environment that Testcontainers can resolve on its own** (no repository configuration
  hardcodes a socket path, and none should). On most machines this just works. On some Docker Desktop for macOS
  installs, Testcontainers' default `UnixSocketClientProviderStrategy` negotiates a stale Docker API version
  against the CLI-compatibility socket and fails with `client version ... is too old`; if you hit that, point
  Testcontainers at Docker Desktop's raw API socket for your user account instead, for example:

  ```bash
  DOCKER_HOST=unix://$HOME/Library/Containers/com.docker.docker/Data/docker.raw.sock \
  TESTCONTAINERS_DOCKER_CLIENT_STRATEGY=org.testcontainers.dockerclient.EnvironmentAndSystemPropertyClientProviderStrategy \
  mvn verify -Pintegration
  ```

  This is a workaround for that specific local Docker Desktop behavior, not a repository requirement; do not add
  it to `~/.testcontainers.properties`, `pom.xml`, or any committed configuration. On Linux CI runners with a
  standard Docker daemon this is normally unnecessary.
- No other environment variables are required beyond what fast mode already needs (see above).
