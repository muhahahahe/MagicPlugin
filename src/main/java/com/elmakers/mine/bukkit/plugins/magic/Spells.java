package com.elmakers.mine.bukkit.plugins.magic;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.enchantment.PrepareItemEnchantEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.InventoryType.SlotType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.event.world.WorldInitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitScheduler;

import com.elmakers.mine.bukkit.dao.BlockList;
import com.elmakers.mine.bukkit.utilities.CSVParser;
import com.elmakers.mine.bukkit.utilities.SetActiveItemSlotTask;
import com.elmakers.mine.bukkit.utilities.UndoQueue;
import com.elmakers.mine.bukkit.utilities.borrowed.Configuration;
import com.elmakers.mine.bukkit.utilities.borrowed.ConfigurationNode;

public class Spells implements Listener 
{
	/*
	 * Public API - Use for hooking up a plugin, or calling a spell
	 */

	public PlayerSpells getPlayerSpells(Player player)
	{
		PlayerSpells spells = playerSpells.get(player.getName());
		if (spells == null)
		{
			spells = new PlayerSpells(this, player);
			playerSpells.put(player.getName(), spells);
		}

		spells.setPlayer(player);

		return spells;
	}

	public void createSpell(Spell template, String name, Material icon, String description, String category, String parameterString)
	{
		createSpell(template, name, icon, description, category, parameterString, null, null);
	}

	public void createSpell(Spell template, String name, Material icon, String description, String category, String parameterString, String propertiesString)
	{
		createSpell(template, name, icon, description, category, parameterString, propertiesString, null);    
	}

	public void createSpell(Spell template, String name, Material icon, String description, String category, String parameterString, String propertiesString, String costsString)
	{
		ConfigurationNode spellNode = new ConfigurationNode();
		ConfigurationNode parameterNode = spellNode.createChild("parameters");
		ConfigurationNode propertiesNode = spellNode.createChild("properties");

		if (parameterString != null && parameterString.length() > 0)
		{
			String[] parameters = parameterString.split(" ");
			Spell.addParameters(parameters, parameterNode);
		}

		if (propertiesString != null && propertiesString.length() > 0)
		{
			String[] properties = propertiesString.split(" ");
			Spell.addParameters(properties, propertiesNode);
		}

		if (costsString != null && costsString.length() > 0)
		{
			List< Map<String, Object> > costs = new ArrayList< Map<String, Object> >();
			String[] costPairs = costsString.split(" ");
			for (int i = 0; i < costPairs.length - 1; i += 2)
			{
				try
				{
					int amount = Integer.parseInt(costPairs[i + 1]);
					Map<String, Object> cost = new HashMap<String, Object>();
					cost.put("material", costPairs[i]);
					cost.put("amount", amount);
					costs.add(cost);
				}
				catch(Exception ex)
				{

				}
			}

			spellNode.setProperty("costs", costs);
		}

		spellNode.setProperty("description", description);
		spellNode.setProperty("icon", icon);
		spellNode.setProperty("category", category);

		template.initialize(this);
		template.load(name, spellNode);

		addSpell(template);
	}

	public void addSpell(Spell variant)
	{
		Spell conflict = spells.get(variant.getKey());
		if (conflict != null)
		{
			log.log(Level.WARNING, "Duplicate spell name: '" + conflict.getKey() + "'");
		}
		else
		{
			spells.put(variant.getKey(), variant);
		}
		Material m = variant.getMaterial();
		if (m != null && m != Material.AIR)
		{
			/*
            if (buildingMaterials.contains(m))
            {
                log.warning("Spell " + variant.getName() + " uses building material as icon: " + m.name().toLowerCase());
            }
			 */
			conflict = spellsByMaterial.get(m);
			if (conflict != null)
			{
				log.log(Level.WARNING, "Duplicate spell material: " + m.name() + " for " + conflict.getKey() + " and " + variant.getKey());
			}
			else
			{
				spellsByMaterial.put(variant.getMaterial(), variant);
			}
		}
	}

	/*
	 * Material use system
	 */

	public List<Material> getBuildingMaterials()
	{
		return buildingMaterials;
	}

	public List<Material> getTargetThroughMaterials()
	{
		return targetThroughMaterials;
	}
	
	/*
	 * Undo system
	 */

