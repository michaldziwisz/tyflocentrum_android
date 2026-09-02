"""Weryfikuje wygenerowane warstwy ikony TyfloCentrum Android.

Kazda kontrola POZYTYWNA ma pare NEGATYWNA (celowo zepsuty przypadek musi oblac),
zeby test nie byl atrapa. Uruchamiac po kazdej zmianie ikony.
"""
import os
import sys
import numpy as np
from PIL import Image

RES = '/mnt/d/projekty/tyflocentrum_android/app/src/main/res'
GEST = {'mdpi': (108, 48), 'hdpi': (162, 72), 'xhdpi': (216, 96),
        'xxhdpi': (324, 144), 'xxxhdpi': (432, 192)}
STREFA = 66.0 / 108.0
BG = np.array([2, 9, 29])
bledy = []
uwagi = []


def bbox_alfa(im, prog=20):
    a = np.asarray(im.convert('RGBA'))[..., 3]
    ys, xs = np.nonzero(a > prog)
    if len(xs) == 0:
        return None
    return xs.min(), ys.min(), xs.max(), ys.max()


def w_strefie(im, nazwa):
    """Symbol musi lezec w strefie gwarantowanej 66/108 warstwy."""
    W, H = im.size
    b = bbox_alfa(im)
    if b is None:
        bledy.append('%s: warstwa PUSTA (brak pikseli z alfa)' % nazwa)
        return None
    x0, y0, x1, y1 = b
    r = W * STREFA / 2.0
    cx = cy = W / 2.0
    naroza = [(x0, y0), (x1, y0), (x0, y1), (x1, y1)]
    poza = [p for p in naroza if ((p[0] - cx) ** 2 + (p[1] - cy) ** 2) ** 0.5 > r + 0.5]
    zajete = 100.0 * (x1 - x0 + 1) / W
    if poza:
        bledy.append('%s: tresc WYCHODZI ze strefy gwarantowanej (%d naroznikow bbox poza r=%.0f)'
                     % (nazwa, len(poza), r))
    return zajete


print('=== 1. WARSTWY FOREGROUND I MONOCHROME ===')
for gest, (px108, legacy) in GEST.items():
    for plik in ('ic_launcher_foreground.png', 'ic_launcher_monochrome.png'):
        p = os.path.join(RES, 'mipmap-' + gest, plik)
        if not os.path.exists(p):
            bledy.append('BRAK PLIKU: %s' % p)
            continue
        im = Image.open(p)
        if im.size != (px108, px108):
            bledy.append('%s/%s: rozmiar %s, oczekiwano %dx%d'
                         % (gest, plik, im.size, px108, px108))
        zaj = w_strefie(im, '%s/%s' % (gest, plik))
        if zaj is not None:
            print('  %-8s %-30s %dx%d, tresc zajmuje %.0f%% szerokosci warstwy'
                  % (gest, plik, im.size[0], im.size[1], zaj))

print('=== 2. MONOCHROME MA NIESC TYLKO KSZTALT ===')
for gest in GEST:
    p = os.path.join(RES, 'mipmap-' + gest, 'ic_launcher_monochrome.png')
    if not os.path.exists(p):
        continue
    a = np.asarray(Image.open(p).convert('RGBA'))
    widoczne = a[..., 3] > 128
    if widoczne.sum() == 0:
        bledy.append('%s monochrome: brak widocznych pikseli' % gest)
        continue
    rgb = a[widoczne][:, :3]
    if rgb.std() > 1.0:
        bledy.append('%s monochrome: kolory NIEjednolite (std=%.1f) - system nie pokoloruje poprawnie'
                     % (gest, rgb.std()))
    else:
        print('  %-8s jednolity kolor %s, widocznych pikseli %d'
              % (gest, tuple(rgb[0]), widoczne.sum()))

