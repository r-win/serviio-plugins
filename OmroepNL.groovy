import org.serviio.library.metadata.*
import org.serviio.library.online.*
import javax.xml.xpath.*
import javax.xml.parsers.DocumentBuilderFactory
import java.security.MessageDigest

/**
 * Omroep NL Base class 
 * 
 * @author Erwin Bovendeur 
 * @version 1.0
 * @releasedate 2012-12-03
 *
 * Changelog:
 * 
 * Version 1.0: 
 * - Created base class with shared functionality of Omroep.nl
 */
class OmroepNL extends WebResourceUrlExtractor {

    final VALID_LINK_URL    = '^http://gemi.st/(\\d+)$'
    final VALID_PAGE_URL    = '^http://www.uitzendinggemist.nl/(programmas/.*/)?afleveringen/.*$'
    final THUMBNAIL_URL     = '<meta content="(.*)" itemprop="image" property="og:image" />'
    final EPISODE_ID        = 'data-episode-id="(\\d+)"'

    def security_token          = null
    static MessageDigest digest = MessageDigest.getInstance("MD5")

    /* Protected methods */

    String xpath(String xmlContent, String xpath) {
        def builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        ByteArrayInputStream inputStream = new ByteArrayInputStream(xmlContent.bytes)
        def records = builder.parse(inputStream).documentElement

        XPath path = XPathFactory.newInstance().newXPath()
        return path.evaluate(xpath, records, XPathConstants.STRING).trim()
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

    String md5(String s) {
        digest.update(s.bytes);
        new BigInteger(1, digest.digest()).toString(16).padLeft(32, '0')
    } 

    String FirstMatch(String content, String regex) {
        def match = content =~ regex
        return match[0][1]
    }

    String strip(String input) {
        return input.replaceAll('<(.|\n)*?>', '')
    }

    String doPost(String url, String body) {
        URL searchUrl = new URL(url)
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

    /* Interface */

    String getExtractorName() {
        return "Omroep NL"
    }
    
    int getVersion() {
        return 10;
    }
    
    boolean extractorMatches(URL feedUrl) {
        return false
    }
    
    WebResourceContainer extractItems(URL resourceUrl, int maxItems) {
        log "Omroep NL can't handle any URL's"
        return null
    }

    ContentURLContainer extractUrl(WebResourceItem item, PreferredQuality requestedQuality) {
        String linkUrl = item.getAdditionalInfo()['infoUrl']
        String thumbnailUrl = item.getAdditionalInfo()['thumbUrl']
        String videoId = item.getAdditionalInfo()['videoId']

        String videoUrl = item.getAdditionalInfo()['videoUrl']
        String pageContent = null

        if (videoUrl == null || videoUrl.length() == 0){
            if (videoId != null && !videoId.contains('/')) { // Not a full URL, so only an Id (as expected), add gemi.st in front of it
                linkUrl = 'http://gemi.st/' + videoId
            }

            /* Check if this is the long url, or the shorter gemi.st domain */
            if (linkUrl ==~ VALID_PAGE_URL) {
                log("Requesting info for page '" + linkUrl.toString() + "'")
                if (pageContent == null) {
                    pageContent = new URL(linkUrl).getText()
                    videoId = FirstMatch(pageContent, EPISODE_ID)
                }
            }
            if (videoId == null && linkUrl ==~ VALID_LINK_URL) {
                videoId = FirstMatch(linkUrl, VALID_LINK_URL)
            }

            if (videoId == null) {
                log("Link '" + linkUrl + "' can't be handled by this plugin")
            }

            String token = getToken()
            String hash = md5(videoId + "|" + token).toUpperCase() /* Really? Come on! */
            String videoInfo = new URL("http://pi.omroep.nl/info/stream/aflevering/" + videoId + "/" + hash).getText()

            /* Depending on the quality requested, return different url's */
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
                videoUrl = "mmsh://" + FirstMatch(asxContent, 'href="mms://(.*)"')
            }
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
}
