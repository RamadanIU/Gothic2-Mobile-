package gothic2.mobile.og.ai;

import android.content.Context;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Голос телефона (TextToSpeech Android): без интернета и бесплатно. Реплика пишется в WAV во временный
 * файл — так известна её длина, а играет её тот же проигрыватель, что и облачные голоса. Жители звучат
 * по-разному высотой и темпом; Геральт — ниже и медленнее.
 */
final class AndroidTts implements Tts {
    private final File cache;
    private final CountDownLatch ready = new CountDownLatch(1);
    private volatile boolean ok;
    private final TextToSpeech tts;
    private final List<android.speech.tts.Voice> ruVoices = new ArrayList<>();

    AndroidTts(Context ctx) {
        cache = new File(ctx.getCacheDir(), "ai-tts");
        cache.mkdirs();
        tts = new TextToSpeech(ctx.getApplicationContext(), status -> {
            ok = status == TextToSpeech.SUCCESS;
            ready.countDown();
        });
    }

    @Override
    public Pcm synth(String text, Voice v) throws IOException {
        try {
            if (!ready.await(8, TimeUnit.SECONDS) || !ok) throw new IOException("голос телефона недоступен");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("прервано");
        }
        synchronized (this) {
            Locale ru = new Locale("ru", "RU");
            if (ruVoices.isEmpty() && tts.getVoices() != null) {
                for (android.speech.tts.Voice vc : tts.getVoices()) {
                    if (vc.getLocale() != null && "ru".equals(vc.getLocale().getLanguage()) && !vc.isNetworkConnectionRequired()) {
                        ruVoices.add(vc);
                    }
                }
            }
            if (!ruVoices.isEmpty() && !v.geralt) tts.setVoice(ruVoices.get(Math.floorMod(v.gameVoice, ruVoices.size())));
            else if (!ruVoices.isEmpty()) tts.setVoice(ruVoices.get(0));
            else tts.setLanguage(ru);
            float pitch = v.geralt ? 0.72f : (v.female ? 1.15f : 0.85f + Math.floorMod(v.gameVoice * 7, 9) * 0.04f);
            float rate = v.geralt ? 0.92f : 0.95f + Math.floorMod(v.gameVoice * 3, 5) * 0.03f;
            tts.setPitch(pitch);
            tts.setSpeechRate(rate);

            File out = new File(cache, "line.wav");
            out.delete();
            CountDownLatch done = new CountDownLatch(1);
            final boolean[] failed = {false};
            String uid = "g2m" + System.nanoTime();
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String id) {
                }

                @Override
                public void onDone(String id) {
                    done.countDown();
                }

                @Override
                @Deprecated
                public void onError(String id) {
                    failed[0] = true;
                    done.countDown();
                }

                @Override
                public void onError(String id, int code) {
                    failed[0] = true;
                    done.countDown();
                }
            });
            if (tts.synthesizeToFile(text, new Bundle(), out, uid) != TextToSpeech.SUCCESS) {
                throw new IOException("голос телефона не принял текст");
            }
            try {
                if (!done.await(30, TimeUnit.SECONDS) || failed[0]) throw new IOException("голос телефона не ответил");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("прервано");
            }
            byte[] b = new byte[(int) out.length()];
            try (InputStream in = new FileInputStream(out)) {
                int off = 0;
                int n;
                while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            }
            return Pcm.fromWav(b);
        }
    }
}
