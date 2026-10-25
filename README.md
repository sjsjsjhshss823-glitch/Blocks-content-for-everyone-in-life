# SafeGuard — Content Safety Prototype

SafeGuard is a small, browser-based demonstration of content-safety controls.

## What this prototype does
- Provides a simple local interface for a parent or user to manage a list of blocked domains.
- Stores the list in the browser's local storage on this device.
- Demonstrates a basic URL check against the configured domain list.

## Important limitations
This is **not** a system-wide content filter and cannot block content across every app or every phone. Browser-local storage only affects this demo in this browser. Reliable device-wide protection requires platform-specific controls (for example, Android VPN/DNS filtering or managed-device policies), permissions, testing, and clear user consent.

## Run
Open `index.html` in a modern browser. No build step is required.
