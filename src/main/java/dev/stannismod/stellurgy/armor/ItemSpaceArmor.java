package dev.stannismod.stellurgy.armor;

import com.mojang.realmsclient.gui.ChatFormatting;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.DamageSource;
import net.minecraft.util.EnumFacing;
import net.minecraft.world.World;
import net.minecraftforge.common.ISpecialArmor;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import dev.stannismod.stellurgy.api.StellurgyItems;
import dev.stannismod.stellurgy.api.atmosphere.Atmosphere;
import dev.stannismod.stellurgy.api.armor.IProtectiveArmor;
import dev.stannismod.stellurgy.api.capability.CapabilitySpaceArmor;
import dev.stannismod.stellurgy.client.render.armor.RenderJetPack;
import dev.stannismod.stellurgy.libvulpes.LibVulpes;
import dev.stannismod.stellurgy.libvulpes.api.IArmorComponent;
import dev.stannismod.stellurgy.libvulpes.api.IJetPack;
import dev.stannismod.stellurgy.libvulpes.api.IModularArmor;
import dev.stannismod.stellurgy.libvulpes.util.EmbeddedInventory;
import dev.stannismod.stellurgy.libvulpes.util.IconResource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.LinkedList;
import java.util.List;

/**
 * Space Armor
 * Any class that extends this will gain the ability to store oxygen and will protect players from the vacuum atmosphere type
 */

public class ItemSpaceArmor extends ItemArmor implements ISpecialArmor, ICapabilityProvider, IProtectiveArmor, IModularArmor {

    private final static String componentNBTName = "componentName";
    private int numModules;

    public ItemSpaceArmor(ArmorMaterial material, EntityEquipmentSlot component, int numModules) {
        super(material, 0, component);
        this.numModules = numModules;
    }

    @Override
    public boolean canBeExternallyModified(@Nonnull ItemStack armor, int slot) {
        return true;
    }

    @Override
    public void addInformation(@Nonnull ItemStack stack, World p_77624_2_, List<String> list, ITooltipFlag p_77624_4_) {
        super.addInformation(stack, p_77624_2_, list, p_77624_4_);

        list.add(LibVulpes.proxy.getLocalizedString("msg.modules"));

        for (ItemStack componentStack : getComponents(stack)) {
            list.add(ChatFormatting.DARK_GRAY + componentStack.getDisplayName());
        }
    }

    @Override
    public boolean isDamageable() {
        return false;
    }

    @Override
    public void setDamage(@Nonnull ItemStack stack, int damage) {
        //Dummy out
    }

    @Override
    @SideOnly(Side.CLIENT)
    public ModelBiped getArmorModel(EntityLivingBase entityLiving,
                                    ItemStack itemStack, EntityEquipmentSlot armorSlot,
                                    ModelBiped _default) {

        if (armorSlot == EntityEquipmentSlot.CHEST) {
            for (ItemStack stack : getComponents(itemStack)) {
                if (stack.getItem() instanceof IJetPack)
                    return new RenderJetPack(_default);
            }
        }
        return super.getArmorModel(entityLiving, itemStack, armorSlot, _default);
    }

    public int getColor(@Nonnull ItemStack stack) {

        NBTTagCompound nbttagcompound = stack.getTagCompound();

        if (nbttagcompound != null) {
            NBTTagCompound nbttagcompound1 = nbttagcompound.getCompoundTag("display");

            if (nbttagcompound1.hasKey("color", 3)) {
                return nbttagcompound1.getInteger("color");
            }
        }

        return 0xFFFFFF;

    }

    @Override
    public ArmorProperties getProperties(EntityLivingBase player, @Nonnull ItemStack armor, DamageSource source, double damage, int slot) {
        if (!source.isUnblockable())
            return new ArmorProperties(0, 0, 1);
        return new ArmorProperties(0, 0, 0);
    }

    private EmbeddedInventory loadEmbeddedInventory(@Nonnull ItemStack stack) {
        if (stack.hasTagCompound()) {
            EmbeddedInventory inv = new EmbeddedInventory(numModules);
            inv.readFromNBT(stack.getTagCompound());
            return inv;
        }
        return new EmbeddedInventory(numModules);
    }

    protected void saveEmbeddedInventory(@Nonnull ItemStack stack, EmbeddedInventory inv) {
        if (stack.hasTagCompound()) {
            inv.writeToNBT(stack.getTagCompound());
        } else {
            NBTTagCompound nbt = new NBTTagCompound();
            inv.writeToNBT(nbt);
            stack.setTagCompound(nbt);
        }
    }

    @Override
    public void onArmorTick(World world, EntityPlayer player, @Nonnull ItemStack armor) {
        super.onArmorTick(world, player, armor);

        if (armor.hasTagCompound()) {

            //Some upgrades modify player capabilities

            EmbeddedInventory inv = loadEmbeddedInventory(armor);
            for (int i = 0; i < inv.getSizeInventory(); i++) {
                ItemStack stack = inv.getStackInSlot(i);
                if (!stack.isEmpty()) {
                    IArmorComponent component = (IArmorComponent) stack.getItem();
                    component.onTick(world, player, armor, inv, stack);
                }
            }

            saveEmbeddedInventory(armor, inv);
        }
    }

