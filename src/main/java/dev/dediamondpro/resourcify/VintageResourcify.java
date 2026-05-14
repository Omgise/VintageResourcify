package dev.dediamondpro.resourcify;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;

@Mod(
    modid = VintageResourcify.MODID,
    version = Tags.VERSION,
    name = "VintageResourcify",
    acceptedMinecraftVersions = "[1.7.10]",
    acceptableRemoteVersions = "*",
    clientSideOnly = true,
    customProperties = {
        @Mod.CustomProperty(k = "license", v = "LGPLv3+SNEED"),
        @Mod.CustomProperty(k = "issueTrackerUrl", v = "https://github.com/JackOfNoneTrades/VintageResourcify/issues")
    }
)
public class VintageResourcify {

    public static final String MODID = "resourcify";
    public static final String MODGROUP = "dev.dediamondpro.resourcify";
    public static final Logger LOG = LogManager.getLogger(MODID);

    @SidedProxy(
        clientSide = MODGROUP + ".ClientProxy",
        serverSide = MODGROUP + ".CommonProxy")
    public static CommonProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        proxy.preInit(event);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init(event);
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        proxy.postInit(event);
    }
}
