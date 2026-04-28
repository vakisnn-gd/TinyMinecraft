import java.util.Locale;

final class FrameProfiler {
    private double sampledSeconds;
    private int sampledFrames;
    private long updateNanos;
    private long worldNanos;
    private long renderNanos;
    private long audioNanos;

    void recordFrame(double deltaTime, long updateNs, long worldNs, long renderNs, long audioNs) {
        if (!GameConfig.ENABLE_FRAME_PROFILING) {
            return;
        }

        sampledSeconds += deltaTime;
        sampledFrames++;
        updateNanos += updateNs;
        worldNanos += worldNs;
        renderNanos += renderNs;
        audioNanos += audioNs;

        if (sampledSeconds < GameConfig.FRAME_PROFILE_LOG_INTERVAL_SECONDS) {
            return;
        }

        double frames = Math.max(1, sampledFrames);
        System.out.println(String.format(
            Locale.US,
            "[frame] fps=%.1f update=%.3fms world=%.3fms render=%.3fms audio=%.3fms",
            sampledFrames / sampledSeconds,
            updateNanos / 1_000_000.0 / frames,
            worldNanos / 1_000_000.0 / frames,
            renderNanos / 1_000_000.0 / frames,
            audioNanos / 1_000_000.0 / frames
        ));

        sampledSeconds = 0.0;
        sampledFrames = 0;
        updateNanos = 0L;
        worldNanos = 0L;
        renderNanos = 0L;
        audioNanos = 0L;
    }

    static void logTask(String label, long durationNanos) {
        if (!GameConfig.ENABLE_DEBUG_LOGS) {
            return;
        }
        System.out.println(String.format(Locale.US, "[task] %s: %.3fms", label, durationNanos / 1_000_000.0));
    }
}
