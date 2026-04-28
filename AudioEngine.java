import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALCCapabilities;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.openal.AL10.AL_BUFFER;
import static org.lwjgl.openal.AL10.AL_FORMAT_MONO16;
import static org.lwjgl.openal.AL10.AL_FORMAT_STEREO16;
import static org.lwjgl.openal.AL10.AL_GAIN;
import static org.lwjgl.openal.AL10.AL_NO_ERROR;
import static org.lwjgl.openal.AL10.AL_ORIENTATION;
import static org.lwjgl.openal.AL10.AL_PITCH;
import static org.lwjgl.openal.AL10.AL_PLAYING;
import static org.lwjgl.openal.AL10.AL_POSITION;
import static org.lwjgl.openal.AL10.AL_SOURCE_STATE;
import static org.lwjgl.openal.AL10.alBufferData;
import static org.lwjgl.openal.AL10.alDeleteBuffers;
import static org.lwjgl.openal.AL10.alDeleteSources;
import static org.lwjgl.openal.AL10.alGenBuffers;
import static org.lwjgl.openal.AL10.alGenSources;
import static org.lwjgl.openal.AL10.alGetError;
import static org.lwjgl.openal.AL10.alGetSourcei;
import static org.lwjgl.openal.AL10.alListener3f;
import static org.lwjgl.openal.AL10.alListenerfv;
import static org.lwjgl.openal.AL10.alSource3f;
import static org.lwjgl.openal.AL10.alSourcePlay;
import static org.lwjgl.openal.AL10.alSourceStop;
import static org.lwjgl.openal.AL10.alSourcef;
import static org.lwjgl.openal.AL10.alSourcei;
import static org.lwjgl.openal.ALC10.alcCloseDevice;
import static org.lwjgl.openal.ALC10.alcCreateContext;
import static org.lwjgl.openal.ALC10.alcDestroyContext;
import static org.lwjgl.openal.ALC10.alcGetError;
import static org.lwjgl.openal.ALC10.alcMakeContextCurrent;
import static org.lwjgl.openal.ALC10.ALC_NO_ERROR;
import static org.lwjgl.openal.ALC10.alcOpenDevice;
import static org.lwjgl.system.MemoryUtil.NULL;

final class AudioEngine {
    private static final int SOURCE_COUNT = 24;
    private static final int SAMPLE_RATE = 22050;

    private final Map<String, Integer> buffers = new HashMap<>();
    private final int[] sources = new int[SOURCE_COUNT];
    private final FloatBuffer listenerOrientation = BufferUtils.createFloatBuffer(6);
    private int sourceCursor;
    private long device = NULL;
    private long context = NULL;
    private boolean enabled;

    void init() {
        try {
            ensureDefaultSounds();

            device = alcOpenDevice((ByteBuffer) null);
            if (device == NULL) {
                System.err.println("AudioEngine: OpenAL device is unavailable.");
                return;
            }
            checkAlcError("alcOpenDevice");

            ALCCapabilities alcCapabilities = ALC.createCapabilities(device);
            context = alcCreateContext(device, (IntBuffer) null);
            if (context == NULL) {
                System.err.println("AudioEngine: failed to create OpenAL context.");
                cleanup();
                return;
            }
            checkAlcError("alcCreateContext");

            alcMakeContextCurrent(context);
            checkAlcError("alcMakeContextCurrent");
            AL.createCapabilities(alcCapabilities);

            for (int i = 0; i < sources.length; i++) {
                sources[i] = alGenSources();
                checkAlError("alGenSources[" + i + "]");
            }

            enabled = true;
            loadSoundSafely("step_grass", Path.of("sounds", "step_grass.wav"));
            loadSoundSafely("step_stone", Path.of("sounds", "step_stone.wav"));
            loadSoundSafely("block_break", Path.of("sounds", "block_break.wav"));
            loadSoundSafely("water_splash", Path.of("sounds", "water_splash.wav"));
            loadSoundSafely("water_ambient", Path.of("sounds", "water_ambient.wav"));
            loadSoundSafely("zombie_growl", Path.of("sounds", "zombie_growl.wav"));
            loadSoundSafely("skeleton_rattle", Path.of("sounds", "skeleton_rattle.wav"));
            loadSoundSafely("pig_oink", Path.of("sounds", "pig_oink.wav"));
            loadSoundSafely("sheep_baa", Path.of("sounds", "sheep_baa.wav"));
            loadSoundSafely("cow_moo", Path.of("sounds", "cow_moo.wav"));
            loadSoundSafely("villager_hmm", Path.of("sounds", "villager_hmm.wav"));
        } catch (Throwable exception) {
            System.err.println("AudioEngine init failed: " + exception.getMessage());
            cleanup();
        }
    }

