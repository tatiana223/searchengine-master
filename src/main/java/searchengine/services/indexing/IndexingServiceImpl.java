package searchengine.services.indexing;

import lombok.RequiredArgsConstructor;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import searchengine.Exception.IndexingAlreadyStartedException;
import searchengine.Exception.IndexingNotStartedException;
import searchengine.Exception.PageOutsideConfigException;
import searchengine.config.Site;
import searchengine.config.SitesList;
import searchengine.model.*;
import searchengine.repository.IndexRepository;
import searchengine.repository.LemmaRepository;
import searchengine.repository.PageRepository;
import searchengine.repository.SiteRepository;

import javax.persistence.Index;
import javax.persistence.criteria.CriteriaBuilder;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ForkJoinPool;

@Service
@RequiredArgsConstructor
public class IndexingServiceImpl implements IndexingService {
    private static final Logger logger = LoggerFactory.getLogger(IndexingServiceImpl.class);

    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final LemmaRepository lemmaRepository;
    private final IndexRepository indexRepository;
    private final SitesList sitesList;
    private ForkJoinPool forkJoinPool;
    private final LemmaExtractor lemmaExtractor;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public synchronized void startIndexing() throws IndexingAlreadyStartedException {
        if (forkJoinPool != null && !forkJoinPool.isShutdown()) {
            throw new IndexingAlreadyStartedException("Индексация уже запущена");
        }

        sitesList.getSites().forEach(site -> {
            List<SiteEntity> existingSite = siteRepository.findByUrl(site.getUrl());
            if (existingSite != null) {
                try {
                    siteRepository.deleteAll(existingSite); // Каскадное удаление сработает
                } catch (Exception e) {
                    logger.warn("Ошибка при удалении сайта {}: {}", site.getUrl(), e.getMessage());
                }
            }
        });
        forkJoinPool = new ForkJoinPool();

        for (Site site : sitesList.getSites()) {
            forkJoinPool.execute(() -> {
                SiteEntity siteEntity = new SiteEntity();
                siteEntity.setUrl(site.getUrl());
                siteEntity.setName(site.getName());
                siteEntity.setStatus(SiteStatus.INDEXING);
                siteEntity.setStatus_time(LocalDateTime.now());

                try {
                    siteEntity = siteRepository.saveAndFlush(siteEntity); // Сохраняем сначала сайт

                    SiteParser parser = new SiteParser(
                            site.getUrl(),
                            siteEntity.getId(), // передаём только ID
                            pageRepository,
                            siteRepository,
                            10,
                            0
                    );


                    parser.invoke(); // Синхронный вызов

                    siteEntity.setStatus(SiteStatus.INDEXED);
                    siteRepository.saveAndFlush(siteEntity);

                } catch (Exception e) {
                    logger.error("Error indexing site {}: {}", site.getUrl(), e.getMessage());
                    if (siteEntity.getId() > 0) {
                        siteEntity.setStatus(SiteStatus.FAILED);
                        siteEntity.setLastError(e.getMessage());
                        siteRepository.saveAndFlush(siteEntity);
                    }
                }
            });
        }
    }

    @Override
    @Transactional
    public void stopIndexing() throws IndexingNotStartedException {
        if (forkJoinPool == null || forkJoinPool.isShutdown()) {
            throw new IndexingNotStartedException("Индексация не запущена");
        }

        forkJoinPool.shutdown();

        List<SiteEntity> indexingSites = siteRepository.findByStatus(SiteStatus.INDEXING);
        indexingSites.forEach(site -> {
            site.setStatus(SiteStatus.FAILED);
            site.setLastError("Индексация остановленв пользователем");
            site.setStatus_time(LocalDateTime.now());
            siteRepository.save(site);
        });

        forkJoinPool = null;
    }


    @Override
    public void indexPage(String pageUrl) throws PageOutsideConfigException, IOException {
        Site siteConfig = sitesList.getSites().stream()
                .filter(s -> pageUrl.startsWith(s.getUrl()))
                .findFirst()
                .orElseThrow(() -> new PageOutsideConfigException("Страница не принадлежит сайтам из конфигурации"));

        SiteEntity siteEntity = siteRepository.findByUrl(siteConfig.getUrl())
                .stream().findFirst()
                .orElseGet(() -> {
                    SiteEntity newSite = new SiteEntity();
                    newSite.setUrl(siteConfig.getUrl());
                    newSite.setName(siteConfig.getName());
                    newSite.setStatus(SiteStatus.INDEXING);
                    newSite.setStatus_time(LocalDateTime.now());
                    return siteRepository.saveAndFlush(newSite);
                });

        Connection.Response response = Jsoup.connect(pageUrl)
                .userAgent("Mozilla/5.0 (compatible; MySearchBot/1.0)")
                .timeout(10000)
                .execute();

        int statusCode = response.statusCode();
        Document doc = response.parse();

        String path = pageUrl.replaceFirst(siteEntity.getUrl(), "");
        if (path.isBlank()) path = "/";

        Page page = new Page();
        page.setSite(siteEntity);
        page.setPath(path);
        page.setCode(statusCode);
        page.setContent(doc.html());
        page = pageRepository.saveAndFlush(page);

        String cleanText = lemmaExtractor.cleanHtml(doc.html());

        Map<String, Integer> lemmas = lemmaExtractor.getLemmas(cleanText);

        for (Map.Entry<String, Integer> entry : lemmas.entrySet()) {
            String lemma = entry.getKey();
            int count = entry.getValue();

            Lemma lemmaEntity = lemmaRepository.findByLemmaAndSite(lemma, siteEntity)
                    .orElseGet(() -> {
                        Lemma newLemma = new Lemma();
                        newLemma.setLemma(lemma);
                        newLemma.setSite(siteEntity);
                        newLemma.setFrequency(0);
                        return newLemma;
                    });

            lemmaEntity.setFrequency(lemmaEntity.getFrequency() + 1);
            lemmaEntity = lemmaRepository.saveAndFlush(lemmaEntity);

            SearchIndex index = new SearchIndex();
            index.setPage(page);
            index.setLemma(lemmaEntity);
            index.setRank(count);
            indexRepository.saveAndFlush(index);

        }

        siteEntity.setStatus(SiteStatus.INDEXED);
        siteEntity.setStatus_time(LocalDateTime.now());
        siteRepository.saveAndFlush(siteEntity);

    }
}