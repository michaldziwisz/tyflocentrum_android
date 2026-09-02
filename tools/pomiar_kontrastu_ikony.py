"""Mierzy kontrast ikony TyfloCentrum - to, czego uzytkownik niewidomy nie oceni sam.

Ikona nie ma wymogu WCAG dla tekstu, ale jesli symbol zlewa sie z tlem, ikona jest
nierozpoznawalna dla osob slabowidzacych - a to realny odbiorca tej aplikacji.
Progi odniesienia: WCAG 1.4.11 (elementy nietekstowe) wymaga 3:1.
"""
import glob
import numpy as np
from PIL import Image


def lum(c):
    """Luminancja relatywna wg WCAG."""
    c = np.asarray(c, dtype=float) / 255.0
    c = np.where(c <= 0.03928, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)
    return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]


def kontrast(a, b):
    la, lb = lum(a), lum(b)
    ja, ci = max(la, lb), min(la, lb)
    return (ja + 0.05) / (ci + 0.05)


BG = (2, 9, 29)
print('=== 1. SYMBOL WOBEC GRANATOWEGO TLA IKONY ===')
fg = Image.open('/mnt/d/projekty/tyflocentrum_android/app/src/main/res/'
                'mipmap-xxxhdpi/ic_launcher_foreground.png').convert('RGBA')
a = np.asarray(fg).astype(float)
krycie = a[..., 3] > 200
if krycie.sum():
    rgb = a[krycie][:, :3]
    # dominujace kolory symbolu
    from collections import Counter
    c = Counter(tuple(int(v) for v in p) for p in rgb)
    for kolor, ile in c.most_common(3):
        k = kontrast(kolor, BG)
        udzial = 100.0 * ile / krycie.sum()
        ocena = 'OK' if k >= 3.0 else 'ZBYT NISKI'
        print('  kolor %-18s udzial %5.1f%%  kontrast wobec tla %5.2f:1  %s'
              % (str(kolor), udzial, k, ocena))
    sredni = rgb.mean(0)
    print('  sredni kolor symbolu %s: kontrast %.2f:1'
          % (tuple(int(v) for v in sredni), kontrast(sredni, BG)))

print()
print('=== 2. WARSTWA MONOCHROME PO POKOLOROWANIU PRZEZ MATERIAL YOU ===')
print('  System sam dobiera pare kolorow z motywu uzytkownika. Sprawdzamy')
print('  skrajne przypadki: jasny symbol na ciemnym tle i odwrotnie.')
pary = [
    ((255, 255, 255), (2, 9, 29), 'biel na granacie (motyw ciemny)'),
    ((2, 9, 29), (255, 255, 255), 'granat na bieli (motyw jasny)'),
    ((176, 208, 255), (11, 31, 59), 'typowa para Material You, ciemny'),
    ((0, 60, 110), (212, 227, 255), 'typowa para Material You, jasny'),
]
for fgc, bgc, opis in pary:
    k = kontrast(fgc, bgc)
    print('  %-38s %5.2f:1  %s' % (opis, k, 'OK' if k >= 3.0 else 'ZBYT NISKI'))

print()
print('=== 3. CZY SYMBOL PRZETRWA MASKI SYSTEMOWE ===')
STREFA = 66.0 / 108.0
for p in sorted(glob.glob('/mnt/d/projekty/tyflocentrum_android/app/src/main/res/'
                          'mipmap-*/ic_launcher_foreground.png')):
    im = Image.open(p).convert('RGBA')
    W, _ = im.size
    al = np.asarray(im)[..., 3]
    ys, xs = np.nonzero(al > 20)
    r_okrag = W * STREFA / 2.0
    cx = cy = W / 2.0
    d = np.sqrt((xs - cx) ** 2 + (ys - cy) ** 2)
    # maska OKRAGLA to najostrzejszy przypadek
    ile_poza = int((d > r_okrag).sum())
    gest = p.split('/')[-2].replace('mipmap-', '')
    print('  %-8s pikseli symbolu poza okregiem gwarantowanym: %d  (zapas %.0f%%)'
          % (gest, ile_poza, 100 * (1 - d.max() / r_okrag)))
