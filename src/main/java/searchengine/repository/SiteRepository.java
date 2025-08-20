package searchengine.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import searchengine.model.SiteEntity;
import searchengine.model.SiteStatus;

import java.util.List;

public interface SiteRepository extends JpaRepository<SiteEntity, Integer> {
    List<SiteEntity> findByUrl(String url);
    List<SiteEntity> findByStatus(SiteStatus status);

}
