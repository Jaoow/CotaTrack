package dev.jaoow.cotatrack.rest.controller;

import dev.jaoow.cotatrack.rest.dto.StocksListResponse;
import dev.jaoow.cotatrack.rest.dto.StocksResponse;
import dev.jaoow.cotatrack.rest.service.UsStocksService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/stocks")
@RequiredArgsConstructor
public class UsStocksController {
    private final UsStocksService service;

    @GetMapping("/{symbols}")
    public StocksResponse quotes(@PathVariable String symbols,
            @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) String interval, @RequestParam(required = false) String range,
            @RequestParam(required = false) Boolean includeDividends) {
        return service.fetch(symbols, from, to, interval, range,
            Boolean.TRUE.equals(includeDividends));
    }

    @GetMapping("/list")
    public StocksListResponse list(@RequestParam(required = false) String search,
            @RequestParam(required = false) String type, @RequestParam(required = false) String sortBy,
            @RequestParam(defaultValue = "asc") String sortOrder, @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int page) {
        return service.list(search, type, sortBy, sortOrder, limit, page);
    }
}
