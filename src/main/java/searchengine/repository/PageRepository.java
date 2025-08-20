package searchengine.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import searchengine.model.Page;
import searchengine.model.SiteEntity;

import java.util.Optional;

public interface PageRepository extends JpaRepository<Page, Integer> {
    Optional<Page> findBySiteAndPath(SiteEntity site, String path);
}