	public UndoQueue getUndoQueue(String playerName)
	{
		UndoQueue queue = playerUndoQueues.get(playerName);
		if (queue == null)
		{
			queue = new UndoQueue();
			queue.setMaxSize(undoQueueDepth);
			playerUndoQueues.put(playerName, queue);
		}
		return queue;
	}

	public void addToUndoQueue(Player player, BlockList blocks)
	{
		UndoQueue queue = getUndoQueue(player.getName());

		queue.add(blocks);
	}

	public boolean undoAny(Player player, Block target)
	{
		for (String playerName : playerUndoQueues.keySet())
		{
			UndoQueue queue = playerUndoQueues.get(playerName);
			if (queue.undo(target))
			{
				if (!player.getName().equals(playerName))
				{
					player.sendMessage("Undid one of " + playerName + "'s spells");
				}
				return true;
			}
		}

		return false;
	}

	public boolean undo(String playerName)
	{
		UndoQueue queue = getUndoQueue(playerName);
		return queue.undo();
	}

	public boolean undo(String playerName, Block target)
	{
		UndoQueue queue = getUndoQueue(playerName);
		return queue.undo(target);
	}

	public BlockList getLastBlockList(String playerName, Block target)
	{
		UndoQueue queue = getUndoQueue(playerName);
		return queue.getLast(target);
	}

	public BlockList getLastBlockList(String playerName)
	{
		UndoQueue queue = getUndoQueue(playerName);
		return queue.getLast();
	}

	public void scheduleCleanup(BlockList blocks)
	{
		Server server = plugin.getServer();
		BukkitScheduler scheduler = server.getScheduler();

		// scheduler works in ticks- 20 ticks per second.
		long ticksToLive = blocks.getTimeToLive() * 20 / 1000;
		scheduler.scheduleSyncDelayedTask(plugin, new CleanupBlocksTask(blocks), ticksToLive);
	}

	/*
	 * Event registration- call to listen for events
	 */

	public void registerEvent(SpellEventType type, Spell spell)
	{
		PlayerSpells spells = getPlayerSpells(spell.getPlayer());
		spells.registerEvent(type, spell);
	}

	public void unregisterEvent(SpellEventType type, Spell spell)
	{
		PlayerSpells spells = getPlayerSpells(spell.getPlayer());
		spells.unregisterEvent(type, spell);
	}

	/*
	 * Random utility functions
	 */

	public boolean cancel(Player player)
	{
		PlayerSpells playerSpells = getPlayerSpells(player);
		return playerSpells.cancel();
	}

	public boolean isQuiet()
	{
		return quiet;
	}

	public boolean isSilent()
	{
		return silent;
	}

	public boolean soundsEnabled()
	{
		return soundsEnabled;
	}

	public boolean isSolid(Material mat)
	{
		return (mat != Material.AIR && mat != Material.WATER && mat != Material.STATIONARY_WATER && mat != Material.LAVA && mat != Material.STATIONARY_LAVA);
	}

	public boolean isSticky(Material mat)
	{
		return stickyMaterials.contains(mat);
	}

	public boolean isStickyAndTall(Material mat)
	{
		return stickyMaterialsDoubleHeight.contains(mat);
	}

	public boolean isAffectedByGravity(Material mat)
	{
		// DOORS are on this list, it's a bit of a hack, but if you consider
		// them
		// as two separate blocks, the top one of which "breaks" when the bottom
		// one does,
		// it applies- but only really in the context of the auto-undo system,
		// so this should probably be its own mat list, ultimately.
		return (mat == Material.GRAVEL || mat == Material.SAND || mat == Material.WOOD_DOOR || mat == Material.IRON_DOOR);
	}

	/*
	 * Get the log, if you need to debug or log errors.
	 */
	public Logger getLog()
	{
		return log;
	}

	public MagicPlugin getPlugin()
	{
		return plugin;
	}
	
	public boolean hasBuildPermission(Player player, Location location) {
		return hasBuildPermission(player, location.getBlock());
	}

	public boolean hasBuildPermission(Player player, Block block) {
		if (regionManager == null) return true;
		
		try {
			Method canBuildMethod = regionManager.getClass().getMethod("canBuild", Player.class, Block.class);
			if (canBuildMethod != null) {
				return (Boolean)canBuildMethod.invoke(regionManager, player, block);
			}
		} catch (Throwable ex) {
		}
		
		return true;
		
	}
	
	/*
	 * Internal functions - don't call these, or really anything below here.
	 */
	
	/*
	 * Saving and loading
	 */

