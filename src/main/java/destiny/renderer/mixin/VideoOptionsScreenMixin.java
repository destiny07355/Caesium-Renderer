package destiny.renderer.mixin;

import destiny.renderer.config.RendererConfig;
import destiny.renderer.gui.DestinySettingsScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.option.GameOptionsScreen;
import net.minecraft.client.gui.screen.option.VideoOptionsScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.option.GameOptions;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Integrates Caesium's settings into the vanilla Video Settings screen.
 *
 * <h2>Two modes</h2>
 * <ul>
 *   <li><b>Full replacement</b> ({@code replaceVideoSettings = true}, default) — opening
 *       Video Settings goes straight to our screen, which already contains every vanilla
 *       video option properly categorised.</li>
 *   <li><b>Button injection</b> — vanilla's screen is left completely intact and a
 *       "Caesium..." button is added to it. Safer for users running other mods
 *       that also modify the video menu.</li>
 * </ul>
 *
 * <h2>Why {@code addOptions} and not {@code init}</h2>
 * {@code init()} is inherited from {@code GameOptionsScreen} and does not appear in
 * VideoOptionsScreen's own bytecode, so a mixin targeting it has nothing to transform and
 * silently never applies. {@code addOptions()} is genuinely declared here.
 */
@Mixin(VideoOptionsScreen.class)
public abstract class VideoOptionsScreenMixin extends GameOptionsScreen {

    // Never invoked; present only to satisfy the compiler that this mixin is a valid
    // subtype of the target's superclass so `parent` is reachable.
    private VideoOptionsScreenMixin(Screen parent, GameOptions options, Text title) {
        super(parent, options, title);
    }

    @Inject(method = "addOptions", at = @At("HEAD"), cancellable = true)
    private void destinyrenderer$maybeReplace(CallbackInfo ci) {
        if (this.client == null) return;
        if (!RendererConfig.get().replaceVideoSettings) return;

        this.client.setScreen(new DestinySettingsScreen(this.parent));
        ci.cancel();
    }

    /**
     * In button-injection mode, adds an entry point to our screen while leaving every
     * vanilla control exactly where the user expects it.
     */
    @Inject(method = "addOptions", at = @At("TAIL"))
    private void destinyrenderer$addButton(CallbackInfo ci) {
        if (this.client == null) return;
        if (RendererConfig.get().replaceVideoSettings) return;

        Screen self = (Screen) (Object) this;
        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("Caesium..."),
                b -> this.client.setScreen(new DestinySettingsScreen(self)))
            .dimensions(this.width / 2 - 75, this.height - 50, 150, 20)
            .build());
    }
}
