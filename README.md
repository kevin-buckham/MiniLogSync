# MiniLogSync (beta)

One-button retrieval of SD-card logs from a rusEFI ECU to an Android phone, so
you don't need a laptop in the car to get your data.

Plug the phone into the ECU's USB port, tap **SYNC NEW LOGS**, and the app:

1. asks the ECU to hand its SD card to the phone (`sdmode pc`, the ECU **stops
   logging** while this is happening, and the app says so loudly),
2. copies only the logs it hasn't copied before to a folder you choose (local
   storage or any cloud provider that shows up in Android's folder picker),
3. hands the card straight back to the ECU (`sdmode auto`) and tells you when
   it's **safe to unplug**,
4. verifies every copy afterwards. A failed file is not marked done, so the
   next sync retries it.

It also trims the padding off each log while copying. rusEFI pre-allocates
every SD log to 32 MB and only cuts it to size on a clean shutdown, which never
happens when the ECU loses power at key-off.

## Status: beta, tested on one car

| | |
|---|---|
| Tested phone | Google Pixel 10 Pro XL (USB-C to USB-C cable) |
| Tested ECU | uaEFI Pro (rusEFI, 2026 development firmware) |
| Android | 8.0+ (minSdk 26) |

Other rusEFI boards with USB and an SD card should work if their firmware has
the `sdmode` console command, but they haven't been tried. Reports welcome.

## Requirements

- **The SD card must be FAT32.** The app reads the card with its own USB
  storage stack ([libaums](https://github.com/magnusja/libaums)), which only
  understands FAT32. Cards larger than 32 GB ship formatted exFAT and **will not
  work** until reformatted as FAT32.
- A data-capable USB-C cable between the phone and the ECU.

## Install

Download the APK from the
[Releases](../../releases) page and sideload it (Android will ask you to allow
installing from your browser or file manager). Releases are signed with the
project's release key, so newer versions install over older ones and keep your
sync history.

## Safety

- The app sends only three console commands: `sdmode pc`, `sdmode auto` and
  `sdmode ecu`. It never writes tune or configuration data.
- `sdmode format` (which wipes the card) does not appear anywhere in this code.
- Nothing on the card is written or deleted.
- **If you drive away while the card is mounted to the phone, the ECU is not
  logging.** Use *Return card to ECU* (under Advanced) if a sync was
  interrupted.

## How it talks to the ECU

rusEFI / TunerStudio binary framing over the USB CDC serial port:

```
[ length : uint16 BE ]  = payload + 1
[ code   : uint8     ]  'E' = TS_EXECUTE (run a console command)
[ payload: N bytes   ]  e.g. "sdmode pc"
[ crc32  : uint32 BE ]  standard CRC-32 over code + payload
```

Reply `00 01 00 <crc32>` = TS_RESPONSE_OK.

## Building

No Android SDK needed locally: GitHub Actions builds an APK on every push. In
this repo the build is signed with a release key stored in Actions secrets.
In a fork (no secrets) it builds an unsigned debug APK instead, which is fine
for testing but can't update an installed release build.

To build locally: `gradle assembleDebug` (Android SDK and Gradle required; no
wrapper is committed).

Maintainers: pushing a tag like `v0.3.0` publishes the signed APK as a GitHub
Release.

## Part of

A Honda D16 + rusEFI project. See the
[D16 rusEFI guide](https://kevin-buckham.github.io/d16-rusefi-guide/) (coming soon).

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).

### Third-party licenses

The APK includes these libraries, each under its own license:

| Library | License |
|---|---|
| [libaums](https://github.com/magnusja/libaums) | Apache-2.0 |
| [usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android) | MIT |
| [AndroidX](https://developer.android.com/jetpack/androidx) (appcompat, activity-ktx, documentfile) | Apache-2.0 |
