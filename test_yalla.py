import requests
from bs4 import BeautifulSoup

r = requests.get('https://yalla-shoot.video/', headers={'User-Agent': 'Mozilla/5.0'})
soup = BeautifulSoup(r.text, 'html.parser')
matches = soup.select('.match-container')
if not matches:
    matches = soup.select('.match')
if not matches:
    matches = soup.find_all('a', href=True)

with open('yalla_shoot_test.txt', 'w', encoding='utf-8') as f:
    f.write(soup.title.text + "\n\n")
    for m in matches[:10]:
        if m.name == 'a':
            f.write(m['href'] + " : " + m.text.strip().replace('\n', ' ') + "\n")
        else:
            f.write(str(m) + "\n\n")
