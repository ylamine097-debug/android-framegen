# FrameGen for Android (Mali / Android 14+) – 2X / 3X / 4X

A live frame-generation overlay. It captures one app (your game), generates 1, 2 or 3
in-between frames on the GPU, and shows them in a click-through overlay.

## Get the APK (no Android Studio needed)
1. Create a free account on github.com and a **new repository** (private is fine).
2. Upload everything in this folder (including the hidden `.github` folder) to it.
   Easiest: on the repo page choose "uploading an existing file" and drag the whole
   folder in, or use GitHub Desktop.
3. Open the **Actions** tab -> "Build APK" -> wait ~5 minutes.
   (If it did not start: Actions -> Build APK -> Run workflow.)
4. Open the finished run -> **Artifacts** -> download `FrameGen-apk`, unzip, copy
   `app-debug.apk` to your phone and install it (allow "install unknown apps").

## Use
1. Open FrameGen, pick **2X / 3X / 4X** and a processing resolution (start with Fast).
2. Press Start, allow "Display over other apps".
3. Press Start again. When Android asks what to share, choose **"A single app"**
   and pick your game. (Do NOT choose "Entire screen" - the overlay would film itself.)
4. Switch to the game. Stop from the notification or inside FrameGen.

## How it works / honest notes
- Engine: OpenGL ES 3.0 only (runs on Mali G-series). Block motion estimation and motion-compensated
  interpolation run in fragment shaders. This is a classic GPU interpolator, NOT a neural network.
  A RIFE-style neural model (ncnn + Vulkan) is the next upgrade but needs a model file and much
  more native code; ask if you want it.
- Untested on real hardware: expect to tune things. First thing to try if it is slow: "Fast".
- Adds roughly one source frame of latency. Best for 30 -> 60 fps; 3X/4X need a 90-120+ Hz screen
  to be worth it.
- Fast camera pans (> ~25 px per frame at processing resolution) fall back to plain blending.
- Some games detect overlays or screen capture; don't use with online games that ban for it.
