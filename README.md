# DroidLink

DroidLink is an Android-to-Android remote screen streaming project focused on low-latency connections and remote input.

## Downloads

The public download page is deployed with GitHub Pages from the `docs/` directory. It automatically checks GitHub Releases for the newest APK.

## Releases

Stable/tested APKs should be stored as GitHub Release assets instead of committed directly into Git history.

Future source builds can be released automatically by creating a version tag such as `v0.9.3`. The `.github/workflows/release-apk.yml` workflow builds the Android project and attaches the generated APK to the matching GitHub Release.

## Known-good build

The initial known-good APK is `DroidLink-0.9.2-beta-debug.apk`. It should be preserved as the first release asset before replacing it with future builds.
