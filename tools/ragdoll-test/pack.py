#!/usr/bin/env python3
# Gothic II Mobile: площадка - собрать страницу просмотра из записей сцен стенда.
#   ./ragtest rec <сцена> out.json [заголовок]      # запись сцены (tools/ragdoll-test/rec.h)
#   python3 pack.py lab_out.html a.json b.json ...  # одна страница со всеми сценами (viewer/lab.html)
# Файл scenes.json рядом со сценой может задать группу и заголовки: {"<сцена>": {"group": "...", "title": "...", "note": "..."}}
import json
import os
import sys


def main():
    out = sys.argv[1]
    here = os.path.dirname(os.path.abspath(__file__))
    html = open(os.path.join(here, 'viewer', 'lab.html'), encoding='utf-8').read()
    meta = {}
    scenes = []
    for p in sys.argv[2:]:
        if os.path.basename(p) == 'scenes.json':
            meta.update(json.load(open(p, encoding='utf-8')))
            continue
        scenes.append(json.load(open(p, encoding='utf-8')))
    for s in scenes:
        m = meta.get(s['name'], {})
        for k in ('group', 'title', 'note'):
            if k in m:
                s[k] = m[k]
    order = list(meta.keys())
    scenes.sort(key=lambda s: order.index(s['name']) if s['name'] in order else len(order))
    blob = json.dumps(scenes, ensure_ascii=False, separators=(',', ':')).replace('</', '<\\/')
    html = html.replace('/*SCENES*/[]', blob, 1)
    open(out, 'w', encoding='utf-8').write(html)
    print('%s: %d scenes, %.1f MB' % (out, len(scenes), len(html.encode('utf-8')) / 1e6))


if __name__ == '__main__':
    main()
