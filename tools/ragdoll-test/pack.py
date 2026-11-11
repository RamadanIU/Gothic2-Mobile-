#!/usr/bin/env python3
# Gothic II Mobile: площадка - собрать страницу просмотра из записей сцен стенда.
#   ./ragtest labs <каталог>                        # все сцены площадки + scenes.json (группы, заголовки)
#   ./ragtest lab <сцена> out.json                  # одна сцена площадки
#   ./ragtest rec <сцена> out.json [заголовок]      # сцена прежнего набора (visScene, duelScene)
#   python3 pack.py lab_out.html a.json b.json ...  # одна страница со всеми сценами (viewer/lab.html)
#   python3 pack.py --artifact out.html ...         # то же без doctype/html/head/body - для публикации
# Числа кадров (мм) упаковываются в base64 по 16 бит: данные вдвое меньше, страница читает их сама (unpack в lab.html)
import base64
import json
import os
import struct
import sys


def i16(arr):
    vals = [max(-32768, min(32767, int(v))) for v in arr]
    return base64.b64encode(struct.pack('<%dh' % len(vals), *vals)).decode('ascii')


def compact(scene):
    for f in scene['frames']:
        f['b'] = [i16(a) for a in f['b']]
        if 'g' in f:
            f['g'] = [i16(a) for a in f['g']]
        if 'w' in f:
            f['w'] = [[a[0], i16(a[1:])] for a in f['w']]
    return scene


def strip_document(html):
    # для публикации страницу оборачивают в свой скелет: свои doctype, html, head, body убрать
    import re
    html = re.sub(r'<!doctype html>\s*', '', html, flags=re.I)
    html = re.sub(r'</?html[^>]*>\s*', '', html, flags=re.I)
    html = re.sub(r'</?head>\s*', '', html, flags=re.I)
    html = re.sub(r'</?body>\s*', '', html, flags=re.I)
    html = re.sub(r'<meta charset="utf-8">\s*', '', html, flags=re.I)
    html = re.sub(r'<meta name="viewport"[^>]*>\s*', '', html, flags=re.I)
    return html


def main():
    args = sys.argv[1:]
    artifact = False
    if args and args[0] == '--artifact':
        artifact = True
        args = args[1:]
    out = args[0]
    here = os.path.dirname(os.path.abspath(__file__))
    html = open(os.path.join(here, 'viewer', 'lab.html'), encoding='utf-8').read()
    meta = {}
    scenes = []
    for p in args[1:]:
        if os.path.basename(p) == 'scenes.json':
            meta.update(json.load(open(p, encoding='utf-8')))
            continue
        scenes.append(compact(json.load(open(p, encoding='utf-8'))))
    for s in scenes:
        m = meta.get(s['name'], {})
        for k in ('group', 'title', 'note'):
            if k in m:
                s[k] = m[k]
    order = list(meta.keys())
    scenes.sort(key=lambda s: order.index(s['name']) if s['name'] in order else len(order))
    blob = json.dumps(scenes, ensure_ascii=False, separators=(',', ':')).replace('</', '<\\/')
    html = html.replace('/*SCENES*/[]', blob, 1)
    if artifact:
        html = strip_document(html)
    open(out, 'w', encoding='utf-8').write(html)
    print('%s: %d scenes, %.1f MB' % (out, len(scenes), len(html.encode('utf-8')) / 1e6))


if __name__ == '__main__':
    main()
