import requests
r = requests.get('https://yalla-shoot.video/livepage/202/5040543', headers={'User-Agent': 'Mozilla/5.0'})
with open('yalla_shoot_full.html', 'w', encoding='utf-8') as f:
    f.write(r.text)
