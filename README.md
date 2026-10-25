# SafeGuard — Android APK prototype

SafeGuard is an Android app prototype for managing a local domain list and checking URLs against it.

## Build an APK automatically
Every push to `main` runs the GitHub Actions workflow in `.github/workflows/build-apk.yml`. When the run succeeds:
1. Open the repository's **Actions** tab.
2. Open the **Build SafeGuard APK** run.
3. Under **Artifacts**, download `SafeGuard-debug-apk`.
4. Extract the ZIP and install `app-debug.apk` on an Android phone. Android may ask you to allow installation from that source.

You can also start a build manually from **Actions → Build SafeGuard APK → Run workflow**.

## Current functionality
- Native Android application (Kotlin).
- Add and remove domains from a list stored locally on the phone.
- Check a URL against that list.

## Important limitations
This is an installable APK prototype, **not yet a system-wide content blocker**. It does not intercept network traffic or block domains in other apps. Device-wide filtering requires an additional tested Android filtering implementation and explicit user consent. The generated debug APK is for testing, not a Play Store release.

## Build locally
Install JDK 17 and Gradle 8.9, then run `gradle assembleDebug`. The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
