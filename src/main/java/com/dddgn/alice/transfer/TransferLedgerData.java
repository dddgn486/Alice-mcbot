package com.dddgn.alice.transfer;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Independent SavedData ledger. It stores evidence, never item stacks or automatic recovery state. */
public final class TransferLedgerData extends SavedData {
    public static final String DATA_KEY = "alice_transfer_ledger";
    private static final int SCHEMA_VERSION = 1;

    private final Map<UUID, Entry> entries = new LinkedHashMap<>();

    /** 保留多少条**物品移动**记录（G5）。 */
    private static final int MOVEMENT_CAPACITY = 32;

    // ==================== G5：**容器写入维度** ====================
    // 2026-09-12 复核：这个账本记的是**请求与状态迁移**，不记"到底哪几件东西从哪去了哪"。
    // 于是"bot 往箱子里放了什么 / 从箱子里拿了什么"没有可审计的痕迹（方块改动有账本，容器没有）。
    // 本批只补**记录**（方向/物品/数量/位置/tick），**不做**自动撤销或恢复。
    /**
     * 一次真实的物品移动。
     *
     * @param leg  `chest_to_bot`（从源箱取出）或 `bot_to_chest`（写入目标箱）
     */
    public record Movement(String requestId, String leg, String itemId, int count, String pos, long tick,
                           String requester, String reason) {
        /** 一行式描述（含授权信息 ⇒ 审计能回答"谁按什么理由动的这箱东西"）。 */
        public String describe() {
            return leg + " " + itemId + " x" + count + " @" + pos + " by=" + requester + " reason=" + reason;
        }
    }

    private final java.util.Deque<Movement> movements = new java.util.ArrayDeque<>();
    private int movementCount;
    private int movementItems;
    private String lastMovement = "-";

    /** 记录一次物品移动（由 `TransferTask` 在两段**已证明**的写入之后调用）。 */
    public void recordMovement(String requestId, String leg, String itemId, int count, String pos, long tick,
                               String requester, String reason) {
        if (count <= 0) {
            return;
        }
        Movement movement = new Movement(requestId, leg, itemId, count, pos, tick, requester, reason);
        movements.addLast(movement);
        while (movements.size() > MOVEMENT_CAPACITY) {
            movements.removeFirst();
        }
        movementCount++;
        movementItems += count;
        lastMovement = movement.describe() + " tick=" + tick;
        setDirty();
    }

    public int movementCount() {
        return movementCount;
    }

    public int movementItems() {
        return movementItems;
    }

    public java.util.List<Movement> movements() {
        return java.util.List.copyOf(movements);
    }

    /** 一行式事实（汇报与自检共用）。 */
    public String describeMovements() {
        return movementCount == 0
                ? "容器写入=0（本档还没有物品进出容器）"
                : "容器写入=" + movementCount + " 次 / " + movementItems + " 件（最后 " + lastMovement + "）";
    }

    public static TransferLedgerData get(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(TransferLedgerData::load, TransferLedgerData::new, DATA_KEY);
    }

