import re, base64, urllib.request, sys
sys.stdout.reconfigure(encoding='utf-8')

headers = {'User-Agent': 'Mozilla/5.0'}

def extract_direct_m3u8(url, name):
    try:
        req = urllib.request.Request(url, headers=headers)
        html = urllib.request.urlopen(req, timeout=5).read().decode('utf-8', errors='ignore')
        m3u8s = re.findall(r'(https?://[^\s"\']+\.m3u8[^\s"\']*)', html)
        if m3u8s:
            print(f"   └─ DIRECT STREAM (.m3u8): {m3u8s[0]}")
        else:
            iframes = re.findall(r'src=["\'](https?://[^"\']+)["\']', html)
            if iframes:
                print(f"   └─ INNER IFRAME: {iframes[0]}")
            else:
                print(f"   └─ PAGE LOADED OK (HTML len: {len(html)})")
    except Exception as e:
        print(f"   └─ FETCH ERROR: {e}")

def resolve(page_url):
    print(f"\n==========================================")
    print(f" PAGE: {page_url}")
    print(f"==========================================")
    req = urllib.request.Request(page_url, headers=headers)
    html = urllib.request.urlopen(req, timeout=10).read().decode('utf-8', errors='ignore')
    options = re.findall(r'<option value="([^"]+)"[^>]*>\s*([^<]+)\s*</option>', html)
    
    for val, label in options:
        if not val:
            continue
        clean_val = val.split('|')[-1].strip()
        try:
            decoded = base64.b64decode(clean_val).decode('utf-8', errors='ignore')
            iframe_src = re.findall(r'src=["\'](https?://[^"\']+)["\']', decoded, re.I)
            url_target = iframe_src[0] if iframe_src else decoded
            print(f"\n[SERVER: {label.strip()}]")
            print(f" ├─ Embed URL: {url_target}")
            extract_direct_m3u8(url_target, label)
        except Exception as e:
            print(f"[SERVER: {label.strip()}] Error: {e}")

resolve('https://anichin.moe/the-supreme-dantian-episode-14-subtitle-indonesia/')
resolve('https://anichin.moe/perfect-world-episode-279-subtitle-indonesia/')
