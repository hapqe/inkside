"""Local speech-to-text worker for the bridge.

Loads one model up front (on the Mac's GPU via MLX) and answers one JSON request
per stdin line: {"id": ..., "path": "/abs/audio"} → {"id": ..., "text": "..."}
or {"id": ..., "error": "..."}. Audio is decoded with ffmpeg, so any format the
tablet records works; language is auto-detected per clip.

TRANSCRIBE_ENGINE picks the library ("whisper" → mlx-whisper, "parakeet" →
parakeet-mlx) and TRANSCRIBE_MODEL the Hugging Face repo. The bridge sets both
and runs this through uv with the matching package:
  uv run --python 3.12 --with mlx-whisper python scripts/transcribe_worker.py
"""

import json
import os
import sys

ENGINE = os.environ.get("TRANSCRIBE_ENGINE", "whisper")
MODEL_ID = os.environ.get("TRANSCRIBE_MODEL", "mlx-community/whisper-large-v3-mlx")


def reply(obj):
    sys.stdout.write(json.dumps(obj) + "\n")
    sys.stdout.flush()


def load_whisper():
    import mlx.core as mx
    from mlx_whisper.transcribe import ModelHolder, transcribe

    # Warm the same cache transcribe() reads, so the first clip is not a cold load.
    ModelHolder.get_model(MODEL_ID, mx.float16)

    def run(path):
        result = transcribe(
            path,
            path_or_hf_repo=MODEL_ID,
            # Each clip stands alone; carrying text over only invites repeats.
            condition_on_previous_text=False,
        )
        return str(result.get("text", ""))

    return run


def load_parakeet():
    from parakeet_mlx import from_pretrained

    model = from_pretrained(MODEL_ID)
    return lambda path: model.transcribe(path).text


def main():
    run = load_parakeet() if ENGINE == "parakeet" else load_whisper()
    reply({"ready": True, "engine": ENGINE, "model": MODEL_ID})
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        req_id = None
        try:
            req = json.loads(line)
            req_id = req.get("id")
            reply({"id": req_id, "text": run(req["path"]).strip()})
        except Exception as e:  # one bad clip must not take the worker down
            reply({"id": req_id, "error": str(e)})


if __name__ == "__main__":
    main()
