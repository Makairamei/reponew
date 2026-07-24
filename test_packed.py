import urllib.request, re

headers = {'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)', 'Referer': 'https://anichin.moe/'}

def check(url):
    print(f"\n--- Testing {url} ---")
    try:
        req = urllib.request.Request(url, headers=headers)
        html = urllib.request.urlopen(req, timeout=10).read().decode('utf-8', errors='ignore')
        print(f"HTML Length: {len(html)}")
        m3u8s = re.findall(r'https?://[^\s"\']+\.m3u8[^\s"\']*', html)
        print(f"Direct m3u8 count: {len(m3u8s)}")
        if m3u8s:
            print(f"Sample m3u8: {m3u8s[0]}")
        evals = re.findall(r'eval\(function\(p,a,c,k,e,d\).*?\)', html)
        print(f"Packed JS count: {len(evals)}")
    except Exception as e:
        print(f"Error: {e}")

check('https://rubyvidhub.com/embed-pjojb8g5h2sm.html')
check('https://morencius.com/embed/nb8twj8hmd2j')
check('https://hgcloud.to/e/t27alv69268l')
