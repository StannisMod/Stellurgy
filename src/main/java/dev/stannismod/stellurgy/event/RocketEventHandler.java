package dev.stannismod.stellurgy.event;
// This code does not work - it should display the earth below rockets at start but it does not.
// The detailed map is scaled too small and it is ugly even with correct scale
// maybe just use leo as earth? 


import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.entity.Entity;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.MathHelper;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent.ElementType;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraft.world.World;
import dev.stannismod.stellurgy.world.WorldRuntime;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;
import dev.stannismod.stellurgy.api.StellurgyConfiguration;
import dev.stannismod.stellurgy.api.armor.IFillableArmor;
import dev.stannismod.stellurgy.client.ClientAtmosphere;
import dev.stannismod.stellurgy.client.FreeFlightHudState;
import dev.stannismod.stellurgy.client.HudLayout;
import dev.stannismod.stellurgy.client.KeyBindings;
import dev.stannismod.stellurgy.client.render.ClientDynamicTexture;
import dev.stannismod.stellurgy.entity.EntityRocket;
import dev.stannismod.stellurgy.inventory.TextureResources;
import dev.stannismod.stellurgy.tile.TilePilotSeat;
import dev.stannismod.stellurgy.util.ItemAirUtils;
import dev.stannismod.stellurgy.libvulpes.api.IArmorComponent;
import dev.stannismod.stellurgy.libvulpes.api.IModularArmor;
import dev.stannismod.stellurgy.libvulpes.client.ResourceIcon;
import dev.stannismod.stellurgy.libvulpes.render.RenderHelper;

import javax.annotation.Nonnull;
import java.util.List;

public class RocketEventHandler extends Gui {


    private static final int numTicksToDisplay = 100;

    /**
     * The HUD state a client WORLD owns: the overlay message, stamped with that world's clock, and
     * the window after arriving in it during which the suffocation warning stays quiet. Owned by the
     * world ({@link WorldRuntime}) because every value here is meaningful only against its clock —
     * kept across a world change, a stamp from an old world is compared with a younger one and the
     * message stays up until the new clock catches up.
     */
    private static final class HudState {
        String message = "";
        long messageUntil = -1000;
        boolean arrived;
        long suppressWarningUntil = Long.MIN_VALUE;
    }

    private static HudState hudOf(World world) {
        return WorldRuntime.of(world, HudState.class, HudState::new);
    }

    private final ResourceLocation background = TextureResources.rocketHud;


    /** [-1,1] clamp for HUD bar/dot geometry; NaN-safe. */
    private static double clampUnit(double v) {
        if (Double.isNaN(v)) return 0;
        return Math.max(-1.0, Math.min(1.0, v));
    }

