package restudio.resync.dialog;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import restudio.flow.data.FlowConnection;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.resync.customization.ReSyncJsonResourceStorage;
import restudio.resync.flow.CustomEventManager;
import restudio.resync.flow.FlowExecutor;
import restudio.resync.flow.FlowPredicateSupport;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.FunctionCallSupport;
import restudio.resync.resources.ReSyncResourceCatalog;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class DialogService {
    private final JavaPlugin plugin;
    private final ReSyncJsonResourceStorage storage;
    private final FlowStorage flowStorage;
    private final FlowExecutor flowExecutor;
    private final DialogApi api;
    private String lastError = "";

    public DialogService(JavaPlugin plugin, ReSyncJsonResourceStorage storage, FlowStorage flowStorage, FlowExecutor flowExecutor) {
        this.plugin = plugin;
        this.storage = storage;
        this.flowStorage = flowStorage;
        this.flowExecutor = flowExecutor;
        this.api = DialogApi.load();
    }

    public boolean supported() {
        return api.supported();
    }

    public String lastError() {
        return lastError;
    }

    public boolean show(Player player, String dialogId) {
        lastError = "";
        if (player == null || dialogId == null || dialogId.isBlank() || storage == null || !supported()) {
            lastError = !supported() ? "Paper Dialog API Unavailable" : "Dialog Context Missing";
            return false;
        }
        JsonObject dialog = storage.get(ReSyncResourceCatalog.DIALOG, dialogId);
        if (dialog == null || !bool(dialog, "enabled", true)) {
            lastError = dialog == null ? "Dialog Not Found" : "Dialog Disabled";
            return false;
        }
        try {
            Object paperDialog = buildDialog(player, dialogId, dialog);
            Method show = showDialogMethod(player, paperDialog);
            if (show == null) {
                lastError = "Player Show Method Missing";
                return false;
            }
            show.setAccessible(true);
            show.invoke(player, paperDialog);
            return true;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            lastError = exception.getClass().getSimpleName() + ": " + (exception.getMessage() == null ? "Dialog Build Failed" : exception.getMessage());
            return false;
        }
    }

    private Object buildDialog(Player player, String dialogId, JsonObject dialog) throws ReflectiveOperationException {
        Object base = api.dialogBaseCreate.invoke(null,
            Component.text(text(dialog, "title", text(dialog, "displayName", dialogId))),
            externalTitle(dialog),
            bool(dialog, "can_close_with_escape", true),
            bool(dialog, "pause", true),
            afterAction(text(dialog, "after_action", "close")),
            body(dialog),
            inputs(dialog)
        );
        Object type = dialogType(player, dialogId, dialog);
        return api.dialogCreate.invoke(null, (java.util.function.Consumer<Object>) builderFactory -> {
            try {
                Object builder = invoke(builderFactory, "empty");
                invoke(builder, "base", base);
                invoke(builder, "type", type);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException(exception);
            }
        });
    }

    private Component externalTitle(JsonObject dialog) {
        String title = text(dialog, "external_title", text(dialog, "displayName", ""));
        return title.isBlank() ? null : Component.text(title);
    }

    private Object afterAction(String value) {
        String name = switch (value == null ? "" : value.toLowerCase(Locale.ROOT)) {
            case "none" -> "NONE";
            case "wait", "wait_for_response" -> "WAIT_FOR_RESPONSE";
            default -> "CLOSE";
        };
        return Enum.valueOf(api.afterActionClass.asSubclass(Enum.class), name);
    }

    private List<Object> body(JsonObject dialog) throws ReflectiveOperationException {
        List<Object> values = new ArrayList<>();
        for (JsonObject block : objectArray(dialog, "body")) {
            String type = text(block, "type", "minecraft:plain_message");
            if ("minecraft:item".equals(type)) {
                values.add(itemBody(block));
            } else {
                values.add(plainBody(text(block, "contents", text(block, "text", "")), integer(block, "width", 200)));
            }
        }
        if (values.isEmpty()) {
            values.add(plainBody(text(dialog, "title", text(dialog, "displayName", "")), 200));
        }
        return values;
    }

    private Object itemBody(JsonObject block) throws ReflectiveOperationException {
        String materialId = text(block, "material", text(block, "item", "minecraft:stone"));
        Material material = Material.matchMaterial(materialId);
        if (material == null && materialId.startsWith("minecraft:")) {
            material = Material.matchMaterial(materialId.substring("minecraft:".length()));
        }
        ItemStack item = new ItemStack(material != null && material.isItem() ? material : Material.STONE, Math.max(1, integer(block, "count", 1)));
        String description = text(block, "description", "");
        Object descriptionBody = description.isBlank() ? null : plainBody(description, integer(block, "description_width", integer(block, "width", 200)));
        return api.dialogBodyItem.invoke(null,
            item,
            descriptionBody,
            bool(block, "show_decorations", bool(block, "decorations", true)),
            bool(block, "show_tooltip", bool(block, "tooltip", true)),
            Math.clamp(integer(block, "width", 32), 1, 256),
            Math.clamp(integer(block, "height", 32), 1, 256)
        );
    }

    private Object plainBody(String text, int width) throws ReflectiveOperationException {
        return api.dialogBodyPlain.invoke(null, Component.text(text == null ? "" : text), Math.clamp(width, 1, 1024));
    }

    private List<Object> inputs(JsonObject dialog) throws ReflectiveOperationException {
        List<Object> values = new ArrayList<>();
        for (JsonObject input : objectArray(dialog, "inputs")) {
            String type = text(input, "type", "minecraft:text");
            String key = text(input, "key", "input_" + values.size());
            Component label = Component.text(text(input, "label", key));
            int width = Math.clamp(integer(input, "width", 200), 1, 1024);
            switch (type) {
                case "minecraft:boolean" -> values.add(api.inputBool.invoke(null, key, label, bool(input, "initial", false), text(input, "on_true", "true"), text(input, "on_false", "false")));
                case "minecraft:number_range" -> values.add(api.inputNumber.invoke(null, key, width, label, text(input, "label_format", "%s: %s"), (float) decimal(input, "min", 0), (float) decimal(input, "max", 100), (float) decimal(input, "initial", decimal(input, "min", 0)), (float) decimal(input, "step", 1)));
                case "minecraft:single_option" -> values.add(api.inputSingle.invoke(null, key, width, optionEntries(input), label, bool(input, "label_visible", true)));
                default -> values.add(api.inputText.invoke(null, key, width, label, bool(input, "label_visible", true), text(input, "initial", ""), Math.max(1, integer(input, "max_length", 128)), multilineOptions(input)));
            }
        }
        return values;
    }

    private List<Object> optionEntries(JsonObject input) throws ReflectiveOperationException {
        List<Object> values = new ArrayList<>();
        String initial = text(input, "initial", "");
        for (JsonElement element : array(input, "options")) {
            String id;
            String label;
            if (element != null && element.isJsonObject()) {
                JsonObject option = element.getAsJsonObject();
                id = text(option, "id", text(option, "value", ""));
                label = text(option, "label", id);
            } else {
                id = element == null || element.isJsonNull() ? "" : element.getAsString();
                label = id;
            }
            if (!id.isBlank()) {
                values.add(api.optionEntryCreate.invoke(null, id, label.isBlank() ? null : Component.text(label), id.equals(initial)));
            }
        }
        if (values.isEmpty()) {
            values.add(api.optionEntryCreate.invoke(null, "option", Component.text("Option"), true));
        }
        return values;
    }

    private Object multilineOptions(JsonObject input) throws ReflectiveOperationException {
        if (!bool(input, "multiline", false)) {
            return null;
        }
        Integer maxLines = integer(input, "max_lines", 4);
        Integer height = integer(input, "height", 80);
        return api.multilineCreate.invoke(null, maxLines, height);
    }

    private Object dialogType(Player player, String dialogId, JsonObject dialog) throws ReflectiveOperationException {
        List<Object> actions = actions(player, dialogId, dialog);
        String type = text(dialog, "type", "minecraft:multi_action");
        if ("minecraft:notice".equals(type)) {
            return actions.isEmpty() ? api.typeNoticeDefault.invoke(null) : api.typeNotice.invoke(null, actions.getFirst());
        }
        if ("minecraft:confirmation".equals(type)) {
            return api.typeConfirmation.invoke(null, actionAt(actions, 0, player, dialogId, dialog), actionAt(actions, 1, player, dialogId, dialog));
        }
        return api.typeMulti.invoke(null, actions, null, Math.max(1, integer(dialog, "columns", 1)));
    }

    private Object actionAt(List<Object> actions, int index, Player player, String dialogId, JsonObject dialog) throws ReflectiveOperationException {
        if (index < actions.size()) {
            return actions.get(index);
        }
        JsonObject action = new JsonObject();
        action.addProperty("label", index == 0 ? "Confirm" : "Cancel");
        return actionButton(player, dialogId, dialog, action, index);
    }

    private List<Object> actions(Player player, String dialogId, JsonObject dialog) throws ReflectiveOperationException {
        List<Object> values = new ArrayList<>();
        List<JsonObject> configured = objectArray(dialog, "actions");
        if (configured.isEmpty()) {
            JsonObject close = new JsonObject();
            close.addProperty("text", "Close");
            configured = List.of(close);
        }
        for (int i = 0; i < configured.size(); i++) {
            values.add(actionButton(player, dialogId, dialog, configured.get(i), i));
        }
        return values;
    }

    private Object actionButton(Player player, String dialogId, JsonObject dialog, JsonObject action, int index) throws ReflectiveOperationException {
        Object dialogAction = api.actionCustom.invoke(null, callback(player, dialogId, dialog, action, index), callbackOptions());
        return api.actionButtonCreate.invoke(null,
            Component.text(text(action, "label", text(action, "text", "Action"))),
            tooltip(action),
            Math.clamp(integer(action, "width", 150), 1, 1024),
            dialogAction
        );
    }

    private Component tooltip(JsonObject action) {
        String tooltip = text(action, "tooltip", "");
        return tooltip.isBlank() ? null : Component.text(tooltip);
    }

    private Object callbackOptions() throws ReflectiveOperationException {
        Object builder = api.callbackOptionsBuilder.invoke(null);
        invoke(builder, "uses", 1);
        invoke(builder, "lifetime", Duration.ofMinutes(5));
        return invoke(builder, "build");
    }

    private Object callback(Player player, String dialogId, JsonObject dialog, JsonObject action, int index) {
        InvocationHandler handler = (proxy, method, args) -> {
            if ("accept".equals(method.getName())) {
                Map<String, Object> vars = responseVars(dialogId, dialog, action, index, args != null && args.length > 0 ? args[0] : null);
                Bukkit.getScheduler().runTask(plugin, () -> handleAction(player, dialogId, action, vars));
            }
            return null;
        };
        return Proxy.newProxyInstance(api.callbackClass.getClassLoader(), new Class<?>[]{api.callbackClass}, handler);
    }

    private Map<String, Object> responseVars(String dialogId, JsonObject dialog, JsonObject action, int index, Object response) {
        Map<String, Object> vars = new HashMap<>();
        Map<String, Object> inputs = new HashMap<>();
        vars.put("dialog.id", dialogId);
        vars.put("dialog.action", text(action, "id", String.valueOf(index)));
        vars.put("dialog.actionLabel", text(action, "label", text(action, "text", "")));
        for (JsonObject input : objectArray(dialog, "inputs")) {
            String key = text(input, "key", "");
            if (key.isBlank()) {
                continue;
            }
            Object value = responseValue(response, text(input, "type", "minecraft:text"), key);
            inputs.put(key, value);
            vars.put("input." + key, value);
            vars.put("dialog.input." + key, value);
        }
        vars.put("dialog.inputs", inputs);
        return vars;
    }

    private Object responseValue(Object response, String type, String key) {
        if (response == null) {
            return "";
        }
        try {
            if ("minecraft:boolean".equals(type)) {
                Object value = response.getClass().getMethod("getBoolean", String.class).invoke(response, key);
                return value != null ? value : false;
            }
            if ("minecraft:number_range".equals(type)) {
                Object value = response.getClass().getMethod("getFloat", String.class).invoke(response, key);
                return value != null ? value : 0F;
            }
            Object value = response.getClass().getMethod("getText", String.class).invoke(response, key);
            return value != null ? value : "";
        } catch (ReflectiveOperationException ignored) {
            return "";
        }
    }

    private void handleAction(Player player, String dialogId, JsonObject action, Map<String, Object> vars) {
        JsonObject resync = object(action, "resync");
        if (!predicatePass(player, resync, vars)) {
            return;
        }
        String mode = text(resync, "actionMode", text(action, "actionMode", ""));
        switch (mode) {
            case "Run Flow" -> runFlow(text(resync, "flowId", ""), player, vars);
            case "Run Function" -> FunctionCallSupport.execute(flowStorage, flowExecutor, object(resync, "action"), player, null, vars);
            case "Run Command" -> runCommands(player, resync, vars);
            case "Open Dialog" -> show(player, text(resync, "dialogId", ""));
            case "Custom Event" -> emitEvent(text(resync, "customEventId", ""), vars);
            default -> {
                JsonObject legacyAction = object(action, "action");
                if (legacyAction != null) {
                    FunctionCallSupport.execute(flowStorage, flowExecutor, legacyAction, player, null, vars);
                }
            }
        }
    }

    private boolean predicatePass(Player player, JsonObject resync, Map<String, Object> vars) {
        String mode = text(resync, "predicateMode", "None");
        if ("Flow".equals(mode)) {
            return FlowPredicateSupport.evaluate(flowStorage, flowExecutor, text(resync, "predicateFlowId", ""), player, null, vars);
        }
        if ("Function".equals(mode)) {
            return FunctionCallSupport.evaluate(flowStorage, flowExecutor, object(resync, "predicate"), player, null, vars);
        }
        return true;
    }

    private void runFlow(String flowId, Player player, Map<String, Object> vars) {
        if (flowStorage == null || flowExecutor == null || flowId == null || flowId.isBlank()) {
            return;
        }
        FlowGraph graph = flowStorage.getGraph(flowId);
        String start = startNode(graph);
        if (graph != null && start != null) {
            flowExecutor.execute(graph, start, player, null, vars);
        }
    }

    private String startNode(FlowGraph graph) {
        if (graph == null || graph.getNodes() == null || graph.getNodes().isEmpty()) {
            return null;
        }
        for (Map.Entry<String, FlowNode> entry : graph.getNodes().entrySet()) {
            boolean hasIncomingFlow = false;
            for (FlowConnection connection : graph.getConnections()) {
                if (entry.getKey().equals(connection.getTargetNodeId()) && ("flow".equals(connection.getTargetPin()) || "next".equals(connection.getTargetPin()))) {
                    hasIncomingFlow = true;
                    break;
                }
            }
            if (!hasIncomingFlow) {
                return entry.getKey();
            }
        }
        return graph.getNodes().keySet().iterator().next();
    }

    private void runCommands(Player player, JsonObject resync, Map<String, Object> vars) {
        List<String> commands = strings(resync, "commands");
        String single = text(resync, "command", "");
        if (!single.isBlank()) {
            commands = new ArrayList<>(commands);
            commands.add(single);
        }
        for (String command : commands) {
            String resolved = substitute(command, vars);
            if (resolved.startsWith("/")) {
                resolved = resolved.substring(1);
            }
            if (!resolved.isBlank()) {
                Bukkit.dispatchCommand(player, resolved);
            }
        }
    }

    private String substitute(String value, Map<String, Object> vars) {
        String result = value == null ? "" : value;
        if (vars == null || vars.isEmpty()) {
            return result;
        }
        for (Map.Entry<String, Object> entry : vars.entrySet()) {
            String replacement = String.valueOf(entry.getValue());
            result = result.replace("$(" + entry.getKey() + ")", replacement).replace("{" + entry.getKey() + "}", replacement);
        }
        return result;
    }

    private void emitEvent(String eventId, Map<String, Object> vars) {
        if (eventId != null && !eventId.isBlank()) {
            CustomEventManager.getInstance().emit(eventId, vars != null ? vars : Map.of());
        }
    }

    private Method showDialogMethod(Player player, Object dialog) {
        for (Method method : player.getClass().getMethods()) {
            if (!"showDialog".equals(method.getName()) || method.getParameterCount() != 1) {
                continue;
            }
            if (method.getParameterTypes()[0].isInstance(dialog)) {
                return method;
            }
        }
        return null;
    }

    private static Object invoke(Object target, String name, Object... args) throws ReflectiveOperationException {
        for (Method method : target.getClass().getMethods()) {
            if (!name.equals(method.getName()) || method.getParameterCount() != args.length) {
                continue;
            }
            method.setAccessible(true);
            Object result = method.invoke(target, args);
            return method.getReturnType() == Void.TYPE ? null : result;
        }
        throw new NoSuchMethodException(name);
    }

    private List<JsonObject> objectArray(JsonObject object, String key) {
        List<JsonObject> values = new ArrayList<>();
        for (JsonElement element : array(object, key)) {
            if (element != null && element.isJsonObject()) {
                values.add(element.getAsJsonObject());
            }
        }
        return values;
    }

    private JsonArray array(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonArray() ? object.getAsJsonArray(key) : new JsonArray();
    }

    private JsonObject object(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonObject() ? object.getAsJsonObject(key) : null;
    }

    private List<String> strings(JsonObject object, String key) {
        List<String> values = new ArrayList<>();
        for (JsonElement element : array(object, key)) {
            if (element != null && !element.isJsonNull()) {
                values.add(element.getAsString());
            }
        }
        return values;
    }

    private String text(JsonObject object, String key, String fallback) {
        return object != null && object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : fallback;
    }

    private boolean bool(JsonObject object, String key, boolean fallback) {
        return object != null && object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsBoolean() : fallback;
    }

    private int integer(JsonObject object, String key, int fallback) {
        return object != null && object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsInt() : fallback;
    }

    private double decimal(JsonObject object, String key, double fallback) {
        return object != null && object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsDouble() : fallback;
    }

    private record DialogApi(
        Class<?> callbackClass,
        Class<?> afterActionClass,
        Method dialogCreate,
        Method dialogBaseCreate,
        Method dialogBodyPlain,
        Method dialogBodyItem,
        Method inputBool,
        Method inputNumber,
        Method inputSingle,
        Method inputText,
        Method optionEntryCreate,
        Method multilineCreate,
        Method typeNoticeDefault,
        Method typeNotice,
        Method typeConfirmation,
        Method typeMulti,
        Method actionButtonCreate,
        Method actionCustom,
        Method callbackOptionsBuilder
    ) {
        static DialogApi load() {
            try {
                Class<?> dialog = Class.forName("io.papermc.paper.dialog.Dialog");
                Class<?> base = Class.forName("io.papermc.paper.registry.data.dialog.DialogBase");
                Class<?> after = Class.forName("io.papermc.paper.registry.data.dialog.DialogBase$DialogAfterAction");
                Class<?> body = Class.forName("io.papermc.paper.registry.data.dialog.body.DialogBody");
                Class<?> input = Class.forName("io.papermc.paper.registry.data.dialog.input.DialogInput");
                Class<?> optionEntry = Class.forName("io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput$OptionEntry");
                Class<?> multiline = Class.forName("io.papermc.paper.registry.data.dialog.input.TextDialogInput$MultilineOptions");
                Class<?> type = Class.forName("io.papermc.paper.registry.data.dialog.type.DialogType");
                Class<?> button = Class.forName("io.papermc.paper.registry.data.dialog.ActionButton");
                Class<?> action = Class.forName("io.papermc.paper.registry.data.dialog.action.DialogAction");
                Class<?> callback = Class.forName("io.papermc.paper.registry.data.dialog.action.DialogActionCallback");
                Class<?> options = Class.forName("net.kyori.adventure.text.event.ClickCallback$Options");
                Class<?> plainBody = Class.forName("io.papermc.paper.registry.data.dialog.body.PlainMessageDialogBody");
                return new DialogApi(
                    callback,
                    after,
                    dialog.getMethod("create", java.util.function.Consumer.class),
                    base.getMethod("create", Component.class, Component.class, boolean.class, boolean.class, after, List.class, List.class),
                    body.getMethod("plainMessage", Component.class, int.class),
                    body.getMethod("item", ItemStack.class, plainBody, boolean.class, boolean.class, int.class, int.class),
                    input.getMethod("bool", String.class, Component.class, boolean.class, String.class, String.class),
                    input.getMethod("numberRange", String.class, int.class, Component.class, String.class, float.class, float.class, Float.class, Float.class),
                    input.getMethod("singleOption", String.class, int.class, List.class, Component.class, boolean.class),
                    input.getMethod("text", String.class, int.class, Component.class, boolean.class, String.class, int.class, multiline),
                    optionEntry.getMethod("create", String.class, Component.class, boolean.class),
                    multiline.getMethod("create", Integer.class, Integer.class),
                    type.getMethod("notice"),
                    type.getMethod("notice", button),
                    type.getMethod("confirmation", button, button),
                    type.getMethod("multiAction", List.class, button, int.class),
                    button.getMethod("create", Component.class, Component.class, int.class, action),
                    action.getMethod("customClick", callback, options),
                    options.getMethod("builder")
                );
            } catch (ReflectiveOperationException ignored) {
                return new DialogApi(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
            }
        }

        boolean supported() {
            return dialogCreate != null;
        }
    }
}
