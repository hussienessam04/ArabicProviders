import requests

r = requests.get('https://yalla-shoot-new.com/', headers={'User-Agent': 'Mozilla/5.0'})
with open('yalla_new_full.html', 'w', encoding='utf-8') as f:
    f.write(r.text)
