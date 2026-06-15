import requests
from bs4 import BeautifulSoup

r = requests.get('https://as-goal.com/', headers={'User-Agent': 'Mozilla/5.0'})
soup = BeautifulSoup(r.text, 'html.parser')

with open('as_goal_test.txt', 'w', encoding='utf-8') as f:
    f.write(soup.title.text + "\n\n")
    matches = soup.find_all('a', href=True)
    for m in matches:
        if 'match' in m['href'] or 'live' in m['href'] or 'vs' in m['href']:
            f.write(m['href'] + " : " + m.text.strip().replace('\n', ' ') + "\n")
