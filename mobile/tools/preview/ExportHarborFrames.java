import com.copenhagen.wallet.ui.HarborSplashTimeline;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Export the compiled production timeline for an offline artwork preview, not a device capture. */
class ExportHarborFrames {
    public static void main(String[] args) throws Exception {
        StringBuilder json = new StringBuilder("[\n");
        for (int i = 0; i <= 66; i++) {
            long ms = Math.min(Math.round(i * 1000.0 / 30), HarborSplashTimeline.DurationMillis);
            var f = HarborSplashTimeline.INSTANCE.frame(ms, false);
            if (i > 0) json.append(",\n");
            json.append(String.format(Locale.ROOT,
                "{\"ms\":%d,\"boatX\":%.5f,\"boatY\":%.5f,\"bankDegrees\":%.5f,\"wakeAlpha\":%.5f,\"arrival\":%.5f,\"originalLogoAlpha\":%.5f,\"artworkAlpha\":%.5f,\"wordmarkAlpha\":%.5f}",
                ms, f.getBoatX(), f.getBoatY(), f.getBankDegrees(), f.getWakeAlpha(), f.getArrival(),
                f.getOriginalLogoAlpha(), f.getArtworkAlpha(), f.getWordmarkAlpha()));
        }
        Files.writeString(Path.of(args[0]), json.append("\n]\n").toString());
    }
}
