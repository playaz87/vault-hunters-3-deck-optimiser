package vhdeckopt;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.network.NetworkConstants;
import vhdeckopt.client.ClientHooks;

@Mod(VhDeckOptimizer.MOD_ID)
public class VhDeckOptimizer {
    public static final String MOD_ID = "vhdeckoptimizer";

    public VhDeckOptimizer() {
        // Optional on both sides: a server without this mod must still accept the client, and vice versa.
        ModLoadingContext.get().registerExtensionPoint(IExtensionPoint.DisplayTest.class,
            () -> new IExtensionPoint.DisplayTest(() -> NetworkConstants.IGNORESERVERONLY, (remote, isServer) -> true));
        Network.register();
        if (FMLEnvironment.dist == Dist.CLIENT) {
            MinecraftForge.EVENT_BUS.register(ClientHooks.class);
        }
    }
}