    @Override
    public String getArmorTexture(@Nonnull ItemStack stack, Entity entity, EntityEquipmentSlot slot, String type) {

        if (type != null) {
            if (stack.getItem() == StellurgyItems.itemSpaceSuit_Leggings)
                return "stellurgy:textures/armor/spaceSuit_layer1_overlay.png";//super.getArmorTexture(stack, entity, slot, type);
            return "stellurgy:textures/armor/spaceSuit_layer2_overlay.png";
        }

        if (stack.getItem() == StellurgyItems.itemSpaceSuit_Leggings)
            return "stellurgy:textures/armor/spaceSuit_layer1.png";//super.getArmorTexture(stack, entity, slot, type);
        return "stellurgy:textures/armor/spaceSuit_layer2.png";
    }

    @Override
    public int getArmorDisplay(EntityPlayer player, @Nonnull ItemStack armor, int slot) {
        return 1;
    }

    @Override
    public void damageArmor(EntityLivingBase entity, @Nonnull ItemStack armor,
                            DamageSource source, int damage, int slot) {
        // TODO Handle armor damage

        if (armor.hasTagCompound()) {

            EmbeddedInventory inv = loadEmbeddedInventory(armor);
            for (int i = 0; i < inv.getSizeInventory(); i++) {
                ItemStack stack = inv.getStackInSlot(i);
                if (!stack.isEmpty()) {
                    IArmorComponent component = (IArmorComponent) stack.getItem();
                    component.onArmorDamaged(entity, armor, stack, source, damage);
                }
            }

            saveEmbeddedInventory(armor, inv);
        }
    }

    @Override
    public void addArmorComponent(World world, @Nonnull ItemStack armor, @Nonnull ItemStack component, int slot) {

        EmbeddedInventory inv = loadEmbeddedInventory(armor);

        if (((IArmorComponent) component.getItem()).onComponentAdded(world, armor)) {
            inv.setInventorySlotContents(slot, component);

            saveEmbeddedInventory(armor, inv);
        }
    }

    @Nonnull
    public ItemStack removeComponent(World world, @Nonnull ItemStack armor, int index) {
        EmbeddedInventory inv = loadEmbeddedInventory(armor);
        ItemStack stack = inv.getStackInSlot(index);
        inv.setInventorySlotContents(index, ItemStack.EMPTY);

        if (!stack.isEmpty()) {
            IArmorComponent component = (IArmorComponent) stack.getItem();
            component.onComponentRemoved(world, armor);
            saveEmbeddedInventory(armor, inv);
        }


        return stack;
    }

    public List<ItemStack> getComponents(@Nonnull ItemStack armor) {

        List<ItemStack> list = new LinkedList<>();

        if (armor.hasTagCompound()) {
            EmbeddedInventory inv = loadEmbeddedInventory(armor);

            for (int i = 0; i < inv.getSizeInventory(); i++) {
                if (!inv.getStackInSlot(i).isEmpty())
                    list.add(inv.getStackInSlot(i));
            }
        }

        return list;
    }

    /**
      * A space suit is proof against everything this mod's air can do. It used to say so by listing
      * every atmosphere by name, which is the same claim written in the form that goes stale.
      */
    @Override
    public boolean protectsFrom(java.util.Set<dev.stannismod.stellurgy.api.atmosphere.AtmosphereHazard> hazards,
                                boolean needsSuppliedOxygen, @Nonnull ItemStack stack,
                                boolean commitProtection) {
        return !hazards.isEmpty();
    }

    @Override
    public int getNumSlots(@Nonnull ItemStack stack) {
        return loadEmbeddedInventory(stack).getSizeInventory();
    }

    @Override
    @Nonnull
    public ItemStack getComponentInSlot(@Nonnull ItemStack stack, int slot) {
        return loadEmbeddedInventory(stack).getStackInSlot(slot);
    }

    @Override
    public IInventory loadModuleInventory(@Nonnull ItemStack stack) {
        return loadEmbeddedInventory(stack);
    }

    @Override
    public void saveModuleInventory(@Nonnull ItemStack stack, IInventory inv) {
        saveEmbeddedInventory(stack, (EmbeddedInventory) inv);
    }

    @Override
    public boolean hasCapability(@Nullable Capability<?> capability, EnumFacing facing) {

        return capability == CapabilitySpaceArmor.PROTECTIVEARMOR;
    }

    @Override
    public <T> T getCapability(@Nullable Capability<T> capability, EnumFacing facing) {
        if (capability == CapabilitySpaceArmor.PROTECTIVEARMOR)
            return (T) this;
        return null;
    }

    public boolean isItemValidForSlot(@Nonnull ItemStack stack, int slot) {
        return true;
    }

    @Override
    public IconResource getResourceForSlot(int slot) {
        return null;
    }
}
