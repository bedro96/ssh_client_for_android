#!/usr/bin/env bash
# Orchestrates the full "download -> Korean subtitles -> Microsoft bumper"
# pipeline described in ../SKILL.md as a single command.
#
# Each stage below is a thin call into the skill it belongs to. This script
# does not reimplement ASR, translation, or subtitle rendering itself — it
# only sequences the existing skills and passes files between them. Swap the
# placeholder commands for whichever concrete CLI/API each skill uses in your
# environment (Whisper API, translation API, etc.).
#
# Usage:
#   produce_localized_clip.sh <youtube_url> <intro_clip> <outro_clip> <output.mp4> [work_dir]
set -euo pipefail

if [[ $# -lt 4 ]]; then
  echo "Usage: $0 <youtube_url> <intro_clip> <outro_clip> <output.mp4> [work_dir]" >&2
  exit 1
fi

YOUTUBE_URL="$1"
INTRO_CLIP="$2"
OUTRO_CLIP="$3"
OUTPUT="$4"
WORK_DIR="${5:-./work}"

mkdir -p "${WORK_DIR}"

echo "[1/5] Downloading source clip from YouTube..."
python "$(dirname "$0")/../../youtube-downloader/scripts/download_video.py" \
  "${YOUTUBE_URL}" -q best -f mp4 -o "${WORK_DIR}/source.mp4"

echo "[2/5] Transcribing English audio to SRT..."
# Delegates to wjs-transcribing-audio's own tooling/prompt flow; placeholder
# shown here assumes a CLI entry point named `transcribe`.
transcribe "${WORK_DIR}/source.mp4" --lang en --out "${WORK_DIR}/source.en.srt"

echo "[3/5] Translating SRT to Korean..."
# Delegates to wjs-translating-subtitles.
translate-srt "${WORK_DIR}/source.en.srt" --target ko --out "${WORK_DIR}/source.ko.srt"

echo "[4/5] Burning Korean subtitles into the video..."
python "$(dirname "$0")/../../wjs-burning-subtitles/scripts/render.py" \
  --video "${WORK_DIR}/source.mp4" --srt "${WORK_DIR}/source.ko.srt" \
  --out "${WORK_DIR}/source.subtitled.mp4"

echo "[5/5] Normalizing and concatenating with the Microsoft logo bumper..."
for f in "${INTRO_CLIP}" "${WORK_DIR}/source.subtitled.mp4" "${OUTRO_CLIP}"; do
  base="$(basename "$f")"
  ffmpeg -y -i "$f" -vf "scale=1920:1080,fps=30" -c:v libx264 -pix_fmt yuv420p \
    -c:a aac -ar 48000 -ac 2 "${WORK_DIR}/norm_${base}"
done

{
  echo "file '${WORK_DIR}/norm_$(basename "${INTRO_CLIP}")'"
  echo "file '${WORK_DIR}/norm_source.subtitled.mp4'"
  echo "file '${WORK_DIR}/norm_$(basename "${OUTRO_CLIP}")'"
} > "${WORK_DIR}/concat_list.txt"

ffmpeg -y -f concat -safe 0 -i "${WORK_DIR}/concat_list.txt" -c copy "${OUTPUT}"

echo "Done: ${OUTPUT}"
