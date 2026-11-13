# Passbook

A small Android app that keeps photos of your wallet cards (university card, ID, driving license) in a Wallet-style stack.

I built it because some cards can't be added to Google Wallet or Apple Wallet, so they ended up buried in my photos. Passbook gives them a proper home.

## Features

- **Card stack:** newest card on top, with horizontal and vertical cards shown in their own shape and iOS-style rubber-band scrolling
- **Flip to the back:** tap "i" or drag sideways; the back takes its bar and frame colour from the card
- **Camera scanner:** card-shaped frame, flash and automatic crop, or pick a photo from the gallery
- **Fingerprint lock:** biometric, or PIN/pattern on Android 11+; cards are hidden in recent apps
- **Spring animations:** cards lift out and follow your finger, and every animation can be interrupted and reversed
- **Shredder delete:** the card turns, then feeds through a metal shredder with haptic feedback
- **Reduce Motion support:** respects Android's "Remove animations" setting
- **Private by design:** photos stay in private storage, with no network, no accounts, and no libraries beyond AndroidX, Material and CameraX

## Tech

Java and XML, minSdk 24. CameraX powers the scanner, AndroidX Biometric the lock, and DynamicAnimation the springs. Everything else, including the shredder, scan frame and colour tinting, is drawn in code.

## Project layout

| File | Purpose |
|---|---|
| `MainActivity` | Card list, gestures, app lock, delete and shredder |
| `CardAdapter` | List rows and add/remove animations |
| `CardStore` | Saves, loads and deletes card photos |
| `CameraActivity`, `ScanFrameView` | Camera scanner with card-shaped frame |
| `Pickers` | The "+" popup and "Add the back side?" dialog |
| `Springs`, `Motion` | Spring helpers and shared animation settings |
| `MetalDrawable`, `TeethView` | The shredder's metal body and hooks |

## Roadmap

- NFC support for compatible cards (requires permissions not yet available)
