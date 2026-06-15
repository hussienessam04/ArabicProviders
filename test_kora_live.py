import requests

r = requests.get('https://kora-live.com/', allow_redirects=True, headers={'User-Agent': 'Mozilla/5.0'})
print(r.url)
print(r.status_code)
