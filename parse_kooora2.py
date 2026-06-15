from bs4 import BeautifulSoup
import re

with open('kooora365_full.html', 'r', encoding='utf-8') as f:
    text = f.read()

soup = BeautifulSoup(text, 'html.parser')
matches = soup.find_all('div', class_=re.compile(r'match'))
matches.extend(soup.find_all('a', class_=re.compile(r'match')))

with open('kooora365_matches.txt', 'w', encoding='utf-8') as f:
    for m in matches[:10]:
        f.write(str(m) + "\n\n")
