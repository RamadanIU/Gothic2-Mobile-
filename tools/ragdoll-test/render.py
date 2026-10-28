#!/usr/bin/env python3
# Gothic II Mobile: кадры стенда регдолла (ragtest vis ...) -> GIF: тело палочками-капсулами, вид сбоку и спереди.
#   ./ragtest vis <сцена> out.txt && python3 render.py out.txt out.gif
# Формат out.txt: "F <время, с> <подпись>" - начало кадра; "B x1 y1 z1 x2 y2 z2 r <вид>" - кость (см);
# "M x y z <цвет>" - метка (рана, цель ступни).
import os
import sys
from PIL import Image, ImageDraw

W, H = 360, 360

COLORS = {
    'p': (200, 160, 110),   # таз и позвоночник
    'h': (230, 200, 160),   # голова
    'a': (120, 170, 230),   # руки
    'l': (110, 200, 140),   # ноги
    'k': (150, 150, 150),   # прочее
    'o': (170, 120, 200),   # другой человек (опора для руки)
    'w': (95, 95, 110),     # стена
}
MARK = {'r': (230, 60, 60), 'g': (80, 220, 80), 'b': (80, 120, 255), 'y': (240, 220, 60)}


def parse(path):
    frames = []
    cur = None
    for ln in open(path, encoding='utf-8'):
        p = ln.split()
        if not p:
            continue
        if p[0] == 'F':
            cur = {'t': float(p[1]), 'label': ' '.join(p[2:]), 'bones': [], 'marks': []}
            frames.append(cur)
        elif p[0] == 'B' and cur is not None:
            v = list(map(float, p[1:8]))
            cur['bones'].append((v[0:3], v[3:6], v[6], p[8] if len(p) > 8 else 'k'))
        elif p[0] == 'M' and cur is not None:
            v = list(map(float, p[1:4]))
            cur['marks'].append((v, p[4] if len(p) > 4 else 'r'))
    return frames


def project(view, c, p):
    # view: 'side' - смотрим вдоль -X (вперёд модели +Z - вправо), 'front' - смотрим с +Z на модель,
    # 'top' - сверху (лежащее тело: где руки и ноги); TOP=1 - вместо вида спереди
    x, y, z = p[0] - c[0], p[1] - c[1], p[2] - c[2]
    s = 1.05
    if view == 'side':
        return (W / 2 + z * s, H * 0.86 - y * s, -x)
    if view == 'top':
        return (W / 2 + z * s, H * 0.5 + x * s, y)
    return (W / 2 + x * s, H * 0.86 - y * s, -z)


def draw_view(img, ox, view, fr, center):
    d = ImageDraw.Draw(img)
    # земля и сетка 50 см
    gy = H * 0.86 + center[1] * 1.05
    if view != 'top':
        d.line([(ox, gy), (ox + W, gy)], fill=(90, 90, 90), width=2)
    for k in range(-6, 7):
        if view == 'side':
            gx = ox + W / 2 + (k * 50 - center[2]) * 1.05
        else:
            gx = ox + W / 2 + (k * 50 - center[0]) * 1.05
        d.line([(gx, gy), (gx, gy + 6)], fill=(90, 90, 90), width=1)
    items = []
    for a, b, r, kind in fr['bones']:
        # стена - только сбоку, позади всего
        if kind == 'w' and view != 'side':
            continue
        pa = project(view, center, a)
        pb = project(view, center, b)
        items.append(((pa[2] + pb[2]) / 2 if kind != 'w' else -1e9, pa, pb, r, kind))
    items.sort(key=lambda t: t[0])
    zs = [t[0] for t in items if t[4] != 'w'] or [0]
    zmin, zmax = min(zs), max(zs)
    for z, pa, pb, r, kind in items:
        col = COLORS.get(kind, COLORS['k'])
        f = 0.65 + 0.35 * ((z - zmin) / (zmax - zmin + 1e-3)) if kind != 'w' else 1.0
        col = tuple(int(c * f) for c in col)
        w = max(2, int(r * 2 * 1.05))
        d.line([(ox + pa[0], pa[1]), (ox + pb[0], pb[1])], fill=col, width=w)
        for q in (pa, pb):
            d.ellipse([ox + q[0] - w / 2, q[1] - w / 2, ox + q[0] + w / 2, q[1] + w / 2], fill=col)
    for p, c in fr['marks']:
        q = project(view, center, p)
        d.ellipse([ox + q[0] - 4, q[1] - 4, ox + q[0] + 4, q[1] + 4], fill=MARK.get(c, MARK['r']))


def main():
    frames = parse(sys.argv[1])
    out = sys.argv[2]
    step = int(sys.argv[3]) if len(sys.argv) > 3 else 1
    t0 = float(sys.argv[4]) if len(sys.argv) > 4 else -1
    t1 = float(sys.argv[5]) if len(sys.argv) > 5 else 1e9
    sheet = out.endswith('.png')
    imgs = []
    # камера мягко следит за телом (среднее костей), иначе оно уходит из кадра
    cx = cz = None
    for i, fr in enumerate(frames):
        if i % step or fr['t'] < t0 or fr['t'] > t1:
            continue
        if fr['bones']:
            px = sum(b[0][0] + b[1][0] for b in fr['bones']) / (2 * len(fr['bones']))
            pz = sum(b[0][2] + b[1][2] for b in fr['bones']) / (2 * len(fr['bones']))
            cx = px if cx is None else cx + (px - cx) * 0.15
            cz = pz if cz is None else cz + (pz - cz) * 0.15
        center = (cx or 0, 0, cz or 0)
        img = Image.new('RGB', (W * 2, H), (28, 26, 24))
        draw_view(img, 0, 'side', fr, center)
        second = 'top' if os.environ.get('TOP') else 'front'
        draw_view(img, W, second, fr, center)
        d = ImageDraw.Draw(img)
        d.text((8, 6), '%.2f s  %s' % (fr['t'], fr['label']), fill=(230, 230, 230))
        d.text((8, H - 16), 'side', fill=(150, 150, 150))
        d.text((W + 8, H - 16), second, fill=(150, 150, 150))
        imgs.append(img)
    if sheet:
        # лист кадров: по 4 в ряд, каждый вдвое меньше
        cols = 3
        tw, th = W * 2 * 2 // 3, H * 2 // 3
        rows = (len(imgs) + cols - 1) // cols
        img = Image.new('RGB', (tw * cols, th * rows), (0, 0, 0))
        for i, im in enumerate(imgs):
            img.paste(im.resize((tw, th)), ((i % cols) * tw, (i // cols) * th))
        img.save(out)
        return
    dur = int(1000 * (frames[1]['t'] - frames[0]['t']) * step) if len(frames) > 1 else 33
    imgs[0].save(out, save_all=True, append_images=imgs[1:], duration=max(dur, 20), loop=0)


if __name__ == '__main__':
    main()
