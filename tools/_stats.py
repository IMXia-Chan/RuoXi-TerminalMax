# -*- coding: utf-8 -*-
import sys
from PIL import Image
for path in sys.argv[1:]:
    im = Image.open(path).convert("L")
    w, h = im.size
    px = list(im.getdata())
    n = len(px)
    bright = sum(1 for v in px if v > 140) * 100.0 / n
    mid = sum(1 for v in px if 60 < v <= 140) * 100.0 / n
    dark = sum(1 for v in px if v <= 60) * 100.0 / n
    print("%-34s %4dx%-4d 亮>140 %5.2f%%  中 60-140 %5.2f%%  暗 %5.2f%%" % (path.split("\\")[-1], w, h, bright, mid, dark))
