---
name: youtube-video-clip-editing
description: End-to-end pipeline that downloads a YouTube clip, burns in Korean subtitles, and brackets the result with a Microsoft-logo intro/outro bumper. Orchestrates four independent skills (youtube-downloader, wjs-transcribing-audio, wjs-translating-subtitles, wjs-burning-subtitles) plus FFmpeg concatenation into a single automated run. Use when the user asks to "download a YouTube clip and add Korean subtitles", "make a localized clip with our Microsoft intro/outro", or similar multi-step video-localization + branding requests.
metadata:
  tags:
    - video
    - youtube
    - subtitles
    - korean
    - localization
    - ffmpeg
    - branding
  pairs-with:
    - skill: youtube-downloader
      reason: Step 1 — fetches the source clip from YouTube
    - skill: wjs-transcribing-audio
      reason: Step 2 — produces the source-language (English) SRT
    - skill: wjs-translating-subtitles
      reason: Step 3 — translates the English SRT to Korean
    - skill: wjs-burning-subtitles
      reason: Step 4 — burns the Korean SRT into the video pixels
    - skill: video-processing-editing
      reason: Step 5 — FFmpeg concat to prepend/append the Microsoft logo bumper
---

# YouTube Video Clip Editing (Download → Korean Subs → Microsoft Bumper)

Turns a YouTube URL into a branded, Korean-subtitled clip in one automated
pipeline. Each stage is a thin wrapper around an existing, independently
usable skill — this skill's job is only to **sequence** them and pass the
right file between steps. Nothing here duplicates those skills' logic.

## Pipeline overview

```
YouTube URL
   │  (1) youtube-downloader
   ▼
source.mp4  (English audio)
   │  (2) wjs-transcribing-audio
   ▼
source.en.srt
   │  (3) wjs-translating-subtitles
   ▼
source.ko.srt
   │  (4) wjs-burning-subtitles
   ▼
source.subtitled.mp4  (Korean subs burned in, English audio kept)
   │  (5) video-processing-editing (FFmpeg concat)
   │      ms-logo-intro.mp4 + source.subtitled.mp4 + ms-logo-outro.mp4
   ▼
final_output.mp4
```

Prerequisite: a Microsoft-logo bumper clip (or two — one for intro, one for
outro) already exported at the same resolution/frame rate you want for the
final video, e.g. `assets/ms-logo-intro.mp4` and `assets/ms-logo-outro.mp4`.
If you only have one bumper clip, it is used for both intro and outro.

## Step-by-step (each step is independently runnable)

### Step 1 — Download the YouTube clip

**Skill:** `youtube-downloader`

**Example prompt:**
> "Download this YouTube video as best-quality mp4: https://www.youtube.com/watch?v=VIDEO_ID"

**Underlying command:**
```bash
python scripts/download_video.py "https://www.youtube.com/watch?v=VIDEO_ID" \
  -q best -f mp4 -o ./work/source.mp4
```

To grab only a sub-range of a long video (a "clip"), pass yt-dlp section
flags instead (skip the whole-video download):
```bash
yt-dlp --download-sections "*01:00-02:30" -f "bv*+ba/b" \
  -o ./work/source.mp4 "https://www.youtube.com/watch?v=VIDEO_ID"
```

**Output:** `work/source.mp4`

### Step 2 — Transcribe the English audio to SRT

**Skill:** `wjs-transcribing-audio`

**Example prompt:**
> "Transcribe work/source.mp4 to an English SRT."

Uses the Whisper-API path (word-level timestamps, self-assembled cues) since
the source is English, not Chinese — see that skill's routing table.

**Output:** `work/source.en.srt`

### Step 3 — Translate the SRT to Korean

**Skill:** `wjs-translating-subtitles`

**Example prompt:**
> "Translate work/source.en.srt to Korean."

Produces a punctuation-bounded Korean SRT (re-segmented so cues end at real
sentence breaks, not mid-sentence).

**Output:** `work/source.ko.srt`

### Step 4 — Burn the Korean subtitles into the video

**Skill:** `wjs-burning-subtitles`

**Example prompt:**
> "Burn work/source.ko.srt into work/source.mp4, keep the original English audio."

**Underlying command (subtitles-only mode):**
```bash
python scripts/render.py --video work/source.mp4 --srt work/source.ko.srt \
  --out work/source.subtitled.mp4
```

**Output:** `work/source.subtitled.mp4` (Korean subs always-visible, English
audio untouched).

### Step 5 — Prepend + append the Microsoft logo bumper

**Skill:** `video-processing-editing`

**Example prompt:**
> "Concatenate assets/ms-logo-intro.mp4, work/source.subtitled.mp4, and
> assets/ms-logo-outro.mp4 into final_output.mp4."

FFmpeg's concat demuxer requires matching codec/resolution/frame rate across
inputs, so re-encode the bumper(s) to match the subtitled clip first if they
differ (or re-encode everything to a common target during the concat step):

```bash
# 1. Normalize all three inputs to the same codec/resolution/fps/sample-rate.
for f in assets/ms-logo-intro.mp4 work/source.subtitled.mp4 assets/ms-logo-outro.mp4; do
  ffmpeg -y -i "$f" -vf "scale=1920:1080,fps=30" -c:v libx264 -pix_fmt yuv420p \
    -c:a aac -ar 48000 -ac 2 "work/norm_$(basename "$f")"
done

# 2. Concat via the concat demuxer (stream copy, no re-encode needed here
#    since all three are already normalized to the same format).
printf "file 'work/norm_ms-logo-intro.mp4'\nfile 'work/norm_source.subtitled.mp4'\nfile 'work/norm_ms-logo-outro.mp4'\n" \
  > work/concat_list.txt
ffmpeg -y -f concat -safe 0 -i work/concat_list.txt -c copy final_output.mp4
```

**Output:** `final_output.mp4` — the finished, branded, Korean-subtitled clip.

## One-shot automated run

To run the whole pipeline as a single request, give one combined prompt:

> "Download https://www.youtube.com/watch?v=VIDEO_ID, transcribe and
> translate it to Korean, burn in the Korean subtitles, and bracket it with
> our Microsoft logo intro/outro from assets/ms-logo-intro.mp4 and
> assets/ms-logo-outro.mp4. Save the result as final_output.mp4."

Or invoke the bundled orchestrator script directly for a single-command run:

```bash
scripts/produce_localized_clip.sh \
  "https://www.youtube.com/watch?v=VIDEO_ID" \
  assets/ms-logo-intro.mp4 \
  assets/ms-logo-outro.mp4 \
  final_output.mp4
```

The script runs Steps 1–5 in order inside a scratch `work/` directory,
stopping immediately (`set -euo pipefail`) if any stage fails so partial
output never gets passed downstream, and prints the path to the finished
file at the end.

## Notes / edge cases

- If the source video isn't in English, run `wjs-transcribing-audio` with
  its language routing (e.g. Volcano ASR for Chinese) before translating —
  Step 2's prompt only needs to name the actual source language.
- If you only have one branding clip, pass it for both intro and outro
  positions in Step 5 / the orchestrator script.
- Re-encoding in Step 5 is required whenever the bumper and the main clip
  don't already share codec/resolution/fps/audio-sample-rate — mismatched
  concat inputs otherwise produce corrupted or out-of-sync output.
- This skill only sequences existing skills; if any of
  `youtube-downloader`, `wjs-transcribing-audio`, `wjs-translating-subtitles`,
  `wjs-burning-subtitles`, or `video-processing-editing` is missing, install
  it first (see each skill's own `SKILL.md`).
