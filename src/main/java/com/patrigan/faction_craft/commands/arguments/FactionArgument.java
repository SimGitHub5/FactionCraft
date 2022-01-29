package com.patrigan.faction_craft.commands.arguments;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.patrigan.faction_craft.faction.Faction;
import com.patrigan.faction_craft.faction.Factions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.TranslatableComponent;

import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;

public class FactionArgument implements ArgumentType<Faction> {
    private static final Collection<String> EXAMPLES = Arrays.asList("minecraft:vanilla");
    public static final DynamicCommandExceptionType ERROR_UNKNOWN_FACTION = new DynamicCommandExceptionType((p_208663_0_) -> {
        return new TranslatableComponent("commands.argument.factionNotFound", p_208663_0_);
    });

    public static FactionArgument factions() {
        return new FactionArgument();
    }

    public static Faction getFaction(CommandContext<CommandSourceStack> source, String name) throws CommandSyntaxException {
        return source.getArgument(name, Faction.class);
    }

    public Faction parse(StringReader p_parse_1_) throws CommandSyntaxException {
        ResourceLocation resourcelocation = ResourceLocation.read(p_parse_1_);
        if (Factions.factionExists(resourcelocation)) {
            return Factions.getFaction(resourcelocation);
        } else {
            throw ERROR_UNKNOWN_FACTION.create(resourcelocation);
        }
    }

    public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> p_listSuggestions_1_, SuggestionsBuilder p_listSuggestions_2_) {
        return SharedSuggestionProvider.suggestResource(Factions.factionKeys(), p_listSuggestions_2_);
    }

    public Collection<String> getExamples() {
        return EXAMPLES;
    }
}
