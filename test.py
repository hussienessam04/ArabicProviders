import urllib.request, json
data = json.loads(urllib.request.urlopen('https://streamed.pk/api/matches/live').read())
for m in data:
    for src in m.get('sources', []):
        try:
            req = urllib.request.Request(f"https://streamed.pk/api/stream/{src['source']}/{src['id']}", headers={'User-Agent': 'Mozilla/5.0'})
            res = urllib.request.urlopen(req).read().decode('utf-8')
            if res != '[]':
                print('Match:', m['title'])
                print('Source:', src['source'], 'ID:', src['id'])
                print('Streams:', res)
                exit(0)
        except Exception as e:
            pass
