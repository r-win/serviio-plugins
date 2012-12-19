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
 * @version 1.1
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
 * Version 1.1:
 * - Added use of OmroepNL Base class
 */
class Cultura24 extends OmroepNL {

    final VALID_FEED_URL = '^http://gemist.cultura.nl/.*$'

    final HTML_SEARCH = '^http://gemist.cultura.nl/zoeken/#(.+)$'
    final HTML_SECTION = '(?s)<section.*?(?:cellsBy|listView).*?>(.*?)<\\/section>'
    final HTML_ARTICLE = '(?s)<article>(.*?)<\\/article>'
    final HTML_ARTICLE_TITLE = '<h1>(.*)</h1>'
    final HTML_ARTICLE_THUMB = '<img src="(.+?)"'
    final HTML_ARTICLE_DATE = '<li class="date">(.+?)</li>'
    final HTML_ARTICLE_VIDEO = '<a href="/player/(.+?)"'

    final SEARCH_URL = 'http://gemist.cultura.nl/cultura/Ajax/Search/default'

    String getExtractorName() {
        return "Cultura 24"
    }

    int getVersion() {
        return 11;
    }    
    
    boolean extractorMatches(URL feedUrl) {
        return feedUrl ==~ VALID_FEED_URL
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

        String json = doPost(SEARCH_URL, body)

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

        if (videoId == null) {
            log("Video Id was not specified!")
            return null
        }

        return super.extractUrl(item, requestedQuality)
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
