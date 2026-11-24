# -*- coding: utf-8 -*-
import sys
from PIL import Image
path, cols = sys.argv[1], int(sys.argv[2])
box = None
if len(sys.argv) > 6:
    box = tuple(int(v) for v in sys.argv[3:7])
im = Image.open(path).convert("L")
if box:
    im = im.crop(box)
w, h = im.size
rows = max(1, int(round(cols * h / float(w) * 0.5)))
cw, ch = w / float(cols), h / float(rows)
ramp = " .:-=+*#%@"
for ry in range(rows):
    line = []
    for rx in range(cols):
        b = im.crop((int(rx * cw), int(ry * ch), max(int(rx * cw) + 1, int((rx + 1) * cw)),
                     max(int(ry * ch) + 1, int((ry + 1) * ch))))
        d = list(b.getdata())
        line.append(ramp[min(9, int(sum(d)/float(len(d)) / 25.6))])
    print("".join(line))
