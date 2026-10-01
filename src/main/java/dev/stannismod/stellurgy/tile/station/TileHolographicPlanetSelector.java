package dev.stannismod.stellurgy.tile.station;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ITickable;
import net.minecraftforge.fml.relauncher.Side;
import dev.stannismod.stellurgy.api.StellurgyBlocks;
import dev.stannismod.stellurgy.api.Constants;
import dev.stannismod.stellurgy.api.dimension.IDimensionProperties;
import dev.stannismod.stellurgy.api.dimension.solar.StellarBody;
import dev.stannismod.stellurgy.api.stations.ISpaceObject;
import dev.stannismod.stellurgy.dimension.DimensionManager;
import dev.stannismod.stellurgy.dimension.DimensionProperties;
import dev.stannismod.stellurgy.entity.EntityUIButton;
import dev.stannismod.stellurgy.entity.EntityUIPlanet;
import dev.stannismod.stellurgy.entity.EntityUIStar;
import dev.stannismod.stellurgy.inventory.TextureResources;
import dev.stannismod.stellurgy.stations.SpaceObjectManager;
import dev.stannismod.stellurgy.util.AstronomicalBodyHelper;
import dev.stannismod.stellurgy.libvulpes.LibVulpes;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.*;
import dev.stannismod.stellurgy.libvulpes.network.PacketHandler;
import dev.stannismod.stellurgy.libvulpes.network.PacketMachine;
import dev.stannismod.stellurgy.libvulpes.util.INetworkMachine;
import dev.stannismod.stellurgy.libvulpes.util.ZUtils.RedstoneState;

import java.util.Collection;
import java.util.LinkedList;
import java.util.List;

public class TileHolographicPlanetSelector extends TileEntity implements ITickable, IButtonInventory, IModularInventory, ISliderBar, INetworkMachine {

    private static final byte SCALEPACKET = 0;
    private static final byte STATEUPDATE = 1;
    private List<EntityUIPlanet> entities;
    private List<EntityUIStar> starEntities;
    private EntityUIPlanet centeredEntity;
    private EntityUIPlanet selectedPlanet;
    private EntityUIStar currentStar;
    private EntityUIButton backButton;
    private StellarBody currentStarBody;
    private ModuleRedstoneOutputButton redstoneControl;
    private RedstoneState state;
    private int selectedId;
    private float onTime;
    private ModuleText targetGrav;
    private float size;
    private boolean allowUpdate = true;  //Hack to get around the delay in entity position
    private boolean stellarMode;

    public TileHolographicPlanetSelector() {
        entities = new LinkedList<>();
        starEntities = new LinkedList<>();
        targetGrav = new ModuleText(6, 45, LibVulpes.proxy.getLocalizedString("msg.planetholo.size"), 0x202020);
        selectedPlanet = null;
        stellarMode = false;
        selectedId = Constants.INVALID_PLANET;
        onTime = 1f;
        size = 0.02f;
        redstoneControl = new ModuleRedstoneOutputButton(174, 4, 1, "", this);
        state = RedstoneState.OFF;
        redstoneControl.setRedstoneState(state);
    }

    @Override
    public void invalidate() {
        super.invalidate();
        cleanup();
    }

    private void cleanup() {
        for (EntityUIPlanet planet : entities) {
            planet.setDead();
        }
        entities.clear();

        for (EntityUIStar star : starEntities) star.setDead();
        starEntities.clear();

        selectedPlanet = null;
        centeredEntity = null;
        //currentStarBody = null;
        selectedId = Constants.INVALID_PLANET;


        if (currentStar != null) {
            currentStar.setDead();
            currentStar = null;
        }
        if (backButton != null) {
            backButton.setDead();
            backButton = null;
        }
    }

    public boolean isEnabled() {
        boolean powered = world.isBlockIndirectlyGettingPowered(getPos()) > 0;
        return (!powered && state == RedstoneState.INVERTED) || (powered && state == RedstoneState.ON) || state == RedstoneState.OFF;
    }

