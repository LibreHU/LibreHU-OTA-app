# LibreHU signing key

`librehu.jks` (alias `librehu`, passwords `librehu`) signs the APKs built by the CI, the same key for every LibreHU
app: an update installs over the previous version (LibreHU OTA) and keeps the app's data.

It is public, like Android's debug key: anyone can sign an APK with it. To use a private key instead, add the
repository secret `LIBREHU_KEYSTORE_B64` (base64 of a keystore with alias `librehu`, passwords `librehu`, or set
`LIBREHU_KEY_ALIAS`, `LIBREHU_KEYSTORE_PASSWORD`, `LIBREHU_KEY_PASSWORD` in the workflow); apps signed with the
public key then have to be uninstalled once before the first update.
