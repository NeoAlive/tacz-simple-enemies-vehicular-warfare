package com.neoalive.tacz_sewv.platform;

import java.util.function.Consumer;
import java.util.function.Supplier;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * Stand-in for Forge's {@code PacketDistributor}: the targets this mod sends to, in Forge's
 * {@code PLAYER.with(() -> player)} / {@code ALL.noArg()} shape, mapped onto NeoForge's static
 * {@link net.neoforged.neoforge.network.PacketDistributor} sends.
 */
public final class PacketDistributor<T> {

    public static final PacketDistributor<ServerPlayer> PLAYER = new PacketDistributor<>(
            (player, payload) -> net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, payload));
    public static final PacketDistributor<Entity> TRACKING_ENTITY = new PacketDistributor<>(
            (entity, payload) -> net.neoforged.neoforge.network.PacketDistributor.sendToPlayersTrackingEntity(entity, payload));
    public static final PacketDistributor<Entity> TRACKING_ENTITY_AND_SELF = new PacketDistributor<>(
            (entity, payload) -> net.neoforged.neoforge.network.PacketDistributor.sendToPlayersTrackingEntityAndSelf(entity, payload));
    public static final PacketDistributor<Void> ALL = new PacketDistributor<>(
            (ignored, payload) -> net.neoforged.neoforge.network.PacketDistributor.sendToAllPlayers(payload));

    private interface Sender<T> {
        void send(T target, CustomPacketPayload payload);
    }

    private final Sender<T> sender;

    private PacketDistributor(Sender<T> sender) {
        this.sender = sender;
    }

    public PacketTarget with(Supplier<? extends T> target) {
        T resolved = target.get();
        return new PacketTarget(payload -> sender.send(resolved, payload));
    }

    public PacketTarget noArg() {
        return new PacketTarget(payload -> sender.send(null, payload));
    }

    /** A resolved destination, as returned by {@code with}/{@code noArg}. */
    public static final class PacketTarget {
        private final Consumer<CustomPacketPayload> send;

        private PacketTarget(Consumer<CustomPacketPayload> send) {
            this.send = send;
        }

        void send(CustomPacketPayload payload) {
            send.accept(payload);
        }
    }
}