    @Override
    public void update() {
        if (!world.isRemote) {
            if (isEnabled()) {

                if (onTime < 1)
                    onTime += .2f / getHologramSize();//0.02f;

                if (allowUpdate) {
                    for (EntityUIPlanet entity : entities) {
                        DimensionProperties properties = entity.getProperties();
                        if (entity != centeredEntity)
                            entity.setPositionPolar(this.pos.getX() + .5, this.pos.getY() + 1, this.pos.getZ() + .5, getInterpHologramSize() * projectionRadius(properties), properties.orbitTheta);
                        entity.setScale(getInterpHologramSize());
                    }

                    if (stellarMode) {
                        for (EntityUIStar entity : starEntities) {
                            entity.setPosition(this.pos.getX() + .5 + getInterpHologramSize() * entity.getStarProperties().getPosX() / 100f, this.pos.getY() + 1, this.pos.getZ() + .5 + getInterpHologramSize() * entity.getStarProperties().getPosZ() / 100f);
                            entity.setScale(getInterpHologramSize());
                        }
                    } else {
                        if (!starEntities.isEmpty()) {
                            float phaseInc = 4 * 360f / starEntities.size();
                            float phase = 0;
                            for (EntityUIStar entity : starEntities) {
                                double deltaX, deltaY;
                                deltaX = companionOffset(entity.getStarProperties())
                                        * Math.cos(entity.getStarProperties().getBaseTheta());
                                deltaY = companionOffset(entity.getStarProperties())
                                        * Math.sin(entity.getStarProperties().getBaseTheta());

                                entity.setPosition(this.pos.getX() + .5 + getInterpHologramSize() * deltaX, this.pos.getY() + 1, this.pos.getZ() + .5 + getInterpHologramSize() * deltaY);
                                entity.setScale(getInterpHologramSize() * entity.getStarProperties().getSize());
                                phase += phaseInc;
                            }
                        }
                    }

                    if (currentStar != null) {
                        currentStar.setScale(getInterpHologramSize());
                        currentStar.setPositionPolar(this.pos.getX() + .5, this.pos.getY() + 1, this.pos.getZ() + .5, 0, 0);
                    }

                    if (centeredEntity != null) {
                        centeredEntity.setPositionPolar(this.pos.getX() + .5, this.pos.getY() + 1, this.pos.getZ() + .5, 0, 0);
                    }

                    if (entities.isEmpty() && starEntities.isEmpty()) {
                        rebuildSystem();
                    }

                    if (backButton != null) {
                        backButton.setPosition(this.pos.getX() + .5, this.pos.getY() + 1.5 + getInterpHologramSize() / 10f, this.pos.getZ() + .5);
                    }
                } else
                    allowUpdate = true;
            } else { //isenabled
                if (backButton != null)
                    cleanup();
            }
        }
    }

    public void selectSystem(int id) {

        if (id >= EntityUIStar.starIDoffset) {
            if (stellarMode) {
                if (selectedId != id) {
                    for (EntityUIStar entity : starEntities) {
                        if (entity.getPlanetID() + EntityUIStar.starIDoffset == id) {
                            entity.setSelected(true);
                            selectedPlanet = entity;
                        } else
                            entity.setSelected(false);
                    }
                    selectedId = id;
                } else {
                    stellarMode = false;
                    currentStarBody = DimensionManager.getInstance().getStar(id - EntityUIStar.starIDoffset);
                    rebuildSystem();
                    selectedId = Constants.INVALID_PLANET;
                }
            }

        } else {
            ISpaceObject station = SpaceObjectManager.getSpaceManager().getSpaceStationFromBlockCoords(this.getPos());
            if (station != null) {
                station.setDestOrbitingBody(id);

                if (selectedPlanet != null && selectedPlanet.getPlanetID() == id) {
                    centeredEntity = selectedPlanet;
                    stellarMode = false;
                    rebuildSystem();
                } else
                    for (EntityUIPlanet entity : entities) {
                        if (entity.getPlanetID() == id) {
                            entity.setSelected(true);
                            selectedPlanet = entity;
                        } else
                            entity.setSelected(false);
                    }
            }
        }
    }

    public void selectSystemWithoutTargeting(int id) {
        if (id >= EntityUIStar.starIDoffset) {
            if (stellarMode) {
                if (selectedId != id) {
                    for (EntityUIStar entity : starEntities) {
                        if (entity.getPlanetID() + EntityUIStar.starIDoffset == id) {
                            entity.setSelected(true);
                            selectedPlanet = entity;
                        } else
                            entity.setSelected(false);
                    }
                    selectedId = id;
                } else {
                    stellarMode = false;
                    currentStarBody = DimensionManager.getInstance().getStar(id - EntityUIStar.starIDoffset);
                    rebuildSystem();
                    selectedId = Constants.INVALID_PLANET;
                }
            }

        } else {
            if (selectedPlanet != null && selectedPlanet.getPlanetID() == id) {
                centeredEntity = selectedPlanet;
                stellarMode = false;
                rebuildSystem();
            } else
                for (EntityUIPlanet entity : entities) {
                    if (entity.getPlanetID() == id) {
                        entity.setSelected(true);
                        selectedPlanet = entity;
                    } else
                        entity.setSelected(false);
                }
        }
    }