    /**
     * Render the backend-agnostic Free Flight HUD from a {@link FreeFlightHudState} snapshot: the
     * text legend (bottom-left), and while flying the velocity bars (only when the backend supplies
     * velocity — the tier-2 ship omits them), the turn-rate dot, and the centre flight cursor.
     * The same code serves a tier-1 rocket and a tier-2 ship.
     */
    private static void renderFreeFlightHud(RenderGameOverlayEvent.Post event, Minecraft mc,
                                            FreeFlightHudState state) {
        FontRenderer fr = mc.fontRenderer;
        List<String> ffLines = KeyBindings.freeFlightHudLines(state);
        // No store of the joined text: `KeyBindings.freeFlightHudLines` is public and this method is
        // where the HUD is drawn, so a watcher on it composes the same line from the same snapshot.
        int lineH = fr.FONT_HEIGHT + 1;
        int scaledH2 = event.getResolution().getScaledHeight();
        // Bottom-left, to the right of the instrument panel, stacked up.
        int ffX = 22;
        int ffY = scaledH2 - 4 - ffLines.size() * lineH;
        for (int i = 0; i < ffLines.size(); i++) {
            // Title/indicator line brighter; legend lines in FF cyan.
            int color = (i == 0) ? 0x66FFE0 : 0xB0F0FF;
            fr.drawStringWithShadow(ffLines.get(i), ffX, ffY + i * lineH, color);
        }
        if (!state.inFlight) {
            return;
        }

        int barX = ffX + 150, barW = 60, barH = 4;
        int barsTop = scaledH2 - 4 - 3 * (barH + 3) - 22;
        // Graphic thrust/velocity bars. Per body axis: a bipolar bar scaled to the craft's own top
        // speed — cyan fill = actual velocity, notch = FA setpoint.
        if (state.hasVelocity) {
            double[] act = {state.bodyForward, state.bodyRight, state.bodyUp};
            double[] sp = {state.faForward, state.faRight, state.faUp};
            String[] axis = {"FWD", "LAT", "VRT"};
            double max = state.barScale;
            for (int i = 0; i < 3; i++) {
                int y = barsTop + i * (barH + 3);
                fr.drawStringWithShadow(axis[i], barX - 22, y - 2, 0xB0F0FF);
                drawRect(barX, y, barX + barW, y + barH, 0xA0202830);
                int mid = barX + barW / 2;
                drawRect(mid, y - 1, mid + 1, y + barH + 1, 0xFF607078);
                int actPx = (int) (clampUnit(act[i] / max) * (barW / 2.0));
                if (actPx >= 0) drawRect(mid, y, mid + Math.max(actPx, 0) + 1, y + barH, 0xFF40D0FF);
                else            drawRect(mid + actPx, y, mid + 1, y + barH, 0xFF40D0FF);
                if (state.flightAssistOn) {
                    int spPx = mid + (int) (clampUnit(sp[i] / max) * (barW / 2.0));
                    drawRect(spPx - 1, y - 1, spPx + 1, y + barH + 1, 0xFFFFE060);
                }
            }
        }

        // Turn-rate dot: deflection from center = commanded yaw (x) / pitch (y). Both backends
        // publish these to the shared KeyBindings fields.
        int boxC = barX + barW + 18, boxR = 8;
        int boxYc = barsTop + (3 * (barH + 3)) / 2;
        drawRect(boxC - boxR, boxYc - boxR, boxC + boxR, boxYc + boxR, 0xA0202830);
        drawRect(boxC - boxR, boxYc, boxC + boxR, boxYc + 1, 0xFF607078);
        drawRect(boxC, boxYc - boxR, boxC + 1, boxYc + boxR, 0xFF607078);
        int dx = (int) (clampUnit(KeyBindings.hudYawRate())   * (boxR - 2));
        int dy = (int) (clampUnit(KeyBindings.hudPitchRate()) * (boxR - 2));
        drawRect(boxC + dx - 1, boxYc + dy - 1, boxC + dx + 2, boxYc + dy + 2, 0xFF40D0FF);

        // Elite-style flight cursor at screen centre: a square deflection zone with a dot at the
        // current (roll = X, pitch = Y) deflection. Absolute — stays where the mouse leaves it.
        ScaledResolution sr = new ScaledResolution(Minecraft.getMinecraft());
        int ccx = sr.getScaledWidth() / 2, ccy = sr.getScaledHeight() / 2;
        int zone = 40;
        drawRect(ccx - zone, ccy - zone, ccx + zone, ccy - zone + 1, 0x50FFFFFF);
        drawRect(ccx - zone, ccy + zone, ccx + zone, ccy + zone + 1, 0x50FFFFFF);
        drawRect(ccx - zone, ccy - zone, ccx - zone + 1, ccy + zone, 0x50FFFFFF);
        drawRect(ccx + zone, ccy - zone, ccx + zone + 1, ccy + zone, 0x50FFFFFF);
        float pt = event.getPartialTicks();
        int fcx = (int) (clampUnit(KeyBindings.flightCursorX(pt)) * zone);
        int fcy = (int) (clampUnit(KeyBindings.flightCursorY(pt)) * zone);
        drawRect(ccx + fcx - 2, ccy + fcy - 2, ccx + fcx + 3, ccy + fcy + 3, 0xFFFFE060);
    }

    /** Show {@code msg} until the current client world's clock passes {@code endTime}. */
    @SideOnly(Side.CLIENT)
    public static void setOverlay(long endTime, String msg) {
        HudState hud = hudOf(Minecraft.getMinecraft().world);
        hud.message = msg;
        hud.messageUntil = endTime;
    }