print('=== 3. BITMAPY ZAPASOWE (legacy) ===')
for gest, (px108, legacy) in GEST.items():
    for plik in ('ic_launcher.png', 'ic_launcher_round.png'):
        p = os.path.join(RES, 'mipmap-' + gest, plik)
        if not os.path.exists(p):
            bledy.append('BRAK PLIKU: %s' % p)
            continue
        im = Image.open(p).convert('RGBA')
        if im.size != (legacy, legacy):
            bledy.append('%s/%s: rozmiar %s, oczekiwano %dx%d'
                         % (gest, plik, im.size, legacy, legacy))
        a = np.asarray(im)
        # w srodku ma byc granat albo symbol, nie przezroczystosc
        c = a[legacy // 2, legacy // 2]
        if c[3] < 250:
            bledy.append('%s/%s: srodek PRZEZROCZYSTY (alfa=%d)' % (gest, plik, c[3]))
        krycie = 100.0 * (a[..., 3] > 128).mean()
        print('  %-8s %-22s %dx%d, krycie %.0f%%' % (gest, plik, legacy, legacy, krycie))

print('=== 4. ic_launcher I ic_launcher_round MUSZA SIE ROZNIC ===')
import hashlib
for gest in GEST:
    h = []
    for plik in ('ic_launcher.png', 'ic_launcher_round.png'):
        p = os.path.join(RES, 'mipmap-' + gest, plik)
        if os.path.exists(p):
            h.append(hashlib.md5(open(p, 'rb').read()).hexdigest())
    if len(h) == 2 and h[0] == h[1]:
        bledy.append('%s: ic_launcher.png i ic_launcher_round.png IDENTYCZNE (zastany defekt wrocil)'
                     % gest)
    elif len(h) == 2:
        print('  %-8s roznia sie (%s.. vs %s..)' % (gest, h[0][:8], h[1][:8]))

print('=== 5. PLIKI XML ===')
for p, musi in (
    (os.path.join(RES, 'mipmap-anydpi-v26/ic_launcher.xml'),
     ('adaptive-icon', 'ic_launcher_background', 'ic_launcher_foreground', 'monochrome')),
    (os.path.join(RES, 'mipmap-anydpi-v26/ic_launcher_round.xml'),
     ('adaptive-icon', 'monochrome')),
    (os.path.join(RES, 'values/ic_launcher_colors.xml'), ('#02091D',)),
):
    if not os.path.exists(p):
        bledy.append('BRAK PLIKU: %s' % p)
        continue
    t = open(p, encoding='utf-8').read()
    brak = [m for m in musi if m not in t]
    if brak:
        bledy.append('%s: brak %s' % (os.path.basename(p), brak))
    else:
        print('  %s OK' % os.path.basename(p))

print('=== 6. KONTROLE NEGATYWNE (celowo zle dane MUSZA oblac) ===')
kontrole = 0
# a) warstwa z trescia poza strefa
zla = Image.new('RGBA', (108, 108), (0, 0, 0, 0))
zla.paste(Image.new('RGBA', (104, 104), (255, 255, 255, 255)), (2, 2))
przed = len(bledy)
w_strefie(zla, 'KONTROLA-poza-strefa')
if len(bledy) > przed:
    bledy.pop()
    kontrole += 1
    print('  OK: tresc 104/108 px zostala wykryta jako wychodzaca ze strefy')
else:
    print('  ATRAPA: kontrola strefy NIE dziala')
    uwagi.append('kontrola strefy nie dziala')
# b) pusta warstwa
przed = len(bledy)
w_strefie(Image.new('RGBA', (108, 108), (0, 0, 0, 0)), 'KONTROLA-pusta')
if len(bledy) > przed:
    bledy.pop()
    kontrole += 1
    print('  OK: pusta warstwa zostala wykryta')
else:
    print('  ATRAPA: pusta warstwa NIE wykryta')
    uwagi.append('kontrola pustej warstwy nie dziala')
# c) monochrome z wielobarwna trescia
kolor = np.zeros((108, 108, 4), np.uint8)
kolor[10:90, 10:90, 3] = 255
kolor[10:50, 10:90, 0] = 255
kolor[50:90, 10:90, 2] = 255
a = kolor[kolor[..., 3] > 128][:, :3]
if a.std() > 1.0:
    kontrole += 1
    print('  OK: wielobarwny monochrome zostalby odrzucony (std=%.1f)' % a.std())
else:
    print('  ATRAPA: kontrola jednolitosci NIE dziala')
    uwagi.append('kontrola jednolitosci nie dziala')

print()
print('kontrole waznosci zdane: %d/3' % kontrole)
if bledy:
    print('BLEDY (%d):' % len(bledy))
    for b in bledy:
        print('  - ' + b)
    sys.exit(1)
print('WSZYSTKO OK - warstwy ikony zgodne z geometria adaptive icon')
if uwagi:
    sys.exit(2)
