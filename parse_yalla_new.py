from bs4 import BeautifulSoup
import re

with open('yalla_new_full.html', 'r', encoding='utf-8') as f:
    text = f.read()

soup = BeautifulSoup(text, 'html.parser')
matches = soup.find_all('div', class_=re.compile(r'match'))
if not matches:
    matches = soup.find_all('a')

with open('yalla_new_matches.txt', 'w', encoding='utf-8') as f:
    f.write("Matches found: " + str(len(matches)) + "\n\n")
    for m in matches[:10]:
        f.write(str(m) + "\n\n")
