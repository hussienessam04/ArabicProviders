import requests

r = requests.get('https://kora-live.com/', headers={'User-Agent': 'Mozilla/5.0'})
with open('kora_live_html.txt', 'w', encoding='utf-8') as f:
    f.write(r.text)
