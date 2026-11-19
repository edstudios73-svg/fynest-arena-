#!/usr/bin/env bash
# Downloads the open face models used by the local face verification engine.
#   YuNet face detector  (MIT licence)        - OpenCV Zoo
#   SFace face recogniser (Apache-2.0 licence) - OpenCV Zoo
set -euo pipefail
DIR="${1:-${SMARTATTEND_MODELS_DIR:-./models}}"
mkdir -p "$DIR"
fetch() {
  local name="$1" url="$2" sha="$3"
  if [ -f "$DIR/$name" ] && echo "$sha  $DIR/$name" | sha256sum -c --status; then
    echo "✓ $name already present"; return
  fi
  echo "↓ $name"
  curl -fsSL --retry 3 -o "$DIR/$name.part" "$url"
  echo "$sha  $DIR/$name.part" | sha256sum -c --status || { echo "Checksum mismatch for $name" >&2; rm -f "$DIR/$name.part"; exit 1; }
  mv "$DIR/$name.part" "$DIR/$name"
  echo "✓ $name"
}
fetch face_detection_yunet_2023mar.onnx \
  https://huggingface.co/opencv/face_detection_yunet/resolve/main/face_detection_yunet_2023mar.onnx \
  8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4
fetch face_recognition_sface_2021dec.onnx \
  https://huggingface.co/opencv/face_recognition_sface/resolve/main/face_recognition_sface_2021dec.onnx \
  0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79
