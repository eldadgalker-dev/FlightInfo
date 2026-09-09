# Signing key

`flightinfo.jks` is the key that signs every APK built by the GitHub Actions
workflow (and by `./gradlew assembleDebug` locally). It is committed so that all
builds carry the SAME signature: Android only installs an update over an
existing app when the signatures match, and GitHub-hosted runners would
otherwise generate a fresh throw-away debug key on every run.

Passwords (store and key): `flightinfo`, alias `flightinfo`. Overridable with
the environment variables KEYSTORE_FILE, KEYSTORE_PASSWORD, KEY_ALIAS,
KEY_PASSWORD; the workflow uses them automatically when the repository secret
KEYSTORE_BASE64 (and the three password/alias secrets) exist.

Consequence of a public key: anyone can produce an APK that Android accepts as
an update to FlightInfo. It cannot be pushed to a phone; the user still has to
download and install it. If that risk matters, move the key to repository
secrets (see README, "Signed releases") and delete this file from the
repository history.
