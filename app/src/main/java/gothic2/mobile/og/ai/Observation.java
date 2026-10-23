package gothic2.mobile.og.ai;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Снимок обстановки из движка (AiNpc::snapshot) — коротким текстом для модели. */
public final class Observation {
    private Observation() {
    }

    public static String describe(Map<String, Object> s) {
        if (s == null || s.isEmpty()) return "Обстановка неизвестна (игра на паузе).";
        StringBuilder sb = new StringBuilder();
        sb.append("Обстановка: день ").append(Json.num(s, "day", 1)).append(", ").append(Json.str(s, "time")).append('.');
        String place = Json.str(s, "place");
        if (!place.isEmpty()) {
            sb.append(" Ближайшее место: «").append(place).append("» (").append(m(s, "placeDist")).append(").");
        }
        String wp = Json.str(s, "waypoint");
        if (!wp.isEmpty()) sb.append(" Точка мира: ").append(wp).append('.');
        if (Json.bool(s, "talking", false)) sb.append(" Сейчас ты разговариваешь с героем.");

        Map<String, Object> me = Json.obj(s.get("self"));
        if (!me.isEmpty()) {
            sb.append("\nТы: здоровье ").append(Json.num(me, "hp", 0)).append('/').append(Json.num(me, "hpMax", 0));
            if (me.containsKey("str")) {
                sb.append(", сила ").append(Json.num(me, "str", 0)).append(", ловкость ").append(Json.num(me, "dex", 0));
            }
            sb.append(", оружие: ").append(Json.str(me, "weapon"));
            if (Json.bool(me, "inParty", false)) sb.append(", идёшь с героем (в его отряде)");
            if (Json.bool(me, "fighting", false)) sb.append(", в бою");
            if (Json.bool(me, "down", false)) sb.append(", без сознания");
            String using = Json.str(me, "using");
            if (!using.isEmpty()) sb.append(", используешь: ").append(using);
            sb.append('.');
            List<Object> items = Json.arr(me.get("items"));
            // Надетое - отдельно: модель видит, что на ней, и что можно надеть вместо (equip_item)
            StringBuilder worn = new StringBuilder();
            StringBuilder bag = new StringBuilder();
            for (Object o : items) {
                Map<String, Object> it = Json.obj(o);
                boolean on = Json.bool(it, "equipped", false);
                StringBuilder out = on ? worn : bag;
                if (out.length() > 0) out.append("; ");
                out.append(item(it, !on));
            }
            sb.append("\nНадето на тебе: ").append(worn.length() > 0 ? worn : "ничего (без доспехов и оружия)").append('.');
            if (bag.length() > 0) {
                sb.append("\nТвой инвентарь (id — для give_item, use_item, equip_item): ").append(bag).append('.');
            }
        }

        Map<String, Object> hero = Json.obj(s.get("hero"));
        if (!hero.isEmpty()) {
            sb.append("\nГерой [hero]: ").append(m(hero, "dist")).append(", ").append(Json.str(hero, "side"));
            if (!Json.bool(hero, "sees", true)) sb.append(", его не видно");
            sb.append("; уровень ").append(Json.num(hero, "level", 1))
                .append(", здоровье ").append(Json.num(hero, "hp", 0)).append('/').append(Json.num(hero, "hpMax", 0));
            String g = Json.str(hero, "guild");
            if (!g.isEmpty()) sb.append(", гильдия: ").append(g);
            String ar = Json.str(hero, "armor");
            sb.append(", одежда: ").append(ar.isEmpty() ? "простая" : ar);
            String mw = Json.str(hero, "melee");
            if (!mw.isEmpty()) sb.append(", оружие: ").append(mw);
            String rw = Json.str(hero, "ranged");
            if (!rw.isEmpty()) sb.append(", за спиной: ").append(rw);
            if (Json.bool(hero, "weaponDrawn", false)) sb.append(" (обнажено!)");
            sb.append(", твоё отношение к нему: ").append(Json.str(hero, "attitude")).append('.');
        }

        List<Object> npcs = Json.arr(s.get("npcs"));
        if (!npcs.isEmpty()) {
            sb.append("\nРядом:");
            for (Object o : npcs) {
                Map<String, Object> n = Json.obj(o);
                sb.append("\n- ").append(Json.str(n, "ref")).append(" «").append(Json.str(n, "name")).append("»");
                StringBuilder d = new StringBuilder();
                if (Json.bool(n, "monster", false)) d.append("зверь/чудовище");
                else {
                    String g = Json.str(n, "guild");
                    d.append(g.isEmpty() ? "человек" : g);
                    if (Json.bool(n, "female", false)) d.append(", женщина");
                }
                d.append(", ").append(m(n, "dist")).append(", ").append(Json.str(n, "side"));
                String st = Json.str(n, "state");
                if (!st.isEmpty()) d.append(", ").append(st);
                long hp = Json.num(n, "hp%", 100);
                if (hp < 100 && !"мёртв".equals(st)) d.append(", здоровье ").append(hp).append('%');
                if (Json.bool(n, "hostile", false)) d.append(", ВРАГ");
                String f = Json.str(n, "fights");
                if (!f.isEmpty()) d.append(", дерётся с ").append(f);
                if (!Json.bool(n, "seen", true)) d.append(", не видно");
                sb.append(" (").append(d).append(')');
            }
        } else {
            sb.append("\nРядом никого.");
        }

        List<Object> ground = Json.arr(s.get("ground"));
        if (!ground.isEmpty()) {
            sb.append("\nНа земле: ");
            int n = 0;
            for (Object o : ground) {
                Map<String, Object> g = Json.obj(o);
                if (n++ > 0) sb.append("; ");
                sb.append(Json.str(g, "ref")).append(" «").append(Json.str(g, "name")).append("» ").append(m(g, "dist"));
            }
            sb.append('.');
        }

        List<Object> mobs = Json.arr(s.get("mobs"));
        if (!mobs.isEmpty()) {
            sb.append("\nВещи мира (use_mob по схеме): ");
            int n = 0;
            for (Object o : mobs) {
                Map<String, Object> g = Json.obj(o);
                if (n++ > 0) sb.append("; ");
                String name = Json.str(g, "name");
                sb.append(Json.str(g, "scheme"));
                if (!name.isEmpty()) sb.append(" «").append(name).append('»');
                sb.append(' ').append(m(g, "dist"));
            }
            sb.append('.');
        }

        List<Object> places = Json.arr(s.get("places"));
        if (!places.isEmpty()) {
            sb.append("\nМеста (для go_to_place): ");
            int n = 0;
            for (Object o : places) {
                Map<String, Object> p = Json.obj(o);
                if (n++ > 0) sb.append("; ");
                sb.append('«').append(Json.str(p, "name")).append("» ").append(m(p, "dist"));
            }
            sb.append('.');
        }
        return sb.toString();
    }

