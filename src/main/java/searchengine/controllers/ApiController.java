package searchengine.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import searchengine.Exception.IndexingAlreadyStartedException;
import searchengine.Exception.IndexingNotStartedException;
import searchengine.dto.indexing.IndexingErrorResponse;
import searchengine.dto.indexing.IndexingResponse;
import searchengine.dto.statistics.StatisticsResponse;
import searchengine.services.indexing.IndexingService;
import searchengine.services.StatisticsService;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ApiController {

    private final StatisticsService statisticsService;
    private final IndexingService indexingService;

    @GetMapping("/statistics")
    public ResponseEntity<StatisticsResponse> statistics() {
        return ResponseEntity.ok(statisticsService.getStatistics());
    }

    @GetMapping("/startIndexing")
    public ResponseEntity<?> startIndexing() {
        try {
            indexingService.startIndexing();
            return ResponseEntity.ok(new IndexingResponse(true));
        } catch (IndexingAlreadyStartedException e) {
            return ResponseEntity.badRequest().body(
                    new IndexingErrorResponse(false, e.getMessage())
            );
        }
    }

    @GetMapping("/stopIndexing")
    public ResponseEntity<?> stopIndexing() {
        try {
            indexingService.stopIndexing();
            return ResponseEntity.ok(new IndexingResponse(true));
        } catch (IndexingNotStartedException e) {
            return ResponseEntity.badRequest().body(
                    new IndexingErrorResponse(false, e.getMessage())
            );
        }
    }

    @PostMapping("/indexPage")
    public ResponseEntity<String> indexPage(@RequestParam String url) {
        try {
            indexingService.indexPage(url);
            return ResponseEntity.ok("Страница не проиндексирована: " + url);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Ошибка при индексации: " + e.getMessage());
        }
    }
}
