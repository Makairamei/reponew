import urllib.request, re

headers = {'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)', 'Referer': 'https://anichin.moe/'}

def unpack(p, a, c, k, e, d):
    def e_func(c, a):
        return ('' if c < a else e_func(c // a, a)) + (chr(c % a + 29) if c % a > 35 else '0123456789abcdefghijklmnopqrstuvwxyz'[c % a])
    
    k = k.split('|')
    e = lambda c: e_func(c, a)
    c = len(k)
    d = {}
    while c > 0:
        c -= 1
        if k[c]:
            d[e_func(c, a)] = k[c]
        else:
            d[e_func(c, a)] = e_func(c, a)
    
    def repl(match):
        w = match.group(0)
        return d.get(w, w)

    return re.sub(r'\b\w+\b', repl, p)

html = urllib.request.urlopen(urllib.request.Request('https://rubyvidhub.com/embed-pjojb8g5h2sm.html', headers=headers)).read().decode('utf-8', errors='ignore')
matches = re.findall(r"eval\(function\(p,a,c,k,e,d\)\{.*?\}\('([^']*)',(\d+),(\d+),'([^']*)'\.split\('\|'\)", html)
for p, a, c, k in matches:
    unpacked = unpack(p, int(a), int(c), k, None, None)
    m3u8 = re.findall(r'https?://[^\s"\']+\.m3u8[^\s"\']*', unpacked)
    print("UNPACKED M3U8 STREAM:", m3u8)
