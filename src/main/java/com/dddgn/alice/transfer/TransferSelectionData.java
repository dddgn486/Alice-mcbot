package com.dddgn.alice.transfer;

import net.minecraft.server.MinecraftServer;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Ephemeral server-only endpoint drafts. They are neither persisted nor ledgered. */
public final class TransferSelectionData {
    public static final long EXPIRY_TICKS = 2400L;
    private static final Map<MinecraftServer, Map<UUID, SelectionDraft>> DRAFTS = new IdentityHashMap<>();
    private TransferSelectionData() { }
    public static Result select(MinecraftServer server, UUID playerId, ChestEndpointRef endpoint, boolean source, long tick) {
        SelectionDraft current = active(server, playerId, tick).orElse(new SelectionDraft(null, null, tick));
        ChestEndpointRef other = source ? current.destination() : current.source();
        if (other != null) {
            if (!other.dimensionId().equals(endpoint.dimensionId())) return Result.failure(TransferCodes.CROSS_DIMENSION_REJECTED);
            if (other.equals(endpoint)) return Result.failure(TransferCodes.SAME_ENDPOINT_REJECTED);
        }
        SelectionDraft next = source ? new SelectionDraft(endpoint, current.destination(), tick) : new SelectionDraft(current.source(), endpoint, tick);
        DRAFTS.computeIfAbsent(server, ignored -> new HashMap<>()).put(playerId, next);
        return Result.accepted(next);
    }
    public static Optional<SelectionDraft> status(MinecraftServer server, UUID playerId, long tick) { return active(server, playerId, tick); }
    public static boolean clear(MinecraftServer server, UUID playerId) {
        Map<UUID, SelectionDraft> drafts = DRAFTS.get(server);
        return drafts != null && drafts.remove(playerId) != null;
    }
    public static void clearServer(MinecraftServer server) { DRAFTS.remove(server); }
    static int fixtureEntryCount(MinecraftServer server) { return DRAFTS.getOrDefault(server, Map.of()).size(); }
    private static Optional<SelectionDraft> active(MinecraftServer server, UUID playerId, long tick) {
        Map<UUID, SelectionDraft> drafts = DRAFTS.get(server);
        SelectionDraft draft = drafts == null ? null : drafts.get(playerId);
        if (draft == null) return Optional.empty();
        if (tick - draft.selectedTick() > EXPIRY_TICKS) { drafts.remove(playerId); return Optional.empty(); }
        return Optional.of(draft);
    }
    public record SelectionDraft(ChestEndpointRef source, ChestEndpointRef destination, long selectedTick) { }
    public record Result(boolean accepted, String code, SelectionDraft draft) {
        private static Result accepted(SelectionDraft draft) { return new Result(true, "accepted", draft); }
        private static Result failure(String code) { return new Result(false, code, null); }
    }
}
