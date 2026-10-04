package dev.stannismod.stellurgy.libvulpes.util;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerChangedDimensionEvent;
import dev.stannismod.stellurgy.libvulpes.api.IJetPack;
import dev.stannismod.stellurgy.libvulpes.api.IModularArmor;

public class InputSyncHandler {

	public static boolean isSpaceDown(EntityPlayer player) {
		return ((JetpackKeys) player).stellurgy$isSpaceDown();
	}
	
	//Called on server (and client in AR KeyBindings class)
	public static void updateKeyPress(EntityPlayer player, int key, boolean state) {
		ItemStack stack;
		switch(key) {
		case 0:
			
			stack = player.inventory.armorInventory.get(2);
			if(!stack.isEmpty()) {
				IJetPack pack;
				if(stack.getItem() instanceof IJetPack) {
					pack = ((IJetPack)stack.getItem());
					pack.setEnabledState(stack, !pack.isEnabled(stack));
				}
				else if(stack.getItem() instanceof IModularArmor) {
					IInventory inv = ((IModularArmor)stack.getItem()).loadModuleInventory(stack);
					
					for(int i = 0; i < inv.getSizeInventory(); i++) {
						if(!inv.getStackInSlot(i).isEmpty() && inv.getStackInSlot(i).getItem() instanceof IJetPack) {
							pack = ((IJetPack)inv.getStackInSlot(i).getItem());
							pack.setEnabledState(inv.getStackInSlot(i), !pack.isEnabled(inv.getStackInSlot(i)));
						}
					}
					((IModularArmor)stack.getItem()).saveModuleInventory(stack, inv);
					
				}
			}
			break;
			
		case 1:
			stack = player.inventory.armorInventory.get(2);
			if(!stack.isEmpty()) {
				IJetPack pack;
				if(stack.getItem() instanceof IJetPack) {
					pack = ((IJetPack)stack.getItem());
					pack.setEnabledState(stack, !pack.isEnabled(stack));
				}
				else if(stack.getItem() instanceof IModularArmor) {
					IInventory inv = ((IModularArmor)stack.getItem()).loadModuleInventory(stack);
					
					for(int i = 0; i < inv.getSizeInventory(); i++) {
						if(!inv.getStackInSlot(i).isEmpty() && inv.getStackInSlot(i).getItem() instanceof IJetPack) {
							pack = ((IJetPack)inv.getStackInSlot(i).getItem());
							pack.changeMode(inv.getStackInSlot(i), inv, player);
						}
					}
					((IModularArmor)stack.getItem()).saveModuleInventory(stack, inv);
					
				}
			}
			break;
		case 57: //SPACE
			((JetpackKeys) player).stellurgy$setSpaceDown(state);
			break;
			
			default:
				
		}
	}
	
	/** A server player keeps its object across a dimension change; the key it held does not carry over. */
	@SubscribeEvent
	public void onDimChanged(PlayerChangedDimensionEvent evt) {
		((JetpackKeys) evt.player).stellurgy$setSpaceDown(false);
	}
}
