package net.tyflopodcast.tyflocentrum.ui.common;

import java.util.List;

/** Rozpoznaje wyłącznie przemijającą pauzę audio dostępnościowego, nie naprawia odtwarzacza. */
public final class AccessibilityAudioPause {
    public static final class Sample {
        public final boolean playing, ready, wantsToPlay, transientFocusLoss, a11yAudio;
        public Sample(boolean playing, boolean ready, boolean wantsToPlay, boolean transientFocusLoss, boolean a11yAudio) {
            this.playing = playing;
            this.ready = ready;
            this.wantsToPlay = wantsToPlay;
            this.transientFocusLoss = transientFocusLoss;
            this.a11yAudio = a11yAudio;
        }
    }
    public static boolean isExpected(List<Sample> samples) {
        if (samples.isEmpty() || !samples.get(0).playing || !samples.get(samples.size()-1).playing) return false;
        boolean inPause = false, witnessedAudio = false, sawPause = false;
        for (Sample sample : samples) {
            if (!sample.ready || !sample.wantsToPlay) return false;
            if (!sample.playing) {
                if (!sample.transientFocusLoss) return false;
                inPause = true;
                sawPause = true;
                witnessedAudio |= sample.a11yAudio;
            } else {
                if (sample.transientFocusLoss) return false;
                if (inPause && !witnessedAudio) return false;
                inPause = false;
                witnessedAudio = false;
            }
        }
        return sawPause && !inPause;
    }
    private AccessibilityAudioPause() {}
}