    void updateListener(PlayerState player) {
        if (!enabled) {
            return;
        }

        try {
            double horizontal = Math.cos(player.pitch);
            float lookX = (float) (Math.cos(player.yaw) * horizontal);
            float lookY = (float) Math.sin(player.pitch);
            float lookZ = (float) (Math.sin(player.yaw) * horizontal);

            alListener3f(AL_POSITION, (float) player.x, (float) (player.y + GameConfig.EYE_HEIGHT), (float) player.z);
            listenerOrientation.clear();
            listenerOrientation.put(lookX).put(lookY).put(lookZ);
            listenerOrientation.put(0.0f).put(1.0f).put(0.0f);
            listenerOrientation.flip();
            alListenerfv(AL_ORIENTATION, listenerOrientation);
            checkAlError("updateListener");
        } catch (RuntimeException exception) {
            System.err.println("AudioEngine listener update failed: " + exception.getMessage());
            enabled = false;
        }
    }

    void playStep(byte blockBelow, double x, double y, double z) {
        if (GameConfig.isWaterBlock(blockBelow) || GameConfig.isLavaBlock(blockBelow)) {
            playSplash(x, y, z);
            return;
        }

        boolean naturalSurface = blockBelow == GameConfig.GRASS
            || blockBelow == GameConfig.DIRT
            || blockBelow == GameConfig.SAND
            || blockBelow == GameConfig.OAK_LEAVES;
        play(naturalSurface ? "step_grass" : "step_stone", 0.55f, 1.0f, x, y, z);
    }

    void playBreak(byte block, double x, double y, double z) {
        float pitch = 0.96f;
        if (GameConfig.isWaterBlock(block) || GameConfig.isLavaBlock(block)) {
            play("water_splash", 0.70f, 0.92f, x, y, z);
            return;
        }
        if (block == GameConfig.SAND || block == GameConfig.DIRT || block == GameConfig.GRASS) {
            pitch = 1.05f;
        } else if (block == GameConfig.DIAMOND_ORE || block == GameConfig.IRON_ORE || block == GameConfig.COBBLESTONE) {
            pitch = 0.88f;
        }
        play("block_break", 0.75f, pitch, x, y, z);
    }

    void playSplash(double x, double y, double z) {
        play("water_splash", 0.68f, 1.0f, x, y, z);
    }

    void playWaterAmbient(double x, double y, double z) {
        play("water_ambient", 0.34f, 1.0f, x, y, z);
    }

    void playWaterBubble(double x, double y, double z) {
        play("water_ambient", 0.22f, 1.18f, x, y, z);
    }

    void playZombieGrowl(double x, double y, double z) {
        play("zombie_growl", 0.42f, 1.0f, x, y, z);
    }

    void playMobAmbient(MobKind kind, double x, double y, double z) {
        if (kind == null) {
            playZombieGrowl(x, y, z);
            return;
        }
        switch (kind) {
            case SKELETON:
                play("skeleton_rattle", 0.34f, 1.0f, x, y, z);
                return;
            case PIG:
                play("pig_oink", 0.34f, 1.0f, x, y, z);
                return;
            case SHEEP:
                play("sheep_baa", 0.34f, 1.0f, x, y, z);
                return;
            case COW:
                play("cow_moo", 0.36f, 1.0f, x, y, z);
                return;
            case VILLAGER:
                play("villager_hmm", 0.32f, 1.0f, x, y, z);
                return;
            case ZOMBIE:
            default:
                playZombieGrowl(x, y, z);
        }
    }

