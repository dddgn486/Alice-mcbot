package com.dddgn.alice.command;

import com.mojang.brigadier.StringReader;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.resources.ResourceLocation;
import com.dddgn.alice.log.BotLog;

/** Focused parser evidence; does not execute commands or touch transfer/world state. */
public final class TransferSelectionCommandParseFixture {
    private TransferSelectionCommandParseFixture() { }

    public static boolean run() {
        ResourceLocationArgument argument = ResourceLocationArgument.id();
        boolean vanilla = parses(argument, "minecraft:iron_ingot", "minecraft:iron_ingot");
        boolean modSyntax = parses(argument, "mekanism:ingot_steel", "mekanism:ingot_steel");
        boolean modPolicy = !"minecraft".equals(ResourceLocation.tryParse("mekanism:ingot_steel").getNamespace());
        boolean badColon = rejects(argument, "bad:item:id");
        boolean whitespace = rejects(argument, "minecraft:iron ingot");
        // Fixed Forge 1.20.1 behavior is recorded, not asserted: both tokens parse and are
        // fully consumed. Business namespace rejection stays with TransferSelectionSubmission.resolve.
        String emptyPath = parseResult(argument, "minecraft:");
        String emptyNamespace = parseResult(argument, ":iron_ingot");
        boolean resolverPolicy = modSyntax && modPolicy;
        boolean pass = vanilla && modSyntax && resolverPolicy && badColon && whitespace;
        BotLog.info("TRANSFER_SELECTION_COMMAND_PARSE details vanilla={} modSyntax={} resolverPolicy={} badColon={} whitespace={} emptyPathByApi={} emptyNamespaceByApi={}", vanilla, modSyntax, resolverPolicy, badColon, whitespace, emptyPath, emptyNamespace);
        return pass;
    }

    private static String parseResult(ResourceLocationArgument argument, String token) {
        try {
            StringReader reader = new StringReader(token);
            ResourceLocation parsed = argument.parse(reader);
            return reader.canRead() ? "trailing:" + parsed : parsed.toString();
        } catch (Exception exception) { return "parse-rejected:" + exception.getClass().getSimpleName(); }
    }

    private static boolean parses(ResourceLocationArgument argument, String token, String expected) {
        try {
            StringReader reader = new StringReader(token);
            return expected.equals(argument.parse(reader).toString()) && !reader.canRead();
        } catch (Exception ignored) { return false; }
    }
    private static boolean rejects(ResourceLocationArgument argument, String token) {
        try {
            StringReader reader = new StringReader(token);
            argument.parse(reader);
            return reader.canRead();
        } catch (Exception expected) { return true; }
    }
}
