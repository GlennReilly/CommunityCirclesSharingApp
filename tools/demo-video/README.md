# demo-video

Re-makes the Lendger demo video after the app changes. The result is a captioned screen demo of about three minutes with no voiceover, in the house style: the app on top with a caption bar underneath, large dark title, problem and end cards, and a synthesised drum 'n' bass bed. The story is two to four people (Alice, Ben, Carol, a new member) in separate browser contexts against a throwaway server.

## Prerequisites

- Python 3 with a virtual environment holding `playwright scipy numpy` (and `pillow` for the sample photo), for example `python3 -m venv ~/lendger-demo-venv && ~/lendger-demo-venv/bin/pip install playwright scipy numpy pillow`.
- Chromium from Playwright: `~/lendger-demo-venv/bin/python -m playwright install chromium` (it lands in `~/.cache/ms-playwright/chromium-*/chrome-linux*/chrome`). Set `CHROME=/path/to/chrome` to use another binary.
- `ffmpeg` and `ffprobe` on the PATH, plus the DejaVu fonts (`/usr/share/fonts/truetype/dejavu/`).
- Java 17 and the server jar: `./gradlew :server:shadowJar` makes `server/build/libs/lendger-server.jar` (override with `LENDGER_JAR`). Rebuild it after every app change, or the video shows the old app.

## Commands, in order

Run from the repo root (any directory works). Output goes to `data/demo/`, which is git-ignored.

```
PY=~/lendger-demo-venv/bin/python
$PY tools/demo-video/record_lendger.py data/demo/take1
$PY tools/demo-video/compose.py        data/demo/take1
$PY tools/demo-video/assemble.py data/demo/take1/screen.mp4 data/demo/take1/marks2.json data/demo/lendger-demo.mp4 tools/demo-video/cards.json
```

Optional: `SHOTS=1` on the record step saves a PNG at every caption (for checking what is on screen); `PORT=18097` changes the server port.

## What each script does

- `record_lendger.py <out-dir>` starts the shadow jar on a throwaway data folder inside the out dir, drives each person's browser with Playwright at 1280x720 (one recorded video per person), types the passphrases, adds an item with a photo, borrows, and so on. Every caption is registered with `cap("text")`. It writes `marks.json` (caption times, whose screen is shown when, the colour-flash times).
- `compose.py <out-dir>` cuts the per-person videos into one `screen.mp4` following the segments in `marks.json`, aligns the clocks using the colour flashes, adds a "Whose screen" label, and writes `marks2.json` (caption times on the cut timeline).
- `assemble.py <screen.mp4> <marks2.json> <out.mp4> <cards.json>` upscales the take to 1080p, burns the bottom caption bar, builds the title, problem and end cards from `cards.json`, synthesises the music and mixes it in. Work files go in `build/` next to the output.
- `make_photo.py <out.jpg>` draws the sample ladder photo; `record_lendger.py` calls it itself.
- `cards.json` holds the colours and the text of the three cards (`\n` splits lines).

## Editing the words

- Cards: edit `cards.json` (`title`, `problem`, `end`: `eyebrow`, `big`, `small`), then re-run only `assemble.py`.
- Captions: edit the `cap('...')` calls in `record_lendger.py`; this needs a new recording, then compose and assemble. Steps and waits sit beside the calls.

## Rules learned

- Record at 1280x720 and upscale to 1080p. The app's layout is designed for that width, and it keeps the text large and the recording small.
- Colour-flash sync: each context flashes red at the start and green at the end. `compose.py` finds them in the video to line up wall-clock marks with video time (video clocks run a little slow). Do not remove the flashes.
- Never touch `data/lendger.db`. The recorder uses a new data folder, `LENDGER_RATE_LIMIT=false` (all browsers share one address) and serves from `http://localhost:PORT`, not `127.0.0.1`, because passkeys (WebAuthn) refuse IP addresses.
- Captions in plain language, no jargon (no "libsodium", "Ed25519", "CBOR" and the like). Say what the person sees and why it matters.
- The take is silent; the music is added only at the end.
- Output stays in the git-ignored `data/demo/` (check: `git check-ignore data/demo/x`). Do not commit video files.

## Music licence

The drum 'n' bass bed is synthesised in `assemble.py` with numpy (kick, snare, hats, bass, pads written to a WAV). There is no sample, loop or third-party audio, so there is nothing to license or attribute.

## Check by ear

A script cannot judge audio. After assembling, a person must listen to the whole file: the music level against the screen, no clicks at the card joins, and no clipping. Also watch it once for caption timing and for any text that is cut off.
