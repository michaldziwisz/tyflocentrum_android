#!/usr/bin/env python3
"""Klient Google Play Developer API na kluczu service accountu.

PO CO TO ISTNIEJE. Przed wydaniem trzeba ODCZYTAC U ZRODLA, jakie versionCode
sklep juz przyjal. Numer w build.gradle.kts mowi tylko, co ZBUDUJESZ - nie, co
Play juz zajal (poprzednie wydanie moglo pojsc z CI albo z innej maszyny).
Kolizja numeru konczy sie odrzuceniem paczki PO buildzie, podpisaniu i wgraniu.

Domyslnie narzedzie tylko CZYTA. Kazda operacja zmieniajaca stan w sklepie
wymaga jawnego --zapisz.

Uzycie:
    python3 tools/play.py wersje                # co jest na kazdej sciezce
    python3 tools/play.py zajete-numery         # zuzyte versionCode
    python3 tools/play.py sciezka production    # szczegoly jednej sciezki
"""

from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

KLUCZ_SA = Path("/home/ubuntu/play-sa.json")
PAKIET = "net.tyflopodcast.tyflocentrum"
ZAKRES = "https://www.googleapis.com/auth/androidpublisher"
BAZA = "https://androidpublisher.googleapis.com/androidpublisher/v3"


def _b64(dane: bytes) -> str:
    import base64

    return base64.urlsafe_b64encode(dane).rstrip(b"=").decode()


def token_dostepu(sciezka_klucza: Path = KLUCZ_SA) -> str:
    """Wymienia klucz service accountu na token OAuth2 (RSA-SHA256, RFC 7523)."""
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import padding

    sa = json.loads(sciezka_klucza.read_text())
    teraz = int(time.time())
    naglowek = {"alg": "RS256", "typ": "JWT"}
    tresc = {
        "iss": sa["client_email"],
        "scope": ZAKRES,
        "aud": sa["token_uri"],
        "iat": teraz,
        "exp": teraz + 3600,
    }
    do_podpisu = f"{_b64(json.dumps(naglowek).encode())}.{_b64(json.dumps(tresc).encode())}".encode()

    from cryptography.hazmat.primitives.asymmetric import rsa

    klucz = serialization.load_pem_private_key(sa["private_key"].encode(), password=None)
    if not isinstance(klucz, rsa.RSAPrivateKey):
        # Klucze service accountow Google sa RSA. Gdy kiedys przestana byc,
        # chcemy jasnego komunikatu, nie bledu atrybutu w polowie podpisywania.
        raise SystemExit(f"klucz nie jest RSA, a {type(klucz).__name__}")
    podpis = klucz.sign(do_podpisu, padding.PKCS1v15(), hashes.SHA256())
    jwt = f"{do_podpisu.decode()}.{_b64(podpis)}"

    dane = urllib.parse.urlencode(
        {"grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer", "assertion": jwt}
    ).encode()
    with urllib.request.urlopen(sa["token_uri"], data=dane, timeout=30) as odp:
        return json.loads(odp.read())["access_token"]


def wywolaj(metoda: str, sciezka: str, token: str, tresc: dict | None = None) -> dict:
    url = f"{BAZA}{sciezka}"
    dane = json.dumps(tresc).encode() if tresc is not None else None
    zadanie = urllib.request.Request(url, data=dane, method=metoda)
    zadanie.add_header("Authorization", f"Bearer {token}")
    if dane:
        zadanie.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(zadanie, timeout=60) as odp:
            surowe = odp.read()
            return json.loads(surowe) if surowe else {}
    except urllib.error.HTTPError as blad:
        tresc_bledu = blad.read().decode(errors="replace")
        print(
            f"BLAD API: {metoda} {sciezka} -> HTTP {blad.code}\n{tresc_bledu}",
            file=sys.stderr,
        )
        raise SystemExit(1) from blad


def nowa_edycja(token: str) -> str:
    return wywolaj("POST", f"/applications/{PAKIET}/edits", token)["id"]


def porzuc_edycje(token: str, edycja: str) -> None:
    """Edycji NIE zostawiamy otwartej - wisiałaby w koncie jako niedokonczona."""
    try:
        wywolaj("DELETE", f"/applications/{PAKIET}/edits/{edycja}", token)
    except SystemExit:
        pass


def main() -> int:
    parser = argparse.ArgumentParser(description="Odczyt stanu wydan w Google Play.")
    parser.add_argument(
        "polecenie", choices=["wersje", "zajete-numery", "sciezka"], help="co odczytac"
    )
    parser.add_argument("argument", nargs="?", help="nazwa sciezki dla 'sciezka'")
    args = parser.parse_args()

    token = token_dostepu()
    edycja = nowa_edycja(token)
    try:
        if args.polecenie == "wersje":
            odp = wywolaj("GET", f"/applications/{PAKIET}/edits/{edycja}/tracks", token)
            for t in odp.get("tracks", []):
                print(f"\nSCIEZKA: {t['track']}")
                for r in t.get("releases", []):
                    kody = ", ".join(str(k) for k in r.get("versionCodes", []) or [])
                    print(
                        f"  nazwa={r.get('name'):12} status={r.get('status'):10} "
                        f"versionCodes=[{kody}] udzial={r.get('userFraction', '100%')}"
                    )
        elif args.polecenie == "zajete-numery":
            odp = wywolaj("GET", f"/applications/{PAKIET}/edits/{edycja}/bundles", token)
            kody = sorted(b["versionCode"] for b in odp.get("bundles", []))
            print("AAB (bundles) zajete versionCode:", kody)
            print("NAJWYZSZY zajety:", max(kody) if kody else "brak")
        elif args.polecenie == "sciezka":
            if not args.argument:
                print("podaj nazwe sciezki", file=sys.stderr)
                return 2
            odp = wywolaj(
                "GET",
                f"/applications/{PAKIET}/edits/{edycja}/tracks/{args.argument}",
                token,
            )
            print(json.dumps(odp, indent=2, ensure_ascii=False))
    finally:
        porzuc_edycje(token, edycja)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
