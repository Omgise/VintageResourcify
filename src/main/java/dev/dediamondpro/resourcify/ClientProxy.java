package dev.dediamondpro.resourcify;

import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import dev.dediamondpro.resourcify.config.Config;
import dev.dediamondpro.resourcify.services.ServiceRegistry;

public class ClientProxy extends CommonProxy {

    @Override
    public void preInit(FMLPreInitializationEvent event) {
        super.preInit(event);

        // Touching Config.Companion.getInstance() runs the companion's init block,
        // which reads config/resourcify.json (or writes defaults if missing).
        // ServiceRegistry's static init registers Modrinth + CurseForge services.
        Config.Companion.getInstance();
        ServiceRegistry.INSTANCE.getAllServices();

        VintageResourcify.LOG.info(
            "VintageResourcify {} initialized with {} service(s).",
            Tags.VERSION,
            ServiceRegistry.INSTANCE.getAllServices()
                .size());
    }

    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);
    }

    @Override
    public void postInit(FMLPostInitializationEvent event) {
        super.postInit(event);
    }
}
