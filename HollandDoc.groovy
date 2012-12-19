import org.serviio.library.metadata.*
import org.serviio.library.online.*
import javax.xml.xpath.*
import javax.xml.parsers.DocumentBuilderFactory
import java.security.MessageDigest
import groovy.json.JsonSlurper

/**
 * Holland Doc
 * 
 * @author Erwin Bovendeur 
 * @version 1.1
 * @releasedate 2012-12-03
 *
 * Holland Doc plugin for Serviio. Allowed URL's are:
 * http://www.hollanddoc.nl/kijk-luister/landen-en-regios.html
 * http://www.hollanddoc.nl/kijk-luister/recent-en-meest-bekeken/recent-toegevoegd.html
 * 
 * The search function of the site can not be used, since it returns all kinds of results
 *
 * Changelog:
 * Version 1.1: 
 * - Added use of OmroepNL Base class
 */
class HollandDoc extends OmroepNL {
    final VALID_FEED_URL = '^http://www.hollanddoc.nl/kijk-luister/.*$'

    final HTML_ARTICLE = '(?s)<div id="vteaser.*?" class=".*?s-normal" data-urn="(.*?)">(.*?)<\\/div> <!-- \\.vteaser -->'
    final HTML_ARTICLE_THUMB = '<img class="abs" src="(.+?)"'

    final HTML_PAGE_TITLE   = '(?s)<title>(.+?)</title>'
    final HTML_PAGER        = '(?s)<ul class="pager">(.*?)</ul>'
    final HTML_PAGER_ITEM   = '(?s)<li><a.*?>(\\d+?)</a></li>'

    String getExtractorName() {
        return "Holland Doc"
    }
    
    int getVersion() {
        return 11;
    }

    boolean extractorMatches(URL feedUrl) {
        return feedUrl ==~ VALID_FEED_URL
    }
    
    WebResourceContainer extractItems(URL resourceUrl, int maxItems) {
        List<WebResourceItem> items = []
        String videoUrn = ""
        String videoUrl = ""
        String videoId = ""
        String videoTitle = ""
        Date releaseDate
        String pageTitle = ""
        String pageThumb = ""
        String thumbUrl = ""
        String cleanUrl = ""
        Short startPage = 1
        Short maxPage = -1
        boolean isFirstPage = true

        // Does this URL already contain a ?currentPage=
        if (resourceUrl ==~ /^.*currentPage=\d*$/) {
            def matcher = resourceUrl =~ /^(.*\?currentPage=)(\d*)$/
            cleanUrl = matcher[0][1]
            startPage = matcher[0][2].toShort()
            log("This URL has multiple pages, starting at page " + startPage)
        } else {
            cleanUrl = resourceUrl.toString() + "?currentPage="
            resourceUrl = new URL(cleanUrl + startPage)
        }

        while (maxItems == -1 || items.size() < maxItems) {
            String pageContent = resourceUrl.getText()

            // Find the maximum page
            if (maxPage == -1) {
                String pager = FirstMatch(pageContent, HTML_PAGER)
                def pages = pager =~ HTML_PAGER_ITEM

                maxPage = pages[items.size() - 1][1].toShort()

                log "Found maximum page: " + maxPage
            }

            // Find the article
            def htmlArticles = pageContent =~ HTML_ARTICLE

            pageTitle = FirstMatch(pageContent, HTML_PAGE_TITLE)

            for (int i = 0; i < htmlArticles.size() && i < maxItems; i++) {
                String article = htmlArticles[i][2]
                videoUrn = htmlArticles[i][1]

                String json = new URL('http://couchdb.vpro.nl/media/' + videoUrn).getText()
                JsonSlurper slurper = new JsonSlurper()
                def result = slurper.parseText(json)

                videoTitle = result.title
                thumbUrl = FirstMatch(article, HTML_ARTICLE_THUMB)
                String date = null
                if (result.episodeOf != null) {
                    date = result.episodeOf[0].added
                } else if (result.memberOf != null) {
                    date = result.memberOf[0].added
                }
                releaseDate = date != null ? Date.parse("yyyy-MM-dd'T'HH:mm:ss", date) : null
                videoId = result.mid
                videoUrl = null

                // Can we translate this URL into a "new" style url, or is this a direct link to a movie file?
                if (result.locations.size() == 0) {
                    continue // No movie found
                } else {
                    String url = result.locations[0].url
                    if (url.startsWith("odi+http")) {
                        // We're good, this is a "normal" movie
                    } else if (url ==~ '\\?aflID=(\\d+)') {
                        def videoIdMatch = url =~'\\?aflID=(\\d+)'
                        videoId = videoIdMatch[0][1] // Now we're good
                    } else {
                        videoUrl = result.locations[0].url
                    }
                }

                WebResourceItem item = new WebResourceItem(title: videoTitle, releaseDate: releaseDate, additionalInfo: ['videoId':videoId,'thumbUrl':thumbUrl,'videoUrl':videoUrl])

                items << item

                if (maxItems != -1 && items.size() >= maxItems) {
                    log("Having enough items (as much as requested)")
                    break;
                }
            }

            if (maxItems == -1 || items.size() < maxItems) {
                // Load the next page
                startPage++

                if (startPage > maxPage) {
                    log("Last page retrieved, this is the end (my friend)")
                    break;
                }

                log("Loading page " + startPage)

                resourceUrl = new URL(cleanUrl + startPage)
            }
        }

        return new WebResourceContainer(title: pageTitle, items: items)
    }    

    static WebResourceContainer testURL(String url, int itemCount = 2) {
        HollandDoc doc = new HollandDoc();
    	URL resourceUrl = new URL(url)
    	WebResourceContainer container = doc.extractItems(resourceUrl, itemCount)
	
        assert container != null, 'Container is empty'
        assert container.items != null, 'Container contains no items'
        assert container.items.size() == itemCount, 'Amount of items is invalid. Expected was ' + itemCount + ', result was ' + container.items.size()

        for (int i = 0; i < container.items.size(); i++) {
            WebResourceItem item = container.items[i]
            ContentURLContainer result = doc.extractUrl(item, PreferredQuality.HIGH)
            println result
        }
    	return container
    }
    
    static void main(args) {
        // this is just to test
    	WebResourceContainer container = testURL("http://www.hollanddoc.nl/kijk-luister/landen-en-regios.html")

    	WebResourceItem singleItem = container.items[1]
        singleItem = new WebResourceItem(title: singleItem.title, releaseDate: singleItem.releaseDate, additionalInfo: ['videoId':singleItem.getAdditionalInfo()['videoId']])

        ContentURLContainer singleResult = new HollandDoc().extractUrl(singleItem, PreferredQuality.MEDIUM)
        println singleResult

        testURL("http://www.hollanddoc.nl/kijk-luister/leve-de-stad.html")
        testURL("http://www.hollanddoc.nl/kijk-luister/recent-en-meest-bekeken/recent-toegevoegd")     
    }
}
