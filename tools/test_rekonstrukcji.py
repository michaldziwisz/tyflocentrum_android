"""Sprawdza, czy wyodrebniony symbol.png odtwarza oryginalny kafel Windows.

Kontrola waznosci: skladamy symbol z powrotem na granatowym tle i porownujemy
z oryginalem. Gdyby wyodrebnianie bylo bledne, blad byl by duzy. Dodatkowo
kontrola NEGATYWNA: to samo porownanie dla PODMIENIONEGO symbolu (przesunietego)
musi dac blad WYRAZNIE wiekszy - inaczej test nic nie mierzy.
"""
import numpy as np
from PIL import Image

BG = np.array([2, 9, 29], dtype=float)
orig = np.asarray(Image.open(
    '/mnt/d/projekty/tyflocentrum_pc/src/TyfloCentrum.Windows.App/Assets/Square310x310Logo.png'
).convert('RGB')).astype(float)
sym = np.asarray(Image.open('/tmp/ikony/symbol_pelny.png').convert('RGBA')).astype(float)

def zloz(s):
    a = s[..., 3:4] / 255.0
    return a * s[..., :3] + (1 - a) * BG

# obszar oceny: tylko wnetrze kafla (tam gdzie symbol moze byc)
maska = sym[..., 3] > 0
# rozszerz maske o otoczke, zeby zlapac ewentualne zgubione piksele
from scipy import ndimage
ocena = ndimage.binary_dilation(maska, iterations=3)

rekon = zloz(sym)
blad = np.abs(rekon - orig).mean(2)
print('REKONSTRUKCJA (symbol zlozony z powrotem na granacie):')
print('  sredni blad na kanal w obszarze symbolu: %.2f/255' % blad[ocena].mean())
print('  maksymalny blad: %.1f' % blad[ocena].max())
print('  pikseli z bledem >12: %d z %d (%.2f%%)'
      % ((blad[ocena] > 12).sum(), ocena.sum(), 100 * (blad[ocena] > 12).mean()))

# KONTROLA NEGATYWNA: przesuniety symbol musi dac znacznie wiekszy blad
sym_zly = np.roll(sym, 9, axis=1)
blad_zly = np.abs(zloz(sym_zly) - orig).mean(2)
print('KONTROLA NEGATYWNA (symbol przesuniety o 9 px):')
print('  sredni blad: %.2f/255' % blad_zly[ocena].mean())
if blad_zly[ocena].mean() > 4 * max(blad[ocena].mean(), 0.1):
    print('  OK - test rozroznia poprawne od bledneho (%.1fx wiekszy blad)'
          % (blad_zly[ocena].mean() / max(blad[ocena].mean(), 0.01)))
else:
    print('  UWAGA - test NIE rozroznia, jest atrapa')
