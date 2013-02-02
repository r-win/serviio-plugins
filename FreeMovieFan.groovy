import org.serviio.library.metadata.*
import org.serviio.library.online.*
import groovy.json.JsonSlurper

/**
 * Free Movie Fans
 * 
 * Note: You CANNOT scrobble using this plugin, which is why isLive is set to true.
 * The reason is that this site also uses a ?start=xxx argument to specify where to start the movie, just like the mediabrowser does 
 *
 * @author Erwin Bovendeur 
 * @version 1.2
 * @releasedate 2013-02-02
 *
 * Changelog:
 * 
 * Version 1.2:
 * - Added some log messages
 * - Changed maxItems to be 50 if set to 0
 *
 * Version 1.1:
 * - Added support for year and search
 * - Added support for a custom amount of items using argument maxItems=xx
 * 
 * Version 1.0:
 * - Initial release
 */
class FreeMovieFan extends WebResourceUrlExtractor {

    final VALID_LINK_URL    = '^(http://www.fmovief.com)/?((category/(\\d+)/([^\\?]+))|(year/(\\d+))|(search/.*?)|(search.php))?(\\?(.+))?$'
    final ITEM_PART         = '(?s)<div class="item">(.*?)<div class="clear">'
    final ITEM_PARSE        = '(?s)<img src="(.+?)".*?<a href=".*?">(.+?)</a>.*<div class="votes" movieid="(.+?)".*?Release: (\\d+)'

    String FirstMatch(String content, String regex) {
        def match = content =~ regex
        return match[0][1]
    }

    String strip(String input) {
        return input.replaceAll('<(.|\n)*?>', '')
    }

    /* Interface */

    String getExtractorName() {
        return "Free Movie Fan"
    }

    int getVersion() {
        return 12;
    }
    
    boolean extractorMatches(URL feedUrl) {
        return feedUrl ==~ VALID_LINK_URL
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

    String doGetAndFindHeader(String url, String header) {
        URL searchUrl = new URL(url)
        def connection = searchUrl.openConnection()

        connection.setRequestMethod("GET")
        connection.instanceFollowRedirects = false
        connection.connect()

        return connection.getHeaderField(header)
    }

    String findArg(String args, String key) {
        if (args == null) return null
        String[] queryArgs = args.split('&')
        for (int i = 0; i < queryArgs.length; i++) {
            String[] queryArg = queryArgs[i].split('=')
            if (queryArg.length == 2 && queryArg[0] == key) {
                return queryArg[1]
            }
        }
        return null
    }

    int getPage(String args) {
        String val = findArg(args, 'p')
        return (val == null) ? 1 : val.toInteger()
    }

    WebResourceContainer extractItems(URL resourceUrl, int maxItems) {
    	List<WebResourceItem> items = []

        def matchUrl = resourceUrl =~ VALID_LINK_URL
        String baseUrl = matchUrl[0][1]
        String path = matchUrl[0][2]
        String args = matchUrl[0][11]

        int currentPage = getPage(args)
        if (path == null) path = ''

        String seperator = '?'

        String max = findArg(args, 'maxItems')
        if (max != null) {
            int iMax = max.toInteger()
            maxItems = iMax > 0 ? Math.min(iMax, maxItems) : maxItems
        }
        if (maxItems < 1) maxItems = 50

        if (path == 'search.php') {
            // Include type and keywords to path
            seperator = '&'
            String type = findArg(args, 'type')
            String keywords = findArg(args, 'keywords')

            path += '?type=' + type + '&keywords=' + keywords 
        }

        // We should call multiple pages to get the correct amount of items
        while (items.size() < maxItems) {
            // Create the url for the correct page
            String url = baseUrl + '/' + path + seperator + 'p=' + currentPage
            URL pageUrl = new URL(url)

            log('FreeMovieFan: Opening url: ' + url)

            String pageContent = pageUrl.getText()

            // Parse the page
            def movieItems = pageContent =~ ITEM_PART
            log('FreeMovieFan: Found ' + movieItems.size() + ' items on the page')
            for (int i = 0; i < movieItems.size() && items.size() < maxItems; i++) {
                // Parse each item into parts
                def parsedItem = movieItems[i] =~ ITEM_PARSE

                String thumbUrl = parsedItem[0][1]
                String title = parsedItem[0][2]
                String movieId = parsedItem[0][3]
                int year = parsedItem[0][4].toInteger()

                log('FreeMovieFan: Parsed item "' + title + '"')

                // The movie id will stay the same, but the URL will be different everytime we try to play the movie, so pass on the movieId instead
                WebResourceItem item = new WebResourceItem(title: title, releaseDate: new Date(year - 1900, 1, 1), additionalInfo: ['movieId':movieId,'thumbUrl':thumbUrl])
                items << item
            }

            currentPage++
        }

        return new WebResourceContainer(title: 'Free Movie Fans', items: items)
    }

    ContentURLContainer extractUrl(WebResourceItem item, PreferredQuality requestedQuality) {
        String movieId = item.getAdditionalInfo()['movieId']
        String thumbUrl = item.getAdditionalInfo()['thumbUrl']

        log ('FreeMovieFan: Extracting url for movie with id ' + movieId)

        // Call the ajax script to get the movie url
        String json = doPost('http://www.fmovief.com/ajax.php?act=fetchplayurl', 'movie=' + movieId)
        JsonSlurper slurper = new JsonSlurper()
        def result = slurper.parseText(json)

        if (result.result) {
            // Retrieve the correct url
            String redirectedUrl = doGetAndFindHeader('http://www.fmovief.com' + result.mediaurl, 'Location')
            log ('FreeMovieFan: Found original URL: ' + redirectedUrl)

            // We'll expire the movie url immediately, it will call this method again when it starts playing. This way, we can retrieve the correct movie URL.
            // The movie Id is correct, and the thumbnail also. We don't need to parse the whole feed again
            return new ContentURLContainer(fileType: MediaFileType.VIDEO, contentUrl: redirectedUrl, thumbnailUrl: thumbUrl, expiresImmediately: true, cacheKey: movieId, live: true)
        }
        return null
    }

    static WebResourceContainer testURL(String url, int itemCount = 2) {
        FreeMovieFan fmf = new FreeMovieFan();
        URL resourceUrl = new URL(url)

        assert fmf.extractorMatches(resourceUrl), 'Url doesn\'t match for this WebResource plugin'

        WebResourceContainer container = fmf.extractItems(resourceUrl, itemCount)

        assert container != null, 'Container is empty'
        assert container.items != null, 'Container contains no items'
        assert container.items.size() == itemCount, 'Amount of items is invalid. Expected was ' + itemCount + ', result was ' + container.items.size()

        for (int i = 0; i < container.items.size(); i++) {
            WebResourceItem item = container.items[i]
            ContentURLContainer result = fmf.extractUrl(item, PreferredQuality.HIGH)
            println result
        }
        return container
    }

    static void main(args) {
        // this is just to test
        testURL("http://www.fmovief.com")
        testURL("http://www.fmovief.com/year/2012")
        testURL("http://www.fmovief.com/search.php?type=director&keywords=John")
        testURL("http://www.fmovief.com/search/star/Philip Seymour Hoffman")
	    testURL("http://www.fmovief.com/category/5/Comedy")
        testURL("http://www.fmovief.com/?p=4")
    }
}
