import json, time, urllib.request, subprocess, sys

def post(url, body, headers):
    req = urllib.request.Request(url, data=json.dumps(body).encode(), headers={'Content-Type': 'application/json', **headers})
    t = time.time()
    with urllib.request.urlopen(req, timeout=20) as r:
        return json.loads(r.read()), time.time() - t

WEB_UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36'

def find(node, key, out):
    if isinstance(node, dict):
        for k, v in node.items():
            if k == key and isinstance(v, dict): out.append(v)
            else: find(v, key, out)
    elif isinstance(node, list):
        for v in node: find(v, key, out)
    return out

# 1. Recherche (comme YtSearch.kt)
web_ctx = {'client': {'clientName': 'WEB', 'clientVersion': '2.20260708.00.00', 'hl': 'fr', 'gl': 'FR'}}
for kind, params in [('playlist', 'EgIQAw=='), ('video', 'EgQQARgC')]:
    d, dt = post('https://www.youtube.com/youtubei/v1/search?prettyPrint=false',
                 {'context': web_ctx, 'query': 'cozy cabin snow fireplace ambience', 'params': params},
                 {'User-Agent': WEB_UA, 'X-Youtube-Client-Name': '1', 'X-Youtube-Client-Version': '2.20260708.00.00', 'Origin': 'https://www.youtube.com'})
    if kind == 'video':
        vids = [v['videoId'] for v in find(d, 'videoRenderer', []) if v.get('videoId')]
        print(f'SEARCH video: {len(vids)} en {dt:.1f}s', vids[:5])
    else:
        old = [p.get('playlistId') for p in find(d, 'playlistRenderer', [])]
        lock = [l.get('contentId') for l in find(d, 'lockupViewModel', []) if 'PLAYLIST' in l.get('contentType', '')]
        print(f'SEARCH playlist: old={len(old)} lockup={len(lock)} en {dt:.1f}s', (old + lock)[:5])
        pls = [p for p in old + lock if p and not p.startswith('RD')]

# 2. Liste d'une playlist via browse
if pls:
    pl = pls[0]
    d, dt = post('https://www.youtube.com/youtubei/v1/browse?prettyPrint=false',
                 {'context': web_ctx, 'browseId': 'VL' + pl},
                 {'User-Agent': WEB_UA, 'X-Youtube-Client-Name': '1', 'X-Youtube-Client-Version': '2.20260708.00.00', 'Origin': 'https://www.youtube.com'})
    a = [v.get('videoId') for v in find(d, 'playlistVideoRenderer', [])]
    b = [l.get('contentId') for l in find(d, 'lockupViewModel', []) if 'VIDEO' in l.get('contentType', '')]
    print(f'BROWSE {pl}: playlistVideoRenderer={len(a)} lockup={len(b)} en {dt:.1f}s', (a + b)[:3])
    keys = set()
    def walk(n, depth=0):
        if depth > 12: return
        if isinstance(n, dict):
            for k, v in n.items():
                if k.endswith('Renderer') or k.endswith('ViewModel'): keys.add(k)
                walk(v, depth + 1)
        elif isinstance(n, list):
            for v in n: walk(v, depth + 1)
    walk(d)
    print('  renderers:', sorted(keys)[:40])

vid = vids[0]
vd, _ = post('https://www.youtube.com/youtubei/v1/guide?prettyPrint=false', {'context': web_ctx},
             {'User-Agent': WEB_UA, 'X-Youtube-Client-Name': '1', 'X-Youtube-Client-Version': '2.20260708.00.00', 'Origin': 'https://www.youtube.com'})
VISITOR = vd.get('responseContext', {}).get('visitorData')
print('VISITOR', (VISITOR or '')[:20])
# 3. Player : plusieurs clients
clients = {
  'VISIONOS': ({'clientName': 'VISIONOS', 'clientVersion': '1.02', 'deviceMake': 'Apple', 'deviceModel': 'RealityDevice17,1',
               'userAgent': 'Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15',
               'osName': 'visionOS', 'osVersion': '26.5.23O471'}, 101),
  'ANDROID_VR': ({'clientName': 'ANDROID_VR', 'clientVersion': '1.65.10', 'deviceMake': 'Oculus', 'deviceModel': 'Quest 3', 'androidSdkVersion': 32,
                 'userAgent': 'com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip',
                 'osName': 'Android', 'osVersion': '12L'}, 28),
}
for name, (client, num) in clients.items():
    try:
        d, dt = post('https://www.youtube.com/youtubei/v1/player?prettyPrint=false',
                     {'context': {'client': {**client, 'hl': 'fr', 'gl': 'FR', 'visitorData': VISITOR}}, 'videoId': vid, 'contentCheckOk': True, 'racyCheckOk': True},
                     {'User-Agent': client['userAgent'], 'X-Youtube-Client-Name': str(num), 'X-Youtube-Client-Version': client['clientVersion'], 'Origin': 'https://www.youtube.com', 'X-Goog-Visitor-Id': VISITOR})
        ps = d.get('playabilityStatus', {})
        sd = d.get('streamingData', {})
        fm = sd.get('adaptiveFormats', []) + sd.get('formats', [])
        withurl = [f for f in fm if f.get('url')]
        print(f'PLAYER {name}: {ps.get("status")} {ps.get("reason","")} en {dt:.1f}s, formats={len(fm)} avec url={len(withurl)} hls={bool(sd.get("hlsManifestUrl"))}')
        for f in sorted(withurl, key=lambda f: f.get('height', 0))[-4:]:
            print('   ', f.get('itag'), f.get('mimeType'), f.get('height'), f.get('bitrate'))
        aud = [f for f in withurl if f.get('mimeType', '').startswith('audio/mp4')]
        test = [f for f in withurl if f.get('mimeType', '').startswith('video/mp4')][:1] + aud[:1]
        for f in test:
            u = f['url']
            r = urllib.request.Request(u + '&range=0-999999', headers={'User-Agent': client['userAgent']})
            try:
                with urllib.request.urlopen(r, timeout=20) as resp:
                    n = len(resp.read())
                    print(f'    GET {f["itag"]} -> {resp.status}, {n} octets')
            except Exception as e:
                print(f'    GET {f["itag"]} -> ERREUR {e}')
            r = urllib.request.Request(u, headers={'User-Agent': client['userAgent'], 'Range': 'bytes=0-999999'})
            try:
                with urllib.request.urlopen(r, timeout=20) as resp:
                    print(f'    GET Range {f["itag"]} -> {resp.status}, {len(resp.read())} octets')
            except Exception as e:
                print(f'    GET Range {f["itag"]} -> ERREUR {e}')
        if name == 'VISIONOS' and sd.get('hlsManifestUrl'):
            print('    hls:', sd['hlsManifestUrl'][:120])
    except Exception as e:
        print(f'PLAYER {name}: ERREUR {e}')

# 4. yt-dlp, pour comparer
for args in (['--extractor-args', 'youtube:player_client=android_vr'], []):
    t = time.time()
    p = subprocess.run(['yt-dlp', '--no-warnings', '-J', '-f', 'bv*[height<=1080]+ba/b', *args, 'https://www.youtube.com/watch?v=' + vid], capture_output=True, text=True)
    ok = p.returncode == 0
    h = json.loads(p.stdout).get('height') if ok else None
    print(f'YT-DLP {args or "defaut"}: ok={ok} hauteur={h} en {time.time()-t:.1f}s', '' if ok else p.stderr[-400:])
