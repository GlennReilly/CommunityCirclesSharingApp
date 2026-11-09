"""Builds the finished demo: title + problem card, captioned screen take (1080p), end card, drum n bass music bed.
Usage: python assemble.py <screen.mp4> <marks2.json> <out.mp4> <cards.json>   (work files go in a build/ folder next to <out.mp4>)
cards.json: {"bg":"0x14102b","accent":"0xb9a7ff","title":{"eyebrow","big","small"},"problem":{...},"end":{...}}  (\\n splits lines)"""
import json, subprocess, sys, os, glob
import numpy as np, wave

if len(sys.argv) != 5 or sys.argv[1] in ('-h', '--help'): sys.exit(__doc__)
SRC, MARKS, OUT, CARDS = sys.argv[1:5]
C = json.load(open(CARDS))
B = os.path.join(os.path.dirname(os.path.abspath(OUT)), 'build'); os.makedirs(B, exist_ok=True)
FONT = '/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf'
FONT_REG = '/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf'
BG, ACCENT, MUTED = C.get('bg', '0x14102b'), C.get('accent', '0xb9a7ff'), C.get('muted', '0x9a93c4')
W, H, FPS = 1920, 1080, 30
MAP_WIDTH = 1920  # Lendger is a centred column: captions centred on the whole frame
FADE = 0.4

def run(*a):
    subprocess.run([str(x) for x in a], check=True)

def put(name, text):
    p = f'{B}/{name}.txt'; open(p, 'w').write(text); return p

def card(name, secs, eyebrow, big, small, big_size, small_size, eyebrow_size=46, x=200):
    """Dark card, block centred vertically: accent eyebrow, big headline, grey body (one drawtext per line)."""
    big_l = big.split('\n'); small_l = small.split('\n') if small else []
    h = (eyebrow_size * 1.9 if eyebrow else 0) + big_size * 1.22 * len(big_l) + (50 + small_size * 1.45 * len(small_l) if small_l else 0)
    y = (H - h) / 2; f = []
    if eyebrow:
        f.append(f"drawtext=fontfile={FONT}:textfile={put(name+'-e', eyebrow)}:fontsize={eyebrow_size}:fontcolor={ACCENT}:x={x}:y={y:.0f}"); y += eyebrow_size * 1.9
    for i, line in enumerate(big_l):
        f.append(f"drawtext=fontfile={FONT}:textfile={put(f'{name}-b{i}', line)}:fontsize={big_size}:fontcolor=white:x={x}:y={y:.0f}"); y += big_size * 1.22
    y += 50
    for i, line in enumerate(small_l):
        f.append(f"drawtext=fontfile={FONT_REG}:textfile={put(f'{name}-s{i}', line)}:fontsize={small_size}:fontcolor={MUTED}:x={x}:y={y:.0f}"); y += small_size * 1.45
    f.append(f"fade=t=in:st=0:d={FADE},fade=t=out:st={secs-FADE}:d={FADE},format=yuv420p")
    run('ffmpeg', '-v', 'error', '-y', '-f', 'lavfi', '-i', f'color=c={BG}:s={W}x{H}:d={secs}:r={FPS}', '-vf', ','.join(f), '-c:v', 'libx264', '-crf', 18, f'{B}/{name}.mp4')

m = json.load(open(MARKS))
start, end, caps = m['start'], m['end'], m['caps']

# Captions: white bold text on a translucent box, centred over the map area.
BAR = 130; SH = H - BAR; SW = round(SH * 16 / 9 / 2) * 2
vf = [f"fps={FPS}", f"trim=start={start}:end={end}", "setpts=PTS-STARTPTS", f"scale={SW}:{SH}:flags=lanczos", "setsar=1", f"pad={W}:{H}:{(W - SW) // 2}:0:color={BG}"]
for i, (t0, t1, text) in enumerate(m.get('labels', [])):
    vf.append(f"drawtext=fontfile={FONT}:textfile={put(f'lab{i}', text)}:fontsize=26:fontcolor=white:box=1:boxcolor=0x0b57d0@0.95:boxborderw=10:x=26:y={SH}+({BAR}-text_h)/2:enable='between(t,{t0:.2f},{t1:.2f})'")
for i, (t, text) in enumerate(caps):
    if not text: continue
    t1 = (caps[i + 1][0] if i + 1 < len(caps) else end) - start - 0.15
    t0 = t - start
    p = put(f'cap{i}', text)
    vf.append(f"drawtext=fontfile={FONT}:textfile={p}:fontsize=46:fontcolor=white:box=1:boxcolor=black@0.62:boxborderw=18:x=({MAP_WIDTH}-text_w)/2:y={SH}+({BAR}-text_h)/2:enable='between(t,{t0:.2f},{t1:.2f})'")
vf.append("format=yuv420p")
run('ffmpeg', '-v', 'error', '-y', '-i', SRC, '-vf', ','.join(vf), '-r', FPS, '-c:v', 'libx264', '-crf', 18, '-preset', 'medium', '-an', f'{B}/screen.mp4')

