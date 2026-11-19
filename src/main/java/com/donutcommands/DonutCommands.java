package com.donutcommands;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.Statistic;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * DonutCommands 1.0.0
 * DonutSMP-style economy: /sell GUI, /shop, /order, /ah, shards, homes, tpa, rtp and more.
 *
 * Notes for cross-version safety (1.20.1 -> 26.x):
 *  - GUIs are identified through a custom InventoryHolder (never InventoryView), because InventoryView
 *    changed from a class to an interface in 1.21 and would break binary compatibility.
 *  - Sounds / statistics are wrapped in try/catch so renamed constants never crash the plugin.
 */
public final class DonutCommands extends JavaPlugin implements Listener {

    private static final String PREFIX = "&6&lDonut &8» &r";
    private static final Random RNG = new Random();

    // ------------------------------------------------------------------ data
    private final Map<UUID, Double> money = new HashMap<>();
    private final Map<UUID, Long> shards = new HashMap<>();
    private final Map<UUID, String> names = new HashMap<>();
    private final Map<Material, Double> sellPrices = new LinkedHashMap<>();
    private final Map<String, Cat> categories = new LinkedHashMap<>();
    private final Map<Integer, Order> orders = new LinkedHashMap<>();
    private final Map<Integer, Listing> listings = new LinkedHashMap<>();
    private final Map<UUID, List<ItemStack>> claims = new HashMap<>();
    private final Map<UUID, Map<String, Location>> homes = new HashMap<>();
    private final Map<UUID, UUID> tpaRequests = new HashMap<>();
    private final Map<UUID, Long> tpaTime = new HashMap<>();
    private final Map<UUID, UUID> lastMsg = new HashMap<>();
    private Location spawn;
    private int nextId = 1;
    private boolean dirty = false;

    private File dataFile;

    static final class Order {
        int id;
        UUID owner;
        String ownerName;
        Material mat;
        int amount;
        int filled;
        double price;
        long created;
    }

    static final class Listing {
        int id;
        UUID seller;
        String sellerName;
        ItemStack item;
        double price;
        long expires;
    }

    static final class Cat {
        String name;
        Material icon;
        final Map<Material, Double> items = new LinkedHashMap<>();
    }

    static final class Gui implements InventoryHolder {
        final String type;
        int page;
        String arg;
        final Map<Integer, Object> map = new HashMap<>();
        Inventory inv;

        Gui(String type) {
            this.type = type;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    // ------------------------------------------------------------------ lifecycle
    @Override
    public void onEnable() {
        getConfig().addDefault("start-balance", 0.0);
        getConfig().addDefault("sell.sell-on-close", false);
        getConfig().addDefault("shop.buy-multiplier", 4.0);
        getConfig().addDefault("ah.max-listings", 20);
        getConfig().addDefault("ah.duration-hours", 48);
        getConfig().addDefault("ah.tax", 0.0);
        getConfig().addDefault("order.max-per-player", 10);
        getConfig().addDefault("rtp.world", "world");
        getConfig().addDefault("rtp.radius", 5000);
        getConfig().addDefault("home.limit", 3);
        getConfig().addDefault("tpa.timeout-seconds", 60);
        getConfig().options().copyDefaults(true);
        saveConfig();

        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }
        dataFile = new File(getDataFolder(), "data.yml");

        loadPrices();
        loadShop();
        loadData();

        Bukkit.getPluginManager().registerEvents(this, this);

        Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (dirty) {
                saveData();
            }
        }, 20L * 60, 20L * 60);

        Bukkit.getScheduler().runTaskTimer(this, this::expireListings, 20L * 60, 20L * 60);

