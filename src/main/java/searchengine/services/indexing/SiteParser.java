package searchengine.services.indexing;

import lombok.RequiredArgsConstructor;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import searchengine.model.Page;
import searchengine.model.SiteEntity;
import searchengine.repository.PageRepository;
import searchengine.repository.SiteRepository;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.RecursiveAction;

@RequiredArgsConstructor
public class SiteParser extends RecursiveAction {
    private static final Logger logger = LoggerFactory.getLogger(SiteParser.class);
    private static final Set<String> visitedUrls = Collections.synchronizedSet(new HashSet<>());

    private final String url;
    private final int siteId;
    private final PageRepository pageRepository;
    private final SiteRepository siteRepository;
    private final int maxDepth;
    private final int currentDepth;


    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "", ".html", ".htm", ".php", ".asp", ".jsp", ".xhtml"
    );

    @Override
    protected void compute() {
        try {
            String normalizedUrl = normalizeUrl(url);
            if (currentDepth >= maxDepth || visitedUrls.contains(normalizedUrl) || !isValidUrl(url)) {
                logger.info("Пропущен URL (глубина/повтор/некорректный): {}", url);
                return;
            }

            visitedUrls.add(normalizedUrl);
            Thread.sleep(500);

            Connection.Response response = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (compatible; MySearchBot/1.0)")
                    .referrer("http://www.google.com")
                    .timeout(10000)
                    .ignoreHttpErrors(true)
                    .execute();

            int statusCode = response.statusCode();
            String contentType = response.contentType();

            if (statusCode != 200 || !isContentTypeSupported(contentType)) {
                logger.info("Пропускаем URL {}: HTTP {} или неподдерживаемый контент {}", url, statusCode, contentType);
                return;
            }

            Document doc = response.parse();
            savePage(doc, statusCode);
            processLinks(doc);

        } catch (IOException | InterruptedException e) {
            logger.error("Ошибка при обработке {}: {}", url, e.getMessage());
        } catch (Exception e) {
            logger.error("Общая ошибка {}: {}", url, e.getMessage());
        }
    }

    private void savePage(Document doc, int statusCode) {
        try {
            Optional<SiteEntity> siteOpt = siteRepository.findById(siteId);
            if (siteOpt.isEmpty()) {
                logger.warn("Сайт с id={} не найден", siteId);
                return;
            }
            SiteEntity site = siteOpt.get();

            String path = url.replaceFirst(site.getUrl(), "");
            if (path.isEmpty() || path.isBlank()) {
                path = "/";
            }

            if (pageRepository.findBySiteAndPath(site, path).isPresent()) {
                logger.debug("Страница уже существует: {}", path);
                return;
            }

            Page page = new Page();
            page.setSite(site);
            page.setPath(path);
            page.setCode(statusCode);
            page.setContent(doc.html());

            pageRepository.saveAndFlush(page);
            logger.info("Сохранена страница: {}", path);

        } catch (Exception e) {
            logger.error("Ошибка сохранения страницы {}: {}", url, e.getMessage());
        }
    }

    private void processLinks(Document doc) {
        Elements links = doc.select("a[href]");
        Set<SiteParser> tasks = new HashSet<>();

        for (Element link : links) {
            String childUrl = link.absUrl("href");
            if (isValidUrl(childUrl)) {
                tasks.add(new SiteParser(
                        childUrl,
                        siteId,
                        pageRepository,
                        siteRepository,
                        maxDepth,
                        currentDepth + 1
                ));
            }
        }

        invokeAll(tasks);
    }

    private boolean isContentTypeSupported(String contentType) {
        if (contentType == null) return false;
        String lowerType = contentType.toLowerCase();
        return lowerType.startsWith("text/html") ||
                lowerType.contains("xhtml") ||
                lowerType.contains("xml");
    }

    private boolean isValidUrl(String rawUrl) {
        try {
            Optional<SiteEntity> siteOpt = siteRepository.findById(siteId);
            if (siteOpt.isEmpty()) return false;

            URI base = new URI(siteOpt.get().getUrl()).normalize();
            URI target = new URI(rawUrl).normalize();

            if (target.getHost() == null || base.getHost() == null) {
                return false;
            }

            if (!target.getHost().equals(base.getHost()) || rawUrl.contains("#")) {
                return false;
            }

            String path = target.getPath().toLowerCase();
            int lastDot = path.lastIndexOf('.');
            if (lastDot != -1) {
                String ext = path.substring(lastDot);
                if (!ALLOWED_EXTENSIONS.contains(ext)) {
                    return false;
                }
            }

            return true;

        } catch (URISyntaxException e) {
            logger.warn("Невалидный URI: {}", rawUrl);
            return false;
        }
    }

    private String normalizeUrl(String url) {
        try {
            return new URI(url).normalize().toString();
        } catch (URISyntaxException e) {
            return url;
        }
    }

}
