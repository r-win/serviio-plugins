import org.serviio.library.metadata.*
import org.serviio.library.online.*
import javax.xml.xpath.*
import javax.xml.parsers.DocumentBuilderFactory
import groovy.json.JsonSlurper
import java.net.URLEncoder

/**
 * Nickelodeon 
 * Should work for the Dutch, Swedish and Norwegian site, possible others
 * 
 * @author Erwin Bovendeur 
 * @version 1.0
 * @releasedate 2012-12-10
 *
 * Changelog:
 * 
 * Version 1.0: 
 * - Initial release
 */
class Nickelodeon extends WebResourceUrlExtractor {

    final VALID_LINK_URL    = '^(http://www\\.nickelodeon\\..+?)/video(s)?/show/.+$'
    final THUMBNAIL_URL     = '<meta content="(.*)" itemprop="image" property="og:image" />'
    final PLAYLIST_ID       = 'data-playlist-id=\'([0-9a-f]+)\''

    final BASE_FEED_URL	    = 'http://api.mtvnn.com/v2/mrss.xml?uri=mgid%3Asensei%3Avideo%3Amtvnn.com%3Alocal_playlist-'
    final CONFIG_URL        = '(http://player.mtvnn.com/.*?/config/config.php)'

    String xpath(String xmlContent, String xpath) {
        def builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        ByteArrayInputStream inputStream = new ByteArrayInputStream(xmlContent.bytes)
        def records = builder.parse(inputStream).documentElement

        XPath path = XPathFactory.newInstance().newXPath()
        return path.evaluate(xpath, records, XPathConstants.STRING).trim()
    }

    String FirstMatch(String content, String regex) {
        def match = content =~ regex
        return match[0][1]
    }

    String strip(String input) {
        return input.replaceAll('<(.|\n)*?>', '')
    }

    /* Interface */

    String getExtractorName() {
        return "Nickelodeon"
    }
    
    boolean extractorMatches(URL feedUrl) {
        return feedUrl ==~ VALID_LINK_URL 
    }

    WebResourceContainer extractItems(URL resourceUrl, int maxItems) {
    	List<WebResourceItem> items = []

    	String baseUrl = FirstMatch(resourceUrl.toString(), VALID_LINK_URL)
    	String pageContent = resourceUrl.getText()
    	
    	String pageTitle
        String countryCode
        String adSite 

        try {
            pageTitle = FirstMatch(pageContent, '<a href="/shows/.+">(.+?)</a>')
        }
        catch(IndexOutOfBoundsException e) {
            pageTitle = FirstMatch(pageContent, '<title>(.*?)</title>').trim()
        }

    	// Find all items on the page	
    	def matcher = pageContent =~ '(?s)teaser_item\'>.*?<a href="(.+?)" class="preview"><span.*?/span>.*?<img.*?src="(.+?)/\\d+x\\d+_?".*?/>.*?<div class=\'description\'>.*?<h3>(.*?)</h3>'

    	for (int i = 0; i < matcher.size() && (maxItems == -1 || i < maxItems); i++) {
    		String url = matcher[i][1]
    		String img = matcher[i][2] + '/320x240'
    		String title = matcher[i][3]

    		String itemContent = new URL(baseUrl + url).getText()
    		String playlistId = FirstMatch(itemContent, PLAYLIST_ID)

            if (countryCode == null || countryCode.length() == 0) {
                // Fetch the country code from the config script, do this only one time, because it's the same for all movies in this feed (can be different per feed though)
                String configUrl = FirstMatch(itemContent, CONFIG_URL)
                try {
                    String configContent = new URL(configUrl).getText()
                    JsonSlurper slurper = new JsonSlurper()
                    def config = slurper.parseText(configContent[1..-2]) // Remove first and last character
                    countryCode = config.countryCode
                    adSite = config.adSettings.doubleClick.adSite
                }
                catch(FileNotFoundException) {
                    // I have to extract the values from the html content instead
                    adSite = FirstMatch(itemContent, 'adSite\\s+:\\s+\\\'(.+?)\\\',')
                    countryCode = FirstMatch(adSite, '^.+?\\.(.+)$')
                }
            }

    		String rss = new URL(BASE_FEED_URL + playlistId).getText()
    		def xmlContent = new XmlSlurper().parseText(rss).declareNamespace(media: "http://search.yahoo.com/mrss/")

            for (int j = 0; j < xmlContent.channel.item.size() && (maxItems == -1 || i + j < maxItems); j++) {
                def itm = xmlContent.channel.item[j]

        		Date releaseDate = Date.parse('yyyy-MM-dd H:m:s Z', itm.pubDate.text())
        		
        		String playlist = itm.'media:group'.'media:content'.@url.text()

                String configUrl = 'http://media.mtvnservices.com/pmt/e1/players/mgid:sensei:video:mtvnn.com:/context9/config.xml?uri=mgid:sensei:video:mtvnn.com:local_playlist-' + playlistId + '-'
                configUrl += countryCode + '-uma_site--ad_site-' + adSite + '-ad_site_referer-' + url + '&type=network&ref=' + baseUrl + '&geo=' + countryCode + '&group=intl&network=None&device=Other'

                String swfUrl = 'http://media.mtvnservices.com/player/prime/mediaplayerprime.1.8.1.swf?uri=mgid:sensei:video:mtvnn.com:local_playlist-' + playlistId + '-NL-uma_site--ad_site-nick.nl-ad_site_referer-'
                swfUrl += url + '&type=network&ref=' + baseUrl + '&geo=' + countryCode + '&group=intl&network=None&device=Other&CONFIG_URL=' + URLEncoder.encode(configUrl)

                String swfAttr = 'swfUrl=' + swfUrl + ' '
                swfAttr += 'pageUrl=' + baseUrl + url + ' '
                swfAttr += 'app=ondemand?ovpfv=2.1.4 '                                  // Not sure what this does, just add it
                swfAttr += 'tcUrl=rtmp://cp8619.edgefcs.net:1935/ondemand?ovpfv=2.1.4 ' // Not sure what this does, just add it
                swfAttr += 'swfVfy=1'                                           // Not sure which codec this is, but the site uses it ;)

                String newTitle = title
                if (xmlContent.channel.item.size() > 1) {
                    newTitle += '(Part ' + (j + 1) + ')'
                }

        		WebResourceItem item = new WebResourceItem(title: newTitle, releaseDate: releaseDate, additionalInfo: ['playlistUrl' : playlist, 'thumbUrl': img, 'swfAttr': swfAttr])

        		items << item
            }
    	}
    	return new WebResourceContainer(title: pageTitle, items: items) 
    }

