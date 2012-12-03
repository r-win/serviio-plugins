import org.serviio.library.metadata.*
import org.serviio.library.online.*
import javax.xml.xpath.*
import javax.xml.parsers.DocumentBuilderFactory
import java.security.MessageDigest
import groovy.json.JsonSlurper

/**
 * Cultura 24
 * 
 * @author Erwin Bovendeur 
 * @version 1.0
 * @releasedate 2012-12-03
 *
 * Cultura 24 plugin for Serviio. Allowed URL's are:
 * http://gemist.cultura.nl/
 * http://gemist.cultura.nl/zoeken/#
 * and pages with search results, like
 * http://gemist.cultura.nl/zoeken/#facet_pomsgenre:literatuur|facet_pomsgenre:film en drama|view:cellsByColumn
 *
 * You can find these URL's by just browsing the site. Just copy the URL of a page you want to add, and add it as
 * WebResource to Serviio.
 *
 * Changelog:
 * Version 1.0: 
 * - Initial release
 */
class Cultura24 extends WebResourceUrlExtractor {

    final VALID_FEED_URL = '^http://gemist.cultura.nl/.*$'

    final HTML_SEARCH = '^http://gemist.cultura.nl/zoeken/#(.+)$'
    final HTML_SECTION = '(?s)<section.*?(?:cellsBy|listView).*?>(.*?)<\\/section>'
    final HTML_ARTICLE = '(?s)<article>(.*?)<\\/article>'
    final HTML_ARTICLE_TITLE = '<h1>(.*)</h1>'
    final HTML_ARTICLE_THUMB = '<img src="(.+?)"'
    final HTML_ARTICLE_DATE = '<li class="date">(.+?)</li>'
    final HTML_ARTICLE_VIDEO = '<a href="/player/(.+?)"'

    final SEARCH_URL = 'http://gemist.cultura.nl/cultura/Ajax/Search/default'
    final THUMBNAIL_URL = '<meta content="(.*)" itemprop="image" property="og:image" />'

    /* Not really required, but the method openURL requires one, so let's just specify a valid one */
    final USER_AGENT = 'Mozilla/5.0 (Windows NT 6.2; WOW64) AppleWebKit/537.15 (KHTML, like Gecko) Chrome/24.0.1295.0 Safari/537.15' 

    def security_token = null
    MessageDigest digest = MessageDigest.getInstance("MD5")

    String getExtractorName() {
        return "Cultura 24"
    }
    
    boolean extractorMatches(URL feedUrl) {
        return feedUrl ==~ VALID_FEED_URL
    }

    String getToken() {
        if (security_token == null) {
            def security_info = new URL("http://pi.omroep.nl/info/security/").getText() 
            def security_hash = xpath(security_info, "/session/key")
            def security_tokens = new String(security_hash.decodeBase64()).split("\\|") 
            security_token = security_tokens[1]
        }
        return security_token
    } 

    def md5(String s) {
        digest.update(s.bytes);
        new BigInteger(1, digest.digest()).toString(16).padLeft(32, '0')
    }

    String doPost(String body) {
        URL searchUrl = new URL(SEARCH_URL)
        def connection = searchUrl.openConnection()

        connection.setRequestMethod("POST")
        connection.doOutput = true

        OutputStreamWriter writer = new OutputStreamWriter(connection.outputStream)
        writer.write(body)
        writer.flush()
        writer.close()
        connection.connect()

        return connection.content.text
    }

    WebResourceContainer parseSearch(URL resourceUrl, int maxItems) {
        // This can be handled with an AJAX call
        // First, parse all the FORM POST arguments (everything after # and splitted by |)
        List<String> elements = FirstMatch(resourceUrl.toString(), HTML_SEARCH).split("\\|")
        List<WebResourceItem> items = []
        String videoId = ""
        String videoTitle = ""
        Date releaseDate
        String thumbUrl = ""

        String body = ''

        for (int i = 0; i < elements.size(); i++) {
            List<String> keyval = elements[i].split(':')

            body += keyval[0] + '=' + keyval[1] + '&'
        }
        body += 'start=0&rows=' + maxItems

        String json = doPost(body)

        JsonSlurper slurper = new JsonSlurper()
        def searchResult = slurper.parseText(json)
        for (int i = 0; i < searchResult.Items.size() && i < maxItems; i++) {
            def searchItem = searchResult.Items[i]

            videoId = searchItem.Id
            videoTitle = searchItem.ProgramTitle
            thumbUrl = searchItem.Image

            releaseDate = Date.parse("yyyy-MM-dd'T'HH:mm:ss", searchItem.Custom[1])

            WebResourceItem item = new WebResourceItem(title: videoTitle, releaseDate: releaseDate, additionalInfo: ['videoId':videoId,'thumbUrl':thumbUrl])
            items << item
        }

        return new WebResourceContainer(title: 'Search results', items: items)
    }

    static String FirstMatch(String content, String regex) {
        def match = content =~ regex
        return match[0][1]
    }
    
