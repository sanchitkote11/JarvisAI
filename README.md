# JARVIS AI (Voice MVP)

A native Android voice assistant. It listens to your voice, speaks replies, chats through the Gemini API, and runs a few local commands.

## Features

- Voice recognition (English / Indian English)
- Spoken replies (text-to-speech)
- Gemini chat with short session memory
- Local commands: open YouTube, Chrome, WhatsApp, Instagram, Camera, Settings; flashlight on/off; current time
- API key stored in encrypted storage (never hardcoded)

## Requirements

- Android 8.0 (API 26) or newer
- Gemini API key from https://aistudio.google.com
- Internet connection
- Google app installed (for speech recognition)

## Build (Termux)

```bash
pkg install -y openjdk-17 gradle unzip wget

# Android SDK command-line tools must be installed to $HOME/android-sdk
export ANDROID_HOME=$HOME/android-sdk
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools
sdkmanager --licenses
sdkmanager "platforms;android-34" "build-tools;34.0.0" "platform-tools"

echo "sdk.dir=$ANDROID_HOME" > local.properties
gradle wrapper --gradle-version 8.7
chmod +x gradlew
./gradlew assembleDebug
```

The APK is produced at:

```
app/build/outputs/apk/debug/app-debug.apk
```

## Install

Copy `app-debug.apk` to the phone, open it in the Files app, allow installs from that app, and tap Install.

## First run

1. Allow microphone access.
2. Paste your Gemini API key, tap **Save key**, then **Test connection**.
3. Tap **Speak** and try "what time is it", "open YouTube", or "flashlight on".

## Notes

- Set `MODEL` in `MainActivity.kt` to any current Gemini model name from AI Studio if the default is retired.
- Male-sounding voice depends on the text-to-speech voice installed on your phone.
- Wake word, continuous listening, and music control are not included yet.
