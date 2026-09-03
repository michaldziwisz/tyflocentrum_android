"""Sprawdza, jaka ikona (grafika 512x512) wisi w LISTINGU Google Play.

Po co osobne narzedzie obok stan_play.py: ikona w APK/AAB (mipmap, ta na pulpicie
telefonu) i ikona w KARCIE SKLEPU to DWA ROZNE zasoby. Wydanie nowej wersji
aplikacji NIE zmienia grafiki listingu - ta idzie osobnym wywolaniem API
(edits.images), a nasz Fastfile ma skip_upload_images: true.

Tylko odczyt: otwiera edycje, czyta, KASUJE edycje. Nic nie publikuje.
"""
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

KLUCZ = '/home/ubuntu/play-sa.json'
PAKIET = 'net.tyflopodcast.tyflocentrum'
BAZA = 'https://androidpublisher.googleapis.com/androidpublisher/v3/applications/%s' % PAKIET


def token():
    d = json.load(open(KLUCZ))
    import base64
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import padding

    def b64(x):
        return base64.urlsafe_b64encode(x).rstrip(b'=')

    teraz = int(time.time())
    naglowek = b64(json.dumps({'alg': 'RS256', 'typ': 'JWT'}).encode())
    tresc = b64(json.dumps({
        'iss': d['client_email'],
        'scope': 'https://www.googleapis.com/auth/androidpublisher',
        'aud': 'https://oauth2.googleapis.com/token',
        'iat': teraz,
        'exp': teraz + 3600,
    }).encode())
    do_podpisu = naglowek + b'.' + tresc
    klucz = serialization.load_pem_private_key(d['private_key'].encode(), password=None)
    podpis = b64(klucz.sign(do_podpisu, padding.PKCS1v15(), hashes.SHA256()))
    dane = urllib.parse.urlencode({
        'grant_type': 'urn:ietf:params:oauth:grant-type:jwt-bearer',
        'assertion': (do_podpisu + b'.' + podpis).decode(),
    }).encode()
    r = urllib.request.urlopen('https://oauth2.googleapis.com/token', dane, timeout=30)
    return json.load(r)['access_token']


def zapytaj(url, tok, metoda='GET'):
    req = urllib.request.Request(url, headers={'Authorization': 'Bearer ' + tok},
                                 method=metoda)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            surowe = r.read()
            return json.loads(surowe) if surowe else {}
    except urllib.error.HTTPError as e:
        return {'BLAD': '%s %s' % (e.code, e.read().decode('utf-8', 'replace')[:300])}


tok = token()
edycja = json.load(urllib.request.urlopen(urllib.request.Request(
    BAZA + '/edits', headers={'Authorization': 'Bearer ' + tok,
                              'Content-Length': '0'}, method='POST'), timeout=30))
eid = edycja['id']

try:
    print('=== JEZYKI LISTINGU ===')
    listingi = zapytaj('%s/edits/%s/listings' % (BAZA, eid), tok)
    jezyki = [l['language'] for l in listingi.get('listings', [])]
    print(jezyki or listingi)

    for jezyk in jezyki:
        print('\n=== GRAFIKI listingu [%s] ===' % jezyk)
        for rodzaj in ('icon', 'featureGraphic'):
            wynik = zapytaj('%s/edits/%s/listings/%s/%s' % (BAZA, eid, jezyk, rodzaj), tok)
            obrazy = wynik.get('images', [])
            if not obrazy:
                print('  %-15s BRAK  %s' % (rodzaj, wynik.get('BLAD', '')))
            for o in obrazy:
                print('  %-15s sha256=%s  sha1=%s' % (
                    rodzaj, o.get('sha256', '?')[:16], o.get('sha1', '?')[:16]))
                print('  %-15s url=%s' % ('', o.get('url', '')))
finally:
    zapytaj('%s/edits/%s' % (BAZA, eid), tok, metoda='DELETE')
    print('\n(edycja skasowana, NIC nie opublikowano)')
