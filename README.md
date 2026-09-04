# FindMyDevice

An Android-first, offline-capable device recovery prototype for a device you own.

## Features in v1

- Direct Boot-aware boot receiver for recovery initialization after restart.
- Foreground location service with GNSS and Android network provider support.
- Local encrypted last-report storage in device-protected storage.
- Cellular cell observation count when Android exposes cell information.
- Nearby Wi-Fi scan result count.
- Nearby BLE scan observation count.
- Confidence score and reported location accuracy.
- Google Maps coordinate link generated locally; no Maps API is used.
- Owner-number allowlist for SMS commands.
- Authenticated SMS command: `FMD LOCATE <command-secret>`.
- SMS response when a working SIM/SMS service is available.
- If no communication channel exists, the latest report remains encrypted locally and can be transmitted later when a permitted channel returns.

## Important platform limits

This application cannot transmit data from a phone that has no communication path. Removing the SIM stops ordinary SMS. No Wi-Fi/Internet prevents network upload. BLE observations can be collected locally, but a nearby relay must also run a compatible recovery protocol. Random nearby devices cannot be commandeered by this app.

Three cellular cells do not guarantee an exact coordinate. The app therefore reports both Android's location accuracy and a separate confidence score. The confidence score is a heuristic, not a certified probability.

Android may restrict background execution, location, Bluetooth, and Wi-Fi scanning depending on OS version, permissions, battery policy, device manufacturer, and user settings. Android 15 also has additional restrictions around foreground services started from `BOOT_COMPLETED`.

## Setup

1. Open the project in Android Studio.
2. Install it on a device you own.
3. Grant the requested permissions.
4. Enter your control phone number.
5. Tap **Save & Start Recovery**.
6. Keep the generated command secret private.

The app shows the generated command secret only for initial setup. Do not publish it.

## SMS

From the configured control number, send:

`FMD LOCATE <command-secret>`

The device replies with the latest cached location report. The current implementation intentionally uses an owner-number allowlist plus a random per-device secret. A future release should replace the static secret command with a nonce/HMAC challenge-response protocol and add replay protection.

## Build

The GitHub Actions workflow builds a debug APK with JDK 17 and Gradle 8.9.

## Scope

This is intended for lawful recovery of devices that the operator owns or is authorized to manage. It does not provide covert access to microphones, cameras, messages, files, or other people's devices.
