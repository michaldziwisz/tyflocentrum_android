"""Odczytuje REALNY stan wydan TyfloCentrum w Google Play (tylko czytanie).

Sensem tego pomiaru jest odpowiedz na pytanie "co uzytkownicy MAJA", bo tag w git
i zielony przebieg CI nie dowodza, ze wydanie doszlo do sklepu ani ze zostalo
zaakceptowane. Nic nie publikuje.
"""
import json
import sys
import urllib.parse
import urllib.request
import time

KLUCZ = '/home/ubuntu/play-sa.json'
PAKIET = 'net.tyflopodcast.tyflocentrum'


def token():
    """OAuth2 JWT bearer flow - bez zewnetrznych bibliotek poza cryptography."""
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


def api(sciezka, tok):
    url = 'https://androidpublisher.googleapis.com/androidpublisher/v3/applications/%s/%s' % (
        PAKIET, sciezka)
    req = urllib.request.Request(url, headers={'Authorization': 'Bearer ' + tok})
    return json.load(urllib.request.urlopen(req, timeout=30))


try:
    tok = token()
except Exception as e:
    print('BLAD uwierzytelnienia: %s' % e)
    sys.exit(1)

print('=== SCIEZKI WYDAN W GOOGLE PLAY (pakiet %s) ===' % PAKIET)
edycja = None
try:
    url = ('https://androidpublisher.googleapis.com/androidpublisher/v3/applications/%s/edits'
           % PAKIET)
    req = urllib.request.Request(url, data=b'{}', method='POST',
                                 headers={'Authorization': 'Bearer ' + tok,
                                          'Content-Type': 'application/json'})
    edycja = json.load(urllib.request.urlopen(req, timeout=30))['id']
except Exception as e:
    print('nie udalo sie otworzyc edycji: %s' % e)
    sys.exit(1)

try:
    dane = api('edits/%s/tracks' % edycja, tok)
    for t in dane.get('tracks', []):
        print('\nsciezka: %s' % t['track'])
        for r in t.get('releases', []):
            kody = r.get('versionCodes', [])
            print('  status=%-12s versionCodes=%s  nazwa=%s'
                  % (r.get('status'), kody, r.get('name')))
            if r.get('userFraction'):
                print('    udzial uzytkownikow: %.0f%%' % (100 * r['userFraction']))
    print()
    # ostatnie wgrane pliki AAB
    b = api('edits/%s/bundles' % edycja, tok)
    kody = sorted(int(x['versionCode']) for x in b.get('bundles', []))
    print('wgrane AAB (versionCode): %s' % kody)
    print('NAJWYZSZY wgrany versionCode: %s' % (kody[-1] if kody else 'brak'))
finally:
    try:
        url = ('https://androidpublisher.googleapis.com/androidpublisher/v3/applications/%s/'
               'edits/%s' % (PAKIET, edycja))
        req = urllib.request.Request(url, method='DELETE',
                                     headers={'Authorization': 'Bearer ' + tok})
        urllib.request.urlopen(req, timeout=30)
        print('\n(edycja zamknieta, NIC nie opublikowano)')
    except Exception as e:
        print('\nuwaga: nie udalo sie zamknac edycji: %s' % e)
