package dev.dediamondpro.resourcify.gui.config;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiScreen;

import cpw.mods.fml.client.config.ConfigGuiType;
import cpw.mods.fml.client.config.DummyConfigElement;
import cpw.mods.fml.client.config.GuiConfig;
import cpw.mods.fml.client.config.IConfigElement;
import dev.dediamondpro.resourcify.VintageResourcify;
import dev.dediamondpro.resourcify.config.Config;

/**
 * Vanilla-Forge-style settings UI. Each element subclasses
 * {@link DummyConfigElement} so {@code set()} writes through to our JSON-backed
 * {@link Config} and triggers a save, instead of just mutating in-memory state.
 * This keeps upstream Resourcify's JSON file the single source of truth while
 * still letting us use Forge's built-in GuiConfig (ESC to close, "Done" button,
 * etc).
 */
public class ResourcifyConfigGui extends GuiConfig {

    public ResourcifyConfigGui(GuiScreen parent) {
        super(parent, buildElements(), VintageResourcify.MODID, false, false, "VintageResourcify Settings");
    }

    private static List<IConfigElement> buildElements() {
        List<IConfigElement> list = new ArrayList<>();
        list.add(
            new DummyConfigElement<Boolean>(
                "debugMode",
                Config.Companion.getInstance()
                    .getDebugMode(),
                ConfigGuiType.BOOLEAN,
                "resourcify.config.debug-mode") {

                @Override
                public void set(Boolean value) {
                    super.set(value);
                    Config.Companion.getInstance()
                        .setDebugMode(value);
                    Config.Companion.save(Config.Companion.getInstance());
                }
            });
        list.add(
            new DummyConfigElement<Boolean>(
                "autoUpdateChecks",
                Config.Companion.getInstance()
                    .getAutoUpdateChecks(),
                ConfigGuiType.BOOLEAN,
                "resourcify.config.auto-update-checks") {

                @Override
                public void set(Boolean value) {
                    super.set(value);
                    Config.Companion.getInstance()
                        .setAutoUpdateChecks(value);
                    Config.Companion.save(Config.Companion.getInstance());
                }
            });
        list.add(
            new DummyConfigElement<Boolean>(
                "enableSounds",
                Config.Companion.getInstance()
                    .getEnableSounds(),
                ConfigGuiType.BOOLEAN,
                "resourcify.config.enable-sounds") {

                @Override
                public void set(Boolean value) {
                    super.set(value);
                    Config.Companion.getInstance()
                        .setEnableSounds(value);
                    Config.Companion.save(Config.Companion.getInstance());
                }
            });
        list.add(
            new DummyConfigElement<String>(
                "markdownTheme",
                Config.Companion.getInstance()
                    .getMarkdownTheme(),
                ConfigGuiType.STRING,
                "resourcify.config.markdownTheme",
                new String[] { "dark", "light" }) {

                @Override
                public void set(String value) {
                    super.set(value);
                    Config.Companion.getInstance()
                        .setMarkdownTheme(value);
                    Config.Companion.save(Config.Companion.getInstance());
                }
            });
        list.add(
            new DummyConfigElement<String>(
                "curseUserAgent",
                Config.Companion.getInstance()
                    .getCurseUserAgent(),
                ConfigGuiType.STRING,
                "resourcify.config.curse-user-agent") {

                @Override
                public void set(String value) {
                    super.set(value);
                    Config.Companion.getInstance()
                        .setCurseUserAgent(value);
                    Config.Companion.save(Config.Companion.getInstance());
                }
            });
        list.add(
            new DummyConfigElement<String>(
                "curseApiToken",
                Config.Companion.getInstance()
                    .getCurseApiToken(),
                ConfigGuiType.STRING,
                "resourcify.config.curse-api-token") {

                @Override
                public void set(String value) {
                    super.set(value);
                    Config.Companion.getInstance()
                        .setCurseApiToken(value);
                    Config.Companion.save(Config.Companion.getInstance());
                }
            });
        return list;
    }
}
