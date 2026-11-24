# -*- coding: utf-8 -*-
"""一次性：放大第 7 题示例截图，确认末尾有没有句号。"""
from PIL import Image

SRC = r"C:\Users\27014\phone-touchpad\tools\_screen.png"
OUT = r"C:\Users\27014\phone-touchpad\tools\_crop.png"

im = Image.open(SRC)
crop = im.crop((740, 465, 1300, 530))
crop = crop.resize((crop.width * 3, crop.height * 3), Image.LANCZOS)
crop.save(OUT)
print("ok", OUT, crop.size)