    private static TransferLedgerData load(CompoundTag root) {
        TransferLedgerData data = new TransferLedgerData();
        for (Tag value : root.getList("entries", Tag.TAG_COMPOUND)) {
            Entry entry = Entry.load((CompoundTag) value);
            if (entry != null) {
                data.entries.put(entry.request().requestId(), entry);
            }
        }
        for (Tag value : root.getList("movements", Tag.TAG_COMPOUND)) {
            CompoundTag tag = (CompoundTag) value;
            Movement movement = new Movement(tag.getString("request"), tag.getString("leg"),
                    tag.getString("item"), tag.getInt("count"), tag.getString("pos"), tag.getLong("tick"),
                    tag.getString("requester"), tag.getString("reason"));
            data.movements.addLast(movement);
            data.lastMovement = movement.describe() + " tick=" + movement.tick();
        }
        data.movementCount = root.getInt("movement_count");
        data.movementItems = root.getInt("movement_items");
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag root) {
        root.putInt("schema", SCHEMA_VERSION);
        ListTag serialized = new ListTag();
        for (Entry entry : entries.values()) {
            serialized.add(entry.save());
        }
        root.put("entries", serialized);
        // G5：物品移动记录（有界，最多 MOVEMENT_CAPACITY 条）
        ListTag movementTags = new ListTag();
        for (Movement movement : movements) {
            CompoundTag tag = new CompoundTag();
            tag.putString("request", movement.requestId());
            tag.putString("leg", movement.leg());
            tag.putString("item", movement.itemId());
            tag.putInt("count", movement.count());
            tag.putString("pos", movement.pos());
            tag.putLong("tick", movement.tick());
            tag.putString("requester", movement.requester());
            tag.putString("reason", movement.reason());
            movementTags.add(tag);
        }
        root.put("movements", movementTags);
        root.putInt("movement_count", movementCount);
        root.putInt("movement_items", movementItems);
        return root;
    }

    public Optional<Entry> find(UUID requestId) {
        return Optional.ofNullable(entries.get(requestId));
    }

    /** 自检辅助：save → load 往返（验证 G5 的移动记录真的进了 NBT）。 */
    public static TransferLedgerData roundTrip(TransferLedgerData source) {
        return load(source.save(new CompoundTag()));
    }

    public boolean admit(TransferRequest request) {
        Objects.requireNonNull(request, "request");
        if (entries.containsKey(request.requestId())) {
            return false;
        }
        entries.put(request.requestId(), new Entry(request));
        setDirty();
        return true;
    }

    public void transition(UUID requestId, State state, Location location, String code, long tick, String evidenceDigest,
                           boolean manualTakeoverRequired) {
        Entry entry = entries.get(requestId);
        if (entry == null) {
            throw new IllegalArgumentException("unknown_request");
        }
        entry.transition(state, location, code, tick, evidenceDigest, manualTakeoverRequired);
        setDirty();
    }

    /**
     * Marks every unfinished request at a server lifecycle boundary.
     *
     * <p>⚠️ §5.9（2026-09-15，客户端实测）：**只有"物品确实在 bot 身上"的条目才挂起**。
     * 此前一律挂起，于是"重启时刚好有一段没跑完的传输"会把这个 bot 的 `assign*` 通路
     * （walk/follow/place/transfer/**维生自检**）**永久**堵死 —— 而 `location != BOT_INVENTORY`
     * 的条目（物品还在源容器 / 压根没动）**没有可接管的东西**，挂起只是纯阻塞。
     * 现在这类直接落 `ABORTED`（终态、不要求人工接管）；这也让**已被旧版本堵死的存档自然自愈**
     * （启动时重跑本方法即结清）。
     */
    public void suspendUnfinished(String code, long tick) {
        int released = 0;
        for (Entry entry : entries.values()) {
            if (isTerminal(entry.state())) {
                continue;
            }
            if (entry.location() != Location.BOT_INVENTORY) {
                entry.transition(State.ABORTED, entry.location(), TransferCodes.ABORTED_NO_BOT_INVENTORY, tick,
                        "lifecycle_no_bot_inventory:" + code + ":" + entry.evidenceDigest(), false);
                released++;
                continue;
            }
            entry.transition(State.SUSPENDED, Location.BOT_INVENTORY, code, tick,
                    "lifecycle:" + code + ":" + entry.evidenceDigest(), true);
        }
        setDirty();
        if (released > 0) {
            // **必须出声**：这条结清把某个 bot 的 assign* 通路（walk/follow/place/transfer/维生自检）
            // 从"永久失效"里放开 —— 2026-09-15 客户端实测的症状就是"用户点了自检物品、bot 没反应"，
            // 而当时的日志里**一个字都没有**（台账 §5.9）。
            com.dddgn.alice.log.BotLog.warn("[Transfer] 启动结清：{} 条**未进过 bot 背包**的未完成传输"
                    + "直接落 ABORTED（code={}，不再挂起阻塞）", released,
                    TransferCodes.ABORTED_NO_BOT_INVENTORY);
        }
    }

