package gothic2.mobile.og;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * «Сведения о лицензиях»: движок OpenGothic и его компоненты (тексты собирает задача
 * generateLicenses из исходников движка) и шрифт значка. Игровые файлы в приложение не входят.
 */
public class LicensesActivity extends Activity {
    private static final String INTRO =
        "Gothic II для Android — оболочка для запуска твоей лицензионной копии Gothic II. "
            + "Игровые файлы в приложение не входят: игра читается из папки, которую ты выбрал.\n\n"
            + "Игру исполняет OpenGothic — открытый движок, написанный заново сообществом "
            + "(github.com/Try/OpenGothic). Gothic II — товарный знак THQ Nordic; приложение с ними не связано.\n\n";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Сведения о лицензиях");
        title.setTextSize(24);
        title.setTextColor(Color.rgb(0xD9, 0xB6, 0x6A));
        title.setTypeface(Typeface.SERIF, Typeface.BOLD);
        col.addView(title);

        TextView body = new TextView(this);
        body.setTextSize(12);
        body.setTextColor(Color.rgb(0xE6, 0xDC, 0xC8));
        body.setTypeface(Typeface.MONOSPACE);
        body.setText(INTRO + asset("licenses/third_party.txt") + "==== Значок ====\n\n" + asset("licenses/OFL-1.1.txt"));
        col.addView(body);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(0x12, 0x0E, 0x0A));
        scroll.addView(col);
        setContentView(scroll);
    }

    private String asset(String name) {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (IOException e) {
            return "(нет " + name + ")\n";
        }
    }
}
