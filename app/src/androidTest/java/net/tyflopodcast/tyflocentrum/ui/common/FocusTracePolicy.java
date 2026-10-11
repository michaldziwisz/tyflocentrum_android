package net.tyflopodcast.tyflocentrum.ui.common;

import java.util.ArrayList;
import java.util.List;

/** Polityka aparatury: poprawny stan końcowy nie unieważnia błędnej historii. */
public final class FocusTracePolicy {
    private final boolean resume;
    private final long startedAt;
    private Long returningAt;
    private boolean targetReturned;
    private final List<String> errors = new ArrayList<>();

    public FocusTracePolicy(boolean resume, long startedAt) {
        this.resume = resume;
        this.startedAt = startedAt;
    }

    public synchronized void markReturn(long time) {
        if (!resume || returningAt != null || time < startedAt) {
            throw new IllegalStateException("Nieprawidłowa granica powrotu");
        }
        returningAt = time;
    }

    public synchronized void record(long time, boolean gained, boolean inApp, Boolean target) {
        if (time < startedAt || (resume && (returningAt == null || time < returningAt))) return;
        if (!inApp) {
            if (gained && (!resume || targetReturned)) errors.add("Fokus opuścił obserwowane okno");
            return;
        }
        if (gained) {
            if (Boolean.TRUE.equals(target)) targetReturned = true;
            else errors.add(target == null ? "Brak źródła zdarzenia fokusu" : "Fokus trafił na inny węzeł");
        } else if ((!resume || targetReturned) && !Boolean.FALSE.equals(target)) {
            errors.add(target == null ? "Brak źródła wyczyszczenia fokusu" : "Wyczyszczono fokus badanego wiersza");
        }
    }

    public synchronized List<String> failures(boolean finalFocus) {
        List<String> result = new ArrayList<>(errors);
        if (!finalFocus) result.add("Brak fokusu na badanym wierszu w pomiarze końcowym");
        if (resume && returningAt == null) result.add("Brak zarejestrowanej granicy powrotu");
        if (resume && !targetReturned) result.add("Brak zdarzenia potwierdzającego powrót fokusu do wiersza");
        return result;
    }
}
