package com.shade.bukkit.towny.event;

import java.util.Arrays;

import org.bukkit.block.Block;
import org.bukkit.block.BlockDamageLevel;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockInteractEvent;
import org.bukkit.event.block.BlockListener;
import org.bukkit.event.block.BlockPlaceEvent;

import com.shade.bukkit.towny.NotRegisteredException;
import com.shade.bukkit.towny.PlayerCache;
import com.shade.bukkit.towny.PlayerCache.TownBlockStatus;
import com.shade.bukkit.towny.Towny;
import com.shade.bukkit.towny.TownyException;
import com.shade.bukkit.towny.TownySettings;
import com.shade.bukkit.towny.object.Coord;
import com.shade.bukkit.towny.object.Resident;
import com.shade.bukkit.towny.object.Town;
import com.shade.bukkit.towny.object.TownBlock;
import com.shade.bukkit.towny.object.TownyUniverse;

//TODO: Admin/Group Build Rights
//TODO: algorithm is updating coord twice when updating permissions 
//TODO: Combine the three similar functions into a single function.

/*
 * Logic:
 * 
 * Check Cache -> if need to update
 * Update Cache by
 * check townblock with
 * wild permissions
 * is there a plot owner? otherwise skip to town permissions
 * is plot owner
 * is plot friend
 * plot owner allow allies? if so check town permissions
 * is the townblock owned by a town?
 * is town resident
 * has town part of town's nation
 * is nation's ally
 * 
 * TODO: wartime allows enemy nations to edit
 * 
 */

public class TownyBlockListener extends BlockListener {
	private final Towny plugin;

	public TownyBlockListener(Towny instance) {
		plugin = instance;
	}

	
	@Override
	public void onBlockDamage(BlockDamageEvent event) {
		if (event.getDamageLevel() == BlockDamageLevel.BROKEN) {
			long start = System.currentTimeMillis();

			onBlockBreakEvent(event, true);

			plugin.sendDebugMsg("onBlockBreakEvent took " + (System.currentTimeMillis() - start) + "ms");
		}
	}

	public void onBlockBreakEvent(BlockDamageEvent event, boolean firstCall) {
		Player player = event.getPlayer();
		Block block = event.getBlock();
		Coord pos = Coord.parseCoord(block);

		// Check cached permissions first
		try {
			PlayerCache cache = getCache(player);
			cache.updateCoord(pos);
			if (!cache.getDestroyPermission())
				event.setCancelled(true);
			return;
		} catch (NullPointerException e) {
			if (firstCall) {
				// New or old destroy permission was null, update it
				updateDestroyCache(player, pos, true);
				onBlockBreakEvent(event, false);
			} else
				plugin.sendErrorMsg(player, "Error updating destroy permissions cache.");
		}

	}

	@Override
	public void onBlockPlace(BlockPlaceEvent event) {
		long start = System.currentTimeMillis();

		onBlockPlaceEvent(event, true);

		plugin.sendDebugMsg("onBlockPlacedEvent took " + (System.currentTimeMillis() - start) + "ms");
	}

	public void onBlockPlaceEvent(BlockPlaceEvent event, boolean firstCall) {
		Player player = event.getPlayer();
		Block block = event.getBlock();
		Coord pos = Coord.parseCoord(block);

		// Check cached permissions first
		try {
			PlayerCache cache = getCache(player);
			cache.updateCoord(pos);
			if (!cache.getBuildPermission()) { // If build cache is empty, throws null pointer
				event.setBuild(false);
				event.setCancelled(true);
			}
			return;
		} catch (NullPointerException e) {
			if (firstCall) {
				// New or old build permission was null, update it
				updateBuildCache(player, pos, true);
				onBlockPlaceEvent(event, false);
			} else
				plugin.sendErrorMsg(player, "Error updating build permissions cache.");
		}
	}
	
	@Override
	public void onBlockInteract(BlockInteractEvent event) {
		long start = System.currentTimeMillis();

		onBlockInteractEvent(event, true);

		plugin.sendDebugMsg("onBlockInteractEvent took " + (System.currentTimeMillis() - start) + "ms");
	}
	
	
	
	public void onBlockInteractEvent(BlockInteractEvent event, boolean firstCall) {
		if (event.getEntity() != null && event.getEntity() instanceof Player) {
			Player player = (Player)event.getEntity();
			Block block = event.getBlock();
			if (!Arrays.asList(TownySettings.getSwitchIds()).contains(block.getTypeId()))
				return;
					
			Coord pos = Coord.parseCoord(block);
	
			// Check cached permissions first
			try {
				PlayerCache cache = getCache(player);
				cache.updateCoord(pos);
				if (!cache.getSwitchPermission())
					event.setCancelled(true);
				return;
			} catch (NullPointerException e) {
				if (firstCall) {
					// New or old build permission was null, update it
					updateSwitchCache(player, pos, true);
					onBlockInteractEvent(event, false);
				} else
					plugin.sendErrorMsg(player, "Error updating build permissions cache.");
			}
		}
	}