    ContentURLContainer extractUrl(WebResourceItem item, PreferredQuality requestedQuality) {
        String playlistUrl = item.getAdditionalInfo()['playlistUrl']
        String thumbnailUrl = item.getAdditionalInfo()['thumbUrl']
        String swfAttr = item.getAdditionalInfo()['swfAttr']
    	def videoUrl

        String playlist = new URL(playlistUrl).getText() 
    	def xmlContent = new XmlSlurper().parseText(playlist)

    	def list = []
    	for (int i = 0; i < xmlContent.video.item.rendition.size(); i++) {
    		def node = xmlContent.video.item.rendition[i]
    		list << ['url': node.src.text(), 'bitrate': node.@bitrate.text().toInteger()] 
    	}
    	list = list.sort{ it.bitrate }.reverse()

        /* Depending on the quality requested, return different url's */
        if (requestedQuality == PreferredQuality.HIGH) {
            videoUrl = list[0] 
        } else if (requestedQuality == PreferredQuality.MEDIUM) {
    		videoUrl = list.size() > 1 ? list[1] : list[0]
        } else if (requestedQuality == PreferredQuality.LOW) {
            videoUrl = list.size() > 2 ? list[2] : list.size() > 1 ? list[1] : list[0]
        }

        return videoUrl == null ? null : new ContentURLContainer(fileType: MediaFileType.VIDEO, contentUrl: videoUrl.url + ' ' + swfAttr, thumbnailUrl: thumbnailUrl)
    }

    static WebResourceContainer testURL(String url, int itemCount = 2) {
        Nickelodeon nick = new Nickelodeon();
        URL resourceUrl = new URL(url)

        assert nick.extractorMatches(resourceUrl), 'Url doesn\t match for this WebResource plugin'

        WebResourceContainer container = nick.extractItems(resourceUrl, itemCount)

        assert container != null, 'Container is empty'
        assert container.items != null, 'Container contains no items'
        assert container.items.size() == itemCount, 'Amount of items is invalid. Expected was ' + itemCount + ', result was ' + container.items.size()

        for (int i = 0; i < container.items.size(); i++) {
            WebResourceItem item = container.items[i]
            ContentURLContainer result = nick.extractUrl(item, PreferredQuality.HIGH)
            println result
        }
        return container
    }

    static void main(args) {
        // this is just to test
        testURL("http://www.nickelodeon.nl/videos/show/280-dora")
        testURL("http://www.nickelodeon.se/video/show/280-dora-utforskaren")
        testURL("http://www.nickelodeon.no/video/show/280-dora-utforskeren")
    }
}