	public void initialize(MagicPlugin plugin)
	{
		// Try to (dynamically) link to WorldGuard:
		try {
			regionManager = plugin.getServer().getPluginManager().getPlugin("WorldGuard");
			Method canBuildMethod = regionManager.getClass().getMethod("canBuild", Player.class, Block.class);
			if (canBuildMethod != null) {
				log.info("WorldGuard found, will respect build permissions for construction spells");
			} else {
				regionManager = null;
			}
		} catch (Throwable ex) {
		}
		
		if (regionManager == null) {
			log.info("WorldGuard not found, not using a region manager.");
		}
		
		this.plugin = plugin;
		load();
		
		// Set up the Wand-tracking timer
		Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, new Runnable() {
			public void run() {
				for (PlayerSpells spells : playerSpells.values()) {
					Player player = spells.getPlayer();
					Wand wand = spells.getActiveWand();
					if (player.isOnline() && wand != null) {
						wand.processRegeneration();
					}
				}
			}
		}, 0, 20);
	}

	public void load()
	{
		File dataFolder = plugin.getDataFolder();
		dataFolder.mkdirs();

		File propertiesFile = new File(dataFolder, propertiesFileName);
		if (!propertiesFile.exists())
		{
			File oldDefaults = new File(dataFolder, propertiesFileNameDefaults);
			oldDefaults.delete();
			plugin.saveResource(propertiesFileNameDefaults, false);
			loadProperties(plugin.getResource(propertiesFileNameDefaults));
		} else {
			loadProperties(propertiesFile);
		}

		File spellsFile = new File(dataFolder, spellsFileName);
		if (!spellsFile.exists())
		{
			File oldDefaults = new File(dataFolder, spellsFileNameDefaults);
			oldDefaults.delete();
			plugin.saveResource(spellsFileNameDefaults, false);
			load(plugin.getResource(spellsFileNameDefaults));
		} else {
			load(spellsFile);
		}
		
		Wand.load(plugin);

		log.info("Magic: Loaded " + spells.size() + " spells and " + Wand.getWandTemplates().size() + " wands");
	}

	protected void save(File spellsFile)
	{
		Configuration config = new Configuration(spellsFile);
		ConfigurationNode spellsNode = config.createChild("spells");

		for (Spell spell : spells.values())
		{
			ConfigurationNode spellNode = spellsNode.createChild(spell.getKey());
			spell.save(spellNode);
		}

		config.save();
	}

	protected void load(File spellsFile)
	{
		load(new Configuration(spellsFile));
	}

	protected void load(InputStream spellsConfig)
	{
		load(new Configuration(spellsConfig));
	}
	
	protected void load(Configuration config)
	{
		config.load();

		ConfigurationNode spellsNode = config.getNode("spells");
		if (spellsNode == null) return;

		List<String> spellKeys = spellsNode.getKeys();
		for (String key : spellKeys)
		{
			ConfigurationNode spellNode = spellsNode.getNode(key);
			Spell newSpell = Spell.loadSpell(key, spellNode, this);
			if (newSpell == null)
			{
				log.warning("Magic: Error loading spell " + key);
				continue;
			}
			addSpell(newSpell);
		}
	}

	protected void loadProperties(File propertiesFile)
	{
		loadProperties(new Configuration(propertiesFile));
	}
	
	protected void loadProperties(InputStream properties)
	{
		loadProperties(new Configuration(properties));
	}
	
	protected void loadProperties(Configuration properties)
	{
		properties.load();

		ConfigurationNode generalNode = properties.getNode("general");
		undoQueueDepth = generalNode.getInteger("undo_depth", undoQueueDepth);
		silent = generalNode.getBoolean("silent", silent);
		quiet = generalNode.getBoolean("quiet", quiet);
		soundsEnabled = generalNode.getBoolean("sounds", soundsEnabled);
		blockPopulatorEnabled = generalNode.getBoolean("enable_block_populator", blockPopulatorEnabled);
		enchantingEnabled = generalNode.getBoolean("enable_enchanting", enchantingEnabled);
		blockPopulatorConfig = generalNode.getNode("populate_chests");

		buildingMaterials = generalNode.getMaterials("building", DEFAULT_BUILDING_MATERIALS);
		targetThroughMaterials = generalNode.getMaterials("target_through", DEFAULT_TARGET_THROUGH_MATERIALS);

		CSVParser csv = new CSVParser();
		stickyMaterials = csv.parseMaterials(STICKY_MATERIALS);
		stickyMaterialsDoubleHeight = csv.parseMaterials(STICKY_MATERIALS_DOUBLE_HEIGHT);
		
		// Parse wand settings
		Wand.WandMaterial = generalNode.getMaterial("wand_item", Wand.WandMaterial);
		Wand.CopyMaterial = generalNode.getMaterial("copy_item", Wand.CopyMaterial);
		Wand.EraseMaterial = generalNode.getMaterial("erase_item", Wand.EraseMaterial);
		Wand.EnchantableWandMaterial = generalNode.getMaterial("wand_item_enchantable", Wand.EnchantableWandMaterial);

		// Parse crafting recipe settings
		boolean craftingEnabled = generalNode.getBoolean("enable_crafting", false);
		if (craftingEnabled) {
			recipeOutputTemplate = generalNode.getString("crafting_output", recipeOutputTemplate);
			wandRecipeUpperMaterial = generalNode.getMaterial("crafting_material_upper", Material.DIAMOND);
			wandRecipeLowerMaterial = generalNode.getMaterial("crafting_material_lower", Material.BLAZE_ROD);
			Wand wand = new Wand(this);
			ShapedRecipe recipe = new ShapedRecipe(wand.getItem());
			recipe.shape("o", "i").
					setIngredient('o', wandRecipeUpperMaterial).
					setIngredient('i', wandRecipeLowerMaterial);
			wandRecipe = recipe;
		}
		
		properties.save();
	}

	public void clear()
	{
		playerSpells.clear();
		spells.clear();
		spellsByMaterial.clear();
	}

	public void reset()
	{
		log.info("Magic: Resetting all spells to default");
		clear();

		File dataFolder = plugin.getDataFolder();
		dataFolder.mkdirs();

		File spellsFile = new File(dataFolder, spellsFileName);
		spellsFile.delete();

		File magicFile = new File(dataFolder, propertiesFileName);
		magicFile.delete();

		Wand.reset(plugin);
		
		load();
	}

	public List<Spell> getAllSpells()
	{
		List<Spell> allSpells = new ArrayList<Spell>();
		allSpells.addAll(spells.values());
		return allSpells;
	}
	
	public boolean allowPhysics(Block block)
	{
		if (physicsDisableTimeout == 0)
			return true;
		if (System.currentTimeMillis() > physicsDisableTimeout)
			physicsDisableTimeout = 0;
		return false;
	}

	public void disablePhysics(int interval)
	{
		physicsDisableTimeout = System.currentTimeMillis() + interval;
	}

	public boolean hasWandPermission(Player player)
	{
		return hasPermission(player, "Magic.wand.use", true);
	}

	public boolean hasPermission(Player player, String pNode, boolean defaultValue)
	{
		boolean isSet = player.isPermissionSet(pNode);
		return isSet ? player.hasPermission(pNode) : defaultValue;
	}

	public boolean hasPermission(Player player, String pNode)
	{
		return hasPermission(player, pNode, false);
	}

	/*
	 * Listeners / callbacks
	 */
	@EventHandler
	public void onContainerClick(InventoryDragEvent event) {
		// this is a huge hack! :\
		// I apologize for any weird behavior this causes.
		// Bukkit, unfortunately, will blow away NBT data for anything you drag
		// Which will nuke a wand or spell.
		// To make matters worse, Bukkit passes a copy of the item in the event, so we can't 
		// even check for metadata and only cancel the event if it involves one of our special items.
		// The best I can do is look for metadata at all, since Bukkit will retain the name and lore.
		ItemStack oldStack = event.getOldCursor();
		if (oldStack != null && oldStack.hasItemMeta()) {
			event.setCancelled(true);
			return;
		}
	}
	
	@SuppressWarnings("deprecation")
	@EventHandler
	public void onPlayerEquip(PlayerItemHeldEvent event)
	{
		Player player = event.getPlayer();
		PlayerInventory inventory = player.getInventory();
		ItemStack next = inventory.getItem(event.getNewSlot());
		ItemStack previous = inventory.getItem(event.getPreviousSlot());

		PlayerSpells playerSpells = getPlayerSpells(player);
		Wand activeWand = playerSpells.getActiveWand();
		
		// Check for active Wand
		if (activeWand != null && Wand.isWand(previous)) {
			// If the wand inventory is open, we're going to let them select a spell or material
			if (activeWand.isInventoryOpen()) {
				// Update the wand item, Bukkit has probably made a copy
				activeWand.setItem(previous);
				
				// Check for spell or material selection
				if (next != null && next.getType() != Material.AIR) {
					Spell spell = Wand.isSpell(next) ? playerSpells.getSpell(next.getType()) : null;
					if (spell != null) {
						playerSpells.cancel();
						activeWand.setActiveSpell(spell.getKey());
					} else {
						Material material = next.getType();
						if (buildingMaterials.contains(material) || material == Wand.EraseMaterial || material == Wand.CopyMaterial) {
							activeWand.setActiveMaterial(material, next.getData().getData());
						}
					}
				}
				// Cancelling the event causes some name bouncing. Trying out just resetting the item slot in a tick.
				Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, new SetActiveItemSlotTask(player, event.getPreviousSlot()), 1);
				return;	
			} else {
				// Otherwise, we're switching away from the wand, so deactivate it.
				activeWand.deactivate();
			}
		}
		
		// If we're switching to a wand, activate it.
		if (next != null && Wand.isWand(next)) {
			Wand newWand = new Wand(this, next);
			newWand.activate(playerSpells);
		}
	}

	@EventHandler
	public void onPlayerMove(PlayerMoveEvent event)
	{
		PlayerSpells spells = getPlayerSpells(event.getPlayer());
		spells.onPlayerMove(event);
	}

	@EventHandler
	public void onEntityDeath(EntityDeathEvent event)
	{
		if (event.getEntityType() == EntityType.PLAYER && event.getEntity() instanceof Player) {
			onPlayerDeath((Player)event.getEntity(), event);
		}
	}

	public void onPlayerDeath(Player player, EntityDeathEvent event)
	{
		PlayerSpells playerSpells = getPlayerSpells(player);
		String rule = player.getWorld().getGameRuleValue("keepInventory");
		Wand wand = playerSpells.getActiveWand();
		if (wand != null  && !rule.equals("true")) {
			List<ItemStack> drops = event.getDrops();
			drops.clear();
			
			// Drop the held wand since it does not get stored
			drops.add(wand.getItem());
			
			// Retrieve stored inventory before deactiavting the wand
			if (playerSpells.hasStoredInventory()) {
				ItemStack[] stored = playerSpells.getStoredInventory().getContents();
				
				// Deactivate the wand.
				wand.deactivate();
	
				// Clear the inventory, which was just restored by the wand
				player.getInventory().clear();
				for (ItemStack stack : stored) {
					if (stack != null) {
						drops.add(stack);
					}
				}
			} else {
				wand.deactivate();
			}
		}

		playerSpells.onPlayerDeath(event);
	}

	public void onPlayerDamage(Player player, EntityDamageEvent event)
	{
		PlayerSpells spells = getPlayerSpells(player);
		spells.onPlayerDamage(event);
	}

	@EventHandler
	public void onEntityDamage(EntityDamageEvent event)
	{
		if (Player.class.isInstance(event.getEntity()))
		{
			Player player = (Player)event.getEntity();
			onPlayerDamage(player, event);
		}
	}

	@EventHandler
	public void onPlayerInteract(PlayerInteractEvent event)
	{
		Player player = event.getPlayer();
		PlayerSpells playerSpells = getPlayerSpells(player);
		Wand wand = playerSpells.getActiveWand();
		
		if (wand == null || !hasWandPermission(player))
		{
			return;
		}
		
		if (event.getAction() == Action.LEFT_CLICK_AIR || event.getAction() == Action.LEFT_CLICK_BLOCK)
		{
			wand.cast();
		}
		boolean toggleInventory = (event.getAction() == Action.RIGHT_CLICK_AIR);
		if (!toggleInventory && event.getAction() == Action.RIGHT_CLICK_BLOCK) {
			Material material = event.getMaterial();
			toggleInventory = !(material == Material.CHEST || material == Material.WOODEN_DOOR 
					|| material == Material.IRON_DOOR_BLOCK || material == Material.ENDER_CHEST
					|| material == Material.ANVIL || material == Material.BREWING_STAND || material == Material.ENCHANTMENT_TABLE
					|| material == Material.STONE_BUTTON || material == Material.LEVER || material == Material.FURNACE
					|| material == Material.BED || material == Material.SIGN_POST || material == Material.COMMAND);
		}
		if (toggleInventory)
		{
			// Check for spell cancel first, e.g. fill or force
			if (!playerSpells.cancel()) {
				if (wand.getHasInventory()) {
					if (wand.isInventoryOpen()) {
						playerSpells.playSound(Sound.CHEST_CLOSE, 0.4f, 0.2f);
						wand.closeInventory();
					} else {
						playerSpells.playSound(Sound.CHEST_OPEN, 0.4f, 0.2f);
						wand.openInventory();
					}
				}
			} else {
				playerSpells.playSound(Sound.NOTE_BASS, 1.0f, 0.7f);
			}
		}
	}

	@EventHandler
	public void onBlockPhysics(BlockPhysicsEvent event)
	{
		if (!allowPhysics(event.getBlock()))
		{
			event.setCancelled(true);
		}
	}

	@EventHandler
	public void onPlayerJoin(PlayerJoinEvent event)
	{
		// Check for wand re-activation.
		Player player = event.getPlayer();
		PlayerSpells playerSpells = getPlayerSpells(player);
		Wand wand = Wand.getActiveWand(this, player);
		if (wand != null) {
			wand.activate(playerSpells);
		}
	}

	@EventHandler
	public void onPlayerQuit(PlayerQuitEvent event)
	{
		Player player = event.getPlayer();
		PlayerSpells playerSpells = getPlayerSpells(player);
		Wand wand = playerSpells.getActiveWand();
		if (wand != null) {
			wand.deactivate();
		}
		
		// Just in case...
		playerSpells.restoreInventory();
		
		playerSpells.onPlayerQuit(event);
	}

	@SuppressWarnings("deprecation")
	@EventHandler
	public void onPluginDisable(PluginDisableEvent event)
	{
		for (PlayerSpells spells : playerSpells.values()) {
			Player player = spells.getPlayer();
			Wand wand = spells.getActiveWand();
			if (wand != null) {
				wand.deactivate();
			}
			spells.restoreInventory();
			player.updateInventory();
		}
	}

	@SuppressWarnings("deprecation")
	@EventHandler
	public void onPluginEnable(PluginEnableEvent event)
	{
		Player[] players = plugin.getServer().getOnlinePlayers();
		for (Player player : players) {
			Wand wand = Wand.getActiveWand(this, player);
			if (wand != null) {
				PlayerSpells spells = getPlayerSpells(player);
				wand.activate(spells);
				player.updateInventory();
			}
		}
		
		// Add our custom recipe if crafting is enabled
		if (wandRecipe != null) {
			plugin.getServer().addRecipe(wandRecipe);
		}
	}
	
	@EventHandler
	public void onPrepareCraftItem(PrepareItemCraftEvent event) 
	{
		Recipe recipe = event.getRecipe();
		if (wandRecipe != null && recipe.getResult().getType() == Wand.WandMaterial) {
			// Verify that this was our recipe
			// Just in case something else can craft our base material (e.g. stick)
			Inventory inventory = event.getInventory();
			if (!inventory.contains(wandRecipeLowerMaterial) || !inventory.contains(wandRecipeUpperMaterial)) {
				return;
			}
			
			Wand wand = Wand.createWand(this, recipeOutputTemplate);
			if (wand == null) {
				wand = new Wand(this);
			}
			event.getInventory().setResult(wand.getItem());
		}
	}
	
	@EventHandler
	public void onCraftItem(CraftItemEvent event) {
		if (!(event.getWhoClicked() instanceof Player)) return;
		
		Player player = (Player)event.getWhoClicked();
		PlayerSpells spells = getPlayerSpells(player);
		
		// Don't allow crafting in the wand inventory.
		if (spells.hasStoredInventory()) {
			event.setCancelled(true); 
			return;
		}
	}

	@EventHandler
	public void onInventoryOpen(InventoryOpenEvent event) {
		if (!(event.getPlayer() instanceof Player) || event.getView().getType() == InventoryType.CRAFTING) return;
		
		Player player = (Player)event.getPlayer();
		PlayerSpells playerSpells = getPlayerSpells(player);
		Wand wand = playerSpells.getActiveWand();
		if (wand != null) {
			wand.deactivate();
		}
	}
	
	@EventHandler
	public void onInventoryClick(InventoryClickEvent event) {
		
		if (!(event.getWhoClicked() instanceof Player)) return;
		
		if (event.getInventory().getType() == InventoryType.ENCHANTING)
		{
			SlotType slotType = event.getSlotType();
			if (slotType == SlotType.CRAFTING) {
				ItemStack cursor = event.getCursor();
				ItemStack current = event.getCurrentItem();
				
				// Make wands into an enchantable item when placing
				if (Wand.isWand(cursor)) {
					Wand wand = new Wand(this, cursor);
					wand.makeEnchantable(true);
				}
				// And turn them back when taking out
				if (Wand.isWand(current)) {
					Wand wand = new Wand(this, current);
					wand.makeEnchantable(false);
				}
			}
		}
		if (event.getInventory().getType() == InventoryType.ANVIL)
		{
			SlotType slotType = event.getSlotType();
			ItemStack cursor = event.getCursor();
			ItemStack current = event.getCurrentItem();
			
			// Set/unset active names when starting to craft
			if (slotType == SlotType.CRAFTING) {
				// Putting a wand into the anvil's crafting slot
				if (Wand.isWand(cursor)) {
					Wand wand = new Wand(this, cursor);
					wand.updateName(false);
				} 
				// Taking a wand out of the anvil's crafting slot
				if (Wand.isWand(current)) {
					Wand wand = new Wand(this, current);
					wand.updateName(true);
				}
			}
			
			// Rename wand when taking from result slot
			if (slotType == SlotType.RESULT && Wand.isWand(current)) {
				ItemMeta meta = current.getItemMeta();
				String newName = meta.getDisplayName();
				Wand wand = new Wand(this, current);
				wand.setName(newName);
			}
		}
	}

	@EventHandler
	public void onInventoryClosed(InventoryCloseEvent event) {
		if (!(event.getPlayer() instanceof Player)) return;
			
		// Update the active wand, it may have changed around
		Player player = (Player)event.getPlayer();
		PlayerSpells playerSpells = getPlayerSpells(player);
		
		// Save the inventory state the the current wand if its spell inventory is open
		// This is just to make sure we don't lose changes made to the inventory
		Wand previousWand = playerSpells.getActiveWand();
		if (previousWand != null && previousWand.isInventoryOpen()) {
			previousWand.saveInventory();
		}
		
		Wand wand = Wand.getActiveWand(this, player);
		boolean changedWands = false;
		if (previousWand != null && wand == null) changedWands = true;
		if (previousWand == null && wand != null) changedWands = true;
		if (previousWand != null && wand != null && !previousWand.equals(wand)) changedWands = true;
		if (changedWands) {
			boolean inventoryWasOpen = false;
			if (previousWand != null) {
				inventoryWasOpen = previousWand.isInventoryOpen();
				previousWand.deactivate();
			}
			if (wand != null) {
				wand.activate(playerSpells);
				if (inventoryWasOpen) {
					wand.openInventory();
				}
			}
		}
	}

	@EventHandler
	public void onPlayerPickupItem(PlayerPickupItemEvent event)
	{
		PlayerSpells spells = getPlayerSpells(event.getPlayer());
		if (spells.hasStoredInventory()) {
			event.setCancelled(true);   		
			if (spells.addToStoredInventory(event.getItem().getItemStack())) {
				event.getItem().remove();
			}
		} else {
			// Hackiness needed because we don't get an equip event for this!
			PlayerInventory inventory = event.getPlayer().getInventory();
			ItemStack inHand = inventory.getItemInHand();
			ItemStack pickup = event.getItem().getItemStack();
			if (Wand.isWand(pickup) && (inHand == null || inHand.getType() == Material.AIR)) {
				Wand wand = new Wand(this, pickup);
				event.setCancelled(true);
				event.getItem().remove();
				inventory.setItem(inventory.getHeldItemSlot(), pickup);
				wand.activate(spells);
			} 
		}
	}

	@EventHandler
	public void onPlayerDropItem(PlayerDropItemEvent event)
	{
		Player player = event.getPlayer();
		PlayerSpells spells = getPlayerSpells(player);
		Wand activeWand = spells.getActiveWand();
		if (activeWand != null) {
			ItemStack inHand = event.getPlayer().getInventory().getItemInHand();
			// Kind of a hack- check if we just dropped a wand, and now have an empty hand
			if (Wand.isWand(event.getItemDrop().getItemStack()) && (inHand == null || inHand.getType() == Material.AIR)) {
				activeWand.deactivate();
				// Clear after inventory restore, since that will put the wand back
				player.setItemInHand(new ItemStack(Material.AIR, 1));
			} else {
				event.setCancelled(true);
			}
		}
	}
	
	@EventHandler
	public void onEnchantItem(EnchantItemEvent event) {
		if (enchantingEnabled && Wand.isWand(event.getItem())) {
			event.getEnchantsToAdd().clear();
			int level = event.getExpLevelCost();
			Wand wand = new Wand(this, event.getItem());
			WandLevel.randomizeWand(wand, true, level);
		}
	}
	
	@EventHandler
	public void onPrepareEnchantItem(PrepareItemEnchantEvent event) {
		if (Wand.isWand(event.getItem())) {
			Set<Integer> levelSet = WandLevel.getLevels();
			ArrayList<Integer> levels = new ArrayList<Integer>();
			levels.addAll(levelSet);
			int[] offered = event.getExpLevelCostsOffered();
			int bonusLevels = event.getEnchantmentBonus();
			for (int i = 0; i < offered.length; i++) {
				int levelIndex = (int)((float)i * levels.size() / (float)offered.length);
				levelIndex += (float)bonusLevels * ((i + 1) / offered.length);
				levelIndex = Math.min(levelIndex, levels.size() - 1);
				offered[i] = levels.get(levelIndex);
			}
			event.setCancelled(false);
		}
	}
	
	@EventHandler
	public void onWorldInit(WorldInitEvent event) {
		// Install our block populator if configured to do so.
		if (blockPopulatorEnabled && blockPopulatorConfig != null) {
			World world = event.getWorld();
			world.getPopulators().add(new WandChestPopulator(this, blockPopulatorConfig));
		}
	}

	public Spell getSpell(Material material) {
		return spellsByMaterial.get(material);
	}
	
	public Spell getSpell(String name) {
		return spells.get(name);
	}

	/*
	 * Private data
	 */
	 private final String                        spellsFileName                 = "spells.yml";
	 private final String                        propertiesFileName             = "magic.yml";
	 private final String                        spellsFileNameDefaults         = "spells.defaults.yml";
	 private final String                        propertiesFileNameDefaults     = "magic.defaults.yml";

	 static final String                         DEFAULT_BUILDING_MATERIALS     = "0,1,2,3,4,5,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,33,34,35,41,42,43,45,46,47,48,49,52,53,55,56,57,58,60,61,62,65,66,67,73,74,79,80,81,82,83,84,85,86,87,88,89,90,91,92,93,94,95,96,97,98,99,100,101,102,103,104,105,106,107,108,109";
	 static final String                         DEFAULT_TARGET_THROUGH_MATERIALS = "0";
	 
	 static final String                         STICKY_MATERIALS               = "37,38,39,50,51,55,59,63,64,65,66,68,70,71,72,75,76,77,78,83";
	 static final String                         STICKY_MATERIALS_DOUBLE_HEIGHT = "64,71,";

	 private List<Material>                      buildingMaterials              = new ArrayList<Material>();
	 private List<Material>                      stickyMaterials                = new ArrayList<Material>();
	 private List<Material>                      stickyMaterialsDoubleHeight    = new ArrayList<Material>();
	 private List<Material>                      targetThroughMaterials  		= new ArrayList<Material>();

	 private long                                physicsDisableTimeout          = 0;
	 private int                                 undoQueueDepth                 = 256;
	 private boolean                             silent                         = false;
	 private boolean                             quiet                          = true;
	 private boolean                             soundsEnabled                  = true;
	 private boolean							 blockPopulatorEnabled			= false;
	 private boolean							 enchantingEnabled				= false;
	 private ConfigurationNode					 blockPopulatorConfig			= null;
	 private HashMap<String, UndoQueue>          playerUndoQueues               = new HashMap<String, UndoQueue>();

	 private final Logger                        log                            = Logger.getLogger("Minecraft");
	 private final HashMap<String, Spell>        spells                         = new HashMap<String, Spell>();
	 private final HashMap<Material, Spell>      spellsByMaterial               = new HashMap<Material, Spell>();
	 private final HashMap<String, PlayerSpells> playerSpells                   = new HashMap<String, PlayerSpells>();

	 private Recipe								 wandRecipe						= null;
	 private Material							 wandRecipeUpperMaterial		= Material.DIAMOND;
	 private Material							 wandRecipeLowerMaterial		= Material.BLAZE_ROD;
	 private String								 recipeOutputTemplate			= "random(1)";
	 
	 private MagicPlugin                         plugin                         = null;
	 private Object								 regionManager					= null;
}