	public void updateSwitchCache(Player player, Coord pos, boolean sendMsg) {
		if (plugin.isTownyAdmin(player)) {
			cacheSwitch(player, pos, true);
			return;
		}
		
		TownyUniverse universe = plugin.getTownyUniverse();
		TownBlock townBlock;
		Town town;
		try {
			townBlock = universe.getWorld(player.getWorld().getName()).getTownBlock(pos);
			town = townBlock.getTown();
		} catch (NotRegisteredException e) {
			// Unclaimed Zone switch rights
			if (plugin.hasPermission(player, "towny.wild.switch"))
				cacheSwitch(player, pos, true);
			else if (!TownySettings.getUnclaimedZoneSwitchRights()) {
				// TODO: Have permission to switch here
				if (sendMsg)
					plugin.sendErrorMsg(player, "Not allowed to toggle switches in the wild.");
				cacheSwitch(player, pos, false);
			} else
				cacheSwitch(player, pos, true);

			return;
		}

		try {
			Resident resident = universe.getResident(player.getName());
			
			// War Time switch rights
			if (universe.isWarTime())
				try {
					if (!resident.getTown().getNation().isNeutral() && !town.getNation().isNeutral()) {
						cacheSwitch(player, pos, true);
						return;
					}	
				} catch (NotRegisteredException e) {
				}
			
			// Resident Plot switch rights
			try {
				Resident owner = townBlock.getResident();
				if (resident == owner)
					cacheSwitch(player, pos, true);
				else if (owner.hasFriend(resident)) {
					if (owner.getPermissions().residentSwitch)
						cacheSwitch(player, pos, true);
					else {
						if (sendMsg)
							plugin.sendErrorMsg(player, "Owner doesn't allow friends to toggle switches.");
						cacheSwitch(player, pos, false);
					}
				} else if (owner.getPermissions().allySwitch)
					// Exit out and use town permissions
					throw new TownyException();
				else {
					if (sendMsg)
						plugin.sendErrorMsg(player, "Owner doesn't allow allies to toggle switches.");
					cacheSwitch(player, pos, false);
				}

				return;
			} catch (NotRegisteredException x) {
			} catch (TownyException x) {
			}

			// Town resident destroy rights
			if (!resident.hasTown())
				throw new TownyException("You don't belong to this town.");

			if (!town.getPermissions().residentSwitch) {
				if (sendMsg)
					plugin.sendErrorMsg(player, "Residents aren't allowed to toggle switches.");
				cacheSwitch(player, pos, false);
			} else if (resident.getTown() != town) {
				// Allied destroy rights
				if (universe.isAlly(resident.getTown(), town) && town.getPermissions().allyDestroy)
					cacheSwitch(player, pos, true);
				else {
					if (sendMsg)
						plugin.sendErrorMsg(player, "Not allowed to toggle switches here.");
					cacheSwitch(player, pos, false);
				}
			} else
				cacheSwitch(player, pos, true);
		} catch (TownyException e) {
			// Outsider destroy rights
			if (!town.getPermissions().outsiderSwitch) {
				if (sendMsg)
					plugin.sendErrorMsg(player, e.getError());
				cacheSwitch(player, pos, false);
			} else
				cacheSwitch(player, pos, true);
		}
	}

