from bs4 import BeautifulSoup

with open('beinmatch_full.html', 'r', encoding='utf-8') as f:
    text = f.read()

soup = BeautifulSoup(text, 'html.parser')
print("Title:", soup.title.text if soup.title else "None")
print("Length:", len(text))
if "Cloudflare" in text or "Just a moment" in text or "captcha" in text:
    print("Found Cloudflare/Captcha")
else:
    matches = soup.find_all('div', class_=lambda c: c and 'match' in c)
    print("Match Divs:", len(matches))
