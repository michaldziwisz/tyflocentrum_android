"""Generuje warstwy adaptive icon TyfloCentrum Android z ikony Windows.

ZASADA GEOMETRII adaptive icon (dokumentacja Androida):
- warstwy maja 108x108 dp, z czego widoczne jest srodkowe 72x72 dp (maska systemu),
- strefa GWARANTOWANA (kazda maska, tez okragla) to srodkowe 66x66 dp.
Symbol z Windows zajmuje 75% szerokosci kafla, wiec przy przeniesieniu 1:1 zostalby
przyciety. Skalujemy go tak, by w calosci lezal w strefie gwarantowanej 66/108.

Wynik:
- ic_launcher_foreground.png (kazda gestosc) - symbol na przezroczystym tle,
- ic_launcher_background: jednolity granat #02091D jako kolor (bez bitmapy),
- ic_launcher_monochrome.png - sylwetka symbolu w jednym kanale, pod Material You,
- mipmap/ic_launcher.png + ic_launcher_round.png - zapasowe bitmapy dla launcherow
  bez wsparcia adaptive icon (i dla Androida < 8, choc minSdk to 26).
"""
import os
import numpy as np
from PIL import Image

BG_HEX = '#02091D'
BG = (2, 9, 29)
SYMBOL = '/tmp/ikony/symbol.png'
RES = '/mnt/d/projekty/tyflocentrum_android/app/src/main/res'

# gestosci: nazwa katalogu -> rozmiar ikony legacy (mipmap) w px
GESTOSCI = {
    'mdpi': 48,
    'hdpi': 72,
    'xhdpi': 96,
    'xxhdpi': 144,
    'xxxhdpi': 192,
}
# warstwa adaptive ma 108 dp; przy mdpi 1 dp = 1 px
SKALA_DP = {'mdpi': 1.0, 'hdpi': 1.5, 'xhdpi': 2.0, 'xxhdpi': 3.0, 'xxxhdpi': 4.0}

# udzial strefy gwarantowanej: 66 z 108 dp
STREFA = 66.0 / 108.0

sym = Image.open(SYMBOL).convert('RGBA')
sw, sh = sym.size
print('symbol zrodlowy: %dx%d' % (sw, sh))


