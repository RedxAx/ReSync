# Mission Profile: RemotelyFlow Ecosystem Implementation

**Role:** Lead Architect & Full-Stack Engineer
**Target:** Implementation of a proprietary, server-backed Visual Scripting Game Engine for Minecraft.
**Ecosystem:**
*   **Client (IDE):** Remotely (Java/Fabric/ReScreen)
*   **Server (Engine):** ReSync (Java/Bukkit/WebSocket)

**Strategic Goal:**
Establish a content creation monopoly by coupling a powerful Client-Side visual editor with a required Server-Side execution engine. The client allows administrators to design GUIs and logic "in-game" via a high-fidelity node editor, but the logic *lives and runs* on the server via ReSync.

---

## 🛠️ Phase 1: The Shared Protocol (The "Language")

We define the data structure first. This JSON schema is the contract between Client and Server.

**Package:** `restudio.flow.data` (Shared Library or mirrored in both projects)

### 1.1 The Logic Schema (`FlowGraph`)
*   **`FlowGraph`**: The root container for a specific logic file (e.g., `cmd_burger.json`).
    *   `UUID id`: Unique ID.
    *   `Map<String, FlowNode> nodes`: All logic blocks.
    *   `List<FlowConnection> connections`: The wiring.
    *   `List<FlowVariable> localVariables`: Variables specific to this graph (e.g., `temp_count`).
*   **`FlowNode`**: A single block.
    *   `String type`: Registry ID (e.g., `minecraft:give_item`, `logic:if`).
    *   `double x, y`: Visual coordinates for `InfiniteScreen`.
    *   `Map<String, Object> inputValues`: Hardcoded values for unconnected inputs (e.g., a specific message string).
*   **`FlowConnection`**: A wire.
    *   `String sourceNodeId`, `String sourcePin`
    *   `String targetNodeId`, `String targetPin`

### 1.2 The GUI Schema (`RemotelyGui`)
*   **`GuiDefinition`**:
    *   `String title`: Supports MiniMessage/Glyphs.
    *   `int rows`: 1-6.
    *   `List<GuiElement> elements`: The items.
*   **`GuiElement`**:
    *   `List<Integer> slots`: The grid positions (e.g., `[0, 1, 9, 10]` for a 2x2 button).
    *   `Visual visual`: Material, ModelData, Lore (or a "Preset Reference").
    *   `String flowId`: The ID of the `FlowGraph` to execute on click.

---

## 🏗️ Phase 2: The Server Engine (ReSync Implementation)

**Context:** Use `restudio.resync.server.ReSyncServer` as the entry point.

### 2.1 The Interpreter (`FlowExecutor`)
We are building a runtime environment. It must be synchronous with the main server thread for API safety.

*   **`FlowRegistry`**:
    *   Use a functional interface: `BiConsumer<FlowContext, FlowNode>`.
    *   **Context:** `FlowContext` holds `Player`, `InventoryClickEvent` (nullable), and `Map<String, Object> variables`.
    *   **Execution:**
        *   **Standard:** `Registry.register("log", (ctx, node) -> Bukkit.getLogger().info(ctx.getInputValue("text")));`
        *   **Flow Control:** Nodes like `If` must return a specific output pin to traverse next.
*   **`FlowRuntime`**:
    *   Handles the traversal. `Map<String, Object> variableScope`.
    *   Must handle **Dynamic Typing**: If a node expects a String but gets an Integer from a wire, convert it automatically (`.toString()`).

### 2.2 The Trigger System
ReSync must hook into Bukkit events.

*   **`GuiListener`**:
    *   Listens to `InventoryClickEvent`.
    *   Checks if `holder instanceof RemotelyHolder`.
    *   Looks up the `GuiElement` based on the clicked Slot.
    *   Triggers the `FlowExecutor` for that element's `flowId`.
*   **`GlobalTriggers`**:
    *   Listener for `PlayerToggleSneak`, `PlayerJoin`, `AsyncChat`.
    *   Checks a `TriggerMap` to see if any Flow is bound to these events.

### 2.3 The "Handover" Protocol (WebSocket)
We use the existing ReSync WebSocket (`v2`) for transferring blueprints.

*   **New Message Types:**
    *   `FLOW_REQUEST`: Client asks for `cmd_burger.json`.
    *   `FLOW_DATA`: Server sends the JSON.
    *   `FLOW_SAVE`: Client sends edited JSON. Server hot-reloads it.
    *   `GUI_STATE`: Server tells Client "You are looking at GUI 'Menu' (ID: 5), it is editable."

---

## 🎨 Phase 3: The Client IDE (Remotely Implementation)

**Context:** Use `restudio.rescreen.ui.core.InfiniteScreen` and `AnimatedWidget`.

### 3.1 The Node Widget (`NodeWidget`)
This is a custom `AnimatedWidget` that renders a logic block.

