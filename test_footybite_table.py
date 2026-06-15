import requests
from bs4 import BeautifulSoup
import sys

url = sys.argv[1] if len(sys.argv) > 1 else 'https://footybite.vc/Hawks-vs-Team-Rhino/69563'
response = requests.get(url)
soup = BeautifulSoup(response.content, 'html.parser')

print(soup.find('table').prettify())
