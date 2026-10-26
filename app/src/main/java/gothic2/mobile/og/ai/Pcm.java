package gothic2.mobile.og.ai;

import java.io.IOException;

/** Звук реплики: 16 бит, моно. */
public final class Pcm {
    public final short[] samples;
    public final int rate;

    public Pcm(short[] samples, int rate) {
        this.samples = samples;
        this.rate = rate;
    }

    public int ms() {
        return rate <= 0 ? 0 : (int) (samples.length * 1000L / rate);
    }

    /** Сырые 16-битные отсчёты little-endian, моно. */
    public static Pcm fromRaw(byte[] b, int rate) {
        short[] s = new short[b.length / 2];
        for (int i = 0; i < s.length; i++) s[i] = (short) ((b[2 * i] & 0xFF) | (b[2 * i + 1] << 8));
        return new Pcm(s, rate);
    }

    /** WAV (RIFF, PCM 16 бит): стерео сводится в моно. */
    public static Pcm fromWav(byte[] b) throws IOException {
        if (b.length < 44 || b[0] != 'R' || b[1] != 'I' || b[2] != 'F' || b[3] != 'F') {
            throw new IOException("не WAV");
        }
        int channels = 1;
        int rate = 0;
        int bits = 16;
        int pos = 12;
        while (pos + 8 <= b.length) {
            String id = new String(b, pos, 4, java.nio.charset.StandardCharsets.US_ASCII);
            int size = le32(b, pos + 4);
            int body = pos + 8;
            if (id.equals("fmt ")) {
                if (body + 16 > b.length) break;
                int format = le16(b, body);
                channels = le16(b, body + 2);
                rate = le32(b, body + 4);
                bits = le16(b, body + 14);
                if (format != 1 && format != 0xFFFE) throw new IOException("сжатый WAV");
            } else if (id.equals("data")) {
                if (bits != 16 || rate <= 0 || channels < 1) throw new IOException("WAV не 16 бит");
                // Размер data бывает 0 или «до конца» у потоковых ответов: берём, что есть
                int len = (size <= 0 || body + size > b.length) ? b.length - body : size;
                int frames = len / (2 * channels);
                short[] s = new short[frames];
                for (int i = 0; i < frames; i++) {
                    int acc = 0;
                    for (int c = 0; c < channels; c++) {
                        int at = body + 2 * (i * channels + c);
                        acc += (short) ((b[at] & 0xFF) | (b[at + 1] << 8));
                    }
                    s[i] = (short) (acc / channels);
                }
                return new Pcm(s, rate);
            }
            if (size < 0) break;
            pos = body + size + (size & 1);
        }
        throw new IOException("в WAV нет звука");
    }

    private static int le16(byte[] b, int at) {
        return (b[at] & 0xFF) | ((b[at + 1] & 0xFF) << 8);
    }

    private static int le32(byte[] b, int at) {
        return (b[at] & 0xFF) | ((b[at + 1] & 0xFF) << 8) | ((b[at + 2] & 0xFF) << 16) | ((b[at + 3] & 0xFF) << 24);
    }
}
