"""Wgrywa ikone 512x512 do LISTINGU Google Play (karta sklepu).

DLACZEGO ISTNIEJE: wydanie nowej wersji aplikacji NIE zmienia grafiki w karcie
sklepu. Ikona w APK (mipmap) i ikona listingu to dwa rozne zasoby - listing idzie
przez edits.images. Nasz Fastfile mial skip_upload_images: true, wiec wydanie
1.0.7 podmienilo ikone na pulpicie, ale w sklepie zostala stara.

ZAPIS DO SKLEPU. Domyslnie robi PROBE (nic nie zatwierdza). Zapis dopiero
z --zapisz, swiadomie, zeby przypadkowe uruchomienie nie zmienilo karty sklepu.
"""
import argparse
import hashlib
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

KLUCZ = '/home/ubuntu/play-sa.json'
PAKIET = 'net.tyflopodcast.tyflocentrum'
JEZYK = 'pl-PL'
BAZA = 'https://androidpublisher.googleapis.com/androidpublisher/v3/applications/%s' % PAKIET
BAZA_UPLOAD = ('https://androidpublisher.googleapis.com/upload/androidpublisher/v3/'
               'applications/%s' % PAKIET)


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


def wolaj(url, tok, metoda='GET', dane=None, typ=None):
    naglowki = {'Authorization': 'Bearer ' + tok}
    if typ:
        naglowki['Content-Type'] = typ
    if dane is None and metoda in ('POST', 'PUT'):
        naglowki['Content-Length'] = '0'
    req = urllib.request.Request(url, data=dane, headers=naglowki, method=metoda)
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            surowe = r.read()
            return json.loads(surowe) if surowe else {}
    except urllib.error.HTTPError as e:
        tresc = e.read().decode('utf-8', 'replace')
        raise SystemExit('BLAD API %s: %s' % (e.code, tresc[:600]))


def main():
    ap = argparse.ArgumentParser(allow_abbrev=False, description=__doc__)
    ap.add_argument('plik', help='ikona PNG 512x512')
    ap.add_argument('--zapisz', action='store_true',
                    help='ZATWIERDZA edycje w sklepie (bez tego tylko proba)')
    a = ap.parse_args()

    surowa = open(a.plik, 'rb').read()
    suma = hashlib.sha256(surowa).hexdigest()
    print('plik: %s (%d B)\nsha256 lokalnie: %s' % (a.plik, len(surowa), suma))

    tok = token()
    edycja = wolaj(BAZA + '/edits', tok, 'POST')
    eid = edycja['id']
    print('edycja: %s' % eid)

    try:
        przed = wolaj('%s/edits/%s/listings/%s/icon' % (BAZA, eid, JEZYK), tok)
        stare = [o.get('sha256') for o in przed.get('images', [])]
        print('ikona W SKLEPIE przed zmiana: %s' % (stare or 'BRAK'))

        wynik = wolaj('%s/edits/%s/listings/%s/icon' % (BAZA_UPLOAD, eid, JEZYK),
                      tok, 'POST', surowa, 'image/png')
        wgrana = wynik.get('image', {})
        print('wgrane do edycji: sha256=%s' % wgrana.get('sha256'))
        if wgrana.get('sha256') != suma:
            print('UWAGA: sklep zwraca INNA sume niz plik lokalny (mogl przetworzyc obraz)')

        if not a.zapisz:
            print('\nPROBA - edycja NIE zatwierdzona, karta sklepu bez zmian.')
            print('Aby zapisac: dodaj --zapisz')
            wolaj('%s/edits/%s' % (BAZA, eid), tok, 'DELETE')
            print('(edycja skasowana)')
            return

        wolaj('%s/edits/%s:commit' % (BAZA, eid), tok, 'POST')
        print('\nEDYCJA ZATWIERDZONA.')
    except SystemExit:
        try:
            wolaj('%s/edits/%s' % (BAZA, eid), tok, 'DELETE')
            print('(edycja skasowana po bledzie)')
        except SystemExit:
            pass
        raise

    # DOWOD PO FAKCIE: czytamy stan z NOWEJ edycji, nie z tej, ktora zapisywala.
    tok2 = token()
    kontrola = wolaj(BAZA + '/edits', tok2, 'POST')
    kid = kontrola['id']
    try:
        po = wolaj('%s/edits/%s/listings/%s/icon' % (BAZA, kid, JEZYK), tok2)
        nowe = [o.get('sha256') for o in po.get('images', [])]
        print('ikona W SKLEPIE po zmianie: %s' % (nowe or 'BRAK'))
        if nowe and nowe != stare:
            print('POTWIERDZONE: ikona listingu ZMIENIONA.')
        else:
            print('UWAGA: suma sie NIE zmienila - sprawdz recznie w Play Console.')
    finally:
        wolaj('%s/edits/%s' % (BAZA, kid), tok2, 'DELETE')


if __name__ == '__main__':
    main()
