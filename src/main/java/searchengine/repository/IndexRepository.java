package searchengine.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import searchengine.model.SearchIndex;

public interface IndexRepository extends JpaRepository<SearchIndex, Integer> {
}
