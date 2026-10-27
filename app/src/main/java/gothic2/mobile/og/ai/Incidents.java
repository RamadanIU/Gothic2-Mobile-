package gothic2.mobile.og.ai;

import java.util.Map;

/**
 * Происшествия с людьми рядом с героем (движок, событие incident): человека сбили с ног (down), он очнулся (up) или
 * погиб (dead). Тексты для памяти — тем, кто это видел, и самому пострадавшему, — и повод для хода очнувшегося:
 * кто его избил, чем, сколько он пролежал. victim и by — из события (ref, id, name, female; у by — hero).
 */
final class Incidents {
    private Incidents() {
    }

    /** Кто бил и чем: «герой, кулаками», «Мика [n4], оружием «Палаш»». */
    static String how(Map<String, Object> by, String weapon) {
        String ref = Json.str(by, "ref");
        String who = Json.bool(by, "hero", false) || "hero".equals(ref) ? "герой"
            : "self".equals(ref) ? Persona.NAME : Json.str(by, "name");
        if (who.isEmpty()) who = "кто-то";
        return weapon.isEmpty() ? who : who + ", " + weapon;
    }

    /** Что видели свидетели: «Мика сбит с ног (бил: герой, кулаками) — без сознания.»; пусто — не происшествие. */
    static String seen(String kind, Map<String, Object> victim, Map<String, Object> by, String weapon, long lay) {
        String who = Hearing.who(victim, true);
        boolean f = Json.bool(victim, "female", false);
        String a = f ? "а" : "";
        switch (kind) {
            case "down":
                return who + " сбит" + a + " с ног (бил: " + how(by, weapon) + ") — без сознания.";
            case "up":
                return who + " очнул" + (f ? "ась" : "ся") + " и встал" + a + " на ноги"
                    + (lay > 0 ? " (пролежал" + a + " без сознания " + lay + " с)" : "") + ".";
            case "dead":
                return who + " убит" + a + " (бил: " + how(by, weapon) + ").";
            default:
                return "";
        }
    }

    /** Что запомнит сам пострадавший житель: что его избили и кто; пусто — нечего. */
    static String victimNote(String kind, Map<String, Object> victim, Map<String, Object> by, String weapon) {
        if (!"down".equals(kind)) return "";
        boolean f = Json.bool(victim, "female", false);
        return "Меня сбили с ног (бил: " + how(by, weapon) + "), я был" + (f ? "а" : "") + " без сознания.";
    }

    /** Повод для хода очнувшегося жителя: что с ним случилось и чего от него ждут. */
    static String wokeUp(Map<String, Object> victim, Map<String, Object> by, String weapon, long lay) {
        boolean f = Json.bool(victim, "female", false);
        String a = f ? "а" : "";
        boolean hero = Json.bool(by, "hero", false) || "hero".equals(Json.str(by, "ref"));
        return "Ты только что " + (f ? "пришла" : "пришёл") + " в себя: тебя сбили с ног (бил: " + how(by, weapon) + ")"
            + (lay > 0 ? ", ты пролежал" + a + " без сознания " + lay + " с" : "") + ". Голова гудит, но ты уже на ногах. "
            + "Кто вокруг и что с ними — в «Обстановке»" + (hero ? "; герой — тот, кто тебя избил" : "") + ". Реши по "
            + "своему нраву и по тому, кто тебя избил, как ответить: скажи вслух, что думаешь (обидчику или тем, кто "
            + "рядом), и/или сделай что-то — пожалуйся страже или своим (talk_to_npc), дай сдачи (attack), уйди (go_home). "
            + "Можно и промолчать.";
    }
}
