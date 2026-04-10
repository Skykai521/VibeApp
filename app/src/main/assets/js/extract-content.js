(function() {
  try {
    var docClone = document.cloneNode(true);
    var article = new Readability(docClone).parse();
    if (!article || !article.content) {
      return JSON.stringify({
        error: 'readability_extraction_failed',
        fallback: document.title || '',
        text: document.body ? document.body.innerText.substring(0, 12000) : ''
      });
    }

    var turndownService = new TurndownService({
      headingStyle: 'atx',
      codeBlockStyle: 'fenced',
      bulletListMarker: '-',
      emDelimiter: '*',
      strongDelimiter: '**',
      linkStyle: 'inlined'
    });

    if (typeof turndownPluginGfm !== 'undefined') {
      turndownService.use(turndownPluginGfm.gfm);
    }

    turndownService.addRule('removeImages', {
      filter: 'img',
      replacement: function() { return ''; }
    });

    var markdown = turndownService.turndown(article.content);

    return JSON.stringify({
      title: article.title || document.title || '',
      content: markdown,
      byline: article.byline || '',
      excerpt: article.excerpt || '',
      siteName: article.siteName || '',
      url: window.location.href
    });
  } catch (e) {
    return JSON.stringify({
      error: e.message || 'unknown_error',
      fallback: document.title || '',
      text: document.body ? document.body.innerText.substring(0, 12000) : ''
    });
  }
})();
