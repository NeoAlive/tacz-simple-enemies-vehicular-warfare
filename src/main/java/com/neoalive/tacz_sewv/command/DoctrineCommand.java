package com.neoalive.tacz_sewv.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import com.neoalive.tacz_sewv.entity.ai.utility.Doctrine;
import com.neoalive.tacz_sewv.entity.ai.utility.PlayerDoctrineData;
import com.neoalive.tacz_sewv.network.PacketDoctrineSync;

/**
 * {@code /sewv doctrine show [player]} reads a doctrine (anyone for themselves, operators for others);
 * {@code /sewv doctrine set <player> <a,p,c,i,s,t,m,r>} writes one (operators, for testing: no ledger, no budget).
 */
public final class DoctrineCommand {

    private DoctrineCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("doctrine")
                .then(Commands.literal("show")
                        .executes(ctx -> show(ctx.getSource(), ctx.getSource().getPlayerOrException()))
                        .then(Commands.argument("player", EntityArgument.player())
                                .requires(s -> s.hasPermission(2))
                                .executes(ctx -> show(ctx.getSource(), EntityArgument.getPlayer(ctx, "player")))))
                .then(Commands.literal("set")
                        .requires(s -> s.hasPermission(2))
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("axes", StringArgumentType.greedyString())
                                        .executes(ctx -> set(ctx.getSource(), EntityArgument.getPlayer(ctx, "player"),
                                                StringArgumentType.getString(ctx, "axes"))))));
    }

    private static int show(CommandSourceStack source, ServerPlayer target) {
        PlayerDoctrineData data = PlayerDoctrineData.get(target.level());
        Doctrine doctrine = data.getDoctrine(target.getUUID());
        String name = target.getGameProfile().getName();
        if (doctrine == null) {
            source.sendSuccess(() -> Component.translatable("message.tacz_sewv.doctrine.show_none", name), false);
        } else {
            String axes = doctrine.toString();
            int budget = data.budget(target.getUUID());
            source.sendSuccess(() -> Component.translatable("message.tacz_sewv.doctrine.show", name, axes, budget), false);
        }
        return 1;
    }

    private static int set(CommandSourceStack source, ServerPlayer target, String raw) throws CommandSyntaxException {
        String[] parts = raw.trim().split("[,\\s]+");
        int[] axes = new int[Doctrine.Axis.VALUES.length];
        if (parts.length != axes.length) {
            source.sendFailure(Component.translatable("message.tacz_sewv.doctrine.set_bad", axes.length));
            return 0;
        }
        try {
            for (int i = 0; i < axes.length; i++) {
                axes[i] = Integer.parseInt(parts[i]);
                if (Math.abs(axes[i]) > Doctrine.AXIS_LIMIT) throw new NumberFormatException();
            }
        } catch (NumberFormatException e) {
            source.sendFailure(Component.translatable("message.tacz_sewv.doctrine.set_bad", axes.length));
            return 0;
        }
        PlayerDoctrineData.get(target.level()).setDoctrine(target.getUUID(), Doctrine.ofAxes(axes));
        PacketDoctrineSync.sendTo(target);
        String name = target.getGameProfile().getName();
        source.sendSuccess(() -> Component.translatable("message.tacz_sewv.doctrine.set_ok", name), true);
        return 1;
    }
}
