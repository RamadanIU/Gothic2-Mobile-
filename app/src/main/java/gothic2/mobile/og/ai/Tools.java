package gothic2.mobile.og.ai;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Инструменты модели: что Геральт и живые жители умеют делать в мире (набор один на всех; кто делает — поле who
 * команды, GeraltBrain). Большая часть — действия движка (AiNpc::cmdAct),
 * talk_to_npc и remember ведёт сам мозг (GeraltBrain).
 */
public final class Tools {
    private Tools() {
    }

    /** Долгие действия: ход тела, о конце которого движок сообщит позже (act_done). */
    static final List<String> LONG = Arrays.asList("go_to_place", "go_to_npc", "attack", "take_item", "use_mob", "loot");

    static final String[] GESTURES = {"nod", "no", "shrug", "come_here", "greet", "dismiss", "get_lost", "salute",
        "look_around", "cheer", "angry"};
    static final String[] ACTIVITIES = {"stand", "guard", "sit", "sit_chair", "sit_bench", "practice_sword", "eat", "drink",
        "look_around", "smoke"};

    public static List<Map<String, Object>> all() {
        List<Map<String, Object>> t = new ArrayList<>();
        t.add(tool("go_to_place", "Пойти к месту острова. place — название из списка «Места» в обстановке.",
            props("place", str("название места")), "place"));
        t.add(tool("go_to_npc", "Подойти к человеку или зверю из списка «Рядом».",
            props("target", str("ref, например n12, или hero")), "target"));
        t.add(tool("follow_hero", "Пойти с героем: идёшь за ним, защищаешь его в бою (вступаешь в его отряд).",
            props()));
        t.add(tool("wait_here", "Остаться на месте / перестать идти за героем.", props()));
        t.add(tool("go_home", "Вернуться к своим делам, на своё место (Геральт — за стол в кабаке Корагона).", props()));
        t.add(tool("attack", "Напасть: на чудовище — насмерть, на человека — сбить с ног. target — ref из «Рядом» или hero.",
            props("target", str("ref")), "target"));
        t.add(tool("stop_fight", "Прекратить бой и убрать оружие.", props()));
        t.add(tool("weapon", "Обнажить (draw=true) или убрать (draw=false) оружие.",
            props("draw", Json.of("type", "boolean")), "draw"));
        t.add(tool("give_item", "Отдать свой предмет герою или другому NPC. item — id из твоего инвентаря (ITMI_GOLD — золото).",
            props("item", str("id предмета"), "count", Json.of("type", "integer", "description", "сколько"),
                "target", str("кому: hero (по умолчанию) или ref")), "item"));
        t.add(tool("take_item", "Подобрать предмет с земли (ref из «На земле»).",
            props("item", str("ref, например i7")), "item"));
        t.add(tool("use_item", "Выпить зелье, съесть или использовать свой предмет (id из инвентаря).",
            props("item", str("id предмета")), "item"));
        t.add(tool("equip_item", "Надеть свою одежду или доспехи, взять оружие (меч, топор, лук, арбалет, щит), надеть кольцо, амулет, пояс. "
                + "item — id из инвентаря; прежнее того же вида снимается само. Смена меча — в любой момент, даже с мечом в руке.",
            props("item", str("id предмета")), "item"));
        t.add(tool("unequip_item", "Снять надетое (одежду, оружие, кольцо и т. п.). item — id из инвентаря.",
            props("item", str("id предмета")), "item"));
        t.add(tool("gesture", "Жест: кивнуть, покачать головой, пожать плечами, подозвать, поприветствовать, отмахнуться, прогнать, отдать честь, оглядеться, обрадоваться, возмутиться.",
            props("name", en(GESTURES)), "name"));
        t.add(tool("look_at", "Повернуться и посмотреть на кого-то.", props("target", str("ref или hero")), "target"));
        t.add(tool("activity", "Заняться делом на месте: стоять, сторожить, сесть на землю, сесть на стул (за стол), сесть на скамью, упражняться с мечом, есть, пить, осматриваться, курить.",
            props("name", en(ACTIVITIES)), "name"));
        t.add(tool("use_mob", "Воспользоваться вещью мира рядом: сесть на скамью, к костру, открыть сундук и т. п. scheme — из «Вещи мира».",
            props("scheme", str("например BENCH, CHESTBIG, CAULDRON")), "scheme"));
        t.add(tool("loot", "Обыскать мёртвого или лежащего без сознания (ref из «Рядом»).",
            props("target", str("ref")), "target"));
        t.add(tool("set_attitude", "Изменить отношение к герою. hostile — он враг, ты нападёшь, если увидишь повод.",
            props("value", en(new String[]{"friendly", "neutral", "angry", "hostile"})), "value"));
        // Закончить разговор с героем модели нельзя: она закрывала диалог сразу после своей реплики, и разговор не
        // складывался. Диалог закрывает герой («Пока.»)
        t.add(tool("talk_to_npc", "Подойти к человеку и сказать ему фразу; он ответит. Беседу можно продолжать: снова "
                + "talk_to_npc с тем же ref — он помнит, о чём вы говорили. Для расспросов местных.",
            props("target", str("ref человека из «Рядом»"), "text", str("что сказать")), "target", "text"));
        t.add(tool("remember", "Запомнить важное надолго: уговор, имя, долг, обещание.",
            props("note", str("коротко, от себя")), "note"));
        return t;
    }

