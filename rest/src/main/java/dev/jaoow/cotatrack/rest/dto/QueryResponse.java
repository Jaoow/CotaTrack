package dev.jaoow.cotatrack.rest.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class QueryResponse<T> implements Response<T> {
    private List<T> results;
    private String elapsedTime;
    private LocalDateTime requestedAt;

    public QueryResponse(List<T> results, long elapsedTime) {
        this.results = results;
        this.elapsedTime = String.format("%dms", elapsedTime);
        this.requestedAt = LocalDateTime.now();
    }
}
