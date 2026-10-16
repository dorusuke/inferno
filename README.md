# Inferno OS

A minimal Android app that turns your phone plus Bluetooth smart glasses into a voice AI.
Say "Hey Inferno", ask anything, call people, read and send WhatsApp, record conversations and get a brief.

It is an app, not a real operating system. Android can't be replaced from an app, but this runs as an always-on service and covers everything you listed.

## Run it

1. Install Android Studio and open the `InfernoOS` folder. Let Gradle sync.
2. Open `local.properties` (Studio creates it) and add:
   ```
   GEMINI_API_KEY=your_key
   GEMINI_MODEL=gemini-2.5-flash
   ```
3. Pair your glasses to the phone as a normal Bluetooth headset.
4. Plug in your phone (USB debugging on) and press Run.
5. Tap **Grant access** until the banner disappears:
   - Runtime permissions: mic, contacts, calls, notifications, bluetooth.
   - **WhatsApp access** = notification access, so Inferno can read WhatsApp messages and reply to chats.
     If the toggle is greyed out: Settings > Apps > Inferno OS > top-right menu > Allow restricted settings.
   - **Display over apps**, so a WhatsApp chat can be opened while the phone is locked in your pocket.

## What it does

| You say | What happens |
|---|---|
| "Hey Inferno" | Replies "Hey, I'm Inferno. I'm ready to help you." then listens for your question |
| "Hey Inferno, what's the capital of Australia?" | Gemini answers, spoken through the glasses |
| "Hey Inferno, call Om" | Looks up the contact and dials |
| "Hey Inferno, any WhatsApp messages?" | Reads recent WhatsApp notifications |
| "Hey Inferno, tell Om on WhatsApp I'm running late" | Replies in the existing chat hands-free, or opens the chat with the text filled in |
| "Hey Inferno, start recording" | Records after the reply finishes |
| Stop & brief (notification, app, or type "stop recording") | Gemini returns summary, key points, decisions, action items and transcript. Saved under Briefs |

You can also type to Inferno from the box at the bottom of the app.

## Known limits

- **Recording stops by button, not voice.** Android lets only one thing use the mic at a time, so wake-word listening pauses while recording. Stop it from the notification or app. Recordings auto-stop at 50 minutes.
- **WhatsApp can't be sent silently by a normal app.** If the chat already has a notification, Inferno replies through it with no taps. Otherwise it opens the chat prefilled and you tap send once.
- **Wake word uses Android's speech recognizer**, which restarts every few seconds, may beep on some phones, and sends audio to Google. It's fine for an MVP. The proper upgrade is an offline wake-word engine (Porcupine or openWakeWord).
- **Glasses audio is phone-call quality** while Inferno holds the Bluetooth mic. Music over the glasses will sound narrow until you turn Inferno off from the app.
- Recording other people: make sure they know.
- The API key is built into the app. Don't share the APK.
- `DEFAULT_COUNTRY_CODE` in `PhoneActions.kt` is `91`. Change it if needed.

## Layout

```
app/src/main/java/com/inferno/os/
  MainActivity.kt      minimal home screen
  VoiceService.kt      wake word, speech in/out, recording, notification
  Brain.kt             Gemini conversation + tool calling
  Gemini.kt            REST client
  PhoneActions.kt      calls, WhatsApp, contacts
  WhatsAppListener.kt  reads WhatsApp notifications, replies through them
  Recorder.kt          recorder + brief generation
  Audio.kt             Bluetooth glasses routing
  State.kt             shared UI state
```

## Next steps worth doing

- Offline wake-word engine, and a glasses button to stop recording.
- Google Search grounding for live info like news and weather.
- Calendar, Gmail and Maps through Google APIs as extra Gemini tools (add a declaration in `Brain.kt` and a case in `PhoneActions.kt`).