def warstwa_foreground(rozmiar_px):
    """Symbol wpisany w strefe gwarantowana warstwy 108 dp.

    UWAGA (blad zlapany testem): strefa gwarantowana to OKRAG o srednicy 66 dp,
    nie kwadrat - maska launchera moze byc okragla. Wpisanie prostokata symbolu
    w kwadrat 66x66 wypuszcza jego naroza poza okrag. Dlatego ograniczamy
    PRZEKATNA symbolu do srednicy strefy.
    """
    plotno = Image.new('RGBA', (rozmiar_px, rozmiar_px), (0, 0, 0, 0))
    srednica = rozmiar_px * STREFA
    przekatna = (sw ** 2 + sh ** 2) ** 0.5
    # 0.98 to zapas na zaokraglenia przy skalowaniu: bez niego bbox wychodzil
    # poza okrag o 1 px na xhdpi i xxhdpi (zlapane testem)
    wsp = 0.98 * srednica / przekatna
    nw, nh = max(1, round(sw * wsp)), max(1, round(sh * wsp))
    maly = sym.resize((nw, nh), Image.LANCZOS)
    warstwa = Image.new('RGBA', (rozmiar_px, rozmiar_px), (0, 0, 0, 0))
    warstwa.paste(maly, ((rozmiar_px - nw) // 2, (rozmiar_px - nh) // 2))
    plotno.alpha_composite(warstwa)
    return plotno


def warstwa_monochrome(rozmiar_px):
    """Sylwetka symbolu: bialy kształt + alfa. System sam pokoloruje."""
    fg = warstwa_foreground(rozmiar_px)
    a = np.asarray(fg).astype(np.uint8).copy()
    a[..., 0:3] = 255                     # kolor nieistotny, liczy sie alfa
    return Image.fromarray(a, 'RGBA')


def bitmapa_legacy(rozmiar_px):
    """Zapasowa ikona 1:1 jak na Windows: granatowy squircle + symbol 75%."""
    plotno = Image.new('RGBA', (rozmiar_px, rozmiar_px), (0, 0, 0, 0))
    # squircle: superelipsa |x|^n + |y|^n = 1, n=4 daje kształt bliski Windows/iOS
    n = 4.0
    promien = rozmiar_px / 2.0
    ss = 4  # nadprobkowanie krawedzi
    duze = rozmiar_px * ss
    yy, xx = np.mgrid[0:duze, 0:duze]
    x = (xx + 0.5) / (duze / 2.0) - 1.0
    y = (yy + 0.5) / (duze / 2.0) - 1.0
    wewn = (np.abs(x) ** n + np.abs(y) ** n) <= 1.0
    maska = Image.fromarray((wewn * 255).astype(np.uint8), 'L').resize(
        (rozmiar_px, rozmiar_px), Image.LANCZOS)
    kafel = Image.new('RGBA', (rozmiar_px, rozmiar_px), BG + (255,))
    kafel.putalpha(maska)
    plotno.alpha_composite(kafel)
    # symbol 75% szerokosci kafla, tak jak w oryginale Windows.
    # UWAGA (blad zlapany testem): paste() z maska MIESZA tez kanal alfa, wiec
    # w miejscach przezroczystych symbolu robila sie dziura w kaflu (srodek
    # ikony mial alfa 219 zamiast 255). alpha_composite sklada poprawnie.
    doc = rozmiar_px * 0.75
    wsp = min(doc / sw, doc / sh)
    nw, nh = max(1, round(sw * wsp)), max(1, round(sh * wsp))
    maly = sym.resize((nw, nh), Image.LANCZOS)
    nakladka = Image.new('RGBA', (rozmiar_px, rozmiar_px), (0, 0, 0, 0))
    nakladka.paste(maly, ((rozmiar_px - nw) // 2, (rozmiar_px - nh) // 2))
    plotno.alpha_composite(nakladka)
    _ = promien
    return plotno


for gest, rozmiar in GESTOSCI.items():
    kat = os.path.join(RES, 'mipmap-' + gest)
    os.makedirs(kat, exist_ok=True)
    px108 = round(108 * SKALA_DP[gest])
    warstwa_foreground(px108).save(os.path.join(kat, 'ic_launcher_foreground.png'))
    warstwa_monochrome(px108).save(os.path.join(kat, 'ic_launcher_monochrome.png'))
    legacy = bitmapa_legacy(rozmiar)
    legacy.save(os.path.join(kat, 'ic_launcher.png'))
    # wariant round: ta sama tresc obcieta do kola
    okrag = Image.new('L', (rozmiar * 4, rozmiar * 4), 0)
    from PIL import ImageDraw
    ImageDraw.Draw(okrag).ellipse((0, 0, rozmiar * 4 - 1, rozmiar * 4 - 1), fill=255)
    okrag = okrag.resize((rozmiar, rozmiar), Image.LANCZOS)
    kolo = Image.new('RGBA', (rozmiar, rozmiar), BG + (255,))
    kolo.putalpha(okrag)
    doc = rozmiar * 0.75
    wsp = min(doc / sw, doc / sh)
    nw, nh = max(1, round(sw * wsp)), max(1, round(sh * wsp))
    maly = sym.resize((nw, nh), Image.LANCZOS)
    nakl = Image.new('RGBA', (rozmiar, rozmiar), (0, 0, 0, 0))
    nakl.paste(maly, ((rozmiar - nw) // 2, (rozmiar - nh) // 2))
    kolo.alpha_composite(nakl)
    kolo.save(os.path.join(kat, 'ic_launcher_round.png'))
    print('%-8s warstwa 108dp=%dpx, legacy=%dpx' % (gest, px108, rozmiar))

# ikona do Google Play 512x512 (bez przezroczystosci, wymog sklepu)
play = bitmapa_legacy(512).convert('RGBA')
tlo = Image.new('RGBA', (512, 512), BG + (255,))
tlo.alpha_composite(play)
tlo.convert('RGB').save('/tmp/ikony/play-icon-512.png')
print('ikona Play 512 w /tmp/ikony/play-icon-512.png')
print('kolor tla warstwy:', BG_HEX)
