---
name: safeguard-android
description: Build, inspect, debug, and test the SafeGuard Android app for DNS-based adult-content filtering. Use when working on Android/Kotlin code, VPN/DNS filtering, foreground service lifecycle, permissions, notifications, app lifecycle, Gradle builds, or GitHub repository changes.
---

# SafeGuard Android Development Skill

## Goal
Maintain SafeGuard as an Android app that helps block adult websites after the user grants Android's required permissions and activates protection. Do not imply that DNS filtering blocks every adult image, video, website, or app on every phone.

## Rules
1. Inspect the repository before editing. Read the README, manifest, Gradle configuration, service implementation, tests, and relevant Git history.
2. Never claim a fix, test, build, APK, or GitHub upload succeeded unless tool output verifies it.
3. Make focused changes and preserve existing working behavior.
4. Explain limitations: encrypted DNS/DoH, direct-IP connections, IPv6, app-embedded content, VPN conflicts, Android version differences, and manufacturer battery restrictions may affect coverage.
5. Respect Android permission prompts, user consent, platform policies, and the device owner's ability to stop protection. Never hide the VPN or make it impossible for the owner to disable.
6. Do not collect or transmit browsing history. Keep filtering local except for clearly documented, user-consented services.
7. Never commit passwords, access tokens, signing keys, or other secrets. Use secure local storage or GitHub Actions secrets.
8. If repository write access is unavailable, report the exact blocker and provide a patch-ready result. Do not claim the upload happened.

## Debugging: VPN stops or notification says disconnected
When the app stops blocking after the user clears Recents/history, or a persistent “VPN not connected” notification appears:

1. Reproduce the sequence and capture Android version, app version, VPN status, and relevant logs.
2. Inspect the `VpnService` lifecycle and ensure `startForeground()` is called promptly with a valid notification as required by the target Android SDK.
3. Check `AndroidManifest.xml` for the VPN service declaration, `BIND_VPN_SERVICE`, foreground-service permissions, and any target-SDK-specific service requirements. Verify current requirements instead of adding a service type blindly.
4. Verify `VpnService.prepare()` consent handling and the path taken when consent is denied or revoked.
5. Inspect `onStartCommand()`, `onRevoke()`, `onDestroy()`, exceptions, and socket/TUN cleanup. Report protected, reconnecting, and off states accurately.
6. Do not assume clearing the Recents screen means the service was stopped. Check the actual service state and Android logs.
7. Prevent notification/restart loops. Use bounded recovery and respect a system or user decision to stop the VPN.
8. Investigate battery optimization and vendor-specific background restrictions, but never promise Android cannot stop the service.
9. For a local DNS VPN, validate TUN routes, packet parsing, DNS transaction IDs, response forwarding, timeouts, IPv4/IPv6 behavior, and cleanup. Avoid silently black-holing all traffic on failure unless fail-closed behavior is explicitly documented to the user.
10. Add regression tests for service state transitions, notification accuracy, and filtering decisions where practical.

## Project-specific checks
- Read the README before changing DNS behavior. Preserve the documented upstream family-filter DNS configuration unless a tested, documented reason justifies changing it.
- Verify which DNS transports and IP versions are actually implemented. Do not describe IPv4 UDP-only handling as full-device or universal protection.
- Check whether the app uses a foreground VPN service, whether its notification reflects the true connection state, and whether the UI can recover from a stopped or revoked VPN.
- Keep network errors visible and actionable without falsely claiming protection is active.

## Build and verification
- Inspect the repository's Gradle wrapper, JDK, and Android Gradle Plugin requirements first.
- Prefer documented project commands. Run relevant tests and the documented debug build, for example `./gradlew test` and `./gradlew assembleDebug` when a wrapper exists.
- Fix the root cause of the first meaningful error and rerun the relevant checks.
- Verify the APK exists and report its exact path and size only after checking it.
- Clearly distinguish code review, unit tests, successful build, emulator/device testing, and real-world filtering tests.

## GitHub workflow
- Confirm the repository, branch, and current file state before writing.
- Review the diff and run available checks before committing.
- Use descriptive commit messages. Do not commit build outputs unless the repository intentionally tracks them.
- Confirm write access and the intended remote. If permission fails, report the exact error rather than retrying unchanged credentials.
- After a push or file write, verify the remote file/commit is present.

## Task report
For each task, report:
- **Cause:** evidence for the diagnosis.
- **Changes:** files changed and why.
- **Verification:** exact checks completed and what remains unverified.
- **Limitations:** material limitations stated plainly.
