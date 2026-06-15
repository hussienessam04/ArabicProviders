from bs4 import BeautifulSoup

with open('kooora365_full.html', 'r', encoding='utf-8') as f:
    text = f.read()

soup = BeautifulSoup(text, 'html.parser')
print("Title:", soup.title.text.encode('utf-8', 'ignore').decode('utf-8') if soup.title else "None")
print("Length:", len(text))
matches = soup.find_all('a', class_=lambda c: c and 'match' in c)
if not matches:
    matches = soup.find_all('div', class_=lambda c: c and 'match' in c)
print("Matches found:", len(matches))
