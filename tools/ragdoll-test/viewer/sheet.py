#!/usr/bin/env python3
# Gothic II Mobile: площадка - лист кадров из снимков shots.js:  python3 sheet.py out.png cols f0.png f1.png ...
import sys
from PIL import Image

out, cols = sys.argv[1], int(sys.argv[2])
imgs = [Image.open(p) for p in sys.argv[3:]]
w, h = imgs[0].size
rows = (len(imgs) + cols - 1) // cols
sheet = Image.new('RGB', (w * cols, h * rows), (0, 0, 0))
for i, im in enumerate(imgs):
    sheet.paste(im, ((i % cols) * w, (i // cols) * h))
sheet.save(out)
