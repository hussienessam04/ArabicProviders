import requests
from bs4 import BeautifulSoup
import urllib3
urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)

r = requests.get('https://livetv.sx/', headers={'User-Agent': 'Mozilla/5.0'}, verify=False)
soup = BeautifulSoup(r.text, 'html.parser')
with open('livetv_test.txt', 'w', encoding='utf-8') as f:
    f.write(soup.title.text + "\n\n")
    matches = soup.find_all('a', href=True)
    live_links = [a for a in matches if 'eventinfo' in a['href']]
    f.write(f"Live links found: {len(live_links)}\n")
    for a in live_links[:10]:
        f.write(a['href'] + " : " + a.text.strip().replace('\n', ' ') + "\n")
