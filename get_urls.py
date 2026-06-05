import bs4
html = open('cimanow.html', 'r', encoding='utf-8', errors='ignore').read()
soup = bs4.BeautifulSoup(html, 'html.parser')
articles = soup.find_all('article')
for a in articles:
    link = a.find('a')
    if link and link.has_attr('href'):
        print(link['href'])
        break
