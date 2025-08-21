package searchengine.services.indexing;

import searchengine.Exception.IndexingAlreadyStartedException;
import searchengine.Exception.IndexingNotStartedException;
import searchengine.Exception.PageOutsideConfigException;

import java.io.IOException;

public interface IndexingService {
    void startIndexing() throws IndexingAlreadyStartedException;

    void stopIndexing() throws IndexingNotStartedException;

    void indexPage(String pageUrl) throws PageOutsideConfigException, IOException;
}