	public void updateDestroyCache(Player player, Coord pos, boolean sendMsg) {
		if (plugin.isTownyAdmin(player)) {
			cacheDestroy(player, pos, true);
			return;
		}
		
		TownyUniverse universe = plugin.getTownyUniverse();
		TownBlock townBlock;
		Town town;
		try {
			townBlock = universe.getWorld(player.getWorld().getName()).getTownBlock(pos);
			town = townBlock.getTown();
		} catch (NotRegisteredException e) {
			// Unclaimed Zone destroy rights
			if (plugin.hasPermission(player, "towny.wild.destroy"))
				cacheDestroy(player, pos, true);
			else if (!TownySettings.getUnclaimedZoneDestroyRights()) {
				// TODO: Have permission to destroy here
				if (sendMsg)
					plugin.sendErrorMsg(player, "Not allowed to destroy in the wild.");
				cacheDestroy(player, pos, false);
			} else
				cacheDestroy(player, pos, true);

			return;
		}

		try {
			Resident resident = universe.getResident(player.getName());
			
			// War Time destroy rights
			if (universe.isWarTime())
				try {
					if (!resident.getTown().getNation().isNeutral() && !town.getNation().isNeutral()) {
						cacheDestroy(player, pos, true);
						return;
					}	
				} catch (NotRegisteredException e) {
				}
			
			// Resident Plot destroy rights
			try {
				Resident owner = townBlock.getResident();
				if (resident == owner)
					cacheDestroy(player, pos, true);
				else if (owner.hasFriend(resident)) {
					if (owner.getPermissions().residentDestroy)
						cacheDestroy(player, pos, true);
					else {
						if (sendMsg)
							plugin.sendErrorMsg(player, "Owner doesn't allow friends to destroy here.");
						cacheDestroy(player, pos, false);
					}
				} else if (owner.getPermissions().allyDestroy)
					// Exit out and use town getPermissions()
					throw new TownyException();
				else {
					if (sendMsg)
						plugin.sendErrorMsg(player, "Owner doesn't allow allies to destroy here.");
					cacheDestroy(player, pos, false);
				}

				return;
			} catch (NotRegisteredException x) {
			} catch (TownyException x) {
			}

			// Town resident destroy rights
			if (!resident.hasTown())
				throw new TownyException("You don't belong to this town.");

			if (!town.getPermissions().residentDestroy) {
				if (sendMsg)
					plugin.sendErrorMsg(player, "Residents aren't allowed to destroy.");
				cacheDestroy(player, pos, false);
			} else if (resident.getTown() != town) {
				// Allied destroy rights
				if (universe.isAlly(resident.getTown(), town) && town.getPermissions().allyDestroy)
					cacheDestroy(player, pos, true);
				else {
					if (sendMsg)
						plugin.sendErrorMsg(player, "Not allowed to destroy here.");
					cacheDestroy(player, pos, false);
				}
			} else
				cacheDestroy(player, pos, true);
		} catch (TownyException e) {
			// Outsider destroy rights
			if (!town.getPermissions().outsiderDestroy) {
				if (sendMsg)
					plugin.sendErrorMsg(player, e.getError());
				cacheDestroy(player, pos, false);
			} else
				cacheDestroy(player, pos, true);
		}
	}

	public void updateBuildCache(Player player, Coord pos, boolean sendMsg) {
		if (plugin.isTownyAdmin(player)) {
			cacheBuild(player, pos, true);
			return;
		}
		
		TownyUniverse universe = plugin.getTownyUniverse();
		TownBlock townBlock;
		Town town;

		try {
			townBlock = universe.getWorld(player.getWorld().getName()).getTownBlock(pos);
			town = townBlock.getTown();
		} catch (NotRegisteredException e) {
			// Unclaimed Zone Build Rights
			if (plugin.hasPermission(player, "towny.wild.build"))
				cacheBuild(player, pos, true);
			else if (!TownySettings.getUnclaimedZoneBuildRights()) {
				// TODO: Have permission to build here
				if (sendMsg)
					plugin.sendErrorMsg(player, "Not allowed to build in the wild.");
				cacheBuild(player, pos, false);
			} else
				cacheBuild(player, pos, true);

			return;
		}

		try {
			Resident resident = universe.getResident(player.getName());

			// War Time build rights
			if (universe.isWarTime())
				try {
					if (!resident.getTown().getNation().isNeutral() && !town.getNation().isNeutral()) {
						cacheBuild(player, pos, true);
						return;
					}	
				} catch (NotRegisteredException e) {
				}
			
			// Resident Plot rights
			try {
				Resident owner = townBlock.getResident();
				if (resident == owner)
					cacheBuild(player, pos, true);
				else if (owner.hasFriend(resident)) {
					if (owner.getPermissions().residentBuild)
						cacheBuild(player, pos, true);
					else {
						if (sendMsg)
							plugin.sendErrorMsg(player, "Owner doesn't allow friends to build here.");
						cacheBuild(player, pos, false);
					}
				} else if (owner.getPermissions().allyBuild)
					// Exit out and use town permissions
					throw new TownyException();
				else {
					if (sendMsg)
						plugin.sendErrorMsg(player, "Owner doesn't allow allies to build here.");
					cacheBuild(player, pos, false);
				}

				return;
			} catch (NotRegisteredException x) {
			} catch (TownyException x) {
			}

			// Town Resident Build Rights
			if (!resident.hasTown())
				throw new TownyException("You don't belong to this town.");

			if (!town.getPermissions().residentBuild) {
				if (sendMsg)
					plugin.sendErrorMsg(player, "Residents aren't allowed to build.");
				cacheBuild(player, pos, false);
			} else if (resident.getTown() != town) {
				// Allied Build Rights
				if (universe.isAlly(resident.getTown(), town) && town.getPermissions().allyBuild)
					cacheBuild(player, pos, true);
				else {
					if (sendMsg)
						plugin.sendErrorMsg(player, "Not allowed to build here.");
					cacheBuild(player, pos, false);
				}
			} else
				cacheBuild(player, pos, true);
		} catch (TownyException e) {
			// Outsider Build Rights
			if (!town.getPermissions().outsiderBuild) {
				if (sendMsg)
					plugin.sendErrorMsg(player, e.getError());
				cacheBuild(player, pos, false);
			} else
				cacheBuild(player, pos, true);
		}
	}
	
