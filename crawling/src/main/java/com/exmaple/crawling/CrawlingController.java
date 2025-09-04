package com.exmaple.crawling;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/crawling")
@RequiredArgsConstructor
public class CrawlingController {
    private final NewsService newsService;

    @GetMapping("/daum")
    public ResponseEntity<?> crawlDaum(@RequestParam(defaultValue = "economy") String category,
                                       @RequestParam(defaultValue = "5") int limit) throws Exception {
        List<Long> ids = newsService.crawlCategory(category, Math.max(1, Math.min(limit, 50)));
        return ResponseEntity.ok(ids);
    }
}