        getLogger().info("DonutCommands 1.0.0 enabled.");
    }

    @Override
    public void onDisable() {
        saveData();
    }

    // ------------------------------------------------------------------ helpers
    private static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    private void msg(CommandSender s, String m) {
        s.sendMessage(color(PREFIX + m));
    }

    private void ping(Player p, boolean ok) {
        try {
            p.playSound(p.getLocation(), ok ? Sound.ENTITY_EXPERIENCE_ORB_PICKUP : Sound.ENTITY_VILLAGER_NO, 1f, 1f);
        } catch (Throwable ignored) {
            // sound constants may differ on future versions
        }
    }

    private boolean needPlayer(CommandSender s) {
        if (s instanceof Player) {
            return true;
        }
        msg(s, "&cThis command is for players only.");
        return false;
    }

    static String fmt(double v) {
        String[] suf = {"", "K", "M", "B", "T"};
        double a = Math.abs(v);
        int i = 0;
        while (a >= 1000 && i < suf.length - 1) {
            a /= 1000;
            i++;
        }
        double val = v < 0 ? -a : a;
        String num;
        if (i == 0) {
            num = (val == Math.floor(val)) ? String.valueOf((long) val) : String.format(Locale.US, "%.2f", val);
        } else {
            num = String.format(Locale.US, "%.2f", val);
            if (num.contains(".")) {
                num = num.replaceAll("0+$", "").replaceAll("\\.$", "");
            }
        }
        return num + suf[i];
    }

    /** Parses 1500, 1.5k, 2m, 3b, 1t. Returns -1 on failure. */
    static double parseAmount(String s) {
        try {
            s = s.toLowerCase(Locale.ROOT).replace(",", "").trim();
            double mult = 1;
            if (s.endsWith("k")) {
                mult = 1e3;
            } else if (s.endsWith("m")) {
                mult = 1e6;
            } else if (s.endsWith("b")) {
                mult = 1e9;
            } else if (s.endsWith("t")) {
                mult = 1e12;
            }
            if (mult != 1) {
                s = s.substring(0, s.length() - 1);
            }
            double d = Double.parseDouble(s) * mult;
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                return -1;
            }
            return d;
        } catch (Exception e) {
            return -1;
        }
    }

    static String pretty(Material m) {
        String[] parts = m.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.toString();
    }

    private static String timeLeft(long ms) {
        if (ms <= 0) {
            return "expired";
        }
        long m = ms / 60000;
        long h = m / 60;
        m = m % 60;
        return h + "h " + m + "m";
    }

    private ItemStack item(Material m, int amt, String name, String... lore) {
        ItemStack it = new ItemStack(m, Math.max(1, amt));
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(name));
            if (lore.length > 0) {
                List<String> l = new ArrayList<>();
                for (String s : lore) {
                    l.add(color(s));
                }
                meta.setLore(l);
            }
            it.setItemMeta(meta);
        }
        return it;
    }

    private Material mat(String name, Material fallback) {
        Material m = Material.matchMaterial(name);
        return m == null ? fallback : m;
    }

    private ItemStack pane() {
        return item(mat("GRAY_STAINED_GLASS_PANE", Material.GLASS_PANE), 1, " ");
    }

    private void give(Player p, ItemStack it) {
        Map<Integer, ItemStack> left = p.getInventory().addItem(it);
        for (ItemStack l : left.values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), l);
        }
    }

    private boolean isPlain(ItemStack it, Material m) {
        if (it == null || it.getType() != m) {
            return false;
        }
        if (it.hasItemMeta()) {
            ItemMeta meta = it.getItemMeta();
            if (meta != null && (meta.hasDisplayName() || meta.hasLore() || meta.hasEnchants())) {
                return false;
            }
        }
        return true;
    }

    private int countItems(Player p, Material m) {
        int c = 0;
        for (ItemStack it : p.getInventory().getStorageContents()) {
            if (isPlain(it, m)) {
                c += it.getAmount();
            }
        }
        return c;
    }

    private void removeItems(Player p, Material m, int amount) {
        ItemStack[] contents = p.getInventory().getStorageContents();
        for (int i = 0; i < contents.length && amount > 0; i++) {
            ItemStack it = contents[i];
            if (!isPlain(it, m)) {
                continue;
            }
            if (it.getAmount() <= amount) {
                amount -= it.getAmount();
                contents[i] = null;
            } else {
                it.setAmount(it.getAmount() - amount);
                amount = 0;
            }
        }
        p.getInventory().setStorageContents(contents);
    }

    private int stat(Player p, String... statNames) {
        for (String n : statNames) {
            try {
                return p.getStatistic(Statistic.valueOf(n));
            } catch (Throwable ignored) {
                // try next
            }
        }
        return 0;
    }

    // ------------------------------------------------------------------ economy
    double bal(UUID u) {
        return money.getOrDefault(u, getConfig().getDouble("start-balance", 0.0));
    }

    void addMoney(UUID u, double amt) {
        money.put(u, bal(u) + amt);
        dirty = true;
    }

    boolean takeMoney(UUID u, double amt) {
        if (bal(u) + 1e-9 < amt) {
            return false;
        }
        money.put(u, bal(u) - amt);
        dirty = true;
        return true;
    }

    long shardsOf(UUID u) {
        return shards.getOrDefault(u, 0L);
    }

    // ------------------------------------------------------------------ prices
    private static final String DEFAULT_SELL =
            "DIAMOND:250,EMERALD:150,GOLD_INGOT:40,IRON_INGOT:20,COPPER_INGOT:8,NETHERITE_INGOT:4000,NETHERITE_SCRAP:800,"
            + "COAL:6,CHARCOAL:5,REDSTONE:3,LAPIS_LAZULI:4,QUARTZ:5,AMETHYST_SHARD:10,DIAMOND_BLOCK:2250,EMERALD_BLOCK:1350,"
            + "GOLD_BLOCK:360,IRON_BLOCK:180,COPPER_BLOCK:72,COAL_BLOCK:54,REDSTONE_BLOCK:27,LAPIS_BLOCK:36,NETHERITE_BLOCK:36000,"
            + "ANCIENT_DEBRIS:700,RAW_IRON:15,RAW_GOLD:30,RAW_COPPER:6,GOLD_NUGGET:4,IRON_NUGGET:2,"
            + "ROTTEN_FLESH:1,BONE:3,BONE_MEAL:1,STRING:3,GUNPOWDER:12,SPIDER_EYE:3,ENDER_PEARL:40,ENDER_EYE:60,BLAZE_ROD:30,"
            + "BLAZE_POWDER:15,GHAST_TEAR:60,SLIME_BALL:8,MAGMA_CREAM:15,NETHER_STAR:3000,SHULKER_SHELL:100,PHANTOM_MEMBRANE:20,"
            + "PRISMARINE_SHARD:6,PRISMARINE_CRYSTALS:6,LEATHER:4,FEATHER:2,INK_SAC:3,GLOW_INK_SAC:6,RABBIT_HIDE:2,"
            + "ECHO_SHARD:150,HEART_OF_THE_SEA:500,NAUTILUS_SHELL:60,TOTEM_OF_UNDYING:800,DRAGON_BREATH:100,"
            + "WHEAT:2,CARROT:2,POTATO:2,BEETROOT:2,SUGAR_CANE:2,SUGAR:2,MELON_SLICE:1,PUMPKIN:4,COCOA_BEANS:3,NETHER_WART:4,"
            + "SWEET_BERRIES:2,KELP:1,DRIED_KELP:1,CACTUS:2,BAMBOO:1,HAY_BLOCK:18,WHEAT_SEEDS:1,PUMPKIN_SEEDS:1,MELON_SEEDS:1,"
            + "BEEF:4,COOKED_BEEF:8,PORKCHOP:4,COOKED_PORKCHOP:8,CHICKEN:3,COOKED_CHICKEN:6,MUTTON:3,COOKED_MUTTON:6,"
            + "RABBIT:3,COD:2,COOKED_COD:4,SALMON:2,COOKED_SALMON:4,APPLE:5,GOLDEN_APPLE:400,ENCHANTED_GOLDEN_APPLE:8000,"
            + "GOLDEN_CARROT:30,BREAD:5,BAKED_POTATO:4,PUMPKIN_PIE:8,CAKE:20,EGG:1,MILK_BUCKET:10,HONEY_BOTTLE:6,HONEYCOMB:5,"
            + "COBBLESTONE:1,STONE:1,DEEPSLATE:1,COBBLED_DEEPSLATE:1,DIRT:1,GRAVEL:1,SAND:1,RED_SAND:1,CLAY_BALL:2,CLAY:8,"
            + "NETHERRACK:1,OBSIDIAN:50,CRYING_OBSIDIAN:75,GLASS:2,BRICK:4,NETHER_BRICK:4,TERRACOTTA:3,END_STONE:2,"
            + "GLOWSTONE_DUST:6,GLOWSTONE:24,SPONGE:100,SEA_LANTERN:30,SOUL_SAND:2,SOUL_SOIL:2,BASALT:1,BLACKSTONE:1,"
            + "TUFF:1,CALCITE:2,ANDESITE:1,DIORITE:1,GRANITE:1,SNOWBALL:1,ICE:2,PACKED_ICE:6,BLUE_ICE:50,"
            + "ELYTRA:5000,TRIDENT:1500,NAME_TAG:150,SADDLE:100,EXPERIENCE_BOTTLE:40,ENCHANTED_BOOK:300,"
            + "DIAMOND_SWORD:550,DIAMOND_PICKAXE:800,DIAMOND_AXE:800,DIAMOND_SHOVEL:260,DIAMOND_HELMET:1250,DIAMOND_CHESTPLATE:2000,"
            + "DIAMOND_LEGGINGS:1750,DIAMOND_BOOTS:1000,IRON_SWORD:45,IRON_PICKAXE:65,IRON_AXE:65,IRON_HELMET:100,"
            + "IRON_CHESTPLATE:160,IRON_LEGGINGS:140,IRON_BOOTS:80,SHIELD:30,BOW:10,CROSSBOW:25,ARROW:1";

    private void loadPrices() {
        File f = new File(getDataFolder(), "prices.yml");
        YamlConfiguration y;
        if (!f.exists()) {
            y = new YamlConfiguration();
            y.options().header("Sell prices per single item. Edit freely, then /donut reload.");
            for (String pair : DEFAULT_SELL.split(",")) {
                String[] kv = pair.split(":");
                y.set("sell." + kv[0], Double.parseDouble(kv[1]));
            }
            for (String wood : new String[]{"OAK", "SPRUCE", "BIRCH", "JUNGLE", "ACACIA", "DARK_OAK", "MANGROVE", "CHERRY"}) {
                y.set("sell." + wood + "_LOG", 4.0);
                y.set("sell." + wood + "_PLANKS", 1.0);
            }
            try {
                y.save(f);
            } catch (IOException e) {
                getLogger().warning("Could not save prices.yml: " + e.getMessage());
            }
        } else {
            y = YamlConfiguration.loadConfiguration(f);
        }
        sellPrices.clear();
        ConfigurationSection sec = y.getConfigurationSection("sell");
        if (sec != null) {
            for (String k : sec.getKeys(false)) {
                Material m = Material.matchMaterial(k);
                if (m != null) {
                    sellPrices.put(m, sec.getDouble(k));
                }
            }
        }
    }

    private double unitSell(ItemStack it) {
        if (it == null || it.getType() == Material.AIR) {
            return 0;
        }
        if (it.hasItemMeta()) {
            ItemMeta meta = it.getItemMeta();
            if (meta != null && (meta.hasDisplayName() || meta.hasLore())) {
                return 0;
            }
        }
        return sellPrices.getOrDefault(it.getType(), 0.0);
    }

    // ------------------------------------------------------------------ shop data
    private void loadShop() {
        File f = new File(getDataFolder(), "shop.yml");
        if (!f.exists()) {
            YamlConfiguration y = new YamlConfiguration();
            y.options().header("Shop categories. items: MATERIAL: buy price per item. Edit freely, then /donut reload.");
            shopDefault(y, "Gear", "DIAMOND_SWORD",
                    "DIAMOND_SWORD:1500,DIAMOND_PICKAXE:2400,DIAMOND_AXE:2400,DIAMOND_HELMET:3500,DIAMOND_CHESTPLATE:5600,"
                            + "DIAMOND_LEGGINGS:4900,DIAMOND_BOOTS:2800,IRON_SWORD:150,IRON_PICKAXE:220,IRON_HELMET:350,"
                            + "IRON_CHESTPLATE:560,IRON_LEGGINGS:490,IRON_BOOTS:280,SHIELD:120,BOW:50,CROSSBOW:90,ARROW:5,ELYTRA:25000");
            shopDefault(y, "Food", "COOKED_BEEF",
                    "COOKED_BEEF:30,COOKED_PORKCHOP:30,COOKED_CHICKEN:25,COOKED_MUTTON:25,COOKED_SALMON:15,COOKED_COD:15,"
                            + "BREAD:20,BAKED_POTATO:15,GOLDEN_CARROT:120,GOLDEN_APPLE:1600,APPLE:20,PUMPKIN_PIE:30,CAKE:80,HONEY_BOTTLE:25");
            shopDefault(y, "Blocks", "GRASS_BLOCK",
                    "STONE:5,COBBLESTONE:4,DEEPSLATE:5,DIRT:4,SAND:5,GRAVEL:5,OAK_LOG:15,SPRUCE_LOG:15,BIRCH_LOG:15,"
                            + "OAK_PLANKS:5,GLASS:10,TERRACOTTA:12,NETHERRACK:4,END_STONE:10,OBSIDIAN:200,GLOWSTONE:100,SEA_LANTERN:120,"
                            + "BRICK:15,SOUL_SAND:8,BLACKSTONE:5,TUFF:5,PACKED_ICE:25,BLUE_ICE:200");
            shopDefault(y, "Farming", "WHEAT",
                    "WHEAT:8,WHEAT_SEEDS:4,CARROT:8,POTATO:8,BEETROOT:8,SUGAR_CANE:8,MELON_SLICE:4,PUMPKIN:16,COCOA_BEANS:12,"
                            + "NETHER_WART:16,SWEET_BERRIES:8,BAMBOO:4,CACTUS:8,KELP:4,BONE_MEAL:4,HAY_BLOCK:70");
            shopDefault(y, "Ores", "DIAMOND",
                    "DIAMOND:1000,EMERALD:600,GOLD_INGOT:160,IRON_INGOT:80,COPPER_INGOT:32,NETHERITE_INGOT:16000,"
                            + "ANCIENT_DEBRIS:2800,COAL:24,REDSTONE:12,LAPIS_LAZULI:16,QUARTZ:20,AMETHYST_SHARD:40,"
                            + "DIAMOND_BLOCK:9000,EMERALD_BLOCK:5400,GOLD_BLOCK:1440,IRON_BLOCK:720");
            shopDefault(y, "Misc", "ENDER_PEARL",
                    "ENDER_PEARL:160,BLAZE_ROD:120,GHAST_TEAR:240,SLIME_BALL:32,GUNPOWDER:48,STRING:12,BONE:12,LEATHER:16,"
                            + "FEATHER:8,PHANTOM_MEMBRANE:80,SHULKER_SHELL:400,EXPERIENCE_BOTTLE:160,NAME_TAG:600,SADDLE:400,"
                            + "TOTEM_OF_UNDYING:3200,NETHER_STAR:12000,ENDER_EYE:240");
            try {
                y.save(f);
            } catch (IOException e) {
                getLogger().warning("Could not save shop.yml: " + e.getMessage());
            }
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        categories.clear();
        ConfigurationSection root = y.getConfigurationSection("categories");
        if (root == null) {
            return;
        }
        for (String name : root.getKeys(false)) {
            ConfigurationSection cs = root.getConfigurationSection(name);
            if (cs == null) {
                continue;
            }
            Cat c = new Cat();
            c.name = name;
            c.icon = mat(cs.getString("icon", "CHEST"), Material.CHEST);
            ConfigurationSection items = cs.getConfigurationSection("items");
            if (items != null) {
                for (String k : items.getKeys(false)) {
                    Material m = Material.matchMaterial(k);
                    if (m != null && m.isItem()) {
                        c.items.put(m, items.getDouble(k));
                    }
                }
            }
            categories.put(name, c);
        }
    }

    private void shopDefault(YamlConfiguration y, String cat, String icon, String items) {
        y.set("categories." + cat + ".icon", icon);
        for (String pair : items.split(",")) {
            String[] kv = pair.split(":");
            y.set("categories." + cat + ".items." + kv[0], Double.parseDouble(kv[1]));
        }
    }

    // ------------------------------------------------------------------ persistence
    private void loadData() {
        if (!dataFile.exists()) {
            return;
        }
        YamlConfiguration c = YamlConfiguration.loadConfiguration(dataFile);
        nextId = Math.max(1, c.getInt("next-id", 1));
        ConfigurationSection pl = c.getConfigurationSection("players");
        if (pl != null) {
            for (String k : pl.getKeys(false)) {
                try {
                    UUID u = UUID.fromString(k);
                    names.put(u, pl.getString(k + ".name", "Unknown"));
                    money.put(u, pl.getDouble(k + ".money", 0));
                    shards.put(u, pl.getLong(k + ".shards", 0));
                } catch (IllegalArgumentException ignored) {
                    // bad uuid
                }
            }
        }
        ConfigurationSection hs = c.getConfigurationSection("homes");
        if (hs != null) {
            for (String k : hs.getKeys(false)) {
                try {
                    UUID u = UUID.fromString(k);
                    ConfigurationSection mine = hs.getConfigurationSection(k);
                    Map<String, Location> map = new HashMap<>();
                    if (mine != null) {
                        for (String hn : mine.getKeys(false)) {
                            Location l = mine.getLocation(hn);
                            if (l != null) {
                                map.put(hn, l);
                            }
                        }
                    }
                    homes.put(u, map);
                } catch (IllegalArgumentException ignored) {
                    // bad uuid
                }
            }
        }
        ConfigurationSection os = c.getConfigurationSection("orders");
        if (os != null) {
            for (String k : os.getKeys(false)) {
                try {
                    Order o = new Order();
                    o.id = Integer.parseInt(k);
                    o.owner = UUID.fromString(os.getString(k + ".owner", ""));
                    o.ownerName = os.getString(k + ".owner-name", "Unknown");
                    Material m = Material.matchMaterial(os.getString(k + ".material", ""));
                    if (m == null) {
                        continue;
                    }
                    o.mat = m;
                    o.amount = os.getInt(k + ".amount");
                    o.filled = os.getInt(k + ".filled");
                    o.price = os.getDouble(k + ".price");
                    o.created = os.getLong(k + ".created");
                    orders.put(o.id, o);
                } catch (IllegalArgumentException ignored) {
                    // skip broken entry
                }
            }
        }
        ConfigurationSection ls = c.getConfigurationSection("listings");
        if (ls != null) {
            for (String k : ls.getKeys(false)) {
                try {
                    Listing l = new Listing();
                    l.id = Integer.parseInt(k);
                    l.seller = UUID.fromString(ls.getString(k + ".seller", ""));
                    l.sellerName = ls.getString(k + ".seller-name", "Unknown");
                    l.item = ls.getItemStack(k + ".item");
                    if (l.item == null) {
                        continue;
                    }
                    l.price = ls.getDouble(k + ".price");
                    l.expires = ls.getLong(k + ".expires");
                    listings.put(l.id, l);
                } catch (IllegalArgumentException ignored) {
                    // skip broken entry
                }
            }
        }
        ConfigurationSection cl = c.getConfigurationSection("claims");
        if (cl != null) {
            for (String k : cl.getKeys(false)) {
                try {
                    UUID u = UUID.fromString(k);
                    List<ItemStack> list = new ArrayList<>();
                    List<?> raw = cl.getList(k);
                    if (raw != null) {
                        for (Object o : raw) {
                            if (o instanceof ItemStack) {
                                list.add((ItemStack) o);
                            }
                        }
                    }
                    claims.put(u, list);
                } catch (IllegalArgumentException ignored) {
                    // bad uuid
                }
            }
        }
        spawn = c.getLocation("spawn");
    }

    private synchronized void saveData() {
        YamlConfiguration c = new YamlConfiguration();
        c.set("next-id", nextId);
        Set<UUID> all = new HashSet<>(money.keySet());
        all.addAll(names.keySet());
        all.addAll(shards.keySet());
        for (UUID u : all) {
            String b = "players." + u;
            c.set(b + ".name", names.getOrDefault(u, "Unknown"));
            c.set(b + ".money", money.getOrDefault(u, 0.0));
            c.set(b + ".shards", shards.getOrDefault(u, 0L));
        }
        for (Map.Entry<UUID, Map<String, Location>> e : homes.entrySet()) {
            for (Map.Entry<String, Location> h : e.getValue().entrySet()) {
                c.set("homes." + e.getKey() + "." + h.getKey(), h.getValue());
            }
        }
        for (Order o : orders.values()) {
            String b = "orders." + o.id;
            c.set(b + ".owner", o.owner.toString());
            c.set(b + ".owner-name", o.ownerName);
            c.set(b + ".material", o.mat.name());
            c.set(b + ".amount", o.amount);
            c.set(b + ".filled", o.filled);
            c.set(b + ".price", o.price);
            c.set(b + ".created", o.created);
        }
        for (Listing l : listings.values()) {
            String b = "listings." + l.id;
            c.set(b + ".seller", l.seller.toString());
            c.set(b + ".seller-name", l.sellerName);
            c.set(b + ".item", l.item);
            c.set(b + ".price", l.price);
            c.set(b + ".expires", l.expires);
        }
        for (Map.Entry<UUID, List<ItemStack>> e : claims.entrySet()) {
            if (!e.getValue().isEmpty()) {
                c.set("claims." + e.getKey(), e.getValue());
            }
        }
        if (spawn != null) {
            c.set("spawn", spawn);
        }
        try {
            c.save(dataFile);
            dirty = false;
        } catch (IOException ex) {
            getLogger().warning("Could not save data.yml: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------------ GUI base
    private Gui make(String type, int size, String title) {
        Gui g = new Gui(type);
        g.inv = Bukkit.createInventory(g, size, color(title));
        return g;
    }

    private void fillRow(Gui g, int from, int to) {
        for (int i = from; i <= to; i++) {
            g.inv.setItem(i, pane());
        }
    }

    private void nav(Gui g, int page, int total) {
        fillRow(g, 45, 53);
        if (page > 0) {
            g.inv.setItem(45, item(Material.ARROW, 1, "&ePrevious page"));
        }
        if ((page + 1) * 45 < total) {
            g.inv.setItem(53, item(Material.ARROW, 1, "&eNext page"));
        }
    }

    // ------------------------------------------------------------------ SELL GUI
    private void openSell(Player p) {
        Gui g = make("sell", 54, "&8Sell Items");
        fillRow(g, 45, 53);
        g.inv.setItem(49, sellButton(g.inv));
        p.openInventory(g.inv);
    }

    private double sellTotal(Inventory inv) {
        double total = 0;
        for (int i = 0; i < 45; i++) {
            ItemStack it = inv.getItem(i);
            total += unitSell(it) * (it == null ? 0 : it.getAmount());
        }
        return total;
    }

    private ItemStack sellButton(Inventory inv) {
        return item(mat("LIME_STAINED_GLASS_PANE", Material.EMERALD), 1, "&a&lSELL",
                "&7Total value: &a$" + fmt(sellTotal(inv)),
                "",
                "&eClick to sell",
                "&8Items without a price are returned.");
    }

    private double doSell(Player p, Inventory inv) {
        double total = 0;
        for (int i = 0; i < 45; i++) {
            ItemStack it = inv.getItem(i);
            double u = unitSell(it);
            if (u > 0) {
                total += u * it.getAmount();
                inv.setItem(i, null);
            }
        }
        if (total > 0) {
            addMoney(p.getUniqueId(), total);
            msg(p, "&aSold items for &f$" + fmt(total) + "&a.");
            ping(p, true);
        }
        return total;
    }

    // ------------------------------------------------------------------ SHOP GUI
    private void openShopMain(Player p) {
        Gui g = make("shopmain", 27, "&8Shop");
        for (int i = 0; i < 27; i++) {
            g.inv.setItem(i, pane());
        }
        int slot = 10;
        for (Cat c : categories.values()) {
            if (slot > 16) {
                break;
            }
            g.inv.setItem(slot, item(c.icon, 1, "&e&l" + c.name, "&7" + c.items.size() + " items", "", "&eClick to open"));
            g.map.put(slot, c.name);
            slot++;
        }
        p.openInventory(g.inv);
    }

    private void openShopCat(Player p, String catName) {
        Cat c = categories.get(catName);
        if (c == null) {
            return;
        }
        Gui g = make("shopcat", 54, "&8Shop » " + c.name);
        g.arg = catName;
        int i = 0;
        for (Map.Entry<Material, Double> e : c.items.entrySet()) {
            if (i >= 45) {
                break;
            }
            Material m = e.getKey();
            g.inv.setItem(i, item(m, 1, "&f" + pretty(m),
                    "&7Price: &a$" + fmt(e.getValue()) + " &7each",
                    "",
                    "&eLeft-click &7buy 1",
                    "&eRight-click &7buy 16",
                    "&eShift-click &7buy 64"));
            g.map.put(i, m.name());
            i++;
        }
        fillRow(g, 45, 53);
        g.inv.setItem(49, item(Material.ARROW, 1, "&cBack"));
        g.map.put(49, "back");
        p.openInventory(g.inv);
    }

    private void buyShop(Player p, Cat c, Material m, int qty) {
        Double unit = c.items.get(m);
        if (unit == null) {
            return;
        }
        if (m.getMaxStackSize() == 1) {
            qty = 1;
        }
        double cost = unit * qty;
        if (!takeMoney(p.getUniqueId(), cost)) {
            msg(p, "&cYou need &f$" + fmt(cost) + " &cto buy that. Balance: &f$" + fmt(bal(p.getUniqueId())));
            ping(p, false);
            return;
        }
        give(p, new ItemStack(m, qty));
        msg(p, "&aBought &f" + qty + "x " + pretty(m) + " &afor &f$" + fmt(cost) + "&a.");
        ping(p, true);
    }

    // ------------------------------------------------------------------ ORDERS
    private void openOrders(Player p, int page) {
        List<Order> list = new ArrayList<>(orders.values());
        list.sort((a, b) -> Double.compare(b.price, a.price));
        int max = Math.max(0, (list.size() - 1) / 45);
        page = Math.max(0, Math.min(page, max));
        Gui g = make("orders", 54, "&8Orders &7(page " + (page + 1) + ")");
        g.page = page;
        for (int i = 0; i < 45; i++) {
            int idx = page * 45 + i;
            if (idx >= list.size()) {
                break;
            }
            Order o = list.get(idx);
            int rem = o.amount - o.filled;
            boolean mine = o.owner.equals(p.getUniqueId());
            g.inv.setItem(i, item(o.mat, Math.min(rem, o.mat.getMaxStackSize()), "&e" + o.ownerName + " &7wants &f" + pretty(o.mat),
                    "&7Remaining: &f" + rem + " &7/ " + o.amount,
                    "&7Price each: &a$" + fmt(o.price),
                    "&7You get for all: &a$" + fmt(o.price * rem),
                    "",
                    mine ? "&cYour order &7(/order cancel " + o.id + ")" : "&eClick &7to fill with items from your inventory"));
            g.map.put(i, o.id);
        }
        nav(g, page, list.size());
        g.inv.setItem(49, item(Material.CHEST, 1, "&6Collect items", "&7Items from your filled orders", "&7and expired listings."));
        p.openInventory(g.inv);
    }

    private void fillOrder(Player p, Order o) {
        if (o.owner.equals(p.getUniqueId())) {
            msg(p, "&cYou can't fill your own order.");
            ping(p, false);
            return;
        }
        int rem = o.amount - o.filled;
        int n = Math.min(rem, countItems(p, o.mat));
        if (n <= 0) {
            msg(p, "&cYou don't have any &f" + pretty(o.mat) + " &cto deliver.");
            ping(p, false);
            return;
        }
        removeItems(p, o.mat, n);
        double pay = n * o.price;
        addMoney(p.getUniqueId(), pay);
        o.filled += n;
        addClaim(o.owner, o.mat, n);
        if (o.filled >= o.amount) {
            orders.remove(o.id);
        }
        dirty = true;
        msg(p, "&aDelivered &f" + n + "x " + pretty(o.mat) + " &afor &f$" + fmt(pay) + "&a.");
        ping(p, true);
        Player buyer = Bukkit.getPlayer(o.owner);
        if (buyer != null) {
            msg(buyer, "&e" + p.getName() + " &afilled &f" + n + "x " + pretty(o.mat) + " &afrom your order. Use &f/order collect&a.");
        }
    }

    private void addClaim(UUID u, Material m, int n) {
        List<ItemStack> list = claims.computeIfAbsent(u, k -> new ArrayList<>());
        int max = m.getMaxStackSize();
        while (n > 0) {
            int c = Math.min(n, max);
            list.add(new ItemStack(m, c));
            n -= c;
        }
        dirty = true;
    }

    private void collect(Player p) {
        List<ItemStack> list = claims.get(p.getUniqueId());
        if (list == null || list.isEmpty()) {
            msg(p, "&7You have nothing to collect.");
            return;
        }
        int got = 0;
        Iterator<ItemStack> it = list.iterator();
        while (it.hasNext()) {
            ItemStack st = it.next();
            Map<Integer, ItemStack> left = p.getInventory().addItem(st.clone());
            if (left.isEmpty()) {
                got += st.getAmount();
                it.remove();
            } else {
                int rest = 0;
                for (ItemStack l : left.values()) {
                    rest += l.getAmount();
                }
                got += st.getAmount() - rest;
                st.setAmount(rest);
                msg(p, "&cYour inventory is full. Free some space and run the command again.");
                break;
            }
        }
        dirty = true;
        if (got > 0) {
            msg(p, "&aCollected &f" + got + " &aitems.");
            ping(p, true);
        }
    }

    private void orderCommand(Player p, String[] a) {
        if (a.length == 0) {
            openOrders(p, 0);
            return;
        }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "create": {
                if (a.length < 4) {
                    msg(p, "&cUsage: /order create <item> <amount> <price each>");
                    return;
                }
                Material m = Material.matchMaterial(a[1]);
                if (m == null || !m.isItem() || m == Material.AIR) {
                    msg(p, "&cUnknown item: &f" + a[1]);
                    return;
                }
                int amount;
                try {
                    amount = Integer.parseInt(a[2]);
                } catch (NumberFormatException e) {
                    msg(p, "&cAmount must be a whole number.");
                    return;
                }
                double price = parseAmount(a[3]);
                if (amount < 1 || amount > 100000 || price <= 0) {
                    msg(p, "&cInvalid amount or price.");
                    return;
                }
                int mine = 0;
                for (Order o : orders.values()) {
                    if (o.owner.equals(p.getUniqueId())) {
                        mine++;
                    }
                }
                if (mine >= getConfig().getInt("order.max-per-player", 10)) {
                    msg(p, "&cYou reached the maximum number of orders.");
                    return;
                }
                double total = amount * price;
                if (!takeMoney(p.getUniqueId(), total)) {
                    msg(p, "&cYou need &f$" + fmt(total) + " &cto place this order.");
                    return;
                }
                Order o = new Order();
                o.id = nextId++;
                o.owner = p.getUniqueId();
                o.ownerName = p.getName();
                o.mat = m;
                o.amount = amount;
                o.price = price;
                o.created = System.currentTimeMillis();
                orders.put(o.id, o);
                dirty = true;
                msg(p, "&aOrder #" + o.id + " placed: &f" + amount + "x " + pretty(m) + " &aat &f$" + fmt(price) + " &aeach (&f$" + fmt(total) + " &aheld).");
                return;
            }
            case "mine": {
                boolean any = false;
                for (Order o : orders.values()) {
                    if (o.owner.equals(p.getUniqueId())) {
                        any = true;
                        msg(p, "&e#" + o.id + " &f" + pretty(o.mat) + " &7" + o.filled + "/" + o.amount + " @ &a$" + fmt(o.price));
                    }
                }
                if (!any) {
                    msg(p, "&7You have no active orders.");
                }
                return;
            }
            case "cancel": {
                if (a.length < 2) {
                    msg(p, "&cUsage: /order cancel <id>");
                    return;
                }
                int id;
                try {
                    id = Integer.parseInt(a[1].replace("#", ""));
                } catch (NumberFormatException e) {
                    msg(p, "&cInvalid id.");
                    return;
                }
                Order o = orders.get(id);
                if (o == null || (!o.owner.equals(p.getUniqueId()) && !p.hasPermission("donutcommands.admin"))) {
                    msg(p, "&cOrder not found.");
                    return;
                }
                double refund = (o.amount - o.filled) * o.price;
                addMoney(o.owner, refund);
                orders.remove(id);
                dirty = true;
                msg(p, "&aOrder cancelled. Refunded &f$" + fmt(refund) + "&a.");
                return;
            }
            case "collect":
                collect(p);
                return;
            default:
                msg(p, "&cUsage: /order [create|mine|cancel|collect]");
        }
    }

    // ------------------------------------------------------------------ AUCTION HOUSE
    private void openAh(Player p, int page) {
        List<Listing> list = new ArrayList<>(listings.values());
        list.sort((a, b) -> Long.compare(b.id, a.id));
        int max = Math.max(0, (list.size() - 1) / 45);
        page = Math.max(0, Math.min(page, max));
        Gui g = make("ah", 54, "&8Auction House &7(page " + (page + 1) + ")");
        g.page = page;
        long now = System.currentTimeMillis();
        for (int i = 0; i < 45; i++) {
            int idx = page * 45 + i;
            if (idx >= list.size()) {
                break;
            }
            Listing l = list.get(idx);
            g.inv.setItem(i, listingIcon(l, now, l.seller.equals(p.getUniqueId()) ? "&cYour listing &7(left-click to view in /ah mine)" : "&eClick to buy"));
            g.map.put(i, l.id);
        }
        nav(g, page, list.size());
        g.inv.setItem(48, item(Material.CHEST, 1, "&6Collect items", "&7Expired listings and order deliveries."));
        g.inv.setItem(50, item(Material.PAPER, 1, "&bYour listings", "&7Click to manage your listings."));
        g.map.put(48, "collect");
        g.map.put(50, "mine");
        p.openInventory(g.inv);
    }

    private ItemStack listingIcon(Listing l, long now, String lastLine) {
        ItemStack it = l.item.clone();
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            List<String> lore = meta.hasLore() && meta.getLore() != null ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            lore.add(color("&8----------------"));
            lore.add(color("&7Price: &a$" + fmt(l.price)));
            lore.add(color("&7Seller: &f" + l.sellerName));
            lore.add(color("&7Expires in: &f" + timeLeft(l.expires - now)));
            lore.add("");
            lore.add(color(lastLine));
            meta.setLore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    private void openAhMine(Player p) {
        Gui g = make("ahmine", 54, "&8Your Listings");
        long now = System.currentTimeMillis();
        int i = 0;
        for (Listing l : listings.values()) {
            if (i >= 45) {
                break;
            }
            if (l.seller.equals(p.getUniqueId())) {
                g.inv.setItem(i, listingIcon(l, now, "&cClick to cancel and get the item back"));
                g.map.put(i, l.id);
                i++;
            }
        }
        fillRow(g, 45, 53);
        g.inv.setItem(49, item(Material.ARROW, 1, "&cBack"));
        g.map.put(49, "back");
        p.openInventory(g.inv);
    }

    private void buyListing(Player p, int id) {
        Listing l = listings.get(id);
        if (l == null) {
            msg(p, "&cThat listing no longer exists.");
            ping(p, false);
            return;
        }
        if (l.seller.equals(p.getUniqueId())) {
            msg(p, "&cYou can't buy your own listing. Use &f/ah mine&c.");
            return;
        }
        if (!takeMoney(p.getUniqueId(), l.price)) {
            msg(p, "&cYou need &f$" + fmt(l.price) + "&c. Balance: &f$" + fmt(bal(p.getUniqueId())));
            ping(p, false);
            return;
        }
        listings.remove(id);
        double tax = Math.max(0, Math.min(1, getConfig().getDouble("ah.tax", 0.0)));
        addMoney(l.seller, l.price * (1 - tax));
        give(p, l.item.clone());
        dirty = true;
        String nm = l.item.getType() == Material.AIR ? "item" : pretty(l.item.getType());
        msg(p, "&aBought &f" + l.item.getAmount() + "x " + nm + " &afor &f$" + fmt(l.price) + "&a.");
        ping(p, true);
        Player seller = Bukkit.getPlayer(l.seller);
        if (seller != null) {
            msg(seller, "&e" + p.getName() + " &abought your &f" + nm + " &afor &f$" + fmt(l.price * (1 - tax)) + "&a.");
        }
    }

    private void expireListings() {
        long now = System.currentTimeMillis();
        List<Listing> gone = new ArrayList<>();
        for (Listing l : listings.values()) {
            if (l.expires <= now) {
                gone.add(l);
            }
        }
        for (Listing l : gone) {
            listings.remove(l.id);
            claims.computeIfAbsent(l.seller, k -> new ArrayList<>()).add(l.item.clone());
            dirty = true;
            Player s = Bukkit.getPlayer(l.seller);
            if (s != null) {
                msg(s, "&cOne of your auction listings expired. Use &f/ah collect&c.");
            }
        }
    }

    private void ahCommand(Player p, String[] a) {
        if (a.length == 0) {
            openAh(p, 0);
            return;
        }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "sell": {
                if (a.length < 2) {
                    msg(p, "&cUsage: /ah sell <price>");
                    return;
                }
                double price = parseAmount(a[1]);
                if (price <= 0) {
                    msg(p, "&cInvalid price.");
                    return;
                }
                ItemStack hand = p.getInventory().getItemInMainHand();
                if (hand == null || hand.getType() == Material.AIR) {
                    msg(p, "&cHold the item you want to sell.");
                    return;
                }
                int mine = 0;
                for (Listing l : listings.values()) {
                    if (l.seller.equals(p.getUniqueId())) {
                        mine++;
                    }
                }
                if (mine >= getConfig().getInt("ah.max-listings", 20)) {
                    msg(p, "&cYou reached the maximum number of listings.");
                    return;
                }
                Listing l = new Listing();
                l.id = nextId++;
                l.seller = p.getUniqueId();
                l.sellerName = p.getName();
                l.item = hand.clone();
                l.price = price;
                l.expires = System.currentTimeMillis() + getConfig().getLong("ah.duration-hours", 48) * 3600_000L;
                listings.put(l.id, l);
                p.getInventory().setItemInMainHand(null);
                dirty = true;
                msg(p, "&aListed &f" + l.item.getAmount() + "x " + pretty(l.item.getType()) + " &afor &f$" + fmt(price) + "&a.");
                ping(p, true);
                return;
            }
            case "mine":
            case "listings":
                openAhMine(p);
                return;
            case "collect":
                collect(p);
                return;
            default:
                msg(p, "&cUsage: /ah [sell <price>|mine|collect]");
        }
    }

    // ------------------------------------------------------------------ GUI events
    @EventHandler
    public void onClick(InventoryClickEvent e) {
        InventoryHolder h = e.getInventory().getHolder();
        if (!(h instanceof Gui)) {
            return;
        }
        Gui g = (Gui) h;
        if (!(e.getWhoClicked() instanceof Player)) {
            return;
        }
        Player p = (Player) e.getWhoClicked();
        int slot = e.getRawSlot();
        boolean top = e.getClickedInventory() != null && e.getClickedInventory() == e.getInventory();

        if (g.type.equals("sell")) {
            if (slot >= 45 && slot < 54) {
                e.setCancelled(true);
                if (slot == 49 && top) {
                    doSell(p, g.inv);
                }
            }
            Bukkit.getScheduler().runTask(this, () -> g.inv.setItem(49, sellButton(g.inv)));
            return;
        }

        e.setCancelled(true);
        if (!top) {
            return;
        }

        switch (g.type) {
            case "shopmain": {
                Object k = g.map.get(slot);
                if (k instanceof String) {
                    openShopCat(p, (String) k);
                }
                break;
            }
            case "shopcat": {
                Object k = g.map.get(slot);
                if (!(k instanceof String)) {
                    break;
                }
                if (k.equals("back")) {
                    openShopMain(p);
                    break;
                }
                Cat c = categories.get(g.arg);
                Material m = Material.matchMaterial((String) k);
                if (c != null && m != null) {
                    int qty = e.isShiftClick() ? 64 : (e.isRightClick() ? 16 : 1);
                    buyShop(p, c, m, qty);
                }
                break;
            }
            case "orders": {
                if (slot == 45 && g.page > 0) {
                    openOrders(p, g.page - 1);
                } else if (slot == 53 && e.getCurrentItem() != null && e.getCurrentItem().getType() == Material.ARROW) {
                    openOrders(p, g.page + 1);
                } else if (slot == 49) {
                    collect(p);
                } else if (g.map.get(slot) instanceof Integer) {
                    Order o = orders.get((Integer) g.map.get(slot));
                    if (o != null) {
                        fillOrder(p, o);
                        openOrders(p, g.page);
                    } else {
                        openOrders(p, g.page);
                    }
                }
                break;
            }
            case "ah": {
                if (slot == 45 && g.page > 0) {
                    openAh(p, g.page - 1);
                } else if (slot == 53 && e.getCurrentItem() != null && e.getCurrentItem().getType() == Material.ARROW) {
                    openAh(p, g.page + 1);
                } else if (g.map.get(slot) instanceof String) {
                    String k = (String) g.map.get(slot);
                    if (k.equals("collect")) {
                        collect(p);
                    } else if (k.equals("mine")) {
                        openAhMine(p);
                    }
                } else if (g.map.get(slot) instanceof Integer) {
                    int id = (Integer) g.map.get(slot);
                    Listing l = listings.get(id);
                    if (l != null && l.seller.equals(p.getUniqueId())) {
                        openAhMine(p);
                    } else {
                        buyListing(p, id);
                        openAh(p, g.page);
                    }
                }
                break;
            }
            case "ahmine": {
                if (g.map.get(slot) instanceof String) {
                    openAh(p, 0);
                } else if (g.map.get(slot) instanceof Integer) {
                    int id = (Integer) g.map.get(slot);
                    Listing l = listings.get(id);
                    if (l != null && l.seller.equals(p.getUniqueId())) {
                        listings.remove(id);
                        give(p, l.item.clone());
                        dirty = true;
                        msg(p, "&aListing cancelled, item returned.");
                    }
                    openAhMine(p);
                }
                break;
            }
            default:
                break;
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        InventoryHolder h = e.getInventory().getHolder();
        if (!(h instanceof Gui)) {
            return;
        }
        Gui g = (Gui) h;
        if (!g.type.equals("sell")) {
            e.setCancelled(true);
            return;
        }
        for (int s : e.getRawSlots()) {
            if (s >= 45 && s < 54) {
                e.setCancelled(true);
                return;
            }
        }
        Bukkit.getScheduler().runTask(this, () -> g.inv.setItem(49, sellButton(g.inv)));
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        InventoryHolder h = e.getInventory().getHolder();
        if (!(h instanceof Gui) || !(e.getPlayer() instanceof Player)) {
            return;
        }
        Gui g = (Gui) h;
        if (!g.type.equals("sell")) {
            return;
        }
        Player p = (Player) e.getPlayer();
        if (getConfig().getBoolean("sell.sell-on-close", false)) {
            doSell(p, g.inv);
        }
        for (int i = 0; i < 45; i++) {
            ItemStack it = g.inv.getItem(i);
            if (it != null && it.getType() != Material.AIR) {
                give(p, it);
                g.inv.setItem(i, null);
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        UUID u = e.getPlayer().getUniqueId();
        names.put(u, e.getPlayer().getName());
        money.putIfAbsent(u, getConfig().getDouble("start-balance", 0.0));
        dirty = true;
        List<ItemStack> list = claims.get(u);
        if (list != null && !list.isEmpty()) {
            msg(e.getPlayer(), "&eYou have items waiting. Use &f/order collect&e.");
        }
    }

    // ------------------------------------------------------------------ teleport helpers
    private void rtp(Player p) {
        World w = Bukkit.getWorld(getConfig().getString("rtp.world", "world"));
        if (w == null) {
            w = p.getWorld();
        }
        if (w.getEnvironment() != World.Environment.NORMAL) {
            msg(p, "&cRandom teleport only works in the overworld.");
            return;
        }
        int radius = Math.max(100, getConfig().getInt("rtp.radius", 5000));
        for (int tries = 0; tries < 40; tries++) {
            int x = RNG.nextInt(radius * 2 + 1) - radius;
            int z = RNG.nextInt(radius * 2 + 1) - radius;
            int y = w.getHighestBlockYAt(x, z);
            Block b = w.getBlockAt(x, y, z);
            String t = b.getType().name();
            if (t.contains("WATER") || t.contains("LAVA") || t.contains("LEAVES") || t.contains("ICE")
                    || t.contains("CACTUS") || t.contains("FIRE") || t.contains("MAGMA")) {
                continue;
            }
            Location l = new Location(w, x + 0.5, y + 1.0, z + 0.5);
            if (!w.getWorldBorder().isInside(l)) {
                continue;
            }
            p.teleport(l);
            msg(p, "&aTeleported to &f" + x + ", " + (y + 1) + ", " + z + "&a.");
            return;
        }
        msg(p, "&cCouldn't find a safe spot, try again.");
    }

    // ------------------------------------------------------------------ commands
    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String c = cmd.getName().toLowerCase(Locale.ROOT);
        Player p = s instanceof Player ? (Player) s : null;

        switch (c) {
            case "bal": {
                UUID id;
                String name;
                if (a.length > 0) {
                    Player o = Bukkit.getPlayerExact(a[0]);
                    if (o == null) {
                        msg(s, "&cPlayer not found (must be online).");
                        return true;
                    }
                    id = o.getUniqueId();
                    name = o.getName();
                } else if (p != null) {
                    id = p.getUniqueId();
                    name = p.getName();
                } else {
                    msg(s, "&cUsage: /bal <player>");
                    return true;
                }
                msg(s, "&e" + name + "&7's balance: &a$" + fmt(bal(id)));
                return true;
            }
            case "pay": {
                if (!needPlayer(s)) {
                    return true;
                }
                if (a.length < 2) {
                    msg(s, "&cUsage: /pay <player> <amount>");
                    return true;
                }
                Player t = Bukkit.getPlayerExact(a[0]);
                if (t == null) {
                    msg(s, "&cPlayer not found (must be online).");
                    return true;
                }
                if (t.getUniqueId().equals(p.getUniqueId())) {
                    msg(s, "&cYou can't pay yourself.");
                    return true;
                }
                double amt = parseAmount(a[1]);
                if (amt <= 0) {
                    msg(s, "&cInvalid amount.");
                    return true;
                }
                if (!takeMoney(p.getUniqueId(), amt)) {
                    msg(s, "&cYou don't have enough money.");
                    return true;
                }
                addMoney(t.getUniqueId(), amt);
                msg(p, "&aYou paid &f" + t.getName() + " &a$" + fmt(amt) + "&a.");
                msg(t, "&aYou received &f$" + fmt(amt) + " &afrom &f" + p.getName() + "&a.");
                return true;
            }
            case "baltop": {
                List<Map.Entry<UUID, Double>> list = new ArrayList<>(money.entrySet());
                list.sort((x, y) -> Double.compare(y.getValue(), x.getValue()));
                msg(s, "&6&lTop balances");
                int n = Math.min(10, list.size());
                for (int i = 0; i < n; i++) {
                    Map.Entry<UUID, Double> e = list.get(i);
                    s.sendMessage(color("&e#" + (i + 1) + " &f" + names.getOrDefault(e.getKey(), "Unknown") + " &8- &a$" + fmt(e.getValue())));
                }
                return true;
            }
            case "sell":
                if (needPlayer(s)) {
                    openSell(p);
                }
                return true;
            case "sellhand": {
                if (!needPlayer(s)) {
                    return true;
                }
                ItemStack hand = p.getInventory().getItemInMainHand();
                double u = unitSell(hand);
                if (hand == null || u <= 0) {
                    msg(s, "&cThat item can't be sold.");
                    return true;
                }
                double total = u * hand.getAmount();
                p.getInventory().setItemInMainHand(null);
                addMoney(p.getUniqueId(), total);
                msg(p, "&aSold for &f$" + fmt(total) + "&a.");
                ping(p, true);
                return true;
            }
            case "shop":
                if (needPlayer(s)) {
                    openShopMain(p);
                }
                return true;
            case "order":
                if (needPlayer(s)) {
                    orderCommand(p, a);
                }
                return true;
            case "ah":
                if (needPlayer(s)) {
                    ahCommand(p, a);
                }
                return true;
            case "shards": {
                UUID id;
                String name;
                if (a.length > 0) {
                    Player o = Bukkit.getPlayerExact(a[0]);
                    if (o == null) {
                        msg(s, "&cPlayer not found (must be online).");
                        return true;
                    }
                    id = o.getUniqueId();
                    name = o.getName();
                } else if (p != null) {
                    id = p.getUniqueId();
                    name = p.getName();
                } else {
                    msg(s, "&cUsage: /shards <player>");
                    return true;
                }
                msg(s, "&e" + name + "&7's shards: &d" + shardsOf(id));
                return true;
            }
            case "rtp":
                if (needPlayer(s)) {
                    rtp(p);
                }
                return true;
            case "spawn": {
                if (!needPlayer(s)) {
                    return true;
                }
                Location l = spawn != null ? spawn : p.getWorld().getSpawnLocation();
                p.teleport(l);
                msg(p, "&aTeleported to spawn.");
                return true;
            }
            case "setspawn": {
                if (!needPlayer(s)) {
                    return true;
                }
                spawn = p.getLocation();
                dirty = true;
                msg(p, "&aSpawn set.");
                return true;
            }
            case "sethome": {
                if (!needPlayer(s)) {
                    return true;
                }
                String n = (a.length > 0 ? a[0] : "home").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
                if (n.isEmpty()) {
                    n = "home";
                }
                Map<String, Location> mine = homes.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>());
                if (!mine.containsKey(n) && mine.size() >= getConfig().getInt("home.limit", 3)) {
                    msg(p, "&cYou reached your home limit.");
                    return true;
                }
                mine.put(n, p.getLocation());
                dirty = true;
                msg(p, "&aHome &f" + n + " &aset.");
                return true;
            }
            case "delhome": {
                if (!needPlayer(s)) {
                    return true;
                }
                if (a.length < 1) {
                    msg(p, "&cUsage: /delhome <name>");
                    return true;
                }
                Map<String, Location> mine = homes.get(p.getUniqueId());
                if (mine == null || mine.remove(a[0].toLowerCase(Locale.ROOT)) == null) {
                    msg(p, "&cHome not found.");
                    return true;
                }
                dirty = true;
                msg(p, "&aHome deleted.");
                return true;
            }
            case "home": {
                if (!needPlayer(s)) {
                    return true;
                }
                Map<String, Location> mine = homes.getOrDefault(p.getUniqueId(), Collections.emptyMap());
                if (mine.isEmpty()) {
                    msg(p, "&cYou have no homes. Use &f/sethome&c.");
                    return true;
                }
                String n = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : (mine.containsKey("home") ? "home" : null);
                if (n == null || !mine.containsKey(n)) {
                    msg(p, "&eYour homes: &f" + String.join(", ", mine.keySet()));
                    return true;
                }
                Location l = mine.get(n);
                if (l.getWorld() == null) {
                    msg(p, "&cThat home's world is not loaded.");
                    return true;
                }
                p.teleport(l);
                msg(p, "&aTeleported to home &f" + n + "&a.");
                return true;
            }
            case "tpa": {
                if (!needPlayer(s)) {
                    return true;
                }
                if (a.length < 1) {
                    msg(s, "&cUsage: /tpa <player>");
                    return true;
                }
                Player t = Bukkit.getPlayerExact(a[0]);
                if (t == null || t.equals(p)) {
                    msg(s, "&cPlayer not found.");
                    return true;
                }
                tpaRequests.put(t.getUniqueId(), p.getUniqueId());
                tpaTime.put(t.getUniqueId(), System.currentTimeMillis());
                msg(p, "&aTeleport request sent to &f" + t.getName() + "&a.");
                msg(t, "&e" + p.getName() + " &awants to teleport to you. &f/tpaccept &aor &f/tpdeny");
                return true;
            }
            case "tpaccept":
            case "tpdeny": {
                if (!needPlayer(s)) {
                    return true;
                }
                UUID req = tpaRequests.remove(p.getUniqueId());
                Long when = tpaTime.remove(p.getUniqueId());
                long timeout = getConfig().getLong("tpa.timeout-seconds", 60) * 1000L;
                Player r = req == null ? null : Bukkit.getPlayer(req);
                if (req == null || when == null || System.currentTimeMillis() - when > timeout || r == null) {
                    msg(p, "&cNo pending teleport request.");
                    return true;
                }
                if (c.equals("tpaccept")) {
                    r.teleport(p);
                    msg(r, "&aTeleporting to &f" + p.getName() + "&a.");
                    msg(p, "&aRequest accepted.");
                } else {
                    msg(r, "&c" + p.getName() + " denied your teleport request.");
                    msg(p, "&aRequest denied.");
                }
                return true;
            }
            case "msg": {
                if (!needPlayer(s)) {
                    return true;
                }
                if (a.length < 2) {
                    msg(s, "&cUsage: /msg <player> <message>");
                    return true;
                }
                Player t = Bukkit.getPlayerExact(a[0]);
                if (t == null) {
                    msg(s, "&cPlayer not found.");
                    return true;
                }
                sendPm(p, t, String.join(" ", Arrays.copyOfRange(a, 1, a.length)));
                return true;
            }
            case "reply": {
                if (!needPlayer(s)) {
                    return true;
                }
                UUID last = lastMsg.get(p.getUniqueId());
                Player t = last == null ? null : Bukkit.getPlayer(last);
                if (t == null) {
                    msg(s, "&cNobody to reply to.");
                    return true;
                }
                if (a.length < 1) {
                    msg(s, "&cUsage: /reply <message>");
                    return true;
                }
                sendPm(p, t, String.join(" ", a));
                return true;
            }
            case "stats": {
                Player t = p;
                if (a.length > 0) {
                    t = Bukkit.getPlayerExact(a[0]);
                }
                if (t == null) {
                    msg(s, "&cPlayer not found (must be online).");
                    return true;
                }
                UUID id = t.getUniqueId();
                int ticks = stat(t, "PLAY_ONE_MINUTE", "PLAY_TIME");
                s.sendMessage(color("&6&l" + t.getName() + "'s stats"));
                s.sendMessage(color("&7Money: &a$" + fmt(bal(id))));
                s.sendMessage(color("&7Shards: &d" + shardsOf(id)));
                s.sendMessage(color("&7Kills: &f" + stat(t, "PLAYER_KILLS")));
                s.sendMessage(color("&7Deaths: &f" + stat(t, "DEATHS")));
                s.sendMessage(color("&7Playtime: &f" + (ticks / 20 / 3600) + "h"));
                return true;
            }
            case "donut":
                return adminCommand(s, a);
            default:
                return false;
        }
    }

    private void sendPm(Player from, Player to, String text) {
        from.sendMessage(color("&7[&dYou &7→ &d" + to.getName() + "&7] &f" + text));
        to.sendMessage(color("&7[&d" + from.getName() + " &7→ &dYou&7] &f" + text));
        lastMsg.put(to.getUniqueId(), from.getUniqueId());
        lastMsg.put(from.getUniqueId(), to.getUniqueId());
    }

    private boolean adminCommand(CommandSender s, String[] a) {
        if (a.length == 0) {
            msg(s, "&cUsage: /donut <reload|eco|shards>");
            return true;
        }
        String sub = a[0].toLowerCase(Locale.ROOT);
        if (sub.equals("reload")) {
            reloadConfig();
            loadPrices();
            loadShop();
            msg(s, "&aReloaded config, prices and shop.");
            return true;
        }
        if ((sub.equals("eco") || sub.equals("shards")) && a.length >= 4) {
            Player t = Bukkit.getPlayerExact(a[2]);
            if (t == null) {
                msg(s, "&cPlayer not found (must be online).");
                return true;
            }
            double amt = parseAmount(a[3]);
            if (amt < 0) {
                msg(s, "&cInvalid amount.");
                return true;
            }
            UUID id = t.getUniqueId();
            String op = a[1].toLowerCase(Locale.ROOT);
            if (sub.equals("eco")) {
                switch (op) {
                    case "give":
                        addMoney(id, amt);
                        break;
                    case "take":
                        money.put(id, Math.max(0, bal(id) - amt));
                        dirty = true;
                        break;
                    case "set":
                        money.put(id, amt);
                        dirty = true;
                        break;
                    default:
                        msg(s, "&cUse give, take or set.");
                        return true;
                }
                msg(s, "&a" + t.getName() + " balance is now &f$" + fmt(bal(id)));
            } else {
                long v = (long) amt;
                switch (op) {
                    case "give":
                        shards.put(id, shardsOf(id) + v);
                        break;
                    case "take":
                        shards.put(id, Math.max(0, shardsOf(id) - v));
                        break;
                    case "set":
                        shards.put(id, v);
                        break;
                    default:
                        msg(s, "&cUse give, take or set.");
                        return true;
                }
                dirty = true;
                msg(s, "&a" + t.getName() + " now has &d" + shardsOf(id) + " &ashards.");
            }
            return true;
        }
        msg(s, "&cUsage: /donut <reload | eco <give|take|set> <player> <amount> | shards <give|take|set> <player> <amount>>");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        String c = cmd.getName().toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            switch (c) {
                case "pay":
                case "tpa":
                case "msg":
                case "stats":
                case "bal":
                case "shards":
                    for (Player o : Bukkit.getOnlinePlayers()) {
                        out.add(o.getName());
                    }
                    break;
                case "order":
                    out.addAll(Arrays.asList("create", "mine", "cancel", "collect"));
                    break;
                case "ah":
                    out.addAll(Arrays.asList("sell", "mine", "collect"));
                    break;
                case "donut":
                    out.addAll(Arrays.asList("reload", "eco", "shards"));
                    break;
                default:
                    break;
            }
        } else if (a.length == 2) {
            if (c.equals("order") && a[0].equalsIgnoreCase("create")) {
                for (Material m : Material.values()) {
                    if (m.isItem() && !m.name().startsWith("LEGACY_")) {
                        out.add(m.name().toLowerCase(Locale.ROOT));
                    }
                }
            } else if (c.equals("donut")) {
                out.addAll(Arrays.asList("give", "take", "set"));
            }
        } else if (a.length == 3 && c.equals("donut")) {
            for (Player o : Bukkit.getOnlinePlayers()) {
                out.add(o.getName());
            }
        }
        String last = a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        if (out.size() > 60) {
            return out.subList(0, 60);
        }
        return out;
    }
}
