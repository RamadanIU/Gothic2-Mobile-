package gothic2.mobile.og.ai;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.HandlerThread;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Голоса реплик: звук готов заранее (put), играет, когда движок начал реплику (line_start) — в диалоге
 * по очереди с героем, вне его — сразу; громкость — по расстоянию до героя. Пропуск реплики касанием
 * обрывает звук.
 */
final class VoicePlayer {
    private final Map<String, Pcm> ready = new ConcurrentHashMap<>();
    private final Map<String, AudioTrack> playing = new ConcurrentHashMap<>();
    private final Handler handler;

    VoicePlayer() {
        HandlerThread t = new HandlerThread("ai-voice");
        t.start();
        handler = new Handler(t.getLooper());
    }

    void put(String id, Pcm pcm) {
        if (pcm != null && pcm.samples.length > 0) ready.put(id, pcm);
    }

    void start(String id, float vol) {
        handler.post(() -> {
            Pcm pcm = ready.remove(id);
            if (pcm == null || vol <= 0.01f) return;
            try {
                int bytes = pcm.samples.length * 2;
                AudioTrack tr = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                    .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(pcm.rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(bytes)
                    .build();
                tr.write(pcm.samples, 0, pcm.samples.length);
                tr.setVolume(Math.min(1f, vol));
                tr.play();
                playing.put(id, tr);
                // Освободить после конца, даже если движок не сообщит
                handler.postDelayed(() -> stop(id), pcm.ms() + 1500L);
            } catch (RuntimeException e) {
                // нет звукового устройства - реплика останется субтитром
            }
        });
    }

    /** Реплика кончилась или пропущена. */
    void end(String id, boolean cut) {
        handler.post(() -> {
            ready.remove(id);
            if (cut) stop(id);
        });
    }

    void stopAll() {
        handler.post(() -> {
            ready.clear();
            for (String id : playing.keySet()) stop(id);
        });
    }

    private void stop(String id) {
        AudioTrack tr = playing.remove(id);
        if (tr == null) return;
        try {
            tr.stop();
        } catch (IllegalStateException ignored) {
            // уже остановлен
        }
        tr.release();
    }
}