    private void rebuildSystem() {
        onTime = 0;
        for (EntityUIPlanet entity : entities)
            entity.setDead();

        for (EntityUIStar body : starEntities)
            body.setDead();

        starEntities.clear();
        entities.clear();
        selectedPlanet = null;

        if (backButton == null) {
            backButton = new EntityUIButton(world, 0, this);
            backButton.setPosition(this.pos.getX() + .5, this.pos.getY() + 1.5, this.pos.getZ() + .5);
            this.getWorld().spawnEntity(backButton);
        }

        if (!stellarMode) {
            List<IDimensionProperties> planetList = currentStarBody == null ? DimensionManager.getInstance().getStar(0).getPlanets() : currentStarBody.getPlanets();
            if (centeredEntity != null) {
                planetList = new LinkedList<>();
                planetList.add(centeredEntity.getProperties());

                for (int id : centeredEntity.getProperties().getChildPlanets())
                    planetList.add(DimensionManager.getInstance().getDimensionProperties(id));

                if (currentStar != null) {
                    currentStar.setDead();
                    currentStar = null;
                }

            } else {
                if (currentStarBody == null)
                    currentStarBody = DimensionManager.getInstance().getStar(0);
                currentStar = new EntityUIStar(world, currentStarBody, this, this.pos.getX() + .5, this.pos.getY() + 1, this.pos.getZ() + .5);
                this.getWorld().spawnEntity(currentStar);

                //Spawn substars
                if (currentStarBody.getSubStars() != null && !currentStarBody.getSubStars().isEmpty()) {
                    float phaseInc = 360f / currentStarBody.getSubStars().size();
                    float phase = 0;
                    int count = 0;
                    Collection<StellarBody> starList = currentStarBody.getSubStars();
                    for (StellarBody body : starList) {

                        double deltaX, deltaY;
                        deltaX = companionOffset(body) * Math.cos(body.getBaseTheta());
                        deltaY = companionOffset(body) * Math.sin(body.getBaseTheta());
                        EntityUIStar entity = new EntityUIStar(world, body, count++, this, this.pos.getX() + .5 + deltaX, this.pos.getY() + 1, this.pos.getZ() + .5 + deltaY);

                        this.getWorld().spawnEntity(entity);
                        starEntities.add(entity);
                        phase += phaseInc;
                    }
                }
            }

            for (IDimensionProperties properties : planetList) {
                EntityUIPlanet entity = new EntityUIPlanet(world, (DimensionProperties) properties, this, this.pos.getX() + .5, this.pos.getY() + 1, this.pos.getZ() + .5);
                //entity.setPositionPolar(this.pos.getX() + .5, this.pos.getY() + 10, this.pos.getZ() + .5,  ((DimensionProperties)properties).orbitalDist/100f, ( (DimensionProperties)properties).orbitTheta);
                this.getWorld().spawnEntity(entity);
                entities.add(entity);

                if (centeredEntity != null && properties == centeredEntity.getProperties())
                    centeredEntity = entity;
            }
        } else {

            if (currentStar != null) {
                currentStar.setDead();
                currentStar = null;
            }

            Collection<StellarBody> starList = DimensionManager.getInstance().getStars();

            for (StellarBody body : starList) {
                EntityUIStar entity = new EntityUIStar(world, body, this, this.pos.getX() + .5, this.pos.getY() + 1, this.pos.getZ() + .5);

                this.getWorld().spawnEntity(entity);
                starEntities.add(entity);
            }
        }
        //Hack to delay position updates by a tick
        allowUpdate = false;
    }


    @Override
    public List<ModuleBase> getModules(int id, EntityPlayer player) {
        List<ModuleBase> modules = new LinkedList<>();

        modules.add(targetGrav);
        modules.add(new ModuleSlider(6, 60, 0, TextureResources.doubleWarningSideBarIndicator, this));
        modules.add(redstoneControl);

        updateText();
        return modules;
    }

    private void updateText() {
        if (world.isRemote) {

            //numThrusters.setText("Number Of Thrusters: 0");
            targetGrav.setText(String.format("%s %f", LibVulpes.proxy.getLocalizedString("msg.planetholo.size"), getHologramSize()));
        }
    }

