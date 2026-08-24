# MiniLogSync

Android companion for a rusEFI ECU: hands the SD card to the phone so logs can
be copied without getting a laptop into the car, then gives the card back so the
ECU resumes logging.

Built for a Classic Mini + Honda D16Y8 on a uaEFI Pro (see the MiniRusEFI
project, `docs/ANDROID_LOG_SYNC_SPEC.md` for the full spec).

## What v1 does

Three commands over the ECU's USB CDC serial link:

| Button | Sends | Effect |
|---|---|---|
| Mount card to phone | `sdmode pc` | SD card appears as USB storage. **ECU stops logging.** |
| Return to ECU (auto) | `sdmode auto` | ECU resumes its configured behaviour (logging) |
| Force ECU logging | `sdmode ecu` | Explicitly assigns the card to the ECU |

File copying is deliberately left to Android's Files app: while the card is
mounted it behaves as ordinary USB storage, so anything (including a cloud
folder) can be the destination.

## Safety

- The app sends **only** those three commands. No tuning, no config writes.
- `sdmode format` exists in the firmware and **wipes the card**; it deliberately
  does not appear anywhere in this codebase.
- Nothing on the card is written or deleted by this app.
- While mounted, the ECU is not logging - the UI says so, loudly.

## Protocol

rusEFI/TunerStudio binary framing, verified against firmware source
(`firmware/console/binary/tunerstudio_io.cpp`):

```
[ length : uint16 BE ]  = payload + 1
[ code   : uint8     ]  'E' = TS_EXECUTE (run a console command)
[ payload: N bytes   ]  e.g. "sdmode pc"
[ crc32  : uint32 BE ]  standard CRC-32 over code + payload
```

Reply `00 01 00 <crc32>` = TS_RESPONSE_OK.

## Building

No local Android SDK needed - GitHub Actions builds the APK on every push
(Actions tab -> latest run -> Artifacts -> `MiniLogSync-debug-apk`).
Sideload the APK on the phone (allow install from unknown sources).

## Status

v1 = mount/unmount remote control. Planned next (spec section 12): one-button
sync with copy history, then truncate-during-copy (logs are 32 MB pre-allocated
regardless of content).
