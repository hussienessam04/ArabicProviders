import requests
from bs4 import BeautifulSoup
import sys

sites = [
    "https://kora-star.tv/",
    "https://beinmatch.tv/",
    "https://yalla-shoot-new.com/",
    "https://yalla-shoot.us/",
    "https://kora-online.tv/"
]

for url in sites:
    print(f"Testing {url}")
    try:
        r = requests.get(url, timeout=10, headers={'User-Agent': 'Mozilla/5.0'})
        print("Status:", r.status_code)
        if r.status_code == 200:
            soup = BeautifulSoup(r.text, 'html.parser')
            print("Title:", soup.title.text.encode('utf-8', 'ignore').decode('utf-8') if soup.title else "No Title")
            matches = soup.find_all('a', href=True)
            live_links = [a['href'] for a in matches if 'match' in a['href'] or 'live' in a['href'] or 'vs' in a['href']]
            print(f"Match/Live Links found: {len(live_links)}")
            for l in live_links[:3]:
                print("  ", l)
    except Exception as e:
        print("Failed:", type(e).__name__)
    print("-" * 40)