    /**
     * Converts overlong suspension into an explicit manual-takeover requirement without inventory writes.
     *
     * <p>§5.9：同上 —— 只有 `BOT_INVENTORY` 的挂起才需要人工接管；其余（含旧版本留下的
     * `NOT_MOVED` 挂起）复用最大挂起时长后直接结清为 `ABORTED`，把 bot 的 `assign*` 通路放开。
     */
    public void expireSuspensions(long tick, long maximumSuspensionTicks) {
        for (Entry entry : entries.values()) {
            if (entry.state() != State.SUSPENDED || tick - entry.suspensionStartedTick() <= maximumSuspensionTicks) {
                continue;
            }
            if (entry.location() != Location.BOT_INVENTORY) {
                entry.transition(State.ABORTED, entry.location(), TransferCodes.ABORTED_NO_BOT_INVENTORY, tick,
                        "suspension_released_no_bot_inventory:" + entry.evidenceDigest(), false);
                continue;
            }
            entry.transition(State.SUSPENDED, entry.location(), TransferCodes.MANUAL_TAKEOVER_REQUIRED, tick,
                    "suspension_expired:" + entry.evidenceDigest(), true);
        }
        setDirty();
    }

    /** Ledger-only abort: bot-held items remain protected for manual takeover. */
    public State abort(UUID requestId, long tick) {
        Entry entry = entries.get(requestId);
        if (entry == null) throw new IllegalArgumentException("unknown_request");
        if (isTerminal(entry.state())) throw new IllegalStateException("terminal_request");
        if (entry.location() == Location.BOT_INVENTORY || entry.state() == State.IN_TRANSIT_BOT
                || entry.state() == State.SUSPENDED) {
            entry.transition(State.SUSPENDED, Location.BOT_INVENTORY, TransferCodes.MANUAL_TAKEOVER_REQUIRED, tick,
                    "abort:manual_takeover:" + entry.evidenceDigest(), true);
        } else {
            entry.transition(State.ABORTED, entry.location(), "aborted", tick,
                    "abort:no_inventory_mutation:" + entry.evidenceDigest(), entry.manualTakeoverRequired());
        }
        setDirty();
        return entry.state();
    }

    /** A suspended or in-transit request prevents unrelated task replacement for its bot. */
    public boolean blocksBot(UUID botId) {
        return entries.values().stream().anyMatch(entry -> entry.request().botId().equals(botId)
                && (entry.state() == State.IN_TRANSIT_BOT || entry.state() == State.SUSPENDED));
    }

    /**
     * **为什么这个 bot 派不上活**（§5.9：把"静默失效"变成可读的话）—— 只读，空串 = 没被挡住。
     *
     * <p>为什么要它：`blocksBot` 一旦为真，`assignWalkTo`/`assignJob` 家族会**静默什么都不做**
     * （2026-09-15 客户端实测：维生自检打印了"就位"，实际连任务都没建 ⇒ 用户看到"bot 没反应"）。
     */
    public String blockingSummary(UUID botId) {
        java.util.List<Entry> hits = entries.values().stream()
                .filter(entry -> entry.request().botId().equals(botId)
                        && (entry.state() == State.IN_TRANSIT_BOT || entry.state() == State.SUSPENDED))
                .toList();
        if (hits.isEmpty()) {
            return "";
        }
        Entry first = hits.get(0);
        return hits.size() + " 条未结清传输（首个 requestId=" + first.request().requestId()
                + " state=" + first.state() + " code=" + first.code()
                + " location=" + first.location() + " manualTakeover=" + first.manualTakeoverRequired() + "）";
    }

