import org.serviio.library.metadata.*
import org.serviio.library.online.*
import javax.xml.xpath.*
import javax.xml.parsers.DocumentBuilderFactory
import java.security.MessageDigest
import java.text.SimpleDateFormat

/**
 * Uitzending Gemist 
 * 
 * @author Erwin Bovendeur 
 * @version 1.4
 * @releasedate 2012-12-03
 * @url https://github.com/r-win/serviio-plugins
 *
 * Changelog:
 * Version 1.5:
 * - Force setting the locale to United States, might resolve unparseable date
 *
 */
class UitzendingGemist extends OmroepNL {

    final VALID_FEED_URL = '^http(s)*://.*uitzendinggemist.nl/.*$'

    String getExtractorName() {
        return "Uitzending Gemist"
    }

    int getVersion() {
        return 14;
    }
    
    boolean extractorMatches(URL feedUrl) {
        return feedUrl ==~ VALID_FEED_URL
    }

    WebResourceContainer extractItems(URL resourceUrl, int maxItems) {
        List<WebResourceItem> items = []
        def itemsAdded = 0 
        String videoUrl = ""
        String videoTitle = ""
        Date releaseDate
        String pageTitle = ""
        String pageThumb = ""
        String thumbUrl = ""
        String cleanUrl = ""
        Short startPage = 0
        boolean hasPages = false
        boolean isFirstPage = true

        log("Parsing file with Uitzending Gemist")

        SimpleDateFormat dateParser = new SimpleDateFormat("E, dd MMM yyyy H:m:s Z", Locale.US)

        // Does this URL already contain a ?page=
        if (resourceUrl ==~ /^.*programmas.*$/) {
            // This URL can contain a page argument, or already does that
            if (resourceUrl ==~ /^.*page=\d*$/) {
                def matcher = resourceUrl =~ /^(.*\?page=)(\d*)$/
                cleanUrl = matcher[0][1]
                startPage = matcher[0][2].toShort()
            } else {
                cleanUrl = resourceUrl.toString() + "?page="
                resourceUrl = new URL(cleanUrl + startPage)
            }
            hasPages = true

            log("This URL has multiple pages, starting at page " + startPage)
        }

        while (maxItems == -1 || items.size() < maxItems) {
            def content = resourceUrl.getText()
            def xmlContent = new XmlSlurper().parseText(content).declareNamespace(media: "http://search.yahoo.com/mrss/")

            // Extract the pageTitle and thumb
            if (isFirstPage) {
                pageTitle = xmlContent.channel.title
                pageThumb = xmlContent.channel.image.url
                isFirstPage = false

                log("Page title: " + pageTitle)
            }

            def nodes = xmlContent.channel.item

            if (nodes.size() == 0) {
                log("Page found without items, this is the end")
                break;
            }

            // Loop the items
            for (int i = 0; i < nodes.size(); i++) {
                def n = nodes[i]
                if (n != null) {
                    videoTitle = strip(n.title.text().trim())
                    videoUrl = n.guid.text().trim()
                    thumbUrl = n."media:thumbnail".@url.text().trim()
                    releaseDate = dateParser.parse(n.pubDate.text().trim())

                    WebResourceItem item = new WebResourceItem(title: videoTitle, releaseDate: releaseDate, additionalInfo: ['infoUrl':videoUrl,'thumbUrl':thumbUrl])
                    items << item
                }

                if (maxItems != -1 && items.size() >= maxItems) {
                    log("Having enough items (as much as requested)")
                    break;
                }
            }

            if (hasPages && (maxItems == -1 || items.size() < maxItems)) {
                // Load the next page
                startPage++
                log("Loading page " + startPage)

                resourceUrl = new URL(cleanUrl + startPage)
            }
        }

        return new WebResourceContainer(title: pageTitle, thumbnailUrl: pageThumb, items: items)
    }    

    static WebResourceContainer testURL(String url) {
    	int itemCount = 2

    	UitzendingGemist uzg = new UitzendingGemist();
    	URL resourceUrl = new URL(url)
    	WebResourceContainer container = uzg.extractItems(resourceUrl, itemCount)
	
        assert container != null, 'Container is empty'
        assert container.items != null, 'Container contains no items'
        assert container.items.size() == itemCount, 'Amount of items is invalid. Expected was ' + itemCount + ', result was ' + container.items.size()

        for (int i = 0; i < container.items.size(); i++) {
            WebResourceItem item = container.items[i]
            ContentURLContainer result = uzg.extractUrl(item, PreferredQuality.HIGH)
            println result
        }
    	return container
    }
    
    static void main(args) {
        // this is just to test
        UitzendingGemist uzg = new UitzendingGemist();
        WebResourceContainer container = testURL("http://www.uitzendinggemist.nl/programmas/354-het-zandkasteel.rss")

        WebResourceItem singleItem = container.items[1]
        singleItem = new WebResourceItem(title: singleItem.title, releaseDate: singleItem.releaseDate, additionalInfo: ['infoUrl':singleItem.getAdditionalInfo()['infoUrl']])

        ContentURLContainer singleResult = uzg.extractUrl(singleItem, PreferredQuality.MEDIUM)
        println singleResult

        // Vandaag
        testURL("http://www.uitzendinggemist.nl/weekarchief/vandaag.rss")
    }
}
