# Pinoy Dino (Android overlay companion)

On-screen Taglish dino: talks (Android TTS, fil-PH if installed), listens when you tap him,
removes the word "yarn" before showing/speaking, 5-stage Mute drama, sleeps 22:00,
wakes 05:00 with alarm (shakes the screen after 3 min), boredom ball-throw after 15 min
using a snippet of your imported notes, and a local notes knowledge base.

## Budget
- Storage: Guard.kt caps app data at 3 GB (APK about 8 MB, notes DB is text only).
- RAM: about 150 MB. No LLM is bundled, so a 6 GB phone is more than enough.

## Build on GitHub
1. Create a repo, upload everything in this folder (keep .github/workflows).
2. Actions tab -> Build APK -> Run workflow (or just push).
3. Download the artifact PinoyDino-debug-apk, install the APK.
4. In the app: step 1, 2, 3, then Import notes if wanted.

## Not included (needs more permissions or work)
- Screen OCR (needs MediaProjection + ML Kit), so the ball uses your imported notes instead.
- Cloud auto-update (use GitHub Releases), lip-sync, Live2D.
- Expressions are cropped at runtime from assets/expressions.png (background is not transparent);
  swap in transparent cut-outs later by editing face() in DinoService.kt.