	public void updateTownBlockStatus(Player player, Coord pos) {
		if (plugin.isTownyAdmin(player)) {
			cacheStatus(player, pos, TownBlockStatus.ADMIN);
			return;
		}
		
		TownyUniverse universe = plugin.getTownyUniverse();
		TownBlock townBlock;
		Town town;
		try {
			townBlock = universe.getWorld(player.getWorld().getName()).getTownBlock(pos);
			town = townBlock.getTown();
		} catch (NotRegisteredException e) {
			// Unclaimed Zone switch rights
			cacheStatus(player, pos, TownBlockStatus.UNCLAIMED_ZONE);
			return;
		}

		try {
			Resident resident = universe.getResident(player.getName());
			
			// War Time switch rights
			if (universe.isWarTime())
				try {
					if (!resident.getTown().getNation().isNeutral() && !town.getNation().isNeutral()) {
						cacheStatus(player, pos, TownBlockStatus.WARTIME);
						return;
					}	
				} catch (NotRegisteredException e) {
				}
			
			// Resident Plot switch rights
			try {
				Resident owner = townBlock.getResident();
				if (resident == owner)
					cacheStatus(player, pos, TownBlockStatus.PLOT_OWNER);
				else if (owner.hasFriend(resident))
					cacheStatus(player, pos, TownBlockStatus.PLOT_FRIEND);
				else
					// Exit out and use town permissions
					throw new TownyException();

				return;
			} catch (NotRegisteredException x) {
			} catch (TownyException x) {
			}

			// Town resident destroy rights
			if (!resident.hasTown())
				throw new TownyException();

			if (resident.getTown() != town) {
				// Allied destroy rights
				if (universe.isAlly(resident.getTown(), town))
					cacheStatus(player, pos, TownBlockStatus.TOWN_ALLY);
				else
					cacheStatus(player, pos, TownBlockStatus.OUTSIDER);
			} else if (resident.isMayor() || resident.getTown().hasAssistant(resident))
				cacheStatus(player, pos, TownBlockStatus.TOWN_OWNER);
			else
				cacheStatus(player, pos, TownBlockStatus.TOWN_RESIDENT);
		} catch (TownyException e) {
			// Outsider destroy rights
			cacheStatus(player, pos, TownBlockStatus.OUTSIDER);
		}
	}
	
	private void cacheStatus(Player player, Coord coord, TownBlockStatus townBlockStatus) {
		PlayerCache cache = getCache(player);
		cache.updateCoord(coord);
		cache.setStatus(townBlockStatus);

		plugin.sendDebugMsg(player.getName() + " (" + coord.toString() + ") Cached Status: " + townBlockStatus);
	}


	public void cacheBuild(Player player, Coord coord, boolean buildRight) {
		PlayerCache cache = getCache(player);
		cache.updateCoord(coord);
		cache.setBuildPermission(buildRight);

		plugin.sendDebugMsg(player.getName() + " (" + coord.toString() + ") Cached Build: " + buildRight);
	}

	public void cacheDestroy(Player player, Coord coord, boolean destroyRight) {
		PlayerCache cache = getCache(player);
		cache.updateCoord(coord);
		cache.setDestroyPermission(destroyRight);

		plugin.sendDebugMsg(player.getName() + " (" + coord.toString() + ") Cached Destroy: " + destroyRight);
	}
	
	public void cacheSwitch(Player player, Coord coord, boolean switchRight) {
		PlayerCache cache = getCache(player);
		cache.updateCoord(coord);
		cache.setSwitchPermission(switchRight);

		plugin.sendDebugMsg(player.getName() + " (" + coord.toString() + ") Cached Switch: " + switchRight);
	}
	
	public PlayerCache getCache(Player player) {
		return plugin.getCache(player);
	}
}