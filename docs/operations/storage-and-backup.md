# File Storage, Backup and Restore

Operational reference for whoever deploys and runs the PMS. It defines where private files must live
in production, and the minimum backup and restore procedure for a small hotel.

It is not a business specification: business behavior lives in
[../requirements/technical-spec-v1.md](../requirements/technical-spec-v1.md). Environment variables
and credential handling are in [configuration-and-secrets.md](configuration-and-secrets.md).

---

## 1. What the PMS stores outside the database

Two kinds of binary file are stored on the filesystem, not in PostgreSQL:

| Content | Configured by | Development default |
| --- | --- | --- |
| Guest passport images | `GUEST_DOCUMENTS_PATH` | `var/guest-documents` |
| Room images | `ROOM_IMAGES_PATH` | `var/room-images` |

Each file is written under a **server-generated storage key** (a random UUID plus the validated
extension). The database row holds that key, and only the key is ever used to resolve the file; the
client never sees a storage key or a filesystem path. Neither directory is ever served as static
content: every read goes through an authorized application endpoint.

The two roots are separate on purpose, so one domain's storage key can never resolve a file
belonging to the other.

## 2. Development

Relative paths are fine locally and remain the default, so a checkout needs no extra configuration.
Both development directories are git-ignored. Nothing in this document changes local development.

## 3. Production requirement

**In production, both paths must be set explicitly to persistent filesystem locations outside the
ephemeral application deployment storage.**

```text
GUEST_DOCUMENTS_PATH=<absolute path on persistent storage>
ROOM_IMAGES_PATH=<absolute path on persistent storage>
```

The reason is that the development defaults are *relative to the process working directory*. On a
deployment whose filesystem is replaced on each release (a container image, a re-provisioned VM, an
unpacked archive in a new directory), a relative path resolves inside storage that does not survive
the next deploy. The database rows would survive and keep pointing at storage keys whose files are
gone, so every passport and room image would become unopenable while the PMS looks healthy.

For V1 a persistent mounted filesystem is sufficient. Object storage (S3 or equivalent) is
explicitly out of scope.

A conventional layout — an example, not a requirement:

```text
/opt/hotel/data/guest-documents
/opt/hotel/data/room-images
```

Choose whatever suits the host. No OS-specific path is hardcoded anywhere in the application, and
none may be: the paths are configuration, never business logic.

Checklist for the two directories:

- on storage that survives a redeploy, a restart and a host reboot;
- writable by the PMS process user, and readable by nothing that does not need them (passport images
  are sensitive personal data);
- included in the backup set below;
- never inside the deployment directory that a release replaces.

## 4. The minimum V1 backup set

```text
PostgreSQL database
        +
Guest passport files (GUEST_DOCUMENTS_PATH)
        +
Room image files (ROOM_IMAGES_PATH)
```

**A PostgreSQL backup alone is not a complete PMS backup.** The database holds the metadata that
references storage keys; the files themselves are on the filesystem. Restoring only the database
leaves every passport and room image permanently unopenable, with no way to recover them from within
the PMS.

## 5. Backup procedure

1. Take a PostgreSQL backup (`pg_dump`, or the equivalent your deployment/hosting provides).
2. Back up the entire `GUEST_DOCUMENTS_PATH` directory.
3. Back up the entire `ROOM_IMAGES_PATH` directory.
4. Store the backup **off-host**, so losing the machine does not lose the backup with it.
5. Keep the database backup and both file backups **associated with the same restore point** — label
   them together and keep them together. A database backup paired with older file backups restores
   rows whose storage keys have no file; the reverse leaves orphaned files.

Take all three parts as close together in time as practical. Files are only ever added under new
random storage keys and removed when their row is removed, so a small skew normally yields at most a
file with no row (harmless, and invisible in the PMS) rather than a row with no file — but a large
skew does not hold that property.

## 6. Restore procedure

1. Restore PostgreSQL from the database backup.
2. Restore Guest document storage into the configured `GUEST_DOCUMENTS_PATH`.
3. Restore Room image storage into the configured `ROOM_IMAGES_PATH`.
4. Start the PMS and confirm it starts cleanly (all required environment variables present; see
   [configuration-and-secrets.md](configuration-and-secrets.md)).
5. Validate, at minimum, all three of:
   - one Guest passport image opens through the PMS passport view;
   - one Room image opens through the PMS Room detail;
   - core Reservation data is readable (the Reservation list and one Reservation's detail).

Steps 2 and 3 must restore into the paths the *restored* environment is configured with. If the
paths differ from the original host's, set the environment variables to the new locations rather
than moving files around afterwards — the storage keys in the database are path-independent, so the
directory may move, but its contents must all arrive together.

If a validation step in 5 fails, the restore is incomplete: do not resume normal operation on the
assumption that missing files will reappear.

## 7. Out of scope for V1

This document states the operational requirement. Deployment automation comes later. V1 deliberately
does **not** include:

- a backup scheduler, cron job, or snapshot coordinator;
- cloud/object storage integration;
- stored file checksums;
- antivirus scanning of uploads.
