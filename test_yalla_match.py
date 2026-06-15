import requests
from bs4 import BeautifulSoup

r = requests.get('https://yalla-shoot.video/livepage/202/5040543', headers={'User-Agent': 'Mozilla/5.0'})
soup = BeautifulSoup(r.text, 'html.parser')

with open('yalla_shoot_match.txt', 'w', encoding='utf-8') as f:
    f.write(soup.title.text + "\n\n")
    iframes = soup.find_all('iframe')
    for i in iframes:
        f.write(str(i) + "\n")
    videos = soup.find_all('video')
    for v in videos:
        f.write(str(v) + "\n")
    scripts = soup.find_all('script')
    for s in scripts:
        if s.string and ('m3u8' in s.string or 'source' in s.string or 'iframe' in s.string):
            f.write(s.string[:200] + "\n")
