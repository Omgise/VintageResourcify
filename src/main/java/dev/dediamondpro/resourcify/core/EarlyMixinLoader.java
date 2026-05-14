package dev.dediamondpro.resourcify.core;

import java.util.List;
import java.util.Set;

import org.fentanylsolutions.fentlib.core.FentEarlyMixinLoader;

import cpw.mods.fml.relauncher.IFMLLoadingPlugin;
import dev.dediamondpro.resourcify.VintageResourcify;

@SuppressWarnings("unused")
@IFMLLoadingPlugin.MCVersion("1.7.10")
public class EarlyMixinLoader extends FentEarlyMixinLoader {

    @Override
    public String getMixinConfig() {
        return "mixins." + VintageResourcify.MODID + ".early.json";
    }

    @Override
    public List<String> getMixins(Set<String> loadedCoreMods) {
        return Mixins.getEarlyMixinsForLoader();
    }
}
