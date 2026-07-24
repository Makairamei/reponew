import re
import base64

def check(file_path):
    html = open(file_path, encoding='utf-8').read()
    options = re.findall(r'<option value="([^"]+)"[^>]*>\s*([^<]+)\s*</option>', html)
    for val, label in options:
        if not val:
            continue
        clean_val = val.split('|')[-1].strip()
        try:
            decoded = base64.b64decode(clean_val).decode('utf-8', errors='ignore')
            print(f"[{label}] => {decoded}")
        except Exception as e:
            print(f"[{label}] FAILED: {e}")

print("=== DANTIAN EP 14 ===")
check('dantian.html')
print("\n=== PERFECT WORLD EP 279 ===")
check('perfect.html')
