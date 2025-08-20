package searchengine.services.indexing;

import searchengine.Exception.IndexingAlreadyStartedException;
import searchengine.Exception.IndexingNotStartedException;
import searchengine.Exception.PageOutsideConfigException;

public interface IndexingService {
    void startIndexing() throws IndexingAlreadyStartedException;

    void stopIndexing() throws IndexingNotStartedException;
}