    void cleanup() {
        if (context != NULL) {
            for (int source : sources) {
                if (source != 0) {
                    alDeleteSources(source);
                }
            }
            for (int buffer : buffers.values()) {
                if (buffer != 0) {
                    alDeleteBuffers(buffer);
                }
            }
            alcMakeContextCurrent(NULL);
            alcDestroyContext(context);
            context = NULL;
        }
        buffers.clear();
        if (device != NULL) {
            alcCloseDevice(device);
            device = NULL;
        }
        enabled = false;
    }

    private void play(String key, float gain, float pitch, double x, double y, double z) {
        if (!enabled) {
            return;
        }

        Integer bufferId = buffers.get(key);
        if (bufferId == null) {
            return;
        }

        try {
            int source = nextSource();
            alSourceStop(source);
            alSourcei(source, AL_BUFFER, bufferId);
            alSourcef(source, AL_GAIN, gain);
            alSourcef(source, AL_PITCH, pitch);
            alSource3f(source, AL_POSITION, (float) x, (float) y, (float) z);
            alSourcePlay(source);
            checkAlError("play:" + key);
        } catch (RuntimeException exception) {
            System.err.println("AudioEngine playback failed for " + key + ": " + exception.getMessage());
            enabled = false;
        }
    }

    private int nextSource() {
        for (int i = 0; i < sources.length; i++) {
            int source = sources[(sourceCursor + i) % sources.length];
            if (alGetSourcei(source, AL_SOURCE_STATE) != AL_PLAYING) {
                sourceCursor = (sourceCursor + i + 1) % sources.length;
                return source;
            }
        }

        int source = sources[sourceCursor];
        sourceCursor = (sourceCursor + 1) % sources.length;
        return source;
    }

    private void loadSound(String key, Path path) throws Exception {
        if (!Files.isRegularFile(path)) {
            throw new IOException("missing WAV file: " + path);
        }
        WavData data = readWave(path);
        int bufferId = alGenBuffers();
        alBufferData(bufferId, data.format, data.data, data.sampleRate);
        checkAlError("loadSound:" + key);
        buffers.put(key, bufferId);
    }