t_, p_, e_ = C['title'], C['problem'], C['end']
card('title', 4.5, t_.get('eyebrow'), t_['big'], t_.get('small'), 230, 64, 50)
card('problem', 7.5, p_.get('eyebrow', 'THE PROBLEM'), p_['big'], p_.get('small'), 72, 50, 46)
card('end', 6.5, e_.get('eyebrow'), e_['big'], e_.get('small'), 200, 42, 46)
open(f'{B}/list.txt', 'w').write(''.join(f"file '{os.path.abspath(B)}/{n}.mp4'\n" for n in ['title', 'problem', 'screen', 'end']))
run('ffmpeg', '-v', 'error', '-y', '-f', 'concat', '-safe', 0, '-i', f'{B}/list.txt', '-r', FPS, '-c:v', 'libx264', '-crf', 18, '-pix_fmt', 'yuv420p', '-an', f'{B}/silent.mp4')
dur = float(subprocess.check_output(['ffprobe', '-v', 'error', '-show_entries', 'format=duration', '-of', 'csv=p=0', f'{B}/silent.mp4']))

# Synthesised drum 'n' bass bed (no licence): 174 BPM two-step breakbeat, reese bass, hats, light stabs. Quiet intro, drums drop in after 4 bars.
from scipy.signal import lfilter, butter
SR = 44100; total = dur + 0.5; n = int(SR * total); t = np.arange(n) / SR
BPM = 174; step = 60 / BPM / 4  # one 16th note
rng = np.random.default_rng(7)
hz = lambda x: 440 * 2 ** ((x - 69) / 12)
drums = np.zeros(n); bass = np.zeros(n); stab = np.zeros(n)
def add(buf, at, snd, gain=1.0):
    a = int(at * SR)
    if a < n: e = min(n, a + len(snd)); buf[a:e] += snd[:e - a] * gain
def kick():
    k = np.arange(int(SR * .28)) / SR; f = 45 + 120 * np.exp(-k * 28)
    return np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-k * 9)
def snare():
    k = np.arange(int(SR * .22)) / SR
    return (rng.standard_normal(len(k)) * .8 + np.sin(2 * np.pi * 190 * k)) * np.exp(-k * 20)
def hat(open_=False):
    k = np.arange(int(SR * (.18 if open_ else .05))) / SR
    return np.diff(rng.standard_normal(len(k) + 1)) * np.exp(-k * (22 if open_ else 90))
K, S, HC, HO = kick(), snare(), hat(), hat(True)
bar = 16 * step; DROP = 4  # bars of intro before the drums
roots = [33, 29, 36, 31]  # A1 F1 C2 G1, one per 2 bars
for bi in range(int(total / bar) + 1):
    t0 = bi * bar; root = roots[(bi // 2) % 4]
    # reese bass: two detuned saws, low-passed; sits under everything, brighter once the drums are in
    f = hz(root); d = int(bar * SR); k = np.arange(d) / SR
    saw = lambda fr: 2 * ((k * fr) % 1) - 1
    r = (saw(f * .994) + saw(f * 1.006)) * .5
    bb, aa = butter(2, (500 if bi >= DROP else 220) / (SR / 2)); r = lfilter(bb, aa, r)
    r += np.sin(2 * np.pi * f * k) * .8
    r *= np.minimum(1, k / .02) * np.minimum(1, (bar - k) / .03)
    add(bass, t0, r, .55)
    # stabs: off-beat minor chord hits, filtered, from the intro on
    for pos_ in (3, 7, 11, 14):
        if bi % 2 == 0 and pos_ == 14: continue
        kk = np.arange(int(SR * .25)) / SR
        chord = sum(np.sin(2 * np.pi * hz(root + 24 + iv) * kk) + .4 * np.sin(4 * np.pi * hz(root + 24 + iv) * kk) for iv in (0, 3, 7))
        add(stab, t0 + pos_ * step, chord * np.exp(-kk * 14), .035 if bi >= DROP else .02)
    if bi < DROP: continue
    for pos_ in (0, 10): add(drums, t0 + pos_ * step, K, .9)
    for pos_ in (4, 12): add(drums, t0 + pos_ * step, S, .55)
    for pos_ in range(0, 16, 2): add(drums, t0 + pos_ * step, HC, .12 if pos_ % 4 else .08)
    for pos_ in (6, 14): add(drums, t0 + pos_ * step, HO, .09)
    if bi % 4 == 3: add(drums, t0 + 15 * step, S, .25)  # small fill
mix = drums * .9 + bass + stab
fade = np.minimum(1, t / 2) * np.minimum(1, (total - t) / 3.5)
mix *= fade; mix /= abs(mix).max()
x = (np.stack([mix, mix], 1) * .85 * 32767).astype(np.int16)
w = wave.open(f'{B}/music.wav', 'wb'); w.setnchannels(2); w.setsampwidth(2); w.setframerate(SR); w.writeframes(x.tobytes()); w.close()
run('ffmpeg', '-v', 'error', '-y', '-i', f'{B}/silent.mp4', '-i', f'{B}/music.wav', '-af', 'loudnorm=I=-18:TP=-1.5:LRA=11,aresample=48000', '-map', '0:v', '-map', '1:a', '-c:v', 'copy', '-c:a', 'aac', '-b:a', '192k', '-shortest', '-movflags', '+faststart', OUT)
print(OUT, dur)
