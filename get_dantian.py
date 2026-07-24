import urllib.request, re, base64, sys
sys.stdout.reconfigure(encoding='utf-8')

headers = {'User-Agent': 'Mozilla/5.0'}
url = 'https://anichin.moe/the-supreme-dantian-episode-14-subtitle-indonesia/'
req = urllib.request.Request(url, headers=headers)
html = urllib.request.urlopen(req, timeout=10).read().decode('utf-8', errors='ignore')
options = re.findall(r'<option value="([^"]+)"[^>]*>\s*([^<]+)\s*</option>', html)

print('=== DANTIAN EPISODE 14 FULL LINKS ===\n')
for val, label in options:
    if not val:
        continue
    clean_val = val.split('|')[-1].strip()
    try:
        decoded = base64.b64decode(clean_val).decode('utf-8', errors='ignore')
        iframe_src = re.findall(r'src=["\'](https?://[^"\']+)["\']', decoded, re.I)
        target = iframe_src[0] if iframe_src else decoded
        print(f"Server Name : {label.strip()}")
        print(f"Embed Link  : {target}\n")
    except Exception as e:
        print(f"Server Name : {label.strip()} -> Error: {e}\n")
