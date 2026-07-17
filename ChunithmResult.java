import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Immutable output from {@link ChunithmCalculator}. */
public final class ChunithmResult {
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal OFFICIAL_DENOMINATOR = BigDecimal.valueOf(50);

    private final List<ChunithmItem> items;
    private final List<ChunithmItem> selectedItems;
    private final List<ChunithmItem> b30;
    private final List<ChunithmItem> n20;
    private final int b30Hundredths;
    private final int n20Hundredths;

    ChunithmResult(List<ChunithmItem> items) {
        this.items = List.copyOf(items);
        selectedItems = filterSelected(this.items, null);
        b30 = filterSelected(this.items, Boolean.FALSE);
        n20 = filterSelected(this.items, Boolean.TRUE);
        b30Hundredths = sumHundredths(b30);
        n20Hundredths = sumHundredths(n20);
    }

    /** All inputs in stable single-chart Rating order. */
    public List<ChunithmItem> items() {
        return items;
    }

    /** All selected B30 and N20 charts in overall Rating order. */
    public List<ChunithmItem> selectedItems() {
        return selectedItems;
    }

    /** Selected legacy-version charts, limited to 30. */
    public List<ChunithmItem> b30() {
        return b30;
    }

    /** Selected latest-version charts, limited to 20. */
    public List<ChunithmItem> n20() {
        return n20;
    }

    public double b30Total() {
        return b30Hundredths / 100.0;
    }

    public double n20Total() {
        return n20Hundredths / 100.0;
    }

    public double totalContribution() {
        return totalHundredths() / 100.0;
    }

    /**
     * Official player Rating: selected contribution sum divided by 50.
     * The divisor stays 50 even when either pool contains fewer charts.
     */
    public double rating() {
        return ratingDecimal().doubleValue();
    }

    public BigDecimal b30TotalDecimal() {
        return BigDecimal.valueOf(b30Hundredths).divide(HUNDRED);
    }

    public BigDecimal n20TotalDecimal() {
        return BigDecimal.valueOf(n20Hundredths).divide(HUNDRED);
    }

    public BigDecimal totalContributionDecimal() {
        return BigDecimal.valueOf(totalHundredths()).divide(HUNDRED);
    }

    /** Exact result; its finest possible increment is 0.0002. */
    public BigDecimal ratingDecimal() {
        return totalContributionDecimal().divide(OFFICIAL_DENOMINATOR);
    }

    public int selectedCount() {
        return selectedItems.size();
    }

    private int totalHundredths() {
        return Math.addExact(b30Hundredths, n20Hundredths);
    }

    private static List<ChunithmItem> filterSelected(
            List<ChunithmItem> items, Boolean newChart) {
        List<ChunithmItem> filtered = new ArrayList<>();
        for (ChunithmItem item : items) {
            if (item.selected()
                    && (newChart == null || item.newChart() == newChart.booleanValue())) {
                filtered.add(item);
            }
        }
        return List.copyOf(filtered);
    }

    private static int sumHundredths(List<ChunithmItem> items) {
        int total = 0;
        for (ChunithmItem item : items) {
            total = Math.addExact(total, item.ratingHundredths());
        }
        return total;
    }
}
