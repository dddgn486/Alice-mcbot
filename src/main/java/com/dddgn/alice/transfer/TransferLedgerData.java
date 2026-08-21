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
        return root;
    }

    public Optional<Entry> find(UUID requestId) {
        return Optional.ofNullable(entries.get(requestId));
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

    /** Marks every unfinished request as manual-only after a server lifecycle boundary. */
    public void suspendUnfinished(String code, long tick) {
        for (Entry entry : entries.values()) {
            if (isTerminal(entry.state())) {
                continue;
            }
            Location location = entry.location() == Location.BOT_INVENTORY
                    ? Location.BOT_INVENTORY : Location.NOT_MOVED;
            entry.transition(State.SUSPENDED, location, code, tick,
                    "lifecycle:" + code + ":" + entry.evidenceDigest(), true);
        }
        setDirty();
    }

    /** A suspended or in-transit request prevents unrelated task replacement for its bot. */
    public boolean blocksBot(UUID botId) {
        return entries.values().stream().anyMatch(entry -> entry.request().botId().equals(botId)
                && (entry.state() == State.IN_TRANSIT_BOT || entry.state() == State.SUSPENDED));
    }

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
            state = nextState;
            location = nextLocation;
            code = nextCode;
            evidenceTick = tick;
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