    /**
     * 请求状态机。
     *
     * <p>注意：`SOURCE_LEG_PRE` / `DESTINATION_LEG_PRE` 是**审计哨兵**（记录"某段写入之前"这一刻），
     * 供事后复盘读时间线；它们**不被决策逻辑读取**（读的是 `IN_TRANSIT_BOT`/`SUSPENDED`/`ABORTED`），
     * 属**证据**而非死码（2026-09-13 传输彻查 F3 的处置结论）。
     */
    public enum State {
        PLANNED, PREFLIGHT_SOURCE, MOVE_TO_SOURCE, SOURCE_LEG_PRE, SOURCE_LEG_SIMULATED, SOURCE_EXTRACTED,
        IN_TRANSIT_BOT, MOVE_TO_DESTINATION, DESTINATION_LEG_PRE, DESTINATION_LEG_SIMULATED,
        DESTINATION_INSERTED, VERIFIED, FAILED_NOT_MOVED, SUSPENDED, UNKNOWN_DISCREPANCY, ABORTED
    }

    public enum Location {
        NOT_MOVED, SOURCE_CHEST, BOT_INVENTORY, DESTINATION_CHEST, UNKNOWN
    }

    public static final class Entry {
        private final TransferRequest request;
        private final List<Transition> transitions = new ArrayList<>();
        private State state = State.PLANNED;
        private Location location = Location.NOT_MOVED;
        private String code = "";
        private String evidenceDigest = "";
        private long evidenceTick;
        private long suspensionStartedTick = -1L;
        private boolean manualTakeoverRequired;

        private Entry(TransferRequest request) {
            this.request = request;
        }

        public TransferRequest request() { return request; }
        public State state() { return state; }
        public Location location() { return location; }
        public String code() { return code; }
        public String evidenceDigest() { return evidenceDigest; }
        public long evidenceTick() { return evidenceTick; }
        public long suspensionStartedTick() { return suspensionStartedTick; }
        public boolean manualTakeoverRequired() { return manualTakeoverRequired; }
        public List<Transition> transitions() { return List.copyOf(transitions); }

        private void transition(State nextState, Location nextLocation, String nextCode, long tick, String digest,
                                boolean nextManualTakeoverRequired) {
            Objects.requireNonNull(nextState, "state");
            Objects.requireNonNull(nextLocation, "location");
            Objects.requireNonNull(nextCode, "code");
            Objects.requireNonNull(digest, "digest");
            if (isTerminal(state) && state != nextState) {
                throw new IllegalStateException("terminal_request_transition");
            }
            if (state == State.IN_TRANSIT_BOT && nextState == State.MOVE_TO_DESTINATION
                    && location != Location.BOT_INVENTORY) {
                throw new IllegalStateException("in_transit_location_required");
            }
            State previousState = state;
            state = nextState;
            location = nextLocation;
            code = nextCode;
            evidenceTick = tick;
            if (nextState == State.SUSPENDED && previousState != State.SUSPENDED) {
                suspensionStartedTick = tick;
            } else if (nextState != State.SUSPENDED) {
                suspensionStartedTick = -1L;
            }
            evidenceDigest = digest;
            manualTakeoverRequired = nextManualTakeoverRequired;
            transitions.add(new Transition(transitions.size() + 1L, nextState, nextLocation, nextCode, tick, digest,
                    nextManualTakeoverRequired));
        }

        private CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            writeRequest(tag, request);
            tag.putString("state", state.name());
            tag.putString("location", location.name());
            tag.putString("code", code);
            tag.putString("digest", evidenceDigest);
            tag.putLong("tick", evidenceTick);
            tag.putLong("suspensionStartedTick", suspensionStartedTick);
            tag.putBoolean("manualTakeover", manualTakeoverRequired);
            ListTag transitionTags = new ListTag();
            for (Transition transition : transitions) {
                transitionTags.add(transition.save());
            }
            tag.put("transitions", transitionTags);
            return tag;
        }