*   **Structure:**
    *   **Header:** Title + Icon + Color (Event=Red, Action=Blue, Logic=Purple).
    *   **Input Rows (Left):**
        *   If **wired**: Show just a colored dot (Pin).
        *   If **unwired**: Show a dot + `TextInputWidget`/`NumberInputWidget` (Embedded).
    *   **Output Rows (Right):** Show colored dots.
*   **Pin Interaction:**
    *   Needs a `getPinBounds(String pinName)` method to return absolute screen coordinates for the wire renderer.

### 3.2 The Flow Editor (`FlowEditorScreen`)
Extends `InfiniteScreen` to get Pan/Zoom for free.

*   **Wire Rendering (Crucial):**
    *   Override `renderHandler`.
    *   Iterate `connections`.
    *   Calculate start (Source Pin) and end (Target Pin) positions *adjusted for Pan/Zoom*.
    *   **Bezier Logic (100% straight lines, no shaders, just use IDrawContext.fill() with a small amount of corners):**
        ```java
        // Pseudo-code for style
        float c1x = startX + 50 * scale; // Control points extend horizontally
        float c1y = startY;
        float c2x = endX - 50 * scale;
        float c2y = endY;
        DrawContext.drawCubicBezier(startX, startY, c1x, c1y, c2x, c2y, endX, endY, color, thickness);
        ```
*   **Interaction State Machine:**
    *   `State: IDLE`
    *   `State: DRAGGING_NODE` (Handled by `InfiniteScreen`)
    *   `State: DRAGGING_WIRE`:
        *   Trigger: Clicking a `Pin`.
        *   Render: Draw line from Pin to Mouse.
        *   Release: Check collision with compatible `Pin` on another node. If valid -> Link. If invalid -> Open Context Menu.

### 3.3 The GUI Designer (`GuiDesignerScreen`)
Also extends `InfiniteScreen`.

*   **Visuals:** Render a `ResourceLocation` texture (Chest GUI) at (0,0).
*   **`SlotBatchWidget`**:
    *   Renders an `ItemStack`.
    *   **Batching:** Implements "Resize Handles" on the corners. Dragging a handle snaps to the 18px grid slots.
    *   **Configuration:** Clicking the widget opens a `SidePanel` (Inspector).
        *   Field: Material.
        *   Field: Name (MiniMessage).
        *   Field: **Texture Preset** (Dropdown of client-side defined styles).
        *   Button: **"Edit Logic"** -> Transitions to `FlowEditorScreen`.

### 3.4 The Edit Overlay (The Bridge)
*   **Hook:** `ReScreenWrapper` or `ScreenMixin`.
*   **Logic:**
    *   Listen for `ReSync` packet: `GUI_STATE { editable: true }`.
    *   If true, use `ICustomWidgetHolder` to inject a small "Edit Mode" `IconButton` onto the `ContainerScreen`.
    *   Clicking it closes the native screen and opens `GuiDesignerScreen` with the data fetched from ReSync.

---

## 📝 Implementation Notes & "Clever Stuff"

1.  **Texture Presets (Client-Side):**
    *   Create a `presets.json` in Remotely config.
    *   `"btn_danger": { "material": "RED_STAINED_GLASS_PANE", "model_data": 500, "name": "<red>Cancel" }`
    *   The Designer UI allows dragging these presets onto the grid.

2.  **Variable Scopes (Env Vars):**
    *   In `FlowEditorScreen`, the right-click menu has a "Variables" tab.
    *   **Global:** `server.name` (Read-only, from Server).
    *   **Local:** User defines `temp_score` in the editor.
    *   **Visuals:** Variable nodes are small capsules, distinct from Action nodes.

3.  **Functions:**
    *   **Custom Node:** `Call Function`. Input: `Function Name`.
    *   **Definition:** A separate `FlowGraph` stored in `functions/`.
    *   **Server Implementation:** `FlowExecutor` pauses current graph, stacks the new graph, executes it, then pops back.

4.  **Error Handling (Visual Debugging):**
    *   If ReSync fails to execute a node (e.g., "Invalid Item"), it sends a `FLOW_ERROR { nodeId: "xyz", msg: "..." }`.
    *   Remotely highlights that node in **RED** in the editor.

## 🚀 Execution Order

1.  **Data Layer:** Define `FlowGraph`, `FlowNode`, `FlowConnection` POJOs.
2.  **Server Registry:** Build the `FlowRegistry` and basic `FlowExecutor` in ReSync.
3.  **Client Nodes:** Implement `NodeWidget` and the `FlowEditorScreen` rendering loop (wires).
4.  **Integration:** Implement the `GUI_STATE` packet and the "Edit Button" injection.
5.  **GUI Designer:** Build the `SlotBatchWidget` and Grid snapping logic.

## Notes:
- FlowExecutor should inherently work on `CompletableFuture<FlowResult>`
- Implement a `TypeAdapterRegistry` to prevent future issues with dynamic type checks.

Start by constructing the **Shared Data Classes** and the **Node Registry**, as these define the capabilities of the entire system.