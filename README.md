Serviio Plugins
====

Contains a set of plugins for use with Serviio, so you can play certain online streams on your DLNA enabled device:
- Dutch websites, like Uitzending Gemist and some Nederland 24 channels
- Nickelodeon (Dutch, Swedish and Norwegian)

For more information about Serviio: http://www.servio.org

The plugins Cultura24, HollandDoc and UitzendingGemist require OmroepNL.groovy to be installed in the plugins directory of Serviio

Plugins included:

* Uitzending Gemist
* Cultura 24
* Holland Doc
* Nickelodeon

## Uitzending Gemist

A few RSS feeds which can be added using the console to Serviio:

Genre Comedy: http://www.uitzendinggemist.nl/genres/comedy.rss 
Omroep Flevoland: http://www.uitzendinggemist.nl/omroepen/omroepflevoland.rss 
Het Zandkasteel: http://www.uitzendinggemist.nl/programmas/354-het-zandkasteel.rss
 
This plugin only supports RSS feeds, and not HTML pages of episodes. Also, make sure you add the RSS feeds as WebResources, and not as Feeds.

## Cultura 24

A plugin for watching missed shows of Culture 24. Allowed URL's include

http://gemist.cultura.nl/
http://gemist.cultura.nl/zoeken/#
and pages with search results, like
http://gemist.cultura.nl/zoeken/#facet_pomsgenre:literatuur|facet_pomsgenre:film%20en%20drama|view:cellsByColumn

## Holland Doc

A plugin for watching missed shows of Holland Doc. Allowed URL's include URL's which start with 
http://www.hollanddoc.nl/kijk-luister/

This plugin will automatically get the videos from the next pages, until the maximum amount of items is retrieved. If you set the amount of items to unlimited in Serviio, this plugin will fetch ALL pages from a specific topic.

## Nickelodeon

A plugin for watching streams from Nickelodeon. Supported are the Dutch, Swedish and Norwegian site of Nickelodeon. To get the correct feed for a program, navigate the site of Nickelodeon, and choose a show. Scroll down, and choose "More Videos". The URL should now contain /video/show, or /videos/show for the dutch site. 

Example URL's for Dora on the three sites below:
http://www.nickelodeon.nl/videos/show/280-dora
http://www.nickelodeon.se/video/show/280-dora-utforskaren
http://www.nickelodeon.no/video/show/280-dora-utforskeren

