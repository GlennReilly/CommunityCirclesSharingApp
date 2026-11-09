"""Cuts the two per-person recordings into one screen take, following marks.json segments (None = cut), labels whose screen
is shown, and writes screen.mp4 + marks2.json (caption times on the new timeline) for assemble.py.
Usage: python compose.py <record-dir>"""
import json, os, subprocess, sys

if len(sys.argv) != 2 or sys.argv[1] in ('-h', '--help'): sys.exit(__doc__)
D = os.path.abspath(sys.argv[1])
m = json.load(open(os.path.join(D, 'marks.json')))
FONT = '/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf'
LABEL = {'alice': "Alice's screen", 'bea': "Ben's screen", 'carol': "Carol's screen", 'dan': "New member's screen"}
segs = m['segs'] + [(m['end'], None)]
kept = [(t, segs[i + 1][0], who) for i, (t, who) in enumerate(segs[:-1]) if who and segs[i + 1][0] - t > 0.05]

def mapt(t):
    """Global recording time -> time on the cut timeline (a time inside a cut maps to the start of the next kept part)."""
    acc = 0.0
    for s, e, _ in kept:
        if t < s: return acc
        if t <= e: return acc + (t - s)
        acc += e - s
    return acc

import numpy as np
def calibrate(who):
    """Video time = wall time + offset (+ drift): found from the red flash at the start and the green one at the end."""
    v = m['videos'][who]; f = m['flashes'][who]
    raw = subprocess.check_output(['ffmpeg', '-v', 'error', '-i', v['file'], '-vf', 'fps=30,scale=4:4', '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-'])
    px = np.frombuffer(raw, np.uint8).reshape(-1, 16, 3).mean(1)
    red = np.where((px[:, 0] > 200) & (px[:, 1] < 60) & (px[:, 2] < 60))[0]
    green = np.where((px[:, 1] > 200) & (px[:, 0] < 60) & (px[:, 2] < 60))[0]
    pts = []
    if len(red): pts.append((f[0], red[0] / 30))
    if len(green) and len(f) > 1: pts.append((f[1], green[0] / 30))
    print(who, 'flash points (wall, video):', [(round(a, 2), round(b, 2)) for a, b in pts], 't0', round(v['t0'], 2))
    if len(pts) == 2:
        (w0, v0), (w1, v1) = pts; k = (v1 - v0) / (w1 - w0); return lambda t: v0 + (t - w0) * k
    w0, v0 = pts[0]; return lambda t: v0 + (t - w0)
clock = {w: calibrate(w) for w in m['videos']}
parts = []
for i, (s, e, who) in enumerate(kept):
    v = m['videos'][who]; a, b = clock[who](s), clock[who](e)
    lab = os.path.join(D, f'label-{who}.txt'); open(lab, 'w').write(LABEL[who])
    out = os.path.join(D, f'part{i:02d}.mp4')
    vf = f"trim=start={a:.3f}:end={b:.3f},setpts=PTS-STARTPTS,fps=30"
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', v['file'], '-vf', vf, '-an', '-c:v', 'libx264', '-crf', '16', '-preset', 'fast', '-pix_fmt', 'yuv420p', out], check=True)
    parts.append(out)
lst = os.path.join(D, 'parts.txt'); open(lst, 'w').write(''.join(f"file '{p}'\n" for p in parts))
screen = os.path.join(D, 'screen.mp4')
subprocess.run(['ffmpeg', '-v', 'error', '-y', '-f', 'concat', '-safe', '0', '-i', lst, '-c', 'copy', screen], check=True)
dur = float(subprocess.check_output(['ffprobe', '-v', 'error', '-show_entries', 'format=duration', '-of', 'csv=p=0', screen]))
# Re-time on the real part lengths: video clocks run a little slow, so wall-clock lengths drift by over a second.
real = [float(subprocess.check_output(['ffprobe', '-v', 'error', '-show_entries', 'format=duration', '-of', 'csv=p=0', p])) for p in parts]
def mapt(t):
    acc = 0.0
    for (s, e, _), d in zip(kept, real):
        if t < s: return acc
        if t <= e: return acc + (t - s) * d / (e - s)
        acc += d
    return acc
caps = [(mapt(t), text) for t, text in m['caps']]
labels = []; acc = 0.0
for (s, e, who), d in zip(kept, real): labels.append((acc, acc + d, LABEL[who])); acc += d
json.dump({'start': 0.0, 'end': dur, 'caps': caps, 'labels': labels}, open(os.path.join(D, 'marks2.json'), 'w'), indent=1)
print('kept parts', len(kept), 'screen', round(dur, 2), 'mapped end', round(mapt(m['end']), 2))
for t, c in caps: print(f'{t:6.1f}  {c}')
