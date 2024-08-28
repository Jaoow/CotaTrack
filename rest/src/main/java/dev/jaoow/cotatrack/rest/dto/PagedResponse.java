package dev.jaoow.cotatrack.rest.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class PagedResponse<T> implements Response<T> {
    private List<T> results;
    private int currentPage;
    private int totalPages;
    private int itemsPerPage;
    private long totalCount;
    private boolean hasNextPage;

    public static <T> PagedResponse<T> paginate(List<T> allItems, int pageNumber, int itemsPerPage) {
        long totalCount = allItems.size();
        int totalPages = (int) Math.ceil((double) totalCount / itemsPerPage);

        int start = Math.min(pageNumber * itemsPerPage, allItems.size());
        int end = Math.min(start + itemsPerPage, allItems.size());

        List<T> pageItems = allItems.subList(start, end);
        boolean hasNextPage = pageNumber < totalPages - 1;

        return new PagedResponse<>(pageItems, pageNumber + 1, totalPages, itemsPerPage, totalCount, hasNextPage);
    }
}
