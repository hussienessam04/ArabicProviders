import requests
from bs4 import BeautifulSoup
import sys

url = sys.argv[1] if len(sys.argv) > 1 else 'https://footybite.vc/Hawks-vs-Team-Rhino/69563'
response = requests.get(url)
soup = BeautifulSoup(response.content, 'html.parser')

# Find where the streams are
streams = soup.find_all('a', href=True)
for s in streams:
    if 'bz/live' in s['href'] or 'stream' in s['href'] or 'live' in s['href'] or 'player' in s['href']:
        print(s['href'], s.text.strip())

# See if there are tables
print("Tables:", len(soup.find_all('table')))
print("TRs:", len(soup.find_all('tr')))
print("Divs:", len(soup.find_all('div')))
