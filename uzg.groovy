import org.serviio.library.metadata.*
import org.serviio.library.online.*
import javax.xml.xpath.*
import javax.xml.parsers.DocumentBuilderFactory
import java.security.MessageDigest

/**
 * Uitzending Gemist 
 * 
 * @author Erwin Bovendeur 
 * @version 1.3
 * @releasedate 2012-12-02
 *
 * Changelog:
 * Version 1.3:
 * - Fixed Vandaag
 *
 * Version 1.2:
 * - Fixed missing thumbnails (thanks to Zip for pointing me in the right direction)
 * - Fixed issue where the backup method for retrieving a thumbnail didn't work
 *
 * Version 1.1:
 * - Converted to WebResourceUrlExtractor to be able to handle ?page=...
 * 
 * Version 1.0: 
 * - Initial release
 */
class UitzendingGemist extends WebResourceUrlExtractor {

    final VALID_FEED_URL = '^http(s)*://.*uitzendinggemist.nl/.*$'
    final VALID_LINK_URL = '^http://gemi.st/(\\d+)$'
    final VALID_PAGE_URL = '^http://www.uitzendinggemist.nl/(programmas/.*/)?afleveringen/.*$'
    final THUMBNAIL_URL = '<meta content="(.*)" itemprop="image" property="og:image" />'
    final EPISODE_ID = 'data-episode-id="(\\d+)"'

    /* Not really required, but the method openURL requires one, so let's just specify a valid one */
    final USER_AGENT = 'Mozilla/5.0 (Windows NT 6.2; WOW64) AppleWebKit/537.15 (KHTML, like Gecko) Chrome/24.0.1295.0 Safari/537.15' 

    def security_token = null
    MessageDigest digest = MessageDigest.getInstance("MD5")

    String getExtractorName() {
        return "Uitzending Gemist"
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
                    videoTitle = n.title.text().trim()
                    videoUrl = n.guid.text().trim()
                    thumbUrl = n."media:thumbnail".@url.text().trim()
                    releaseDate = Date.parse("E, dd MMM yyyy H:m:s Z", n.pubDate.text().trim())

                    WebResourceItem item = new WebResourceItem(title: videoTitle, releaseDate: releaseDate, additionalInfo: ['videoUrl':videoUrl,'thumbUrl':thumbUrl])
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

    ContentURLContainer extractUrl(WebResourceItem item, PreferredQuality requestedQuality) {
        String linkUrl = item.getAdditionalInfo()['videoUrl']
        String thumbnailUrl = item.getAdditionalInfo()['thumbUrl']

        String pageContent = null
        String videoId = null

        /* Check if this is the long url, or the shorter gemi.st domain */
        if (linkUrl ==~ VALID_PAGE_URL) {
            log("Requesting info for page '" + linkUrl.toString() + "'")
            if (pageContent == null) {
                pageContent = new URL(linkUrl).getText()

                def episode = pageContent =~ EPISODE_ID
                if (episode.hasGroup()) {
                    videoId = episode[0][1]
                }
            }
        }
        if (videoId == null && linkUrl ==~ VALID_LINK_URL) {
            def matcher = linkUrl =~ VALID_LINK_URL
            assert matcher != null
            assert matcher.hasGroup()

            if (matcher.matches()) {
                videoId = matcher[0][1]
            }
        }

        if (videoId == null) {
            log("Link '" + linkUrl + "' can't be handled by this plugin")
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
            if (pageContent == null) {
                pageContent = new URL(linkUrl).getText()
            }
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
        singleItem = new WebResourceItem(title: singleItem.title, releaseDate: singleItem.releaseDate, additionalInfo: ['videoUrl':singleItem.getAdditionalInfo()['videoUrl']])

        ContentURLContainer singleResult = uzg.extractUrl(singleItem, PreferredQuality.MEDIUM)
        println singleResult

	// Vandaag
	testURL("http://www.uitzendinggemist.nl/weekarchief/vandaag.rss")
    }
}
