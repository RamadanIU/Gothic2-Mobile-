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
        String time = Json.str(s, "time");
        sb.append("Обстановка: день ").append(Json.num(s, "day", 1)).append(", ").append(time);
        String part = partOfDay(time);
        if (!part.isEmpty()) sb.append(" (").append(part).append(')');
        sb.append('.');
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
            if (Json.bool(me, "waiting", false)) sb.append(", ждёшь героя здесь (ты в его отряде)");
            else if (Json.bool(me, "inParty", false)) sb.append(", идёшь с героем (в его отряде)");
            if (Json.bool(me, "away", false)) sb.append(", ты ушёл от своих дел (go_home — вернуться)");
            if (Json.bool(me, "fighting", false)) sb.append(", в бою");
            if (Json.bool(me, "down", false)) sb.append(", без сознания");
            if (isSleep(Json.str(me, "zs"))) sb.append(", ты спал(а) — тебя разбудили");
            String using = Json.str(me, "using");
            if (!using.isEmpty()) sb.append(", используешь: ").append(using);
            sb.append('.');
            String inc = incident(Json.obj(me.get("inc")), Json.bool(me, "female", false), true);
            if (!inc.isEmpty()) sb.append("\n").append(inc);
            String known = heroHistory(me);
            if (!known.isEmpty()) sb.append("\n").append(known);
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
            String hc = Json.str(hero, "cond");
            if ("down".equals(hc)) sb.append(", БЕЗ СОЗНАНИЯ (лежит, дышит, скоро очнётся)");
            else if ("dead".equals(hc)) sb.append(", МЁРТВ");
            String hu = Json.str(hero, "using");
            if (!hu.isEmpty()) sb.append(", использует: ").append(hu);
            if (Json.bool(hero, "swim", false)) sb.append(", плывёт");
            if (Json.bool(hero, "sneak", false)) sb.append(", крадётся");
            if (Json.bool(hero, "torch", false)) sb.append(", с факелом");
            sb.append(", твоё отношение к нему: ").append(Json.str(hero, "attitude")).append('.');
            String hi = incident(Json.obj(hero.get("inc")), false, false);
            if (!hi.isEmpty()) sb.append(" Герой: ").append(hi).append('.');
        }

        List<Object> npcs = Json.arr(s.get("npcs"));
        if (!npcs.isEmpty()) {
            sb.append("\nРядом (состояние — то, что видно сейчас; оно важнее прежних записей памяти):");
            for (Object o : npcs) {
                Map<String, Object> n = Json.obj(o);
                sb.append("\n- ").append(Json.str(n, "ref")).append(" «").append(Json.str(n, "name")).append("»");
                StringBuilder d = new StringBuilder();
                boolean female = Json.bool(n, "female", false);
                if (Json.bool(n, "monster", false)) d.append("зверь/чудовище");
                else {
                    String g = Json.str(n, "guild");
                    d.append(g.isEmpty() ? "человек" : g);
                    if (female) d.append(", женщина");
                }
                d.append(", ").append(m(n, "dist")).append(", ").append(Json.str(n, "side"));
                d.append(state(n, female));
                if (Json.bool(n, "hostile", false)) d.append(", ВРАГ");
                String f = Json.str(n, "fights");
                if (!f.isEmpty()) d.append(", дерётся с ").append(f);
                if (!Json.bool(n, "seen", true)) d.append(", не видно");
                if (Json.bool(n, "party", false)) d.append(", в отряде героя");
                // Как выглядит: по одежде и оружию видно, кто перед тобой
                if (!Json.bool(n, "monster", false)) {
                    String looks = looks(n);
                    if (!looks.isEmpty()) d.append("; ").append(looks);
                }
                String att = Json.str(n, "att");
                if (!att.isEmpty() && !"нейтрально".equals(att)) d.append("; к тебе: ").append(att);
                String inc = incident(Json.obj(n.get("inc")), female, false);
                if (!inc.isEmpty()) d.append("; ").append(inc);
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

    /**
     * Состояние человека или зверя в «Рядом»: «, МЁРТВ», «, без сознания (лежит, дышит, скоро очнётся)», «, сидит на
     * скамье», «, ранен (здоровье 3%), но в сознании и на ногах». cond из движка (up/down/dead) — явно: по одной полоске
     * здоровья модели путали очнувшегося после драки (здоровья мало, а он уже стоит) с лежащим без сознания или мёртвым.
     */
    static String state(Map<String, Object> n, boolean female) {
        String cond = Json.str(n, "cond");
        String st = Json.str(n, "state");
        long hp = Json.num(n, "hp%", 100);
        if ("dead".equals(cond) || (cond.isEmpty() && "мёртв".equals(st))) return female ? ", МЕРТВА" : ", МЁРТВ";
        if ("down".equals(cond) || (cond.isEmpty() && "без сознания".equals(st))) {
            return ", БЕЗ СОЗНАНИЯ (лежит, дышит, скоро очнётся)";
        }
        StringBuilder d = new StringBuilder();
        String act = activity(Json.str(n, "zs"));
        if (!act.isEmpty() && (st.isEmpty() || "сидит".equals(st) || "лежит".equals(st) || "разговаривает".equals(st))) {
            st = act;
        } else if (!act.isEmpty() && !act.equals(st)) {
            st = st + ", " + act;
        }
        if (!st.isEmpty()) d.append(", ").append(st);
        if (hp < 100) {
            d.append(female ? ", ранена" : ", ранен").append(" (здоровье ").append(hp).append("%)");
            if ("up".equals(cond)) d.append(", но в сознании и на ногах");
        } else if ("up".equals(cond) && !Json.obj(n.get("inc")).isEmpty()) {
            d.append(", в сознании и на ногах");
        }
        return d.toString();
    }

    /** Время суток по часам «ЧЧ:ММ»: ночью люди спят, вечером — в кабаке; пусто — время неизвестно. */
    static String partOfDay(String time) {
        int h;
        try {
            h = Integer.parseInt(time.substring(0, time.indexOf(':')));
        } catch (RuntimeException e) {
            return "";
        }
        if (h >= 22 || h < 5) return "ночь";
        if (h < 11) return "утро";
        if (h < 17) return "день";
        return "вечер";
    }

    /** Занятия распорядка игры (ZS_… из скриптов) — по началу имени; первое совпадение. */
    private static final String[][] ACTIVITIES = {
        {"ZS_MAGICSLEEP", "спит (заколдован)"},
        {"ZS_SLEEP", "спит"},
        {"ZS_MAGICFREEZE", "заморожен магией"},
        {"ZS_SIT_CAMPFIRE", "сидит у костра"},
        {"ZS_SIT_BENCH", "сидит на скамье"},
        {"ZS_SIT_CHAIR", "сидит на стуле"},
        {"ZS_SIT_THRONE", "сидит на троне"},
        {"ZS_SMALLTALK", "болтает с кем-то"},
        {"ZS_STAND_GUARDING", "стоит на страже"},
        {"ZS_GUARD_PASSAGE", "охраняет проход"},
        {"ZS_STAND_EATING", "ест"},
        {"ZS_STAND_DRINKING", "пьёт"},
        {"ZS_PRACTICE_SWORD", "упражняется с мечом"},
        {"ZS_PRACTICE_MAGIC", "упражняется в магии"},
        {"ZS_SMITH", "работает в кузнице"},
        {"ZS_COOK", "готовит еду"},
        {"ZS_ROAST", "жарит мясо"},
        {"ZS_STOMP_HERB", "толчёт травы"},
        {"ZS_POTION_ALCHEMY", "варит зелья"},
        {"ZS_PICK", "работает в поле"},
        {"ZS_SAW", "пилит"},
        {"ZS_SWEEP", "подметает"},
        {"ZS_PRAY", "молится"},
        {"ZS_PREACH", "проповедует"},
        {"ZS_READ", "читает"},
        {"ZS_SMOKE", "курит"},
        {"ZS_DANCE", "танцует"},
        {"ZS_PLAY_LUTE", "играет на лютне"},
        {"ZS_WASH", "умывается"},
        {"ZS_PEE", "справляет нужду"},
        {"ZS_FLEE", "убегает"},
        {"ZS_WATCHFIGHT", "смотрит на драку"},
        {"ZS_HEALSELF", "лечится"},
        {"ZS_RANSACK", "обыскивает тело"},
        {"ZS_OBSERVEPLAYER", "следит за героем"},
        {"ZS_OBSERVESUSPECT", "следит за героем"},
        {"ZS_CLEARROOM", "выгоняет чужака из дома"},
        {"ZS_FOLLOW_PLAYER", "идёт за героем"},
        {"ZS_GUIDE_PLAYER", "ведёт героя"},
        {"ZS_WALKAROUND", "бродит"},
        {"ZS_TALK", "разговаривает с героем"},
    };

    /** Занятие по состоянию распорядка (ZS_SLEEP → «спит»); пусто — обычное (стоит, идёт) или неизвестное. */
    static String activity(String zs) {
        if (zs.isEmpty()) return "";
        String z = zs.toUpperCase(Locale.ROOT);
        for (String[] a : ACTIVITIES) if (z.startsWith(a[0])) return a[1];
        return "";
    }

    static boolean isSleep(String zs) {
        String z = zs.toUpperCase(Locale.ROOT);
        return z.startsWith("ZS_SLEEP") || z.startsWith("ZS_MAGICSLEEP");
    }

    /**
     * Происшествие (inc из движка): кто бил, чем, когда; сбит ли с ног, очнулся ли. self — о тебе самом («Недавно тебя
     * сбили с ног: …»), иначе — о другом («сбит с ног 2 мин назад (бил: герой, кулаками, 3 удара); пролежал без
     * сознания 25 с, очнулся 1 мин назад»). Пусто — ничего не было.
     */
    static String incident(Map<String, Object> inc, boolean female, boolean self) {
        if (inc.isEmpty()) return "";
        String by = Json.str(inc, "by");
        String who = Json.bool(inc, "byHero", false) || "hero".equals(by) ? "герой"
            : "self".equals(by) ? "ты" : Json.str(inc, "byName");
        if (who.isEmpty()) who = "кто-то";
        if (!by.isEmpty() && !"hero".equals(by) && !"self".equals(by)) who = who + " [" + by + "]";
        StringBuilder how = new StringBuilder(who);
        String weapon = Json.str(inc, "weapon");
        if (!weapon.isEmpty()) how.append(", ").append(weapon);
        long hits = Json.num(inc, "hits", 0);
        if (hits > 1) how.append(", ").append(hits).append(' ').append(plural(hits, "удар", "удара", "ударов"));
        String a = female ? "а" : "";
        boolean knocked = inc.containsKey("downAgo");
        boolean up = inc.containsKey("upAgo");
        StringBuilder sb = new StringBuilder();
        if (inc.containsKey("deadAgo")) {
            sb.append(self ? "Тебя убили" : (female ? "убита " : "убит ") + ago(Json.num(inc, "deadAgo", 0)));
            sb.append(" (бил: ").append(how).append(')');
            return sb.toString();
        }
        if (self) {
            if (knocked) {
                sb.append("Недавно тебя сбили с ног (").append(ago(Json.num(inc, "downAgo", 0))).append("): бил ")
                    .append(how).append('.');
                if (up) {
                    sb.append(" Ты пролежал").append(a).append(" без сознания ").append(Json.num(inc, "lay", 0))
                        .append(" с и очнул").append(female ? "ась " : "ся ").append(ago(Json.num(inc, "upAgo", 0)))
                        .append(" — сейчас ты на ногах.");
                } else {
                    sb.append(" Ты без сознания.");
                }
            } else {
                sb.append("Недавно тебя били (").append(ago(Json.num(inc, "ago", 0))).append("): ").append(how).append('.');
            }
            return sb.toString();
        }
        if (knocked) {
            sb.append(up ? "был" + a + " сбит" + a + " с ног " : "сбит" + a + " с ног ").append(ago(Json.num(inc, "downAgo", 0)))
                .append(" (бил: ").append(how).append(')');
            if (up) {
                sb.append(", пролежал").append(a).append(" без сознания ").append(Json.num(inc, "lay", 0)).append(" с, очнул")
                    .append(female ? "ась " : "ся ").append(ago(Json.num(inc, "upAgo", 0)));
            }
        } else {
            sb.append("его недавно били (").append(ago(Json.num(inc, "ago", 0))).append("): ").append(how);
            if (female) sb.replace(0, 3, "её");
        }
        return sb.toString();
    }

    /**
     * Что NPC помнит о герое по скриптам игры: последняя драка с ним, его преступление, которое NPC видел и ещё не
     * простил, говорили ли они в диалоге игры. Пусто — ничего.
     */
    static String heroHistory(Map<String, Object> me) {
        StringBuilder sb = new StringBuilder();
        switch ((int) Json.num(me, "fightHero", 0)) {
            case 1: sb.append("В последней драке с героем ты проиграл — он тебя побил. "); break;
            case 2: sb.append("В последней драке с героем ты его побил. "); break;
            case 3: sb.append("Твоя последняя драка с героем прервалась. "); break;
            default: break;
        }
        String[] crimes = {"", "убил овцу", "напал на человека", "украл", "убил человека"};
        int crime = (int) Json.num(me, "crime", 0);
        if (crime > 0 && crime < crimes.length) {
            sb.append("Ты видел, как герой ").append(crimes[crime]).append(", — это ещё не прощено (штраф не уплачен). ");
        }
        if (me.containsKey("talked")) {
            sb.append(Json.bool(me, "talked", false) ? "Ты уже разговаривал с героем раньше. "
                : "Раньше ты с героем не разговаривал (по памяти игры). ");
        }
        String r = sb.toString().trim();
        return r.isEmpty() ? "" : "Что ты знаешь о герое по прошлому: " + r;
    }

    /** «40 с назад», «3 мин назад», «давно». */
    static String ago(long sec) {
        if (sec < 60) return sec + " с назад";
        if (sec < 3600) return (sec / 60) + " мин назад";
        return "давно";
    }

    static String plural(long n, String one, String few, String many) {
        long m10 = n % 10;
        long m100 = n % 100;
        if (m10 == 1 && m100 != 11) return one;
        if (m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14)) return few;
        return many;
    }

    /** Одежда и оружие человека: «одежда: …, оружие: … (обнажено), лук: …, уровень 5»; пусто — ничего не видно. */
    static String looks(Map<String, Object> p) {
        StringBuilder d = new StringBuilder();
        String ar = Json.str(p, "armor");
        d.append("одежда: ").append(ar.isEmpty() ? "простая" : ar);
        String mw = Json.str(p, "melee");
        if (!mw.isEmpty()) d.append(", оружие: ").append(mw);
        String rw = Json.str(p, "ranged");
        if (!rw.isEmpty()) d.append(", ").append(mw.isEmpty() ? "оружие: " : "за спиной: ").append(rw);
        if (Json.bool(p, "drawn", false) || Json.bool(p, "weaponDrawn", false)) d.append(" (оружие обнажено!)");
        long lv = Json.num(p, "level", 0);
        if (lv > 0) d.append(", уровень ").append(lv);
        return d.toString();
    }

    /**
     * Кто говорит с тобой — крупно, чтобы модель отвечала с оглядкой на него: имя, гильдия (бандит, горожанин,
     * ополченец…), пол, одежда, оружие, здоровье, твоё отношение, в отряде ли героя, что о нём известно в округе.
     * p — описание из движка (describe) или блок героя из снимка; known — его известность (NpcLore.reputation).
     */
    public static String speaker(Map<String, Object> p, String name, String known) {
        StringBuilder sb = new StringBuilder();
        sb.append(name.isEmpty() ? Json.str(p, "name") : name);
        String g = Json.str(p, "guild");
        sb.append(" — ").append(g.isEmpty() ? "без гильдии" : "гильдия: " + g);
        sb.append(Json.bool(p, "female", false) ? ", женщина" : ", мужчина");
        sb.append("; ").append(looks(p)).append('.');
        long hp = p.containsKey("hp%") ? Json.num(p, "hp%", 100)
            : Json.num(p, "hpMax", 0) > 0 ? Json.num(p, "hp", 0) * 100 / Json.num(p, "hpMax", 1) : 100;
        if (hp < 100) {
            sb.append(" Ранен: здоровье ").append(hp).append('%');
            if ("up".equals(Json.str(p, "cond"))) sb.append(", но в сознании и на ногах");
            sb.append('.');
        }
        String inc = incident(Json.obj(p.get("inc")), Json.bool(p, "female", false), false);
        if (!inc.isEmpty()) sb.append(" Недавно ").append(inc).append('.');
        if (Json.bool(p, "party", false)) sb.append(" Идёт с героем (в его отряде).");
        String att = Json.str(p, "myAtt");
        if (att.isEmpty()) att = Json.str(p, "attitude");
        if (!att.isEmpty()) sb.append(" Ты относишься к нему: ").append(att).append('.');
        if (!known.isEmpty()) sb.append(" Что о нём известно в округе: ").append(known);
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
