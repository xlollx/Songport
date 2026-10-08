#!/usr/bin/env python3
"""Daily check of what the app depends on outside its own code, with anonymous calls only.

Each check mirrors a step the app takes: the Spotify web route's query-hash registry and player
page, the Spotify short share links, Apple Music's web developer token, Deezer's public catalogue
(the ISRC oracle), song.link, YouTube Music's and Amazon Music's player pages. A failure here is
an early warning that a route will break for users; it says nothing about accounts or playlists.
"""
import base64
import json
import re
import sys
import urllib.error
import urllib.parse
import urllib.request

DESKTOP = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
MOBILE = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
JWT = re.compile(r"eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def get(url, ua=DESKTOP, follow=True, timeout=30):
    req = urllib.request.Request(url, headers={"User-Agent": ua, "Accept": "text/html,application/json,*/*", "Accept-Language": "en-US,en;q=0.8"})
    opener = urllib.request.build_opener() if follow else urllib.request.build_opener(NoRedirect())
    try:
        with opener.open(req, timeout=timeout) as r:
            return r.status, dict(r.headers), r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read().decode("utf-8", "replace")


def is_jwt(tok):
    try:
        head = base64.urlsafe_b64decode(tok.split(".")[0] + "==").decode()
        return "alg" in json.loads(head)
    except Exception:
        return False


def spotify_registry():
    code, _, body = get("https://raw.githubusercontent.com/Jigen-Ohtsusuki/spotify-gql-registry/main/hashes.json")
    assert code == 200, f"HTTP {code}"
    hashes = json.loads(body)
    assert isinstance(hashes, dict) and len(hashes) > 10, "registry empty"
    assert any("laylist" in k for k in hashes), "no playlist operations in the registry"


def spotify_player_page():
    code, _, body = get("https://open.spotify.com/")
    assert code == 200, f"HTTP {code}"
    assert 'id="session"' in body or "appServerConfig" in body, "no session script in the player page"


def spotify_short_link():
    code, headers, body = get("https://open.spotify.com/s/CSKhEuR", ua=MOBILE, follow=False)
    location = headers.get("Location") or headers.get("location") or ""
    landed = urllib.parse.unquote(urllib.parse.unquote(location + body))
    assert "/playlist/" in landed, f"HTTP {code}, nothing playlist-like in the hop"


def apple_developer_token():
    code, _, html = get("https://music.apple.com/us/browse")
    assert code == 200, f"HTTP {code}"
    for m in JWT.finditer(html):
        if is_jwt(m.group(0)):
            return
    scripts = re.findall(r'(?:src|href)="((?:https://music\.apple\.com)?/assets/[^"]+\.js)"', html)
    scripts = sorted(set(scripts), key=lambda s: 0 if "/index" in s else 1)
    assert scripts, "no script bundles in the page"
    for path in scripts[:12]:
        code, _, js = get("https://music.apple.com" + path.replace("https://music.apple.com", ""))
        if code != 200:
            continue
        for m in JWT.finditer(js):
            if is_jwt(m.group(0)):
                return
    raise AssertionError("no developer token in the page or its bundles")


def deezer_catalogue():
    code, _, body = get("https://api.deezer.com/search?q=queen%20bohemian%20rhapsody&limit=1")
    assert code == 200, f"HTTP {code}"
    data = json.loads(body).get("data") or []
    assert data and data[0].get("isrc") is not None or data, "no results"
    code, _, body = get(f"https://api.deezer.com/track/{data[0]['id']}")
    assert json.loads(body).get("isrc"), "track without ISRC"


def odesli():
    code, _, body = get("https://api.song.link/v1-alpha.1/links?url=" + urllib.parse.quote("https://open.spotify.com/track/4u7EnebtmKWzUH433cf5Qv", safe=""))
    assert code == 200, f"HTTP {code}"
    assert "linksByPlatform" in json.loads(body), "unexpected answer"


def youtube_music_page():
    code, _, body = get("https://music.youtube.com/", ua=DESKTOP)
    assert code == 200, f"HTTP {code}"
    assert "INNERTUBE_API_KEY" in body or "innertube" in body.lower(), "no innertube config in the page"


def amazon_music_page():
    code, _, body = get("https://music.amazon.com/")
    assert code in (200, 302, 303), f"HTTP {code}"


CHECKS = [
    ("Spotify web: query-hash registry", spotify_registry),
    ("Spotify web: player page", spotify_player_page),
    ("Spotify: short share link resolves", spotify_short_link),
    ("Apple Music web: developer token", apple_developer_token),
    ("Deezer: public catalogue and ISRC", deezer_catalogue),
    ("song.link: track links", odesli),
    ("YouTube Music: player page", youtube_music_page),
    ("Amazon Music: player page", amazon_music_page),
]


def main():
    failed = 0
    for name, fn in CHECKS:
        try:
            fn()
            print(f"OK    {name}")
        except Exception as e:  # noqa: BLE001
            failed += 1
            print(f"FAIL  {name}: {e}")
    print(f"\n{len(CHECKS) - failed}/{len(CHECKS)} checks passed")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
