package com.dddgn.alice.item;

import com.dddgn.alice.transfer.TransferCodes;

/** Focused evidence for the pre-block selector interaction handler. */
public final class TransferEndpointSelectorEventsFixture {
    private TransferEndpointSelectorEventsFixture() { }

    public static boolean run() {
        int[] writes = {0};
        String[] roles = {null, null};
        String normal = TransferEndpointSelectorEvents.dispatch(true, true, false, source -> {
            roles[0] = TransferEndpointSelectorEvents.roleFor(source);
            writes[0]++;
            return "accepted";
        });
        String secondary = TransferEndpointSelectorEvents.dispatch(true, true, true, source -> {
            roles[1] = TransferEndpointSelectorEvents.roleFor(source);
            writes[0]++;
            return "accepted";
        });
        int beforeNonSelector = writes[0];
        TransferEndpointSelectorEvents.dispatch(true, false, false, source -> { writes[0]++; return "accepted"; });
        boolean singleWrite = "accepted".equals(normal) && "accepted".equals(secondary)
                && "destination".equals(roles[0]) && "source".equals(roles[1])
                && writes[0] == beforeNonSelector && beforeNonSelector == 2;
        boolean unauthorizedNoWrite = noWriteFor(TransferCodes.UNAUTHORIZED_ACTOR, writes);
        boolean invalidNoWrite = noWriteFor(TransferCodes.ENDPOINT_NOT_SINGLE_CHEST, writes);
        return singleWrite && unauthorizedNoWrite && invalidNoWrite
                && TransferEndpointSelectorEvents.preservesVanillaInteraction();
    }

    private static boolean noWriteFor(String code, int[] writes) {
        int before = writes[0];
        String result = TransferEndpointSelectorEvents.dispatch(true, true, false, source -> code);
        return code.equals(result) && writes[0] == before;
    }
}
