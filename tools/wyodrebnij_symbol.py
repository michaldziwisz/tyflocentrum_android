"""Wyodrebnia symbol z kafla ikony Windows TyfloCentrum jako PNG z alfa.

Kafel to granatowy squircle #02091D na bialych naroznikach. Symbol wewnatrz jest
cyjanowy z bialymi elementami. Zakladamy zlozenie:
    p = a*C + (1-a)*BG
gdzie C to czysty kolor symbolu, BG to granat kafla. Dla kazdego piksela dobieramy
najblizszy kolor odniesienia C z palety symbolu, liczymy alfe z odleglosci od tla
i odzyskujemy C przez odwrocenie zlozenia (un-premultiply wobec BG).
"""
import numpy as np
from collections import deque
from PIL import Image

SRC = '/mnt/d/projekty/tyflocentrum_pc/src/TyfloCentrum.Windows.App/Assets/Square310x310Logo.png'
BG = np.array([2, 9, 29], dtype=float)
# paleta symbolu zmierzona w poprzednim kroku: cyjan i biel
PALETA = np.array([[2, 177, 251], [255, 255, 255]], dtype=float)

im = Image.open(SRC).convert('RGB')
a = np.asarray(im).astype(float)
H, W, _ = a.shape

# 1. maska "poza kaflem": biale, spojne z naroznikow
jasne = a.sum(2) > 600
poza = np.zeros((H, W), bool)
q = deque()
for y, x in ((0, 0), (0, W - 1), (H - 1, 0), (H - 1, W - 1)):
    poza[y, x] = True
    q.append((y, x))
while q:
    y, x = q.popleft()
    for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        ny, nx = y + dy, x + dx
        if 0 <= ny < H and 0 <= nx < W and not poza[ny, nx] and jasne[ny, nx]:
            poza[ny, nx] = True
            q.append((ny, nx))

# 2. dla kazdego piksela: najblizszy kolor palety (po kierunku od tla)
d_bg = a - BG                                   # wektor od tla
odl_bg = np.linalg.norm(d_bg, axis=2)
# rzut na kazdy kolor palety, wybieramy ten o najwiekszej zgodnosci kierunku
najlepszy = np.zeros((H, W), int)
najlepsza_zgodnosc = np.full((H, W), -2.0)
for i, C in enumerate(PALETA):
    kier = C - BG
    kier_n = kier / np.linalg.norm(kier)
    zgod = (d_bg @ kier_n) / np.maximum(odl_bg, 1e-6)
    lepszy = zgod > najlepsza_zgodnosc
    najlepsza_zgodnosc = np.where(lepszy, zgod, najlepsza_zgodnosc)
    najlepszy = np.where(lepszy, i, najlepszy)

C_wyb = PALETA[najlepszy]                        # (H,W,3)
dlug_C = np.linalg.norm(C_wyb - BG, axis=2)
# alfa = jak daleko piksel odszedl od tla w stosunku do pelnego koloru
alfa = np.clip(odl_bg / np.maximum(dlug_C, 1e-6), 0, 1)
# 3. odzyskanie czystego koloru: C = (p - (1-a)*BG) / a
with np.errstate(divide='ignore', invalid='ignore'):
    kolor = (a - (1 - alfa[..., None]) * BG) / np.maximum(alfa[..., None], 1e-6)
kolor = np.clip(kolor, 0, 255)
# gdzie alfa znikoma - kolor niestabilny, bierzemy kolor palety
slaba = alfa < 0.06
kolor[slaba] = C_wyb[slaba]
alfa[slaba] = 0.0

# 4. wszystko poza kaflem i pas 6 px przy jego krawedzi to NIE symbol
from scipy import ndimage
kafel = np.logical_not(poza)
kafel_e = ndimage.binary_erosion(kafel, ndimage.generate_binary_structure(2, 1), iterations=6)
alfa[np.logical_not(kafel_e)] = 0.0

out = np.dstack([kolor, alfa * 255]).astype(np.uint8)
sym = Image.fromarray(out, 'RGBA')
ys, xs = np.nonzero(alfa > 0.08)
box = (xs.min(), ys.min(), xs.max() + 1, ys.max() + 1)
print('symbol bbox w kaflu:', box, 'rozmiar %dx%d' % (box[2] - box[0], box[3] - box[1]))
sym.crop(box).save('/tmp/ikony/symbol.png')
sym.save('/tmp/ikony/symbol_pelny.png')
print('pikseli alfa>0.5: %d' % (alfa > 0.5).sum())
print('zapisano /tmp/ikony/symbol.png (obciety) i symbol_pelny.png')
