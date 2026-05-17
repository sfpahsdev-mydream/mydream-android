# MyDream Android

MyDream is an Android-native smart alarm lab app. The current MVP focuses on
collecting historical Samsung Health sleep-stage data, exporting it as JSONL,
and preparing the Android app structure for later on-device inference and alarm
runtime work.

The final product direction is a personalized smart alarm that learns a user's
sleep time-series patterns and chooses the latest suitable wake time before a
deadline.

## Current Scope

- Android Native / Kotlin / Jetpack Compose
- Samsung Health Data SDK integration
- Sleep-session and sleep-stage retrieval
- JSONL export/share flow for training and evaluation
- Portable internal sleep domain models
- Placeholder modules for on-device inference and alarm runtime

Actual alarm scheduling, snooze behavior, feedback logging, and production
on-device model inference are deferred.

## Modules

- `:app`: Android host app, theme, manifest, and feature assembly
- `:feature:lab`: MVP lab/data-collector screen and JSONL export flow
- `:feature:on-device-inference`: planned local inference experiments
- `:feature:alarm-runtime`: planned alarm scheduling/runtime work
- `:core:sleep`: shared sleep domain models and `SleepDataSource`
- `:core:export`: sleep-session JSONL exporter
- `:data:samsung-health`: Samsung Health Data SDK adapter

## Requirements

- Android Studio
- JDK 11 compatible toolchain
- Android SDK with compile SDK 36 support
- Samsung Health Data SDK AAR
- A Samsung device/environment where Samsung Health Data SDK permissions can be
  tested

## Samsung Health SDK

The Samsung Health Data SDK AAR is not committed to this repository.

Download it from Samsung's official developer resources and place it here:

```text
app/libs/samsung-health-data-api-1.1.0.aar
```

`app/libs/*.aar` is intentionally ignored by git. Check Samsung's license and
distribution terms before sharing the SDK binary anywhere.

## Local Setup

Create or keep your local Android SDK settings in:

```text
local.properties
```

`local.properties` is ignored by git.

## Build And Test

From the repository root:

```powershell
.\gradlew.bat test
```

For a debug build:

```powershell
.\gradlew.bat assembleDebug
```

## Data Flow

```text
Samsung Health Data SDK
-> SamsungHealthSleepDataSource
-> internal SleepSession / SleepStage models
-> SleepSessionJsonlExporter
-> JSONL file shared from the Lab screen
-> mydream-training-evaluation pipeline
```

The exported JSONL can be parsed by the separate training/evaluation repository:

```text
mydream-training-evaluation
```

## Privacy

Do not commit personal health exports, generated CSV files, model outputs,
keystores, local SDK paths, APKs, or Samsung SDK binaries.

The app may handle sensitive sleep and health data. Keep exports local unless
you intentionally move them to a trusted training environment.