    /** Предмет: «Имя [ID] — вид, урон 70 x2, нужно силы 120». withCount — сколько штук (надетое — одно). */
    static String item(Map<String, Object> it, boolean withCount) {
        StringBuilder sb = new StringBuilder();
        sb.append(Json.str(it, "name")).append(" [").append(Json.str(it, "id")).append(']');
        StringBuilder d = new StringBuilder();
        String kind = kindName(Json.str(it, "kind"));
        if (!kind.isEmpty()) d.append(kind);
        long dmg = Json.num(it, "dmg", 0);
        if (dmg > 0) d.append(d.length() > 0 ? ", " : "").append("урон ").append(dmg);
        if (it.containsKey("prot")) d.append(d.length() > 0 ? ", " : "").append("защита ").append(Json.num(it, "prot", 0));
        String need = Json.str(it, "need");
        if (!need.isEmpty()) d.append(d.length() > 0 ? ", " : "").append("не надеть — нужно ").append(need);
        if (d.length() > 0) sb.append(" — ").append(d);
        long c = Json.num(it, "n", 1);
        if (withCount && c > 1) sb.append(" x").append(c);
        return sb.toString();
    }

    /** Вид предмета из снимка (AiNpc::itemKind) по-русски; пусто — без пометки. */
    static String kindName(String kind) {
        switch (kind) {
            case "armor": return "доспехи/одежда";
            case "melee": return "одноручное оружие";
            case "melee2h": return "двуручное оружие";
            case "bow": return "лук";
            case "crossbow": return "арбалет";
            case "shield": return "щит";
            case "ammo": return "стрелы/болты";
            case "ring": return "кольцо";
            case "amulet": return "амулет";
            case "belt": return "пояс";
            case "potion": return "зелье";
            case "food": return "еда";
            case "rune": return "руна";
            case "scroll": return "свиток";
            case "doc": return "письмо/книга";
            default: return "";
        }
    }

    /** Расстояние в метрах: «3,5 м». */
    static String m(Map<String, Object> o, String key) {
        double d = Json.dbl(o, key, 0);
        if (d >= 10) return String.format(Locale.ROOT, "%d м", Math.round(d));
        return String.format(Locale.ROOT, "%.1f м", d).replace('.', ',');
    }
}
