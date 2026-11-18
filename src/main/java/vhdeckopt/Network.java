package vhdeckopt;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import vhdeckopt.client.ClientHooks;
import vhdeckopt.vault.VaultAdapter;

/**
 * The two messages used to apply a layout: the client asks, the server answers.
 * The channel is optional on both sides, so a client with the mod can join a
 * server without it (the Apply button is then unavailable) and the other way round.
 */
public final class Network {
    private static final Logger LOGGER = LogManager.getLogger("vhdeckoptimizer");
    private static final int MAX_SLOTS = 54;

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        new ResourceLocation(VhDeckOptimizer.MOD_ID, "main"), () -> "1", version -> true, version -> true);

    private Network() {
    }

    public static void register() {
        CHANNEL.registerMessage(0, Apply.class, Apply::write, Apply::read, Apply::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(1, Result.class, Result::write, Result::read, Result::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    /** Client to server: make the deck in this open binder match this layout. */
    public record Apply(int containerId, List<VaultAdapter.Wanted> layout) {
        void write(FriendlyByteBuf buffer) {
            buffer.writeVarInt(this.containerId);
            buffer.writeVarInt(this.layout.size());
            for (VaultAdapter.Wanted slot : this.layout) {
                buffer.writeVarInt(slot.x());
                buffer.writeVarInt(slot.y());
                buffer.writeNbt(slot.card());
            }
        }

        static Apply read(FriendlyByteBuf buffer) {
            int containerId = buffer.readVarInt();
            int size = buffer.readVarInt();
            if (size < 0 || size > MAX_SLOTS) {
                throw new IllegalArgumentException("Bad layout size " + size);
            }
            List<VaultAdapter.Wanted> layout = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                int x = buffer.readVarInt();
                int y = buffer.readVarInt();
                CompoundTag card = buffer.readNbt();
                layout.add(new VaultAdapter.Wanted(x, y, card));
            }
            return new Apply(containerId, layout);
        }

        void handle(Supplier<NetworkEvent.Context> context) {
            context.get().enqueueWork(() -> {
                ServerPlayer player = context.get().getSender();
                if (player == null) {
                    return;
                }
                String refused;
                try {
                    refused = VaultAdapter.apply(player, this.containerId, this.layout);
                } catch (RuntimeException e) {
                    LOGGER.error("Applying a deck layout for {} failed", player.getGameProfile().getName(), e);
                    refused = "Server error: " + e;
                }
                CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new Result(refused == null, refused == null ? "Layout applied." : refused));
            });
            context.get().setPacketHandled(true);
        }
    }

    /** Server to client: whether the layout was applied, and if not, why. */
    public record Result(boolean applied, String text) {
        void write(FriendlyByteBuf buffer) {
            buffer.writeBoolean(this.applied);
            buffer.writeUtf(this.text, 512);
        }

        static Result read(FriendlyByteBuf buffer) {
            return new Result(buffer.readBoolean(), buffer.readUtf(512));
        }

        void handle(Supplier<NetworkEvent.Context> context) {
            context.get().enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientHooks.applyResult(this.applied, this.text)));
            context.get().setPacketHandled(true);
        }
    }
}