    WebResourceContainer extractItems(URL resourceUrl, int maxItems) {
        List<WebResourceItem> items = []
        String videoId = ""
        String videoTitle = ""
        Date releaseDate
        String pageTitle = ""
        String pageThumb = ""
        String thumbUrl = ""
        String cleanUrl = ""
        Short startPage = 0
        boolean hasPages = false
        boolean isFirstPage = true

        log("Parsing file with Cultura 24")

        // Is this a search page?
        if (resourceUrl ==~ HTML_SEARCH) {
            log "Search URL found, redirecting to AJAX JSON query"
            return parseSearch(resourceUrl, maxItems)
        } else {
            log "Normal page found, parsing HTML"
        }

        String pageContent = resourceUrl.getText()

        def htmlSection = pageContent =~ HTML_SECTION
        String sectionContent = htmlSection[0][1]

        // Find the article
        def htmlArticles = sectionContent =~ HTML_ARTICLE

        pageTitle = FirstMatch(sectionContent, HTML_ARTICLE_TITLE)

        for (int i = 0; i < htmlArticles.size() && i < maxItems; i++) {
            String article = htmlArticles[i][1]
            videoTitle = FirstMatch(article, HTML_ARTICLE_TITLE)
            thumbUrl = FirstMatch(article, HTML_ARTICLE_THUMB)
            String date = FirstMatch(article, HTML_ARTICLE_DATE)
            releaseDate = Date.parse("dd MMMM yyyy", date)
            videoId = FirstMatch(article, HTML_ARTICLE_VIDEO)

            WebResourceItem item = new WebResourceItem(title: videoTitle, releaseDate: releaseDate, additionalInfo: ['videoId':videoId,'thumbUrl':thumbUrl])

            items << item
        }

        return new WebResourceContainer(title: pageTitle, items: items)
    }    

    ContentURLContainer extractUrl(WebResourceItem item, PreferredQuality requestedQuality) {
        String videoId = item.getAdditionalInfo()['videoId']
        String thumbnailUrl = item.getAdditionalInfo()['thumbUrl']

        if (videoId == null) {
            log("Video Id was not specified!")
            return null
        }

        String token = getToken()
        String hash = md5(videoId + "|" + token).toUpperCase() /* Really? Come on! */
        String videoInfo = new URL("http://pi.omroep.nl/info/stream/aflevering/" + videoId + "/" + hash).getText()

        /* Depending on the quality requested, return different url's */
        String videoUrl = null
        if (requestedQuality == PreferredQuality.HIGH) {
            videoUrl = xpath(videoInfo, "/streams/stream[@compressie_formaat=\"mov\" and @compressie_kwaliteit=\"std\"]/streamurl") 
            if (videoUrl == null || videoUrl.length() == 0) {
                videoUrl = xpath(videoInfo, "/streams/stream[@compressie_formaat=\"wvc1\" and @compressie_kwaliteit=\"std\"]/streamurl") 
            }
        } else if (requestedQuality == PreferredQuality.MEDIUM) {
            videoUrl = xpath(videoInfo, "/streams/stream[@compressie_formaat=\"mov\" and @compressie_kwaliteit=\"bb\"]/streamurl") 
        } else if (requestedQuality == PreferredQuality.LOW) {
            videoUrl = xpath(videoInfo, "/streams/stream[@compressie_formaat=\"mov\" and @compressie_kwaliteit=\"sb\"]/streamurl") 
        }

        if (videoUrl.endsWith("type=asx")) {
            // Extract the mms link
            URL asxContent = new URL(videoUrl).getText()
            def mmsref = asxContent =~ 'href="mms://(.*)"'
            videoUrl = "mmsh://" + mmsref[0][1]
        }

        if (thumbnailUrl == null || thumbnailUrl == '') {
            String pageContent = new URL('http://gemi.st/' + videoId).getText()
            def thumb = pageContent =~ THUMBNAIL_URL
            thumbnailUrl = thumb[0][1]
        }
        return new ContentURLContainer(fileType: MediaFileType.VIDEO, contentUrl: videoUrl, thumbnailUrl: thumbnailUrl, expiresImmediately: true, cacheKey: requestedQuality.toString() + "_" + videoId)
    }

    String xpath(String xmlContent, String xpath) {
        def builder     = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        ByteArrayInputStream inputStream = new ByteArrayInputStream(xmlContent.bytes)
        def records     = builder.parse(inputStream).documentElement

        XPath path = XPathFactory.newInstance().newXPath()
        return path.evaluate(xpath, records, XPathConstants.STRING).trim()
    }

    static WebResourceContainer testURL(String url) {
    	int itemCount = 2

        Cultura24 cult = new Cultura24();
    	URL resourceUrl = new URL(url)
    	WebResourceContainer container = cult.extractItems(resourceUrl, itemCount)
	
        assert container != null, 'Container is empty'
        assert container.items != null, 'Container contains no items'
        assert container.items.size() == itemCount, 'Amount of items is invalid. Expected was ' + itemCount + ', result was ' + container.items.size()

        for (int i = 0; i < container.items.size(); i++) {
            WebResourceItem item = container.items[i]
            ContentURLContainer result = cult.extractUrl(item, PreferredQuality.HIGH)
            println result
        }
    	return container
    }
    
    static void main(args) {
        // this is just to test
        testURL("http://gemist.cultura.nl/zoeken/#")

    	WebResourceContainer container = testURL("http://gemist.cultura.nl/")

    	WebResourceItem singleItem = container.items[1]
        singleItem = new WebResourceItem(title: singleItem.title, releaseDate: singleItem.releaseDate, additionalInfo: ['videoId':singleItem.getAdditionalInfo()['videoId']])

        ContentURLContainer singleResult = new Cultura24().extractUrl(singleItem, PreferredQuality.MEDIUM)
        println singleResult

        testURL("http://gemist.cultura.nl/zoeken/#facet_pomsgenre:literatuur|facet_pomsgenre:film en drama|view:cellsByColumn")
    }
}
