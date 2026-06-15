import requests
from bs4 import BeautifulSoup
import sys

url = sys.argv[1] if len(sys.argv) > 1 else 'https://footybite.vc/Hawks-vs-Team-Rhino/69563'
response = requests.get(url)
soup = BeautifulSoup(response.content, 'html.parser')

stream_table = soup.select('table tr')
for row in stream_table:
    columns = row.select('td')
    if len(columns) < 4: continue
    
    stream_link = row.select('td a[href]')
    if not stream_link: continue
    stream_link = stream_link[0]['href']
    
    language = columns[3].text
    quality = columns[2].text
    channel_name = columns[0].text
    print(f"{language} - {channel_name} ({quality}): {stream_link}")
