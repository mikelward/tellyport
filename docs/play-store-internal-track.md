# Play Store Internal Track & GitHub Prerelease Pipeline

Every qualifying push to `main` builds a signed Android App Bundle (`app-release.aab`),
archives it as a 30-day workflow artifact (`app-release-aab`), publishes a durable GitHub
prerelease (`v<versionCode>`) via `scripts/publish-github-release.sh`, and — once
`PLAY_SERVICE_ACCOUNT_JSON` is configured — uploads it to Google Play's **Internal testing**
track with release notes built from every qualifying commit subject since the last published
release.

## Generating the upload keystore

Keep this keystore safe. It is only the upload credential — Play App Signing holds the
app-signing key that actually reaches devices — so losing it never bricks the listing or
blocks users from updating.

```sh
KEYSTORE_PASSWORD=$(openssl rand -hex 24)
keytool -genkeypair \
  -keystore release.keystore \
  -alias tellyport \
  -storetype PKCS12 \
  -storepass "$KEYSTORE_PASSWORD" \
  -keypass "$KEYSTORE_PASSWORD" \
  -dname "CN=Tellyport Release, O=Tellyport, C=US" \
  -validity 36500 \
  -keyalg RSA \
  -keysize 2048
base64 -w0 release.keystore > release.keystore.b64
echo "Password: $KEYSTORE_PASSWORD"
```

Store `release.keystore` and its password in a password manager before deleting anything.
GitHub secrets are write-only, so once pasted they cannot be read back out. Delete both
`release.keystore` and `release.keystore.b64` locally once uploaded (`.gitignore` excludes
them only as defense in depth).

## Required secrets (`production` environment)

Add these in repo **Settings → Environments → `production`** (restrict its deployment
branches to `main`). **Environment scope only — never as repository secrets.**

| Secret | Description |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | Base64-encoded PKCS12 keystore bytes (`base64 -w0 release.keystore`). |
| `RELEASE_KEYSTORE_PASSWORD` | Random hex password set when generating the keystore. |
| `RELEASE_KEY_PASSWORD` | Same value as `RELEASE_KEYSTORE_PASSWORD` (PKCS12 convention). |
| `RELEASE_KEY_ALIAS` | Key alias inside the keystore (`tellyport`). |
| `PLAY_SERVICE_ACCOUNT_JSON` | Optional. Full JSON contents of the Google Play service account key. Adding this turns on automatic uploads to the Play `internal` track for `tv.tellyport`. |

## Release notes & `versionCode`

- **`versionCode`** derives monotonically from `git rev-list --count HEAD` (`app/build.gradle.kts`).
  CI checks out with `fetch-depth: 0` and enforces `checkReleaseVersionDerivation` on every
  release build so a shallow clone can never publish a truncated version code.
- **Release notes** collect every qualifying commit subject since the last successful Play
  upload (excluding `ci:`, `docs:`, `internal:`, `refactor:`, `test:`/`tests:` prefixes and
  pure `.md`/root-dotfile housekeeping commits), formatted as a `• ` bulleted list capped at
  Play's 500-character limit and 50 distinct subjects.
- If a push has no qualifying commits queued, `RELEASE_NOTES_SKIP=true` skips publishing so
  no redundant release is created. If a newer push already published a higher `versionCode`,
  `scripts/publish-github-release.sh` sets `RELEASE_SUPERSEDED=true` so neither GitHub
  Releases nor Play receives a stale bundle.
