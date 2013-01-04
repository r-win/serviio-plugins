import org.serviio.library.metadata.*
import org.serviio.library.online.*

/**
 * RTL XL 
 * RTL 4, 5 & 7
 * 
 * @author Erwin Bovendeur 
 * @version 1.1
 * @releasedate 2013-01-04
 *
 * Changelog:
 * 
 * Version 1.1:
 * - Added support for specifying a show type. Possible show types are: onlineonly, fragmenten, eps_fragment, multi_fragment, uitzending
 *   You can specify a type by prepending ?type= to the url. You can specify more than one type by using the | as delimiter
 *   e.g.: http://www.rtl.nl/xl/#/a/10821?type=uitzending|fragmenten
 * Version 1.0: 
 * - Initial release
 */
class RTLXL extends WebResourceUrlExtractor {

    final VALID_LINK_URL    = '^http://www.rtl.nl/xl/#/a/([\\d]+)(\\?type=([a-zA-Z|]+))?$'
    final BASE_FEED_URL     = 'http://www.rtl.nl/system/s4m/ipadfd/d=ipad/fmt=adaptive/ak='

    final M3U8_ITEM         = '(?s)#EXT-X-STREAM.*?BANDWIDTH=([\\d]+?),.*?RESOLUTION=.*?(http://.+?\\.m3u8)'

    String FirstMatch(String content, String regex) {
        def match = content =~ regex
        return match[0][1]
    }

    String strip(String input) {
        return input.replaceAll('<(.|\n)*?>', '')
    }

    /* Interface */

    String getExtractorName() {
        return "RTL XL"
    }

    int getVersion() {
        return 11;
    }
    
    boolean extractorMatches(URL feedUrl) {
        return feedUrl ==~ VALID_LINK_URL
    }

    // Valid class names: 
    // onlineonly => only online available
    // fragmenten => short fragment for one episode
    // eps_fragment => teaser for one episode
    // multi_fragment => teaser for multiple episodes (e.g. week overview)
    // uitzending => a complete show

    String[] validClassNames(String type) {
        String[] items = type.split('\\|')

        List<String> lst = []

        for (int i = 0; i < items.length; i++) {
            if (items[i] in ['onlineonly', 'fragmenten', 'eps_fragment', 'multi_fragment', 'uitzending']) {
                lst << items[i]
            }
        }
        return lst as String[]
    }

    WebResourceContainer extractItems(URL resourceUrl, int maxItems) {
    	List<WebResourceItem> items = []

        def matchUrl = resourceUrl =~ VALID_LINK_URL
    	String programId = matchUrl[0][1]

        // We can check for classnames, to filter out certain episodes
        String types = matchUrl[0].size() > 2 ? matchUrl[0][3] : null
        String[] classNames = types != null ? validClassNames(types) : []

        URL videoUrl = new URL(BASE_FEED_URL + programId)

        String programXml = videoUrl.getText()
        String programThumbnail, pageTitle

        def xmlContent = new XmlSlurper().parseText(programXml).declareNamespace(i: "http://www.w3.org/2001/XMLSchema-instance")

        for (int i = 0; i < xmlContent.item.size() && (maxItems == -1 || items.size() < maxItems); i++) {
            def itm = xmlContent.item[i]

            if (classNames.length > 0) {
                String classname = itm.classname.text()
                if (!(classname in classNames)) {
                    log 'Skipping item with class ' + classname
                    continue;
                }
            }

            if (programThumbnail == null || programThumbnail.length() == 0) {
                pageTitle = itm.serienaam.text()
                programThumbnail = itm.seriescoverurl.text()
            }
            
            Date releaseDate = Date.parse("yyyy-MM-dd'T'HH:mm:ss", itm.broadcastdatetime.text())
            String title = itm.title.text()
            String thumbnail = itm.thumbnail.text()
            String url = itm.movie.text()

            WebResourceItem item = new WebResourceItem(title: title, releaseDate: releaseDate, additionalInfo: ['playlistUrl' : url, 'thumbUrl': thumbnail])
            items << item
        }

        return new WebResourceContainer(title: pageTitle, thumbnailUrl: programThumbnail, items: items)
    }

    ContentURLContainer extractUrl(WebResourceItem item, PreferredQuality requestedQuality) {
        String playlistUrl = item.getAdditionalInfo()['playlistUrl']
        String thumbnailUrl = item.getAdditionalInfo()['thumbUrl']
    	def videoUrl

        String playlist = new URL(playlistUrl).getText() 

        // This is an m3u8, so parse it as text, use a regex to filter the interesting streams
        def matcher = playlist =~ M3U8_ITEM

        def list = []
        for (int i = 0; i < matcher.size(); i++) {
            list << ['url': matcher[i][2], 'bitrate': matcher[i][1].toInteger()] 
        }
        list = list.sort{ it.bitrate }.reverse()
        
        // Depending on the quality requested, return different url's
        if (requestedQuality == PreferredQuality.HIGH) {
            videoUrl = list[0] 
        } else if (requestedQuality == PreferredQuality.MEDIUM) {
    		videoUrl = list.size() > 1 ? list[1] : list[0]
        } else if (requestedQuality == PreferredQuality.LOW) {
            videoUrl = list.size() > 2 ? list[list.size() - 1] : list.size() > 1 ? list[1] : list[0]
        }

        return videoUrl == null ? null : new ContentURLContainer(fileType: MediaFileType.VIDEO, contentUrl: videoUrl.url, thumbnailUrl: thumbnailUrl)
    }

    static WebResourceContainer testURL(String url, int itemCount = 2) {
        RTLXL rtl = new RTLXL();
        URL resourceUrl = new URL(url)

        assert rtl.extractorMatches(resourceUrl), 'Url doesn\t match for this WebResource plugin'

        WebResourceContainer container = rtl.extractItems(resourceUrl, itemCount)

        assert container != null, 'Container is empty'
        assert container.items != null, 'Container contains no items'
        assert container.items.size() == itemCount, 'Amount of items is invalid. Expected was ' + itemCount + ', result was ' + container.items.size()

        for (int i = 0; i < container.items.size(); i++) {
            WebResourceItem item = container.items[i]
            ContentURLContainer result = rtl.extractUrl(item, PreferredQuality.HIGH)
            println result
        }
        return container
    }

    static void main(args) {
        // this is just to test
        testURL("http://www.rtl.nl/xl/#/a/276832")
	    testURL("http://www.rtl.nl/xl/#/a/10821?type=uitzending|fragmenten|blabla")
//        testURL("http://www.nickelodeon.se/video/show/280-dora-utforskaren")
//        testURL("http://www.nickelodeon.no/video/show/280-dora-utforskeren")
    }
}
