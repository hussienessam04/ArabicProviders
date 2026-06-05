import urllib.request
import re
try:
    req = urllib.request.Request('https://live.footybite.to/', headers={'User-Agent': 'Mozilla/5.0'})
    html = urllib.request.urlopen(req).read().decode('utf-8')
    links = re.findall(r'href=[\'\"](https?://footybite\.vc/[^\'\"]+)[\'\"]', html)
    for link in set(links): print(link)
except Exception as e:
    print(e)
