package searchengine.services.indexing;

import lombok.RequiredArgsConstructor;
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
import searchengine.model.SiteEntity;
import searchengine.model.SiteStatus;
import searchengine.repository.PageRepository;
import searchengine.repository.SiteRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ForkJoinPool;

@Service
@RequiredArgsConstructor
public class IndexingServiceImpl implements IndexingService {
    private static final Logger logger = LoggerFactory.getLogger(IndexingServiceImpl.class);

    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final SitesList sitesList;
    private ForkJoinPool forkJoinPool;

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

}