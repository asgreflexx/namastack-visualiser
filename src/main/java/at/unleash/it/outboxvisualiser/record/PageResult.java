package at.unleash.it.outboxvisualiser.record;

import java.util.List;

public record PageResult<T>(List<T> items, int page, int size, long total) {

    public int totalPages() {
        return total == 0 ? 1 : (int) ((total + size - 1) / size);
    }

    public boolean hasPrevious() {
        return page > 0;
    }

    public boolean hasNext() {
        return page + 1 < totalPages();
    }
}
