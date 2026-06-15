import requests

r = requests.get('https://beinmatch.tv/', headers={'User-Agent': 'Mozilla/5.0'})
with open('beinmatch_full.html', 'w', encoding='utf-8') as f:
    f.write(r.text)
