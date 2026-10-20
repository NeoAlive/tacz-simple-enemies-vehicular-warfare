package com.neoalive.tacz_sewv.platform;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.HandlerThread;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Stand-in for Forge's {@code SimpleChannel}, so the 80-odd message classes keep their Forge shape
 * ({@code encode(buf)}, a {@code (FriendlyByteBuf)} constructor, {@code handle(Supplier<Context>)})
 * and only their imports differ from main.
 *
 * <p>Each registered message class becomes its own NeoForge payload type,
 * {@code <namespace>:<snake_case_class_name>}, carried in a {@link Payload} envelope. Handlers are
 * registered on the NETWORK thread so {@code enqueueWork} defers to the main thread exactly as it
 * did on Forge. {@code registerMessage} only records; {@link #onRegisterPayloads} hands everything
 * to NeoForge, after which further registrations are a hard error rather than a silently dead packet.
 */
public final class SimpleChannel {

    private static final List<SimpleChannel> CHANNELS = new ArrayList<>();

    private final ResourceLocation name;
    private final String version;
    private final List<Registration<?>> registrations = new ArrayList<>();
    private final Map<Class<?>, Registration<?>> byClass = new HashMap<>();
    private Runnable registerHook;
    private boolean flushed;

    SimpleChannel(ResourceLocation name, String version) {
        this.name = name;
        this.version = version;
        synchronized (CHANNELS) {
            CHANNELS.add(this);
        }
    }

    /**
     * Code to run before this channel is handed to NeoForge if it has not run yet — the message
     * registration method, so it no longer matters whether common setup or the payload event
     * comes first. It must be idempotent.
     */
    public SimpleChannel registerWith(Runnable hook) {
        this.registerHook = hook;
        return this;
    }

    /** Same shape as Forge's; {@code index} is ignored (NeoForge keys payloads by type id). */
    public <MSG> void registerMessage(int index, Class<MSG> type, BiConsumer<MSG, FriendlyByteBuf> encoder,
                                      Function<FriendlyByteBuf, MSG> decoder,
                                      BiConsumer<MSG, Supplier<NetworkEvent.Context>> handler) {
        if (flushed) {
            throw new IllegalStateException("Message " + type.getName() + " registered on " + name
                    + " after payload registration closed — it would never be delivered");
        }
        if (byClass.containsKey(type)) {
            throw new IllegalStateException("Message " + type.getName() + " registered twice on " + name);
        }
        Registration<MSG> reg = new Registration<>(this, type, payloadId(type), encoder, decoder, handler);
        registrations.add(reg);
        byClass.put(type, reg);
    }

    public void sendToServer(Object message) {
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(wrap(message));
    }

    public void send(PacketDistributor.PacketTarget target, Object message) {
        target.send(wrap(message));
    }

    private CustomPacketPayload wrap(Object message) {
        Registration<?> reg = byClass.get(message.getClass());
        if (reg == null) {
            throw new IllegalArgumentException("Unregistered message " + message.getClass().getName() + " on " + name);
        }
        return reg.wrapUnchecked(message);
    }

    private ResourceLocation payloadId(Class<?> type) {
        String snake = type.getSimpleName().replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
        return ResourceLocation.fromNamespaceAndPath(name.getNamespace(), snake);
    }

    /** Mod-bus listener; {@code TaczSewv} adds it. */
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        List<SimpleChannel> channels;
        synchronized (CHANNELS) {
            channels = List.copyOf(CHANNELS);
        }
        for (SimpleChannel channel : channels) {
            if (channel.registerHook != null) channel.registerHook.run();
            // registrar(version): the namespace comes from each payload's type id.
            PayloadRegistrar registrar = event.registrar(channel.version)
                    .executesOn(HandlerThread.NETWORK);
            for (Registration<?> reg : channel.registrations) {
                reg.register(registrar);
            }
            channel.flushed = true;
        }
    }

    /** The envelope every message travels in. */
    public record Payload<MSG>(Type<Payload<MSG>> type, MSG message) implements CustomPacketPayload {
    }

    private static final class Registration<MSG> {
        private final Class<MSG> clazz;
        private final CustomPacketPayload.Type<Payload<MSG>> type;
        private final StreamCodec<RegistryFriendlyByteBuf, Payload<MSG>> codec;
        private final BiConsumer<MSG, Supplier<NetworkEvent.Context>> handler;

        Registration(SimpleChannel channel, Class<MSG> clazz, ResourceLocation id,
                     BiConsumer<MSG, FriendlyByteBuf> encoder, Function<FriendlyByteBuf, MSG> decoder,
                     BiConsumer<MSG, Supplier<NetworkEvent.Context>> handler) {
            this.clazz = clazz;
            this.type = new CustomPacketPayload.Type<>(id);
            this.handler = handler;
            this.codec = StreamCodec.of(
                    (buf, payload) -> encoder.accept(payload.message(), buf),
                    buf -> new Payload<>(this.type, decoder.apply(buf)));
        }

        void register(PayloadRegistrar registrar) {
            registrar.playBidirectional(type, codec, (payload, ctx) -> {
                NetworkEvent.Context context = new NetworkEvent.Context(ctx);
                handler.accept(payload.message(), () -> context);
            });
        }

        CustomPacketPayload wrapUnchecked(Object message) {
            return new Payload<>(type, clazz.cast(message));
        }
    }
}
