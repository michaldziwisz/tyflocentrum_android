"""Weryfikuje zasoby ikony WYCIAGNIETE Z APK, nie z drzewa zrodel.

Sensem tego pomiaru jest to, ze test_ikony.py sprawdza pliki na dysku, a AGP
przepakowuje i moze przetworzyc PNG (crunch). Dowodem jest zawartosc APK.
"""
import glob
import sys
import numpy as np
from PIL import Image

KAT = '/tmp/apk_check/res'
STREFA = 66.0 / 108.0
bledy = []

print('=== ZASOBY WYPAKOWANE Z APK ===')
for p in sorted(glob.glob(KAT + '/mipmap-*/ic_launcher_foreground.png') +
                glob.glob(KAT + '/mipmap-*/ic_launcher_monochrome.png')):
    im = Image.open(p).convert('RGBA')
    a = np.asarray(im)
    W, H = im.size
    ys, xs = np.nonzero(a[..., 3] > 20)
    if len(xs) == 0:
        bledy.append('%s: PUSTA warstwa w APK' % p)
        continue
    r = W * STREFA / 2.0
    cx = cy = W / 2.0
    naroza = [(xs.min(), ys.min()), (xs.max(), ys.min()),
              (xs.min(), ys.max()), (xs.max(), ys.max())]
    poza = [q for q in naroza if ((q[0] - cx) ** 2 + (q[1] - cy) ** 2) ** 0.5 > r + 0.5]
    if poza:
        bledy.append('%s: tresc poza strefa gwarantowana' % p)
    nazwa = '/'.join(p.split('/')[-2:])
    print('  %-46s %dx%d, tresc %d%% szer, alfa max %d'
          % (nazwa, W, H, 100 * (xs.max() - xs.min() + 1) // W, a[..., 3].max()))

print('=== BITMAPY ZAPASOWE Z APK ===')
for p in sorted(glob.glob(KAT + '/mipmap-*/ic_launcher.png') +
                glob.glob(KAT + '/mipmap-*/ic_launcher_round.png')):
    im = Image.open(p).convert('RGBA')
    a = np.asarray(im)
    W, H = im.size
    srodek = a[H // 2, W // 2]
    if srodek[3] < 250:
        bledy.append('%s: srodek przezroczysty (alfa=%d)' % (p, srodek[3]))
    nazwa = '/'.join(p.split('/')[-2:])
    print('  %-46s %dx%d, srodek alfa=%d, krycie %d%%'
          % (nazwa, W, H, srodek[3], 100 * (a[..., 3] > 128).mean()))

print('=== MONOCHROME: JEDNOLITY KOLOR ===')
for p in sorted(glob.glob(KAT + '/mipmap-*/ic_launcher_monochrome.png')):
    a = np.asarray(Image.open(p).convert('RGBA'))
    w = a[..., 3] > 128
    if w.sum() == 0:
        bledy.append('%s: brak widocznych pikseli' % p)
        continue
    std = a[w][:, :3].std()
    if std > 1.0:
        bledy.append('%s: kolory niejednolite (std=%.1f)' % (p, std))
print('  sprawdzono %d plikow' % len(glob.glob(KAT + '/mipmap-*/ic_launcher_monochrome.png')))

print('=== KONTROLA WAZNOSCI: zepsuty zasob MUSI oblac ===')
zly = np.zeros((108, 108, 4), np.uint8)
zly[2:106, 2:106, 3] = 255
ys, xs = np.nonzero(zly[..., 3] > 20)
r = 108 * STREFA / 2.0
naroza = [(xs.min(), ys.min()), (xs.max(), ys.max())]
if any(((q[0] - 54) ** 2 + (q[1] - 54) ** 2) ** 0.5 > r for q in naroza):
    print('  OK: warstwa 104/108 px zostalaby odrzucona')
else:
    bledy.append('KONTROLA WAZNOSCI NIE DZIALA - pomiar jest atrapa')

print()
if bledy:
    print('BLEDY (%d):' % len(bledy))
    for b in bledy:
        print('  - ' + b)
    sys.exit(1)
print('APK ZAWIERA POPRAWNE ZASOBY IKONY')
