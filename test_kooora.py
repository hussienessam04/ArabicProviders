import requests
from bs4 import BeautifulSoup

r = requests.get('https://kooora365.com/', headers={'User-Agent': 'Mozilla/5.0'})
soup = BeautifulSoup(r.text, 'html.parser')
matches = soup.find_all('a', href=True)

with open('kooora_test.txt', 'w', encoding='utf-8') as f:
    for m in matches[:15]:
        if 'match' in m['href'] or 'live' in m['href'] or 'vs' in m['href']:
            f.write(m['href'] + "\n")
