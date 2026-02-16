# Daily Diary

An AI-powered voice diary Android app that records your day and sends you a beautifully summarized diary entry every night.

## Features

- 🎙️ **24/7 Background Recording** - Continuously captures audio with intelligent voice activity detection
- 📝 **AI Transcription** - Uses OpenAI Whisper to convert speech to text
- 🤖 **Smart Summarization** - GPT creates organized, personal diary entries
- 📧 **Nightly Email** - Automatically sends your daily diary to your email
- 🔋 **Battery Optimized** - Smart recording with silence detection to save power
- 🔄 **Auto-restart** - Automatically resumes after device reboot

## Setup

### Prerequisites

- Android device running Android 8.0 (Oreo) or higher
- OpenAI API key (for Whisper + GPT)
- Gmail account with App Password (for sending emails)

### Configuration

1. Install the APK on your Android device
2. Open the app and go to **Settings**
3. Enter your **OpenAI API Key**
4. Configure your **email settings** (sender email, app password, recipient email)
5. Set your preferred **summary time** (default: 10:00 PM)
6. Tap **Start Recording** on the main screen
7. Disable battery optimization when prompted

### Gmail Setup

1. Enable 2-Factor Authentication on your Google Account
2. Go to Security → App Passwords
3. Generate a new App Password for "Mail"
4. Use that app password in the email settings

## Architecture

- **AudioRecordingService** - Foreground service for continuous microphone access
- **SpeechToTextProcessor** - OpenAI Whisper API integration
- **TranscriptionManager** - Room database for local storage
- **AISummarizer** - GPT API for diary generation
- **EmailSender** - SMTP email delivery
- **DailySummaryWorker** - WorkManager for scheduled nightly processing

## Building

```bash
export JAVA_HOME=$(/opt/homebrew/bin/brew --prefix openjdk)/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
./gradlew assembleDebug
```

The APK will be at `app/build/outputs/apk/debug/app-debug.apk`

## Privacy

- All audio is processed locally and via your own OpenAI API key
- Audio files are deleted immediately after transcription
- Transcriptions are stored locally on your device
- Only the daily summary is sent via email
- No data is shared with third parties
