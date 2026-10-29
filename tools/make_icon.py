#!/usr/bin/env python3
"""Builds the Android launcher icon layers and the in-app mascot images from the FileBandit artwork.

usage: make_icon.py <artwork.webp> <res dir> <assets dir> [preview dir]

* drawable-nodpi/ic_launcher_fg.png   108dp adaptive foreground (artwork, feathered, inside the safe zone)
* drawable-nodpi/ic_launcher_bg.png   108dp adaptive background (the artwork's blue, extended outwards)
* drawable-nodpi/mascot.png           256px mascot for empty states
* assets/mascot.png                   96px logo for the iPhone page (inlined by the build into index.html)
"""
import os, sys
import numpy as np
from PIL import Image, ImageFilter, ImageDraw

src, res, assets = sys.argv[1], sys.argv[2], sys.argv[3]
preview = sys.argv[4] if len(sys.argv) > 4 else None

art = Image.open(src).convert("RGB")
a = np.array(art).astype(np.int16)
h, w = a.shape[:2]

# 1. The artwork sits on white. Find the rounded square it occupies (anything not near-white).
nonwhite = ~((a[:, :, 0] > 244) & (a[:, :, 1] > 244) & (a[:, :, 2] > 244))
ys, xs = np.where(nonwhite)
x0, x1, y0, y1 = xs.min(), xs.max(), ys.min(), ys.max()
side = min(x1 - x0, y1 - y0) + 1
x0 = (x0 + x1 + 1 - side) // 2
y0 = (y0 + y1 + 1 - side) // 2
art = art.crop((x0, y0, x0 + side, y0 + side))
S = art.size[0]

# 2. Soft rounded-square alpha that stays just inside the artwork's own edge (no white fringe).
inset = int(S * 0.006)
radius = int(S * 0.235)
mask = Image.new("L", (S * 2, S * 2), 0)
ImageDraw.Draw(mask).rounded_rectangle((inset * 2, inset * 2, S * 2 - inset * 2, S * 2 - inset * 2), radius=radius * 2, fill=255)
mask = mask.resize((S, S), Image.LANCZOS).filter(ImageFilter.GaussianBlur(S * 0.014))
art_rgba = art.convert("RGBA")
art_rgba.putalpha(mask)

# 3. Adaptive layers are 108dp; draw at 6x = 648px. The safe circle is 66dp, so the artwork is 66dp wide.
L = 648
art_px = int(L * 66 / 108)
fg = Image.new("RGBA", (L, L), (0, 0, 0, 0))
small = art_rgba.resize((art_px, art_px), Image.LANCZOS)
fg.paste(small, ((L - art_px) // 2, (L - art_px) // 2), small)

# 4. Background: the artwork's blue, extended. Take the art's edge colours, push them outwards, blur.
cover = art.resize((L, L), Image.LANCZOS)
c = np.array(cover).astype(np.float32)
yy, xx = np.mgrid[0:L, 0:L]
# distance-based blend: heavy blur of a very small copy gives a smooth gradient that follows the art's colours
tiny = art.resize((12, 12), Image.BOX)
bg = tiny.resize((L, L), Image.BICUBIC).filter(ImageFilter.GaussianBlur(L * 0.08))
# keep it blue: pull dark raccoon fur out of the average by clamping towards the nearest-edge blue
bg_arr = np.array(bg).astype(np.float32)
edge_cols = np.concatenate([np.array(art.resize((L, L)))[2:10].reshape(-1, 3),
                            np.array(art.resize((L, L)))[-10:-2].reshape(-1, 3)]).astype(np.float32)
ref = np.array(art.resize((L, L)))
mid = slice(int(L * 0.35), int(L * 0.65))
top = ref[int(L * 0.03):int(L * 0.05), mid].reshape(-1, 3).mean(axis=0)
bottom = ref[int(L * 0.95):int(L * 0.97), mid].reshape(-1, 3).mean(axis=0)
grad = np.zeros((L, L, 3), np.float32)
t = (yy / (L - 1)).astype(np.float32)[..., None]
grad[:] = top * (1 - t) + bottom * t
bg_img = Image.fromarray(np.clip(grad, 0, 255).astype(np.uint8), "RGB")

def save(img, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path, optimize=True)

save(fg, os.path.join(res, "drawable-nodpi", "ic_launcher_fg.png"))
save(bg_img, os.path.join(res, "drawable-nodpi", "ic_launcher_bg.png"))
save(art_rgba.resize((256, 256), Image.LANCZOS), os.path.join(res, "drawable-nodpi", "mascot.png"))
save(art_rgba.resize((96, 96), Image.LANCZOS), os.path.join(assets, "mascot.png"))
print("wrote icon layers; artwork box", (x0, y0, side), "top", top, "bottom", bottom)

if preview:
    os.makedirs(preview, exist_ok=True)
    full = Image.alpha_composite(bg_img.convert("RGBA"), fg)
    shapes = {}
    P = L
    def masked(kind):
        m = Image.new("L", (P * 2, P * 2), 0)
        d = ImageDraw.Draw(m)
        vis = int(P * 72 / 108)          # the launcher shows the central 72dp of 108dp
        off = (P - vis) // 2
        box = (off * 2, off * 2, (off + vis) * 2, (off + vis) * 2)
        if kind == "circle": d.ellipse(box, fill=255)
        elif kind == "squircle": d.rounded_rectangle(box, radius=int(vis * 0.42), fill=255)
        else: d.rounded_rectangle(box, radius=int(vis * 0.2), fill=255)
        m = m.resize((P, P), Image.LANCZOS)
        out = Image.new("RGBA", (P, P), (240, 240, 240, 255))
        out.paste(full, (0, 0), m)
        return out
    sheet = Image.new("RGBA", (P * 3 + 40, P + 20), (240, 240, 240, 255))
    for i, k in enumerate(["circle", "squircle", "rounded"]):
        sheet.paste(masked(k), (10 + i * (P + 10), 10))
    sheet.convert("RGB").save(os.path.join(preview, "icon_masks.png"))
    full.convert("RGB").save(os.path.join(preview, "icon_full_layer.png"))