    private static Map<String, Object> tool(String name, String desc, Map<String, Object> props, String... required) {
        List<Object> req = new ArrayList<>(Arrays.asList((Object[]) required));
        Map<String, Object> params = Json.of("type", "object", "properties", props);
        if (!req.isEmpty()) params.put("required", req);
        return Json.of("type", "function", "function", Json.of("name", name, "description", desc, "parameters", params));
    }

    private static Map<String, Object> props(Object... kv) {
        return Json.of(kv);
    }

    private static Map<String, Object> str(String desc) {
        return Json.of("type", "string", "description", desc);
    }

    private static Map<String, Object> en(String[] values) {
        return Json.of("type", "string", "enum", new ArrayList<Object>(Arrays.asList(values)));
    }

    /** Действие движка для инструмента: имя и аргументы (null — инструмент не для движка). */
    static Map<String, Object> engineAct(String id, String tool, Map<String, Object> args) {
        String name = tool;
        Map<String, Object> a = Json.of();
        switch (tool) {
            case "go_to_place":
                a.put("place", Json.str(args, "place"));
                break;
            case "go_to_npc":
            case "attack":
            case "look_at":
            case "loot":
                a.put("target", Json.str(args, "target"));
                if (tool.equals("look_at")) name = "turn_to";
                break;
            case "follow_hero":
            case "wait_here":
            case "go_home":
            case "stop_fight":
                break;
            case "weapon":
                name = Json.bool(args, "draw", true) ? "draw_weapon" : "holster_weapon";
                break;
            case "give_item":
                a.put("item", Json.str(args, "item"));
                a.put("count", Math.max(1, Json.num(args, "count", 1)));
                String to = Json.str(args, "target");
                if (!to.isEmpty()) a.put("target", to);
                break;
            case "take_item":
            case "use_item":
                a.put("item", Json.str(args, "item"));
                break;
            case "equip_item":
            case "unequip_item":
                a.put("item", Json.str(args, "item"));
                name = tool.equals("equip_item") ? "equip" : "unequip";
                break;
            case "gesture":
            case "activity":
                a.put("name", Json.str(args, "name"));
                break;
            case "use_mob":
                a.put("scheme", Json.str(args, "scheme"));
                break;
            case "set_attitude":
                a.put("value", Json.str(args, "value"));
                break;
            default:
                return null;
        }
        return Json.of("cmd", "act", "id", id, "name", name, "args", a);
    }
}
