import org.serviio.library.metadata.*
import org.serviio.library.online.*
import javax.xml.xpath.*
import javax.xml.parsers.DocumentBuilderFactory
import java.security.MessageDigest

/**
 * Uitzending Gemist 
 * 
 * @author Erwin Bovendeur 
 * @version 1.0
 * @releasedate 2012-11-29
 *
 */
class UitzendingGemist extends FeedItemUrlExtractor {

    final VALID_FEED_URL = '^http(s)*://.*uitzendinggemist.nl/.*$'
    final VALID_LINK_URL = '^http://gemi.st/(\\d+)$'
    final VALID_PAGE_URL = '^http://www.uitzendinggemist.nl/programmas/.*/afleveringen/.*$'
    final THUMBNAIL_URL = '<meta content="(.*)" itemprop="image" property="og:image" />'
    final EPISODE_ID = 'data-episode-id="(\\d+)"'

    /* Not really required, but the method openURL requires one, so let's just specify a valid one */
    final USER_AGENT = 'Mozilla/5.0 (Windows NT 6.2; WOW64) AppleWebKit/537.15 (KHTML, like Gecko) Chrome/24.0.1295.0 Safari/537.15' 

    def security_token = null
    MessageDigest digest = MessageDigest.getInstance("MD5")

    String getExtractorName() {
        return getClass().getName()
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
    
    ContentURLContainer extractUrl(Map links, PreferredQuality requestedQuality) {
        def linkUrl = links.alternate != null ? links.alternate : links.default
        def thumbnailUrl = links.thumbnail

		def pageContent = null
		def videoId = null

		/* Check if this is the long url, or the shorter gemi.st domain */
		if (linkUrl ==~ VALID_PAGE_URL) {
			log("Requesting info for page '" + linkUrl.toString() + "'")
			if (pageContent == null) {
				pageContent = linkUrl.getText()

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
			log("Link '" + linkUrl.toString() + "' can't be handled by this plugin")
		}

		def token = getToken()
		def hash = md5(videoId + "|" + token).toUpperCase() /* Really? Come on! */
		def videoInfo = new URL("http://pi.omroep.nl/info/stream/aflevering/" + videoId + "/" + hash).getText()

		/* Depending on the quality requested, return different url's */
		def videoUrl = null
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
			def asxContent = new URL(videoUrl).getText()
			def mmsref = asxContent =~ 'href="mms://(.*)"'
			videoUrl = "mmsh://" + mmsref[0][1]
		}

		if (thumbnailUrl == null) {
			if (pageContent == null) {
				pageContent = linkUrl.getText()
			}
			def thumb = pageContent =~ THUMBNAIL_URL
			thumbnailUrl = thumb[0][1]
		}
        return new ContentURLContainer(fileType: MediaFileType.VIDEO, contentUrl: videoUrl, thumbnailUrl: thumbnailUrl, expiresImmediately: true, cacheKey: videoId)
    }

    String xpath(String xmlContent, String xpath) {
		def builder     = DocumentBuilderFactory.newInstance().newDocumentBuilder()
		def inputStream = new ByteArrayInputStream(xmlContent.bytes)
		def records     = builder.parse(inputStream).documentElement

		def path = XPathFactory.newInstance().newXPath()
		return path.evaluate(xpath, records, XPathConstants.STRING).trim()
    }
    
    static void main(args) {
		// this is just to test
		UitzendingGemist uzg = new UitzendingGemist();
		assert uzg.extractorMatches(new URL("http://www.uitzendinggemist.nl/kijktips.rss"))

        Map links = ['default': new URL('http://gemi.st/15005786')]
        ContentURLContainer result = uzg.extractUrl(links, PreferredQuality.HIGH)
        println "Result: $result"

        links = ['default': new URL('http://www.uitzendinggemist.nl/programmas/354-het-zandkasteel/afleveringen/1309320')]
        result = uzg.extractUrl(links, PreferredQuality.HIGH)
        println "Result: $result"

        links = ['default': new URL('http://gemi.st/15011972')]
        result = uzg.extractUrl(links, PreferredQuality.HIGH)
        println "Result: $result"
    }
}
