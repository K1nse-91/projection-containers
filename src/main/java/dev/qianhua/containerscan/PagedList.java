package dev.qianhua.containerscan;

final class PagedList {
    static final int PAGE_SIZE = 24;
    private static final int ROWS_PER_NOTCH = 3;

    private int entries;
    private int visibleRows;
    private int page;
    private int offset;
    private double wheelRows;

    void resize(int entries, int visibleRows) {
        this.entries = entries;
        this.visibleRows = visibleRows;
        page = Math.min(page, pageCount(entries) - 1);
        offset = Math.min(offset, lastOffset());
    }

    static int pageCount(int entries) {
        return Math.max(1, (entries - 1) / PAGE_SIZE + 1);
    }

    int page() {
        return page;
    }

    int pages() {
        return pageCount(entries);
    }

    int first() {
        return page * PAGE_SIZE + offset;
    }

    int end() {
        return first() + Math.min(visibleRows, rowsOnPage() - offset);
    }

    void reset() {
        page = 0;
        offset = 0;
        wheelRows = 0;
    }

    boolean scroll(double verticalAmount) {
        if (verticalAmount == 0 || !Double.isFinite(verticalAmount)) {
            return false;
        }
        double rows = -verticalAmount * Math.min(visibleRows, ROWS_PER_NOTCH);
        if (wheelRows * rows < 0) {
            wheelRows = 0;
        }
        wheelRows = Math.clamp(wheelRows + rows, -PAGE_SIZE, PAGE_SIZE);
        int step = (int) wheelRows;
        wheelRows -= step;
        if (step == 0) {
            return false;
        }

        int bottom = lastOffset();
        if (step > 0 && offset == bottom) {
            return changePage(1, false);
        }
        if (step < 0 && offset == 0) {
            return changePage(-1, true);
        }
        offset = Math.clamp(offset + step, 0, bottom);
        return true;
    }

    boolean changePage(int direction) {
        return changePage(direction, false);
    }

    private boolean changePage(int direction, boolean startAtBottom) {
        int next = Math.clamp(page + direction, 0, pages() - 1);
        if (next == page) {
            return false;
        }
        page = next;
        offset = startAtBottom ? lastOffset() : 0;
        wheelRows = 0;
        return true;
    }

    private int rowsOnPage() {
        return Math.min(PAGE_SIZE, entries - page * PAGE_SIZE);
    }

    private int lastOffset() {
        return Math.max(0, rowsOnPage() - visibleRows);
    }
}