        private static Entry load(CompoundTag tag) {
            try {
                TransferRequest request = readRequest(tag);
                Entry entry = new Entry(request);
                entry.state = State.valueOf(tag.getString("state"));
                entry.location = Location.valueOf(tag.getString("location"));
                entry.code = tag.getString("code");
                entry.evidenceDigest = tag.getString("digest");
                entry.evidenceTick = tag.getLong("tick");
                entry.suspensionStartedTick = tag.contains("suspensionStartedTick")
                        ? tag.getLong("suspensionStartedTick") : (entry.state == State.SUSPENDED ? entry.evidenceTick : -1L);
                entry.manualTakeoverRequired = tag.getBoolean("manualTakeover");
                for (Tag value : tag.getList("transitions", Tag.TAG_COMPOUND)) {
                    entry.transitions.add(Transition.load((CompoundTag) value));
                }
                return entry;
            } catch (RuntimeException ignored) {
                return null;
            }
        }
    }

    private static boolean isTerminal(State state) {
        return state == State.VERIFIED || state == State.FAILED_NOT_MOVED || state == State.UNKNOWN_DISCREPANCY
                || state == State.ABORTED;
    }

    public record Transition(long sequence, State state, Location location, String code, long tick,
                             String evidenceDigest, boolean manualTakeoverRequired) {
        private CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putLong("sequence", sequence);
            tag.putString("state", state.name());
            tag.putString("location", location.name());
            tag.putString("code", code);
            tag.putLong("tick", tick);
            tag.putString("digest", evidenceDigest);
            tag.putBoolean("manualTakeover", manualTakeoverRequired);
            return tag;
        }

        private static Transition load(CompoundTag tag) {
            return new Transition(tag.getLong("sequence"), State.valueOf(tag.getString("state")),
                    Location.valueOf(tag.getString("location")), tag.getString("code"), tag.getLong("tick"),
                    tag.getString("digest"), tag.getBoolean("manualTakeover"));
        }
    }

    private static void writeRequest(CompoundTag tag, TransferRequest request) {
        tag.putUUID("requestId", request.requestId());
        tag.putUUID("actorId", request.actorId());
        tag.putUUID("botId", request.botId());
        writeEndpoint(tag, "source", request.source());
        writeEndpoint(tag, "destination", request.destination());
        tag.putString("itemId", request.itemId().toString());
        tag.putInt("count", request.count());
        tag.putLong("createdTick", request.createdServerTick());
    }

    private static TransferRequest readRequest(CompoundTag tag) {
        ResourceLocation itemId = ResourceLocation.tryParse(tag.getString("itemId"));
        if (itemId == null) {
            throw new IllegalArgumentException(TransferCodes.INVALID_ITEM_ID);
        }
        return new TransferRequest(tag.getUUID("requestId"), tag.getUUID("actorId"), tag.getUUID("botId"),
                readEndpoint(tag, "source"), readEndpoint(tag, "destination"), itemId, tag.getInt("count"),
                tag.getLong("createdTick"));
    }

    private static void writeEndpoint(CompoundTag tag, String key, ChestEndpointRef endpoint) {
        CompoundTag endpointTag = new CompoundTag();
        endpointTag.putString("dimension", endpoint.dimensionId().toString());
        endpointTag.putInt("x", endpoint.position().getX());
        endpointTag.putInt("y", endpoint.position().getY());
        endpointTag.putInt("z", endpoint.position().getZ());
        tag.put(key, endpointTag);
    }

    private static ChestEndpointRef readEndpoint(CompoundTag tag, String key) {
        CompoundTag endpoint = tag.getCompound(key);
        ResourceLocation dimension = ResourceLocation.tryParse(endpoint.getString("dimension"));
        if (dimension == null) {
            throw new IllegalArgumentException("invalid_dimension");
        }
        return new ChestEndpointRef(dimension, new BlockPos(endpoint.getInt("x"), endpoint.getInt("y"),
                endpoint.getInt("z")));
    }
}
