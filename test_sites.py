import requests
from bs4 import BeautifulSoup

def test_site(url):
    print(f"Testing {url}")
    try:
        r = requests.get(url, timeout=10, headers={'User-Agent': 'Mozilla/5.0'})
        print("Status:", r.status_code)
        if r.status_code == 200:
            soup = BeautifulSoup(r.text, 'html.parser')
            print("Title:", soup.title.text if soup.title else "No Title")
            matches = soup.find_all('a', href=True)
            print(f"Links found: {len(matches)}")
            for a in matches[:5]:
                print("  ", a['href'])
    except Exception as e:
        print("Failed:", e)
    print("-" * 40)

sites = [
    "https://yalla-shoot.io/",
    "https://yalla-shoot.video/",
    "https://kooora365.com/",
    "https://yalla-live.io/",
    "https://kora-live.com/",
    "https://www.yallakora.com/",
    "https://livetv.sx/"
]

for s in sites:
    test_site(s)
