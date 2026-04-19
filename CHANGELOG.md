# Changelog

## 0.0.8

- Established the local `appUserId` during `configure()` and aligned startup flows around the resolved local identity.
- Switched `logOut()` to the local-only anonymous reset flow and removed the backend logout dependency from the Android SDK surface.
- Tightened bootstrap sequencing so offerings warmup runs in the background while purchase sync, dead-letter retry, and customer refresh complete deterministically.
- Extended bridge/plugin configure flows with optional `appUserId` support and refreshed Android release metadata for Maven Central publishing.
