import requests
import sys

url = sys.argv[1] if len(sys.argv) > 1 else 'https://footybite.vc/Hawks-vs-Team-Rhino/69563'
response = requests.get(url)
print(response.text[:1000])
