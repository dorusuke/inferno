# Inferno OS v2

Android app: say "Hey Inferno", ask anything, call people, read and send WhatsApp, record conversations and get a brief.

## What's new in v2
- No more beep: media volume is muted while Inferno listens. Soft chimes (made in code) replace it.
- Profile icon (top right): Google sign-in, API key, usage, voice, sounds.
- Recordings section: every recording with its brief, playback, redo brief, delete.
- AI voice: pick any installed phone voice, or a Gemini voice.
- Usage: requests used and left today, tokens, reset time.

## Install
Download the APK from the `build-output` branch (file `InfernoOS.apk`). v2 is signed with a fixed key,
so uninstall v1 once before installing.

## Google sign-in (one-time setup, optional)
The API key works without any of this. To sign in with your Google account instead:
1. console.cloud.google.com > create or pick a project.
2. APIs & Services > Library > enable **Generative Language API**.
3. OAuth consent screen: External, add yourself under **Test users**.
4. Credentials > Create credentials > OAuth client ID > **Android**.
   - Package name: `com.inferno.os`
   - SHA-1: `CD:2B:F5:90:98:81:3B:E7:5C:75:E2:A1:E6:B0:98:D8:25:29:5D:1A`
5. Open the app > profile icon > Sign in with Google.

## Notes
- "Remaining usage" is an estimate counted on the phone. Google doesn't report quota to apps.
- While Inferno listens, other media on the phone is muted (turn this off in Profile > Sounds).
- WhatsApp can't be sent silently. Existing chats are answered through their notification, otherwise you tap send once.
