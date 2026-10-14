package dev.bigenderchest;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Doubles the ender chest to 54 slots.
 *
 * Slots 0-26 are the player's REAL vanilla ender chest (so nothing is lost if the plugin is removed
 * and other plugins still see those items). Slots 27-53 are extra slots stored in plugins/BigEnderChest/data.
 * The plugin has no commands; the big chest only opens by right-clicking an ender chest block.
 */
public class BigEnderChest extends JavaPlugin implements Listener {

    private static final int VANILLA_SIZE = 27;
    private static final int BIG_SIZE = 54;

    private File dataDir;
    private boolean blockCommands;
    private final Set<String> blockedCommands = new HashSet<>();
    private Component blockedMessage = Component.empty();

    /** Marks our custom inventory. */
    private static class BigHolder implements InventoryHolder {
        final UUID owner;
        final Location chestLocation;
        Inventory inv;

        BigHolder(UUID owner, Location chestLocation) {
            this.owner = owner;
            this.chestLocation = chestLocation;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        dataDir = new File(getDataFolder(), "data");
        if (!dataDir.exists() && !dataDir.mkdirs()) {
            getLogger().warning("Could not create data folder: " + dataDir);
        }
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        // Save and close any big ender chest that is open during shutdown/reload
        for (Player p : Bukkit.getOnlinePlayers()) {
            Inventory top = p.getOpenInventory().getTopInventory();
            if (top.getHolder() instanceof BigHolder) {
                save(p, top);
                p.closeInventory();
            }
        }
    }

    private void loadSettings() {
        reloadConfig();
        blockCommands = getConfig().getBoolean("block-enderchest-commands", true);
        blockedCommands.clear();
        for (String s : getConfig().getStringList("blocked-commands")) {
            blockedCommands.add(s.toLowerCase(Locale.ROOT));
        }
        blockedMessage = LegacyComponentSerializer.legacyAmpersand().deserialize(
                getConfig().getString("blocked-message",
                        "&cYou can only open your ender chest by right-clicking an ender chest block."));
    }

    // ------------------------------------------------------------------
    // Opening the big chest
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getHand() != EquipmentSlot.HAND) return;

        Block block = e.getClickedBlock();
        if (block == null || block.getType() != Material.ENDER_CHEST) return;

        Player p = e.getPlayer();
        if (p.getGameMode() == GameMode.SPECTATOR) return;

        // Same as vanilla: sneaking while holding an item places a block instead of opening
        if (p.isSneaking()
                && (!p.getInventory().getItemInMainHand().isEmpty()
                || !p.getInventory().getItemInOffHand().isEmpty())) {
            return;
        }

        // Same as vanilla: an ender chest with a solid block on top cannot be opened
        if (block.getRelative(BlockFace.UP).getType().isOccluding()) return;

        e.setCancelled(true);
        openBigChest(p, block.getLocation());
    }

    private void openBigChest(Player p, Location chestLoc) {
        BigHolder holder = new BigHolder(p.getUniqueId(), chestLoc);
        Inventory inv = Bukkit.createInventory(holder, BIG_SIZE, Component.text("Ender Chest"));
        holder.inv = inv;

        // Slots 0-26: the player's real ender chest
        Inventory real = p.getEnderChest();
        for (int i = 0; i < VANILLA_SIZE; i++) {
            ItemStack it = real.getItem(i);
            if (it != null) inv.setItem(i, it.clone());
        }

        // Slots 27-53: extra slots from our data file
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(dataFile(p.getUniqueId()));
        for (int i = VANILLA_SIZE; i < BIG_SIZE; i++) {
            ItemStack it = cfg.getItemStack("slots." + i);
            if (it != null) inv.setItem(i, it);
        }

        p.openInventory(inv);
        chestLoc.getWorld().playSound(chestLoc.clone().add(0.5, 0.5, 0.5),
                Sound.BLOCK_ENDER_CHEST_OPEN, SoundCategory.BLOCKS, 0.5f, 1.0f);
    }

    // ------------------------------------------------------------------
    // Saving
    // ------------------------------------------------------------------

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof BigHolder holder)) return;
        if (!(e.getPlayer() instanceof Player p)) return;

        save(p, e.getInventory());

        Location loc = holder.chestLocation;
        if (loc.getWorld() != null) {
            loc.getWorld().playSound(loc.clone().add(0.5, 0.5, 0.5),
                    Sound.BLOCK_ENDER_CHEST_CLOSE, SoundCategory.BLOCKS, 0.5f, 1.0f);
        }
    }

    private void save(Player p, Inventory inv) {
        // First 27 slots go back into the real vanilla ender chest
        Inventory real = p.getEnderChest();
        for (int i = 0; i < VANILLA_SIZE; i++) {
            real.setItem(i, inv.getItem(i));
        }

        // Extra 27 slots go to our file
        YamlConfiguration cfg = new YamlConfiguration();
        for (int i = VANILLA_SIZE; i < BIG_SIZE; i++) {
            ItemStack it = inv.getItem(i);
            if (it != null && !it.isEmpty()) cfg.set("slots." + i, it);
        }

        File target = dataFile(p.getUniqueId());
        File tmp = new File(dataDir, p.getUniqueId() + ".yml.tmp");
        try {
            Files.writeString(tmp.toPath(), cfg.saveToString(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp.toPath(), target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            // Never lose items: if we cannot write the file, give the extra items back to the player
            getLogger().severe("Could not save ender chest of " + p.getName() + ": " + ex.getMessage());
            for (int i = VANILLA_SIZE; i < BIG_SIZE; i++) {
                ItemStack it = inv.getItem(i);
                if (it != null && !it.isEmpty()) {
                    p.getInventory().addItem(it).values()
                            .forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
                }
            }
        }
    }

    private File dataFile(UUID id) {
        return new File(dataDir, id + ".yml");
    }

    // ------------------------------------------------------------------
    // Block /ec style commands (optional, see config.yml)
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (!blockCommands) return;
        if (e.getPlayer().hasPermission("bigenderchest.bypass")) return;

        String msg = e.getMessage().trim();
        if (msg.startsWith("/")) msg = msg.substring(1);
        if (msg.isEmpty()) return;

        String label = msg.split("\\s+")[0].toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0) label = label.substring(colon + 1);

        if (blockedCommands.contains(label)) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(blockedMessage);
        }
    }
}
