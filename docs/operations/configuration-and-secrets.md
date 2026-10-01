# Configuration and Secrets

Operational reference for whoever deploys and runs the PMS. It covers the environment variables the
application requires, how it behaves when they are missing, and which credentials must be rotated.

It is not a business specification: business behavior lives in
[../requirements/technical-spec-v1.md](../requirements/technical-spec-v1.md).

---

## 1. Environment variables

The application reads configuration through Spring from `src/main/resources/application.yml`. Local
development copies [`../../.env.example`](../../.env.example) to `.env`, which is git-ignored and
must never be committed.

| Variable | Required | Default | Notes |
| --- | --- | --- | --- |
| `DATABASE_URL` | recommended | `jdbc:postgresql://localhost:5432/hotel` | The default points at a local database and is not suitable for production. |
| `DATABASE_USERNAME` | **yes** | none | No default; startup fails without it. |
| `DATABASE_PASSWORD` | **yes** | none | No default; startup fails without it. |
| `JWT_SECRET` | **yes** | none | Signing secret for issued tokens. Per-environment, long, random. |
| `BOOTSTRAP_ADMIN_USERNAME` | **yes** | none | Username of the initial ADMIN account. |
| `BOOTSTRAP_ADMIN_PASSWORD` | **yes** | none | Must satisfy the application password policy (see §3). |
| `GUEST_DOCUMENTS_PATH` | **yes in production** | `var/guest-documents` | Guest passport image storage. See [storage-and-backup.md](storage-and-backup.md). |
| `ROOM_IMAGES_PATH` | **yes in production** | `var/room-images` | Room image storage. See [storage-and-backup.md](storage-and-backup.md). |
| `HOTEL_I18N_COOKIE_SECURE` | no | `false` | Set to `true` when the PMS is served over HTTPS. |

## 2. Missing configuration fails the start

`DATABASE_USERNAME`, `DATABASE_PASSWORD`, `JWT_SECRET`, `BOOTSTRAP_ADMIN_USERNAME` and
`BOOTSTRAP_ADMIN_PASSWORD` have **no default value in `application.yml`**. If any of them is absent,
the Spring placeholder cannot be resolved and the application refuses to start.

That is deliberate. There is no insecure fallback value for any credential, and none may be added:
a fallback would turn a misconfigured deployment into a silently weakly-secured one.

The only exception to "no defaults" is the test classpath. `src/test/resources/application.properties`
supplies obvious placeholder strings for `security.jwt-secret` and the bootstrap admin pair, because
every `@SpringBootTest` boots beans that read them regardless of what the test exercises. Those
values live only on the test classpath, are never real credentials, and never reach production.

## 3. Bootstrap administrator password policy

`BOOTSTRAP_ADMIN_PASSWORD` is validated against the same policy User Management enforces for every
other account (`com.example.hotel.common.validation.PasswordPolicy`): 8–72 characters, and at most 72
UTF-8 bytes.

- A configured password that violates the policy **aborts startup** with a message that states the
  requirement and never echoes the value. It is not accepted, weakened, or replaced.
- The check runs before the account-existence lookup, so a violating configuration cannot stay
  hidden behind an administrator that an earlier release already created.
- If both the username and the password are configured as blank, no administrator is bootstrapped.
  This is the pre-existing behavior for a deployment that manages its accounts another way; it is
  not a fallback credential.

After the initial administrator exists, change its password through User Management rather than by
editing the environment variable — the variable only ever creates the account.

## 4. Credentials requiring operator rotation

A `.env` file containing real values for the variables below was tracked in git history. Removing it
from tracking (done) does **not** invalidate anything that was already committed, and this task
deliberately does not rewrite git history. Every secret that was ever committed must therefore be
treated as compromised and rotated **outside the repository**, by the operator:

| Category | What to do | Where |
| --- | --- | --- |
| Database password | Change the PostgreSQL role's password, then update `DATABASE_PASSWORD` in every environment that uses it. | PostgreSQL + deployment environment |
| JWT signing secret | Generate a new long random value and set `JWT_SECRET`. Existing tokens stop validating, so sessions must sign in again — schedule accordingly. | Deployment environment |
| Bootstrap ADMIN username / password | Change the existing administrator account's password through User Management, and set `BOOTSTRAP_ADMIN_PASSWORD` (and the username, if it was exposed) to new values. | PMS User Management + deployment environment |

Rotation cannot be performed by changing repository files. Editing `.env`, `.env.example` or
`application.yml` changes what the application *reads*; it does not change the credential that the
database, the token signer, or the account actually accepts.

After rotating, confirm that no environment still uses an old value (the application will fail to
start or fail to authenticate if one does), and keep secrets in the deployment platform's secret
store rather than in a file inside the working tree.

## 5. Handling secrets safely

- Never commit a file containing real credentials. `.env` and `.env.*` are git-ignored;
  `.env.example` is the only tracked variant and holds placeholders only.
- Never paste a credential into an issue, a log, a commit message, or a report. Stating that a
  secret exists and must be rotated is enough.
- The application never logs credential material, and rejection messages never reproduce a
  configured password.