    /**
     * A companion star's offset from its primary in the hologram, before the hologram's own scale: 5
     * per AU, which is what 0.05 per distance unit drew while a unit was a hundredth of an AU.
     */
    private static double companionOffset(StellarBody companion) {
        return companion.getOrbitalDistance() / (double) AstronomicalBodyHelper.DISTANCE_UNITS_PER_AU * 5d;
    }

    /** The hologram block every projected body stands out from the centre, whatever its distance. */
    private static final double PROJECTION_INNER_RADIUS = .1d;
    /**
     * Moon-view units per hologram block, for a moon about its centred planet: 100, as a moon's raw
     * distance was projected while Luna stood at 150, so Luna stands 1.6 blocks out as she did then
     * (see {@link AstronomicalBodyHelper#MOON_VIEW_UNITS_AT_LUNA}). Through the planet law a moon
     * stood at its planet's edge.
     */
    private static final double MOON_VIEW_UNITS_PER_HOLOGRAM_BLOCK = 100d;

    /**
     * How far from the centre a body is projected, before the hologram's own scale: a planet one
     * block per AU of its distance from its star, a moon by its distance from its planet.
     */
    private static double projectionRadius(DimensionProperties body) {
        if (body.isMoon()) {
            return PROJECTION_INNER_RADIUS
                    + AstronomicalBodyHelper.moonViewUnits(body.orbitalDist) / MOON_VIEW_UNITS_PER_HOLOGRAM_BLOCK;
        }
        return PROJECTION_INNER_RADIUS + body.orbitalDist / (double) AstronomicalBodyHelper.DISTANCE_UNITS_PER_AU;
    }

    private float getHologramSize() {
        return (size * 10 + 0.8f);
    }

    private float getInterpHologramSize() {
        return getHologramSize() * onTime;
    }

    @Override
    public String getModularInventoryName() {
        return StellurgyBlocks.blockPlanetHoloSelector.getLocalizedName();
    }

    @Override
    public boolean canInteractWithContainer(EntityPlayer entity) {
        return true;
    }

    @Override
    public float getNormallizedProgress(int id) {
        return size;
    }

    @Override
    public void setProgress(int id, int progress) {
        size = progress / 100f;
    }

    @Override
    public int getProgress(int id) {
        return (int) (size * 100);
    }

    @Override
    public int getTotalProgress(int id) {
        return 100;
    }

    @Override
    public void setTotalProgress(int id, int progress) {
    }

    @Override
    public void setProgressByUser(int id, int progress) {
        size = progress / 100f;
        PacketHandler.sendToServer(new PacketMachine(this, SCALEPACKET));
        updateText();
    }

    public int getCurrentPlanetID() {
        return selectedPlanet.dimension;
    }

    public int getCurrentStarID() {
        return currentStar.getPlanetID();
    }

    @Override
    public void writeDataToNetwork(ByteBuf out, byte id) {
        if (id == SCALEPACKET) {
            out.writeFloat(size);
        }
        if (id == STATEUPDATE) {
            out.writeByte(state.ordinal());
        }

    }

    @Override
    public void readDataFromNetwork(ByteBuf in, byte packetId,
                                    NBTTagCompound nbt) {
        if (packetId == SCALEPACKET) {
            nbt.setFloat("scale", in.readFloat());
        } else if (packetId == STATEUPDATE) {
            nbt.setByte("state", in.readByte());
        }

    }

    @Override
    public void useNetworkData(EntityPlayer player, Side side, byte id,
                               NBTTagCompound nbt) {
        if (id == SCALEPACKET) {
            size = nbt.getFloat("scale");
        } else if (id == STATEUPDATE) {
            state = RedstoneState.values()[nbt.getByte("state")];
        }
    }

    public void onInventoryButtonPressed(int buttonId) {
        //Back button
        if (buttonId == 0) {
            if (currentStar != null)
                stellarMode = true;
            selectedPlanet = null;
            centeredEntity = null;
            rebuildSystem();
        } else if (buttonId == 1) {
            state = redstoneControl.getState();
            PacketHandler.sendToServer(new PacketMachine(this, STATEUPDATE));
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound compound) {
        compound = super.writeToNBT(compound);
        state.writeToNBT(compound);

        return compound;
    }

    @Override
    public void readFromNBT(NBTTagCompound compound) {
        super.readFromNBT(compound);
        state = RedstoneState.createFromNBT(compound);
        redstoneControl.setRedstoneState(state);
    }
}