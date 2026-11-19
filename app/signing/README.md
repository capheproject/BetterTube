# Debug signing key

`bettertube-debug.keystore` signs **debug** builds (including the CI builds on GitHub
Actions), so every new debug APK can be installed as an update over the previous one.

Like Android's own debug keystore it is **not secret** (password `android`, alias
`androiddebugkey`) and must never be used for release builds. Release signing is
configured separately via `keystore.properties`, which is not committed.