    /**
     * Free Flight camera attitude. Locks the render camera to the craft's
     * yaw/pitch/roll every FRAME (interpolated), overriding the vanilla
     * mouse-driven view. This is what makes the mouse smooth while moving: the
     * mouse only feeds the deflection cursor (read per tick in KeyBindings) and
     * never leaks into the camera between ticks — plus it applies the roll
     * (bank) DOF, which vanilla has no player-camera field for.
     */
    @SubscribeEvent
    public void onFreeFlightCameraSetup(net.minecraftforge.client.event.EntityViewRenderEvent.CameraSetup event) {
        net.minecraft.entity.Entity view = Minecraft.getMinecraft().getRenderViewEntity();
        if (view == null) return;
        net.minecraft.entity.Entity ridden = view.getRidingEntity();
        float p = (float) event.getRenderPartialTicks();

        // Flight recorder, per-FRAME channel, taken before every branch below because each of them
        // returns. This is the only sample in the game that is the pilot's actual eye point, so it
        // is the only one that can answer "is the PICTURE jerking" as opposed to "is the ship". A
        // frame that arrives late — chunk meshing, a collection pause — shows up here as a gap and
        // nowhere else.

        if (ridden instanceof dev.stannismod.stellurgy.entity.EntityRocket) {
            dev.stannismod.stellurgy.entity.EntityRocket rocket =
                    (dev.stannismod.stellurgy.entity.EntityRocket) ridden;
            if (!(rocket.isFreeFlight() && rocket.isInFlight())) return;

            // Slerp the attitude quaternion this frame, then derive the camera Euler -
            // pole-safe through loops (see RendererRocket). The quaternion is the FF
            // attitude source of truth; deriving yaw/pitch/roll here reproduces the
            // craft basis exactly, so the view looks out the nose and banks with roll.
            dev.stannismod.stellurgy.api.FreeFlightPhysics.Quat cq =
                    dev.stannismod.stellurgy.api.FreeFlightPhysics.slerp(
                            rocket.getPrevFfQuat(), rocket.getFfQuat(), p);
            float[] e = dev.stannismod.stellurgy.api.FreeFlightPhysics.eulerFromQuat(cq);
            // +180: the vanilla camera-yaw convention faces opposite the raw
            // heading, so the ship yaw must be flipped to look out the nose.
            event.setYaw(e[0] + 180f);
            event.setPitch(e[1]);
            event.setRoll(e[2]);
            return;
        }

        // Tier-2 ship: the pilot rides a seat dummy, not a rocket. Lock the camera to the ship's
        // attitude, slerped from its previous to its current tick by partialTicks for a smooth
        // per-frame view - the same nose-lock + no-free-look behaviour as the rocket. Without this
        // the ship view jitters (mouse leaks into free-look between ticks).
        TilePilotSeat seat = TilePilotSeat.forRider(ridden, Minecraft.getMinecraft().world);
        dev.stannismod.stellurgy.api.FreeFlightPhysics.Quat cq = seat != null && seat.isLinked()
                ? dev.stannismod.stellurgy.integration.vs.VSIntegration.getShipAttitude(
                        Minecraft.getMinecraft().world, seat.getPos(), p)
                : null;
        if (cq != null) {
            float[] e = dev.stannismod.stellurgy.api.FreeFlightPhysics.eulerFromQuat(cq);
            event.setYaw(e[0] + 180f);
            event.setPitch(e[1]);
            event.setRoll(e[2]);
            return;
        }

        // A crew member standing on a deck: level the horizon with the deck, and nothing else. Rolling
        // the view is the ONLY degree of freedom added - his yaw and pitch come back exactly as he
        // aimed them, so getLook(), and therefore which block he mines, is untouched. Composing a full
        // ship-frame look instead would silently aim his cursor somewhere the camera is not pointing.
        //
        // Gate on the SAME "actually on a deck" truth the movement uses (ShipFrameTravel is resolving
        // this body), not on mere containment in the ship's world AABB. That box is axis-aligned and
        // overlaps a large air (and, for a grounded ship, terrain) volume around the hull; levelling the
        // view for anyone inside it hijacks the camera of a player merely flying up through the airspace
        // or standing on the ground beside the hull - he is not on the deck, so his view must be his own.
        // ABOARD specifically: a HULL-STAND body - standing on the OUTER hull, where the ship frame
        // has no floor beneath it - keeps world-frame semantics, so its camera is its own and is
        // never levelled to a deck it is not standing on.
        if (!dev.stannismod.stellurgy.integration.vs.ShipFrameTravel.isResolvingAboard(view)) {
            return;
        }
        dev.stannismod.stellurgy.api.FreeFlightPhysics.Quat shipQ =
                dev.stannismod.stellurgy.client.ShipFrameCamera.viewShipQuat(view, p);
        if (shipQ == null) {
            return;
        }
        double[] shipUp = shipQ.rotate(0.0, 1.0, 0.0);
        // A walking crew member's aim lives in the DECK frame (DeckLook): his world yaw/pitch are
        // DERIVED from it through the ship attitude, and the camera is the SAME composition
        // (ship attitude * deck look) - one transform for the mouse, the aim and the view, at any
        // attitude. Deriving here, with the exact quat this frame's camera composes, makes the
        // crosshair ray and the rendered view one rotation by construction. The old roll-only
        // horizon levelling was singular on a vertical deck (the roll estimate flips with the look
        // direction there, and the mouse feel flipped with it); a composed look has no such pole.
        float[] e;
        if (view == Minecraft.getMinecraft().player) {
            dev.stannismod.stellurgy.client.DeckLook.frame(view, shipQ);
        }
        if (view == Minecraft.getMinecraft().player
                && dev.stannismod.stellurgy.client.DeckLook.isActive()) {
            dev.stannismod.stellurgy.api.FreeFlightPhysics.Quat cam =
                    shipQ.mul(dev.stannismod.stellurgy.client.DeckLook.lookQuat());
            e = dev.stannismod.stellurgy.api.FreeFlightPhysics.eulerFromQuat(cam);
        } else {
            // Not this client's own deck look (spectating an aboard body, or the deck look could
            // not engage this instant): fall back to roll-levelling the body's own world aim.
            e = dev.stannismod.stellurgy.client.ShipFrameCamera
                    .deckLevelledCameraEuler(shipUp, event.getYaw() - 180f, (float) event.getPitch());
            if (e == null) {
                return; // looking straight along the deck normal: roll is undefined, hold the last one
            }
        }
        event.setYaw(e[0] + 180f);
        event.setPitch(e[1]);
        event.setRoll(e[2]);
    }

