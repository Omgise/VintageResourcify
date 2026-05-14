package dev.dediamondpro.resourcify.core;

import org.fentanylsolutions.fentlib.core.FentMixins;
import org.fentanylsolutions.fentlib.util.MixinUtil;

public class Mixins extends FentMixins {

    private static final Mixins INSTANCE = new Mixins();

    @Override
    protected void registerMixins(MixinUtil.Registry registry) {
        // Mixins get registered here during task #5. The skeleton intentionally
        // leaves this empty so the three mixin configs (early/mid/late) stay
        // valid with no entries — see JackModNotes §4: "keep the file so the
        // project shape stays uniform" even when a phase has zero mixins.
    }

    public static java.util.List<String> getEarlyMixinsForLoader() {
        return INSTANCE.getEarlyMixins();
    }

    public static java.util.List<String> getLateMixinsForLoader(java.util.Set<String> loadedCoreMods) {
        return INSTANCE.getLateMixins(loadedCoreMods);
    }
}
