package dev.dediamondpro.resourcify.core;

import org.fentanylsolutions.fentlib.core.FentMixins;
import org.fentanylsolutions.fentlib.util.MiscUtil.Side;
import org.fentanylsolutions.fentlib.util.MixinUtil;
import org.fentanylsolutions.fentlib.util.MixinUtil.Phase;

public class Mixins extends FentMixins {

    private static final Mixins INSTANCE = new Mixins();

    @Override
    protected void registerMixins(MixinUtil.Registry registry) {
        registry.mixin("AbstractResourcePackAccessor")
            .side(Side.CLIENT)
            .phase(Phase.EARLY)
            .build();
        registry.mixin("PackScreenAccessor")
            .side(Side.CLIENT)
            .phase(Phase.EARLY)
            .build();
        registry.mixin("MixinGuiScreenResourcePacks")
            .side(Side.CLIENT)
            .phase(Phase.EARLY)
            .build();
        registry.mixin("ResourcePackEntryAccessor")
            .side(Side.CLIENT)
            .phase(Phase.EARLY)
            .build();
    }

    public static java.util.List<String> getEarlyMixinsForLoader() {
        return INSTANCE.getEarlyMixins();
    }

    public static java.util.List<String> getLateMixinsForLoader(java.util.Set<String> loadedCoreMods) {
        return INSTANCE.getLateMixins(loadedCoreMods);
    }
}
