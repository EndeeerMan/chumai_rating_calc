import java.util.ArrayList;
import java.util.List;

/**
 * Immutable ranked output and selected subtotals produced by
 * {@link B50Calculator}.
 */
public final class B50Result {
    private final List<B50Item> items;
    private final List<B50Item> selectedItems;
    private final List<B50Item> legacySelected;
    private final List<B50Item> currentSelected;
    private final int legacyTotal;
    private final int currentTotal;

    B50Result(List<B50Item> items) {
        this.items = List.copyOf(items);
        selectedItems = filterSelected(this.items, null);
        legacySelected = filterSelected(this.items, Version.LEGACY);
        currentSelected = filterSelected(this.items, Version.CURRENT);
        legacyTotal = sumRating(legacySelected);
        currentTotal = sumRating(currentSelected);
    }

    /** All inputs in deterministic overall ranking order. */
    public List<B50Item> items() {
        return items;
    }

    /** Selected B50 items in deterministic overall ranking order. */
    public List<B50Item> selectedItems() {
        return selectedItems;
    }

    /** Selected legacy items in legacy-pool ranking order. */
    public List<B50Item> legacySelected() {
        return legacySelected;
    }

    /** Selected current-version items in current-pool ranking order. */
    public List<B50Item> currentSelected() {
        return currentSelected;
    }

    public int legacyTotal() {
        return legacyTotal;
    }

    public int currentTotal() {
        return currentTotal;
    }

    /** Alias suited to wire formats that expose the aggregate as "total". */
    public int total() {
        return totalRating();
    }

    public int totalRating() {
        return Math.addExact(legacyTotal, currentTotal);
    }

    public int selectedCount() {
        return selectedItems.size();
    }

    private static List<B50Item> filterSelected(
            List<B50Item> items, Version version) {
        List<B50Item> filtered = new ArrayList<>();
        for (B50Item item : items) {
            if (item.selected() && (version == null || item.version() == version)) {
                filtered.add(item);
            }
        }
        return List.copyOf(filtered);
    }

    private static int sumRating(List<B50Item> items) {
        int total = 0;
        for (B50Item item : items) {
            total = Math.addExact(total, item.rating());
        }
        return total;
    }
}
