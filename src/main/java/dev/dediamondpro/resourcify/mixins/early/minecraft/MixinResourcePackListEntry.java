package dev.dediamondpro.resourcify.mixins.early.minecraft;

import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.resources.ResourcePackListEntry;
import net.minecraft.client.resources.ResourcePackListEntryFound;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.dediamondpro.resourcify.gui.pack.PackOverlayRenderer;

/**
 * Overlay the platform badge in the bottom-right corner of the pack icon for
 * any resource pack we installed. Entries the user dropped in manually have
 * no index record and stay un-badged.
 */
@Mixin(ResourcePackListEntry.class)
public abstract class MixinResourcePackListEntry {

    @Shadow
    protected abstract boolean func_148310_d();

    @Shadow
    protected abstract boolean func_148309_e();

    @Shadow
    protected abstract boolean func_148308_f();

    @Shadow
    protected abstract boolean func_148314_g();

    @Shadow
    protected abstract boolean func_148307_h();

    @Inject(method = "drawEntry", at = @At("TAIL"))
    private void resourcify$drawPlatformBadge(int slotIndex, int x, int y, int listWidth, int slotHeight,
        Tessellator tessellator, int mouseX, int mouseY, boolean isSelected, CallbackInfo ci) {
        Object self = this;
        if (!(self instanceof ResourcePackListEntryFound)) return;
        boolean rowHovered = mouseX >= x && mouseX < x + listWidth && mouseY >= y && mouseY < y + slotHeight;
        if (rowHovered) {
            PackOverlayRenderer.INSTANCE.markResourcePackEntryHovered(self);
        }
        ResourcePackRepositoryEntryAccessor entry = (ResourcePackRepositoryEntryAccessor) ((ResourcePackListEntryFound) self)
            .func_148318_i();
        java.io.File file = entry.getResourcePackFile();
        if (file == null) return;
        java.io.File folder = PackOverlayRenderer.INSTANCE.resourcePacksFolder();
        String platform = PackOverlayRenderer.INSTANCE.lookupPlatform(folder, file);
        // Badge: only shown for packs we installed (have a platform record).
        if (platform != null) {
            int badge = 10;
            // Vanilla draws the 32x32 pack icon at the row's top-left (x, y).
            // Anchor the badge to its bottom-right corner.
            PackOverlayRenderer.INSTANCE.drawBadge(platform, x + 32 - badge, y + 32 - badge, badge, mouseX, mouseY);
        }
        // Cross: shown for real files in the resource-pack folder while the
        // row is hovered. Virtual repository entries do not get a delete
        // affordance.
        if (rowHovered && PackOverlayRenderer.INSTANCE.canDeletePack(folder, file)) {
            int cross = 16;
            // Bigger right inset so the cross stays clear of the GuiSlot
            // scrollbar that vanilla paints inside the slot bounds. Row
            // content sits a couple pixels above the slot center because
            // vanilla pads the bottom; nudge the cross up to compensate.
            int cx = x + listWidth - cross - 10;
            int cy = y + (slotHeight - cross) / 2 - 2;
            PackOverlayRenderer.INSTANCE.drawDeleteButton(folder, file, file.getName(), cx, cy, cross, mouseX, mouseY);
        }
    }

    @Inject(method = "mousePressed", at = @At("HEAD"))
    private void resourcify$playSoundOnPackArrow(int index, int x, int y, int mouseEvent, int relativeX, int relativeY,
        CallbackInfoReturnable<Boolean> cir) {
        if (!this.func_148310_d() || relativeX > 32) return;
        if (this.func_148309_e() || (relativeX < 16 && this.func_148308_f())) {
            PackOverlayRenderer.INSTANCE.playEntrySelectSound();
            return;
        }
        if (relativeX > 16 && ((relativeY < 16 && this.func_148314_g()) || (relativeY > 16 && this.func_148307_h()))) {
            PackOverlayRenderer.INSTANCE.playEntryTickSound();
        }
    }
}
