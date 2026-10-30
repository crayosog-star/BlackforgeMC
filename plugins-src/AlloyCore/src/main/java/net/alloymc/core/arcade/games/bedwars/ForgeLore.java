package net.alloymc.core.arcade.games.bedwars;

import java.util.ArrayList;
import java.util.List;

/**
 * The Forge Wars easter eggs as plain data (no Bukkit), so the tests check exactly what the game plays:
 * the Smith's Song (an original note-block tune, played when someone finds all three of the Smith's hammers or the
 * hidden jukebox) and the Golem's story, one line per pink ore vein.
 *
 * The story, kept slight on purpose: the Smith of the Keep shaped the Golem to guard the forge-fire. When the Keep
 * fell, the Smith went down into the dark and never came back, and the Golem waited so long the pink heart-ore grew
 * through its back. It still wakes when the fire runs hot (diamond generators II) and goes looking for the Smith.
 * The Smith's three hammers lie scattered round the Core; bring them together and the Smith's Song plays.
 */
final class ForgeLore {
    private ForgeLore() {}

    static final int HAMMERS = 3;

    /** One line per pink ore vein, in the order the veins are listed in the map. */
    static final List<String> VEIN_LINES = List.of(
            "The Smith shaped him from the Keep's own stone, to guard the forge-fire.",
            "When the Keep fell, the Smith went down into the dark and did not come back.",
            "He waited by the Smith's door so long, the heart-ore grew through his back.",
            "When the fire runs hot, he wakes, and goes looking for the Smith.");

    /** One note of the song: when (ticks from the start), which sound, pitch and volume. */
    record Note(int tick, String sound, float pitch, float volume) {}

    static final String HARP = "minecraft:block.note_block.harp", BELL = "minecraft:block.note_block.bell",
            BASS = "minecraft:block.note_block.bass", DRUM = "minecraft:block.note_block.basedrum",
            HAT = "minecraft:block.note_block.hat", CHIME = "minecraft:block.note_block.chime",
            ANVIL = "minecraft:block.anvil.land";

    /** Ticks per eighth note (150 bpm). */
    static final int STEP = 4, BAR = STEP * 8, BARS = 8;

    /** The Smith's Song: 8 bars in D minor, harp melody over bass, an anvil clank on the strong bars. ~13 s. */
    static final List<Note> SONG = song();

    /** Note-block pitch for a note name like "D4", "Bb4" or "C#5" (the playable range is F#3 to F#5). */
    static float pitch(String name) { return (float) Math.pow(2, (semis(name) - 12) / 12.0); }

    /** Semitones above F#3 (0..24), the note block's "clicks". */
    static int semis(String name) {
        int i = "C D EF G A B".indexOf(name.charAt(0));
        if (i < 0) throw new IllegalArgumentException(name);
        int at = 1;
        if (name.charAt(at) == '#') { i++; at++; } else if (name.charAt(at) == 'b') { i--; at++; }
        int midi = 12 * (Integer.parseInt(name.substring(at)) + 1) + i;
        return midi - 54;
    }

    private static List<Note> song() {
        List<Note> l = new ArrayList<>();
        // melody: one token per eighth note, "-" holds the note before
        String melody = String.join(" ",
                "D4 F4 A4 D5 - C5 A4 -",
                "Bb4 - A4 G4 F4 G4 A4 -",
                "D5 - E5 F5 E5 D5 C5 -",
                "A4 - - - C#5 - D5 -",
                "F5 - E5 D5 C5 - A4 -",
                "Bb4 - C5 D5 C5 Bb4 A4 -",
                "G4 A4 Bb4 - A4 G4 F4 E4",
                "D4 - - - - - - -");
        String[] tok = melody.split(" ");
        for (int i = 0; i < tok.length; i++) if (!tok[i].equals("-")) l.add(new Note(i * STEP, HARP, pitch(tok[i]), 0.9f));
        // bass: two notes a bar (a note block's bass sounds two octaves down)
        String[] bass = {"D4", "A3", "Bb3", "C4", "D4", "A3", "A3", "A3", "D4", "F4", "Bb3", "C4", "G3", "A3", "D4", "D4"};
        for (int i = 0; i < bass.length; i++) l.add(new Note(i * BAR / 2, BASS, pitch(bass[i]), 0.8f));
        for (int b = 0; b < BARS; b++) {
            l.add(new Note(b * BAR, DRUM, 1f, 0.5f));
            l.add(new Note(b * BAR + BAR / 2, DRUM, 1f, 0.4f));
            if (b < BARS - 1) { l.add(new Note(b * BAR + BAR / 4, HAT, 1.4f, 0.25f)); l.add(new Note(b * BAR + 3 * BAR / 4, HAT, 1.4f, 0.25f)); }
            if (b % 2 == 0) l.add(new Note(b * BAR, ANVIL, 1.5f, 0.2f));   // the forge keeps time
        }
        // bells on the held notes, and a last chime over the final D
        l.add(new Note(3 * BAR, BELL, pitch("A3"), 0.5f));
        l.add(new Note(7 * BAR, BELL, pitch("D4"), 0.6f));
        l.add(new Note(7 * BAR + STEP * 2, CHIME, pitch("A4"), 0.5f));
        l.add(new Note(7 * BAR + STEP * 4, CHIME, pitch("D5"), 0.5f));
        l.sort(java.util.Comparator.comparingInt(Note::tick));
        return List.copyOf(l);
    }

    /** How long the song lasts, in ticks. */
    static int length() { return SONG.get(SONG.size() - 1).tick() + STEP * 4; }
}
