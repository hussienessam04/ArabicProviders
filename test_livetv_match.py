import requests
from bs4 import BeautifulSoup
import urllib3
urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)

r = requests.get('https://livetv.sx/enx/eventinfo/385452026_bilbao_valencia/', headers={'User-Agent': 'Mozilla/5.0'}, verify=False)
soup = BeautifulSoup(r.text, 'html.parser')
with open('livetv_match.txt', 'w', encoding='utf-8') as f:
    tables = soup.find_all('table')
    f.write(f"Tables: {len(tables)}\n")
    links = soup.find_all('a', href=True)
    for l in links:
        if 'javascript' not in l['href'] and '#' not in l['href']:
            f.write(l['href'] + "\n")