    private WavData readWave(Path path) throws Exception {
        if (!Files.exists(path)) {
            throw new IOException("WAV file not found: " + path);
        }

        try (AudioInputStream source = AudioSystem.getAudioInputStream(path.toFile())) {
            AudioFormat base = source.getFormat();
            AudioFormat decoded = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                base.getSampleRate(),
                16,
                base.getChannels(),
                base.getChannels() * 2,
                base.getSampleRate(),
                false
            );

            try (AudioInputStream pcm = AudioSystem.getAudioInputStream(decoded, source)) {
                byte[] bytes = pcm.readAllBytes();
                ByteBuffer buffer = BufferUtils.createByteBuffer(bytes.length);
                buffer.put(bytes);
                buffer.flip();
                int format = decoded.getChannels() == 1 ? AL_FORMAT_MONO16 : AL_FORMAT_STEREO16;
                return new WavData(buffer, format, (int) decoded.getSampleRate());
            }
        }
    }

    private void loadSoundSafely(String key, Path path) {
        try {
            loadSound(key, path);
        } catch (Exception exception) {
            System.err.println("AudioEngine: failed to load " + path + " (" + exception.getMessage() + ")");
        }
    }

    private void ensureDefaultSounds() throws IOException {
        Path soundDirectory = Path.of("sounds");
        Files.createDirectories(soundDirectory);

        ensureWave(soundDirectory.resolve("step_grass.wav"), 0.12, (time, progress) -> {
            double noise = pseudoNoise(progress * 170.0) * 0.45;
            double tone = Math.sin(time * 2.0 * Math.PI * 180.0) * 0.20;
            return (noise + tone) * envelope(progress, 0.12, 0.0);
        });
        ensureWave(soundDirectory.resolve("step_stone.wav"), 0.10, (time, progress) -> {
            double toneA = Math.sin(time * 2.0 * Math.PI * 480.0) * 0.40;
            double toneB = Math.sin(time * 2.0 * Math.PI * 780.0) * 0.18;
            return (toneA + toneB) * envelope(progress, 0.08, 0.0);
        });
        ensureWave(soundDirectory.resolve("block_break.wav"), 0.18, (time, progress) -> {
            double crack = pseudoNoise(progress * 260.0) * 0.75;
            double body = Math.sin(time * 2.0 * Math.PI * 140.0) * 0.12;
            return (crack + body) * envelope(progress, 0.05, 0.0);
        });
        ensureWave(soundDirectory.resolve("water_splash.wav"), 0.24, (time, progress) -> {
            double whoosh = pseudoNoise(progress * 220.0) * 0.42;
            double wave = Math.sin(time * 2.0 * Math.PI * (260.0 - progress * 120.0)) * 0.20;
            return (whoosh + wave) * envelope(progress, 0.20, 0.0);
        });
        ensureWave(soundDirectory.resolve("water_ambient.wav"), 0.72, (time, progress) -> {
            double burble = Math.sin(time * 2.0 * Math.PI * 72.0) * 0.16;
            double bubble = Math.sin(time * 2.0 * Math.PI * (138.0 + progress * 40.0)) * 0.10;
            double foam = pseudoNoise(progress * 48.0 + time * 26.0) * 0.18;
            return (burble + bubble + foam) * envelope(progress, 0.12, 0.16);
        });
        ensureWave(soundDirectory.resolve("zombie_growl.wav"), 0.54, (time, progress) -> {
            double growl = Math.sin(time * 2.0 * Math.PI * 84.0) * 0.42;
            double rumble = Math.sin(time * 2.0 * Math.PI * 43.0) * 0.22;
            double grit = pseudoNoise(progress * 35.0 + 8.0) * 0.12;
            return (growl + rumble + grit) * envelope(progress, 0.12, 0.18);
        });
        ensureWave(soundDirectory.resolve("skeleton_rattle.wav"), 0.34, (time, progress) -> {
            double click = Math.sin(time * 2.0 * Math.PI * 420.0) * 0.18;
            double clack = Math.sin(time * 2.0 * Math.PI * 780.0) * 0.14;
            double grit = pseudoNoise(progress * 160.0) * 0.12;
            return (click + clack + grit) * envelope(progress, 0.05, 0.10);
        });
        ensureWave(soundDirectory.resolve("pig_oink.wav"), 0.28, (time, progress) -> {
            double oinkA = Math.sin(time * 2.0 * Math.PI * 180.0) * 0.26;
            double oinkB = Math.sin(time * 2.0 * Math.PI * 240.0) * 0.18;
            return (oinkA + oinkB) * envelope(progress, 0.03, 0.14);
        });
        ensureWave(soundDirectory.resolve("sheep_baa.wav"), 0.42, (time, progress) -> {
            double baa = Math.sin(time * 2.0 * Math.PI * 150.0) * 0.26;
            double air = pseudoNoise(progress * 80.0 + time * 30.0) * 0.08;
            return (baa + air) * envelope(progress, 0.04, 0.18);
        });
        ensureWave(soundDirectory.resolve("cow_moo.wav"), 0.52, (time, progress) -> {
            double moo = Math.sin(time * 2.0 * Math.PI * 104.0) * 0.34;
            double formant = Math.sin(time * 2.0 * Math.PI * 156.0) * 0.16;
            return (moo + formant) * envelope(progress, 0.06, 0.18);
        });
        ensureWave(soundDirectory.resolve("villager_hmm.wav"), 0.46, (time, progress) -> {
            double hmm = Math.sin(time * 2.0 * Math.PI * 132.0) * 0.28;
            double nose = Math.sin(time * 2.0 * Math.PI * 196.0) * 0.10;
            return (hmm + nose) * envelope(progress, 0.05, 0.16);
        });
    }

    private void ensureWave(Path path, double durationSeconds, SampleGenerator generator) throws IOException {
        if (Files.exists(path)) {
            return;
        }

        int sampleCount = Math.max(1, (int) Math.round(durationSeconds * SAMPLE_RATE));
        byte[] pcmBytes = new byte[sampleCount * 2];
        for (int i = 0; i < sampleCount; i++) {
            double progress = sampleCount <= 1 ? 0.0 : i / (double) (sampleCount - 1);
            double time = i / (double) SAMPLE_RATE;
            short sample = (short) Math.round(clamp(generator.sample(time, progress)) * 32767.0);
            pcmBytes[i * 2] = (byte) (sample & 0xFF);
            pcmBytes[i * 2 + 1] = (byte) ((sample >>> 8) & 0xFF);
        }

        writeWaveFile(path, pcmBytes, SAMPLE_RATE, 1, 16);
    }

    private void writeWaveFile(Path path, byte[] pcmBytes, int sampleRate, int channels, int bitsPerSample) throws IOException {
        try (OutputStream output = Files.newOutputStream(path);
             DataOutputStream stream = new DataOutputStream(new BufferedOutputStream(output))) {
            int byteRate = sampleRate * channels * bitsPerSample / 8;
            int blockAlign = channels * bitsPerSample / 8;
            int dataLength = pcmBytes.length;

            writeAscii(stream, "RIFF");
            writeLittleEndianInt(stream, 36 + dataLength);
            writeAscii(stream, "WAVE");
            writeAscii(stream, "fmt ");
            writeLittleEndianInt(stream, 16);
            writeLittleEndianShort(stream, 1);
            writeLittleEndianShort(stream, channels);
            writeLittleEndianInt(stream, sampleRate);
            writeLittleEndianInt(stream, byteRate);
            writeLittleEndianShort(stream, blockAlign);
            writeLittleEndianShort(stream, bitsPerSample);
            writeAscii(stream, "data");
            writeLittleEndianInt(stream, dataLength);
            stream.write(pcmBytes);
        }
    }

    private void writeAscii(DataOutputStream stream, String value) throws IOException {
        for (int i = 0; i < value.length(); i++) {
            stream.writeByte(value.charAt(i));
        }
    }

    private void writeLittleEndianShort(DataOutputStream stream, int value) throws IOException {
        stream.writeByte(value & 0xFF);
        stream.writeByte((value >>> 8) & 0xFF);
    }

    private void writeLittleEndianInt(DataOutputStream stream, int value) throws IOException {
        stream.writeByte(value & 0xFF);
        stream.writeByte((value >>> 8) & 0xFF);
        stream.writeByte((value >>> 16) & 0xFF);
        stream.writeByte((value >>> 24) & 0xFF);
    }

    private double envelope(double progress, double attack, double release) {
        double fadeIn = attack <= 0.0 ? 1.0 : clamp(progress / attack);
        double fadeOut = release <= 0.0 ? 1.0 : clamp((1.0 - progress) / release);
        return Math.min(fadeIn, fadeOut);
    }

    private double pseudoNoise(double seed) {
        double value = Math.sin(seed * 12.9898 + 78.233) * 43758.5453;
        return (value - Math.floor(value) - 0.5) * 2.0;
    }

    private double clamp(double value) {
        return Math.max(-1.0, Math.min(1.0, value));
    }

    private void checkAlError(String step) {
        int error = alGetError();
        if (error != AL_NO_ERROR) {
            throw new IllegalStateException("OpenAL error at " + step + ": " + error);
        }
    }

    private void checkAlcError(String step) {
        if (device == NULL) {
            return;
        }
        int error = alcGetError(device);
        if (error != ALC_NO_ERROR) {
            throw new IllegalStateException("OpenALC error at " + step + ": " + error);
        }
    }

    @FunctionalInterface
    private interface SampleGenerator {
        double sample(double time, double progress);
    }

    private static final class WavData {
        final ByteBuffer data;
        final int format;
        final int sampleRate;

        private WavData(ByteBuffer data, int format, int sampleRate) {
            this.data = data;
            this.format = format;
            this.sampleRate = sampleRate;
        }
    }
}
