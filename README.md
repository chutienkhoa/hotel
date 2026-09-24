# hotel

Project for hotel management

## Documentation

- Business source of truth: [docs/requirements/technical-spec-v1.md](docs/requirements/technical-spec-v1.md)
- Coding and architecture rules: [docs/coding-rules/](docs/coding-rules/)
- Running it in production:
  - [docs/operations/configuration-and-secrets.md](docs/operations/configuration-and-secrets.md) —
    required environment variables, startup behavior when they are missing, credential rotation
  - [docs/operations/storage-and-backup.md](docs/operations/storage-and-backup.md) — persistent
    file-storage requirement, and the database + files backup/restore runbook

Local development needs a `.env`; copy [.env.example](.env.example) and replace every value. `.env`
is git-ignored and must never be committed.

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
  hardcodes a socket path or an API version, and none should). On most machines this just works. On some Docker
  Desktop for macOS installs, Testcontainers' bundled docker-java client negotiates a stale Docker API version
  and every PostgreSQL test stands down with `client version ... is too old. Minimum supported API version is
  ...`. If you hit that, pin the client API version for the test JVM on the command line:

  ```bash
  mvn verify -Pintegration -DargLine="-Dapi.version=1.44"
  ```

  Any version the daemon supports works (`docker version` prints its API version). If the daemon socket itself
  cannot be reached, additionally point Testcontainers at Docker Desktop's socket for your user account, for
  example:

  ```bash
  DOCKER_HOST=unix://$HOME/.docker/run/docker.sock \
  TESTCONTAINERS_DOCKER_CLIENT_STRATEGY=org.testcontainers.dockerclient.EnvironmentAndSystemPropertyClientProviderStrategy \
  mvn verify -Pintegration -DargLine="-Dapi.version=1.44"
  ```

  Note that Docker Desktop's *raw* socket (`.../Data/docker.raw.sock`) cannot be bind-mounted into a container,
  so Testcontainers' resource reaper fails to start with it; use the standard socket above.

  These are workarounds for specific local Docker Desktop behavior, not repository requirements; do not add them
  to `~/.testcontainers.properties`, `pom.xml`, or any committed configuration, and do not upgrade Testcontainers
  or docker-java to work around a local Docker installation. On Linux CI runners with a standard Docker daemon
  this is normally unnecessary.
- No other environment variables are required beyond what fast mode already needs (see above).
