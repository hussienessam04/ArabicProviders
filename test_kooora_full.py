import requests

r = requests.get('https://kooora365.com/', headers={'User-Agent': 'Mozilla/5.0'})
with open('kooora365_full.html', 'w', encoding='utf-8') as f:
    f.write(r.text)