    /**
     * Suppress the first-person hand/held-item render while piloting a Free
     * Flight craft. The camera is hard-locked to the craft axes every frame
     * ({@link #onFreeFlightCameraSetup}) while the held item still renders off
     * the player's own (now-overridden) rotation, so it jitters against the
     * locked view — and a block bobbing in the cockpit adds nothing anyway.
     */
    @SubscribeEvent
    public void onFreeFlightRenderHand(net.minecraftforge.client.event.RenderSpecificHandEvent event) {
        net.minecraft.entity.Entity view = Minecraft.getMinecraft().getRenderViewEntity();
        if (view == null) return;
        net.minecraft.entity.Entity ridden = view.getRidingEntity();
        if (ridden instanceof dev.stannismod.stellurgy.entity.EntityRocket
                && ((dev.stannismod.stellurgy.entity.EntityRocket) ridden).isFreeFlight()
                && ((dev.stannismod.stellurgy.entity.EntityRocket) ridden).isInFlight()) {
            event.setCanceled(true);
            return;
        }
        // Same reasoning for a tier-2 ship pilot (camera hard-locked to the ship nose).
        TilePilotSeat seat = TilePilotSeat.forRider(ridden, Minecraft.getMinecraft().world);
        if (seat != null && seat.isLinked()) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onScreenRender(RenderGameOverlayEvent.Post event) {
        Entity ride;
        if (event.getType() == ElementType.HOTBAR) {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.player == null || mc.world == null) {
                return;
            }
            if ((ride = mc.player.getRidingEntity()) instanceof EntityRocket) {
                EntityRocket rocket = (EntityRocket) ride;

                GlStateManager.enableBlend();

                mc.renderEngine.bindTexture(background);

                //Draw BG
                this.drawTexturedModalRect(0, 0, 0, 0, 17, 252);

                //Draw altitude indicator
                float percentOrbit = MathHelper.clamp((float) ((rocket.posY - rocket.world.provider.getAverageGroundLevel()) / (float) (StellurgyConfiguration.getCurrentConfig().orbit - rocket.world.provider.getAverageGroundLevel())), 0f, 1f);
                this.drawTexturedModalRect(3, 8 + (int) (79 * (1 - percentOrbit)), 17, 0, 6, 6); //6 to 83

                //Draw Velocity indicator
                this.drawTexturedModalRect(3, 94 + (int) (69 * (0.5 - (MathHelper.clamp((float) (rocket.motionY), -1f, 1f) / 2f))), 17, 0, 6, 6); //94 to 161

                //Draw fuel indicator
                int size = (int) (68 * rocket.getNormallizedProgress(0));
                this.drawTexturedModalRect(3, 242 - size, 17, 75 - size, 3, size); //94 to 161

                GlStateManager.disableBlend();
                String str = rocket.getTextOverlay();
                if (!str.isEmpty()) {

                    String[] strs = str.split("\n");
                    int vertPos = 0;
                    for (String strPart : strs) {

                        FontRenderer fontRenderer = mc.fontRenderer;

                        float scale = str.length() < 50 ? 1f : 0.5f;

                        int screenX = (int) ((event.getResolution().getScaledWidth() / (scale * 6) - fontRenderer.getStringWidth(strPart) / 2));
                        int screenY = (int) ((event.getResolution().getScaledHeight() / 18) / scale) + 18 * vertPos;


                        GL11.glPushMatrix();
                        GL11.glScalef(scale * 3, scale * 3, scale * 3);

                        fontRenderer.drawStringWithShadow(strPart, screenX, screenY, 0xFFFFFF);

                        GL11.glPopMatrix();

                        vertPos++;
                    }
                }
                // New bottom-right hint
                if (mc.currentScreen == null) { // no GUI open
                    FontRenderer fontRenderer = mc.fontRenderer;
                    String keyName = GameSettings.getKeyDisplayString(
                            KeyBindings.getOpenRocketUI().getKeyCode()
                    );
                    String hint = I18n.format("msg.entity.rocket.openGuiHint", keyName);

                    int scaledW = event.getResolution().getScaledWidth();
                    int scaledH = event.getResolution().getScaledHeight();
                    int textWidth = fontRenderer.getStringWidth(hint);
                    int textHeight = fontRenderer.FONT_HEIGHT;

                    float scale = 1.0F;
                    float x = (scaledW - 4 - textWidth * scale) / scale;
                    float y = (scaledH - 4 - textHeight * scale) / scale;

                    GL11.glPushMatrix();
                    GL11.glScalef(scale, scale, scale);
                    fontRenderer.drawStringWithShadow(hint, x, y, 0xFFFFFF);
                    GL11.glPopMatrix();
                }

                // Free Flight Mode HUD is rendered below (backend-agnostic — it also serves the
                // tier-2 ship), driven by a FreeFlightHudState snapshot rather than this rocket.


            }

            // Free Flight HUD — backend-agnostic: renders for a tier-1 rocket AND a tier-2 ship
            // (piloted from a seat), driven by one snapshot. Outside the EntityRocket block above
            // because the ship pilot rides a seat dummy, not a rocket.
            if (mc.currentScreen == null) {
                FreeFlightHudState ffState = FreeFlightHudState.forView(mc.player, mc.world);
                if (ffState != null) {
                    renderFreeFlightHud(event, mc, ffState);
                }
            }

            //Draw the O2 Bar if needed
            if (!(mc.player.capabilities.isCreativeMode || mc.player.isSpectator())) {
                ItemStack chestPiece = mc.player.getItemStackFromSlot(EntityEquipmentSlot.CHEST);
                IFillableArmor fillable = null;
                if (!chestPiece.isEmpty() && chestPiece.getItem() instanceof IFillableArmor)
                    fillable = (IFillableArmor) chestPiece.getItem();
                else if (ItemAirUtils.INSTANCE.isStackValidAirContainer(chestPiece))
                    fillable = new ItemAirUtils.ItemAirWrapper(chestPiece);

                if (fillable != null) {
                    float size = fillable.getAirRemaining(chestPiece) / (float) fillable.getMaxAir(chestPiece);

                    GlStateManager.enableBlend();
                    mc.renderEngine.bindTexture(background);
                    GlStateManager.color(1f, 1f, 1f);
                    int width = 83;
                    int screenX = HudLayout.oxygenBarX(event.getResolution().getScaledWidth());
                    int screenY = HudLayout.oxygenBarY(event.getResolution().getScaledHeight());

                    //Draw BG
                    this.drawTexturedModalRect(screenX, screenY, 23, 0, width, 17);
                    this.drawTexturedModalRect(screenX, screenY, 23, 17, (int) (width * size), 17);
                }
            }

            //Draw module icons
            if (!(mc.player.capabilities.isCreativeMode || mc.player.isSpectator()) && !mc.player.getItemStackFromSlot(EntityEquipmentSlot.HEAD).isEmpty() && mc.player.getItemStackFromSlot(EntityEquipmentSlot.HEAD).getItem() instanceof IModularArmor) {
                for (EntityEquipmentSlot slot : EntityEquipmentSlot.values()) {
                    renderModuleSlots(mc.player.getItemStackFromSlot(slot), 4 - slot.getIndex(), event);
                }
            }


            long worldTime = mc.world.getTotalWorldTime();
            HudState hud = hudOf(mc.world);
            ClientAtmosphere air = ClientAtmosphere.of(mc.world);

            // First frame in this world (a dimension change or a new connection each build one): hold
            // the warning for 40 ticks.
            if (!hud.arrived) {
                hud.arrived = true;
                hud.suppressWarningUntil = worldTime + 40;
            }

            // Tell the player he's suffocating if needed
            if (worldTime >= hud.suppressWarningUntil && air.suffocatedWithin(worldTime, numTicksToDisplay)) {
                FontRenderer fontRenderer = mc.fontRenderer;
                String str = "";
                if (air.atmosphere() != null) {
                    str = air.atmosphere().getDisplayMessage();
                }

                int screenX = event.getResolution().getScaledWidth() / 6 - fontRenderer.getStringWidth(str) / 2;
                int screenY = event.getResolution().getScaledHeight() / 18;

                GL11.glPushMatrix();
                GL11.glScalef(3, 3, 3);

                fontRenderer.drawStringWithShadow(str, screenX, screenY, 0xFF5656);
                GlStateManager.color(1f, 1f, 1f);
                mc.getTextureManager().bindTexture(TextureResources.progressBars);
                this.drawTexturedModalRect(screenX + fontRenderer.getStringWidth(str) / 2 - 8, screenY - 16, 0, 156, 16, 16);

                GL11.glPopMatrix();
            }

            //Draw arbitrary string
            if (worldTime <= hud.messageUntil) {
                FontRenderer fontRenderer = mc.fontRenderer;
                GL11.glPushMatrix();
                GL11.glScalef(2, 2, 2);
                int loc = 0;
                for (String str : hud.message.split("\n")) {

                    int screenX = event.getResolution().getScaledWidth() / 4 - fontRenderer.getStringWidth(str) / 2;
                    int screenY = event.getResolution().getScaledHeight() / 12 + loc * (event.getResolution().getScaledHeight()) / 12;


                    fontRenderer.drawStringWithShadow(str, screenX, screenY, 0xFF5656);
                    loc++;
                }

                GlStateManager.color(1f, 1f, 1f);
                GL11.glPopMatrix();
            }
        }
    }

