"""Mierzy WNETRZE kafla ikony na pulpicie, z wykluczeniem tapety i ramki fokusu.

PULAPKA, w ktora sam wpadlem: pierwszy pomiar wzial dominujacy kolor calego
wycinka za tlo, a najczestszym kolorem po granacie byla NIEBIESKA RAMKA
zaznaczenia TalkBacka (8,78,223) biegnaca po brzegach wycinka. Wyszlo z tego
"kontrast 2.99:1 ZBYT NISKI", co bylo artefaktem pomiaru, nie cecha ikony.
Wniosek ogolny: przy pomiarze ze zrzutu ekranu wyznaczaj obszar oceny GEOMETRYCZNIE
(spojny obszar kafla), nie statystycznie po czestosci kolorow.
"""
import sys
import numpy as np
from PIL import Image
from collections import deque, Counter

GRANAT = np.array([2, 9, 29])


def lum(c):
    c = np.asarray(c, dtype=float) / 255.0
    c = np.where(c <= 0.03928, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)
    return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]


def kontrast(a, b):
    la, lb = lum(a), lum(b)
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)


def analiza(plik, nazwa, tlo_kafla=None):
    a = np.asarray(Image.open(plik).convert('RGB')).astype(int)
    H, W, _ = a.shape
    print('=== %s ===' % nazwa)
    # 1. kafel: spojny obszar wokol SRODKA wycinka (tam zawsze jest ikona)
    if tlo_kafla is None:
        tlo_kafla = a[H // 2, W // 2]
    tol = 70
    podobny = np.abs(a - tlo_kafla).sum(2) < tol
    kafel = np.zeros((H, W), bool)
    q = deque([(H // 2, W // 2)])
    kafel[H // 2, W // 2] = True
    while q:
        y, x = q.popleft()
        for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            ny, nx = y + dy, x + dx
            if 0 <= ny < H and 0 <= nx < W and not kafel[ny, nx] and podobny[ny, nx]:
                kafel[ny, nx] = True
                q.append((ny, nx))
    ys, xs = np.nonzero(kafel)
    if len(xs) < 50:
        print('  nie udalo sie wyznaczyc kafla (srodek to moze byc symbol)')
        return
    x0, x1, y0, y1 = xs.min(), xs.max(), ys.min(), ys.max()
    print('  tlo kafla %-18s bbox kafla %dx%d px'
          % (str(tuple(tlo_kafla)), x1 - x0 + 1, y1 - y0 + 1))
    # 2. wnetrze kafla = prostokat wpisany, zeby wykluczyc tapete i ramke fokusu
    cx, cy = (x0 + x1) / 2.0, (y0 + y1) / 2.0
    r = min(x1 - x0, y1 - y0) / 2.0
    pol = r * 0.70                       # kwadrat wpisany w okrag kafla
    ix0, ix1 = int(cx - pol), int(cx + pol)
    iy0, iy1 = int(cy - pol), int(cy + pol)
    wnetrze = a[iy0:iy1, ix0:ix1].reshape(-1, 3)
    d = np.abs(wnetrze - tlo_kafla).sum(1)
    sym = wnetrze[d > 110]
    udzial = 100.0 * len(sym) / len(wnetrze)
    print('  obszar oceny: %dx%d px (wnetrze kafla, bez tapety i ramki fokusu)'
          % (ix1 - ix0, iy1 - iy0))
    if len(sym) == 0:
        print('  BRAK symbolu w kaflu - IKONA PUSTA')
        return
    c = Counter(tuple(p) for p in sym)
    glowny = np.array(c.most_common(1)[0][0])
    k_gl = kontrast(glowny, tlo_kafla)
    sredni = sym.mean(0)
    k_sr = kontrast(sredni, tlo_kafla)
    print('  symbol zajmuje %.1f%% wnetrza kafla' % udzial)
    print('  glowny kolor symbolu %-18s kontrast %.2f:1  %s'
          % (str(tuple(glowny)), k_gl, 'OK' if k_gl >= 3 else 'ZBYT NISKI'))
    print('  sredni kolor symbolu %-18s kontrast %.2f:1  %s'
          % (str(tuple(int(v) for v in sredni)), k_sr, 'OK' if k_sr >= 3 else 'ZBYT NISKI'))
    najj = sym[np.argmax([lum(p) for p in sym])]
    print('  najjasniejszy piksel  %-18s kontrast %.2f:1'
          % (str(tuple(int(v) for v in najj)), kontrast(najj, tlo_kafla)))


analiza('/tmp/ikony/ikona_test.png', 'NASZA NOWA (wersja testowa)', GRANAT)
print()
analiza('/tmp/ikony/ikona_prod_a.png', 'PRODUKCYJNA 1.0.5 (dla porownania)')
print()
analiza('/tmp/ikony/ikona_prod_b.png', 'TRZECIA POZYCJA TYFLOCENTRUM')