    private void renderModuleSlots(@Nonnull ItemStack armorStack, int slot, RenderGameOverlayEvent event) {
        int index = 1;
        float color = 0.85f + 0.15F * MathHelper.sin(2f * (float) Math.PI * ((Minecraft.getMinecraft().world.getTotalWorldTime()) % 60) / 60f);
        BufferBuilder buffer = Tessellator.getInstance().getBuffer();
        GlStateManager.enableBlend();
        GlStateManager.enableAlpha();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        float alpha = 0.6f;


        if (!armorStack.isEmpty()) {

            boolean modularArmorFlag = armorStack.getItem() instanceof IModularArmor;

            if (modularArmorFlag || ItemAirUtils.INSTANCE.isStackValidAirContainer(armorStack)) {

                int size = 24;
                int panelX = HudLayout.suitPanelX(event.getResolution().getScaledWidth());
                int screenY = HudLayout.suitPanelY(event.getResolution().getScaledHeight()) + (slot - 1) * (size + 8);
                int screenX = panelX;

                //Draw BG
                GlStateManager.color(1f, 1f, 1f, 1f);
                Minecraft.getMinecraft().renderEngine.bindTexture(TextureResources.frameHUDBG);
                buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
                RenderHelper.renderNorthFaceWithUV(buffer, this.zLevel - 1, screenX - 4, screenY - 4, screenX + size, screenY + size + 4, 0d, 0.5d, 0d, 1d);
                Tessellator.getInstance().draw();

                Minecraft.getMinecraft().renderEngine.bindTexture(TextureResources.frameHUDBG);
                buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
                RenderHelper.renderNorthFaceWithUV(buffer, this.zLevel - 1, screenX + size, screenY - 3, screenX + 2 + size, screenY + size + 3, 0.5d, 0.5d, 0d, 0d);
                Tessellator.getInstance().draw();

                //Draw Icon
                GlStateManager.color(color, color, color, color);
                Minecraft.getMinecraft().renderEngine.bindTexture(TextureResources.armorSlots[slot - 1]);
                buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
                RenderHelper.renderNorthFaceWithUV(buffer, this.zLevel - 1, screenX, screenY, screenX + size, screenY + size, 0d, 1d, 1d, 0d);
                Tessellator.getInstance().draw();

                if (modularArmorFlag) {
                    List<ItemStack> stacks = ((IModularArmor) armorStack.getItem()).getComponents(armorStack);
                    for (ItemStack stack : stacks) {
                        GlStateManager.color(1f, 1f, 1f, 1f);
                        ((IArmorComponent) stack.getItem()).renderScreen(stack, stacks, event, this);

                        ResourceIcon icon = ((IArmorComponent) stack.getItem()).getComponentIcon(stack);
                        ResourceLocation texture = null;
                        if (icon != null)
                            texture = icon.getResourceLocation();

                        //if(texture != null) {

                        screenX = panelX + 4 + index * (size + 2);

                        //Draw BG

                        Minecraft.getMinecraft().renderEngine.bindTexture(TextureResources.frameHUDBG);
                        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
                        RenderHelper.renderNorthFaceWithUV(buffer, this.zLevel - 1, screenX - 4, screenY - 4, screenX + size - 2, screenY + size + 4, 0.5d, 0.5d, 0d, 1d);
                        Tessellator.getInstance().draw();


                        if (texture != null) {
                            //Draw Icon
                            Minecraft.getMinecraft().renderEngine.bindTexture(texture);
                            GlStateManager.color(color, color, color, alpha);
                            buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
                            RenderHelper.renderNorthFaceWithUV(buffer, this.zLevel - 1, screenX, screenY, screenX + size, screenY + size, icon.getMinU(), icon.getMaxU(), icon.getMaxV(), icon.getMinV());
                            Tessellator.getInstance().draw();
                        } else {
                            GL11.glPushMatrix();
                            GlStateManager.translate(screenX, screenY, 0);
                            GlStateManager.scale(1.5f, 1.5f, 1.5f);
                            Minecraft.getMinecraft().getRenderItem().renderItemIntoGUI(stack, 0, 0);
                            GL11.glPopMatrix();
                        }

                        index++;
                        //}
                    }
                }

                screenX = (index) * (size + 2) + panelX - 12;
                //Draw BG
                GlStateManager.color(1, 1, 1, 1f);
                Minecraft.getMinecraft().renderEngine.bindTexture(TextureResources.frameHUDBG);
                buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
                RenderHelper.renderNorthFaceWithUV(buffer, this.zLevel - 1, screenX + 12, screenY - 4, screenX + size, screenY + size + 4, 0.75d, 1d, 0d, 1d);
                Tessellator.getInstance().draw();
            }
        }

        GlStateManager.disableAlpha();
    }
}
