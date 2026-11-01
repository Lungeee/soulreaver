package dev.soulreaver;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.WitherSkeleton;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Soulreaver - a soul-drinking greatsword + the Void Reaver boss.
 *
 * SWORD ABILITIES
 *  - Soul Siphon  (passive)      Every hit builds a Soul (max 10) and heals you. Souls decay if you stop fighting.
 *                                5+ souls = Speed, 10 souls = Strength + wild aura (SOUL FRENZY).
 *  - Soul Rend    (passive)      Every 5th hit detonates the target: Wither, Slowness, bonus damage, sonic boom.
 *  - Reaper's Mark(passive)      +30% damage to targets under 30% health. Kills heal you and refund souls.
 *  - Voidstep     (right-click)  Phase-dash up to 9 blocks, cutting through everything on the way. 6s cooldown.
 *  - Cataclysm    (sneak+right)  Leap, then slam. Radius and damage scale with your Souls. 14s cooldown.
 *  - Undying Wrath(passive)      A killing blow instead leaves you at 4 hearts, shielded, and blasts enemies away. 90s cooldown.
 */
public final class SoulreaverPlugin extends JavaPlugin implements Listener {

    private static final Particle.DustTransition VOID_DUST =
            new Particle.DustTransition(Color.fromRGB(0x7B2FFF), Color.fromRGB(0x00E5FF), 1.4f);
    private static final TextColor PURPLE = TextColor.color(0x9B5CFF);

    private NamespacedKey swordKey;
    private NamespacedKey bossKey;

    private final Map<UUID, Integer> souls = new HashMap<>();
    private final Map<UUID, Integer> combo = new HashMap<>();
    private final Map<UUID, Long> lastHit = new HashMap<>();
    private final Map<UUID, Long> dashCd = new HashMap<>();
    private final Map<UUID, Long> slamCd = new HashMap<>();
    private final Map<UUID, Long> undyingCd = new HashMap<>();

    /** Prevents our own ability damage from re-triggering the on-hit passives. */
    private boolean internalDamage = false;

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onEnable() {
        swordKey = new NamespacedKey(this, "soulreaver_sword");
        bossKey = new NamespacedKey(this, "void_reaver_boss");
        Bukkit.getPluginManager().registerEvents(this, this);
        startAuraTask();
        startStatusTask();
        getLogger().info("Soulreaver loaded. Hold your breath.");
    }

    // ------------------------------------------------------------------ the sword

    public ItemStack createSword() {
        ItemStack item = new ItemStack(Material.NETHERITE_SWORD);
        ItemMeta meta = item.getItemMeta();

        meta.displayName(Component.text("Soulreaver", PURPLE, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        lore.add(line("Forged from the last breath of a dying star.", NamedTextColor.DARK_GRAY));
        lore.add(Component.empty());
        lore.add(line("Soul Siphon", NamedTextColor.LIGHT_PURPLE));
        lore.add(line("  Hits build Souls & heal you. 5 = Speed, 10 = Frenzy.", NamedTextColor.GRAY));
        lore.add(line("Soul Rend", NamedTextColor.LIGHT_PURPLE));
        lore.add(line("  Every 5th hit detonates your target.", NamedTextColor.GRAY));
        lore.add(line("Reaper's Mark", NamedTextColor.LIGHT_PURPLE));
        lore.add(line("  Wounded enemies take +30% damage.", NamedTextColor.GRAY));
        lore.add(line("Voidstep  [Right-Click]", NamedTextColor.AQUA));
        lore.add(line("  Phase-dash through your enemies.", NamedTextColor.GRAY));
        lore.add(line("Cataclysm  [Sneak + Right-Click]", NamedTextColor.AQUA));
        lore.add(line("  Slam the ground. Scales with Souls.", NamedTextColor.GRAY));
        lore.add(line("Undying Wrath", NamedTextColor.RED));
        lore.add(line("  Refuses to let you die. Once.", NamedTextColor.GRAY));
        meta.lore(lore);

        meta.setUnbreakable(true);
        meta.addEnchant(Enchantment.SHARPNESS, 7, true);
        meta.addEnchant(Enchantment.FIRE_ASPECT, 2, true);
        meta.addEnchant(Enchantment.LOOTING, 5, true);
        meta.addEnchant(Enchantment.SWEEPING_EDGE, 5, true);
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(swordKey, PersistentDataType.BYTE, (byte) 1);

        item.setItemMeta(meta);
        return item;
    }

    private Component line(String text, TextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    private boolean isSword(ItemStack item) {
        if (item == null || item.getType() != Material.NETHERITE_SWORD || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(swordKey, PersistentDataType.BYTE);
    }

    private boolean holdsSword(Player p) {
        return isSword(p.getInventory().getItemInMainHand());
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("soulreaver.admin")) {
            sender.sendMessage(Component.text("You lack the power.", NamedTextColor.RED));
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(Component.text("/soulreaver <give [player] | boss>", NamedTextColor.GRAY));
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "give" -> {
                Player target = args.length > 1 ? Bukkit.getPlayerExact(args[1])
                        : (sender instanceof Player p ? p : null);
                if (target == null) {
                    sender.sendMessage(Component.text("Player not found.", NamedTextColor.RED));
                    return true;
                }
                target.getInventory().addItem(createSword());
                target.sendMessage(Component.text("The Soulreaver answers your call.", PURPLE));
                target.playSound(target.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1f, 0.6f);
            }
            case "boss" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage("Only players can summon the boss.");
                    return true;
                }
                spawnBoss(p.getLocation());
            }
            default -> sender.sendMessage(Component.text("/soulreaver <give [player] | boss>", NamedTextColor.GRAY));
        }
        return true;
    }

    // ------------------------------------------------------------------ passive tasks

    /** Orbiting soul-fire aura around anyone holding the blade. */
    private void startAuraTask() {
        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                t += 2;
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (!holdsSword(p)) continue;
                    World w = p.getWorld();
                    int s = souls.getOrDefault(p.getUniqueId(), 0);
                    Location base = p.getLocation();

                    for (int i = 0; i < 2; i++) {
                        double a = t * 0.25 + i * Math.PI;
                        Location l = base.clone().add(Math.cos(a) * 0.9, 1.0 + Math.sin(t * 0.1) * 0.5, Math.sin(a) * 0.9);
                        w.spawnParticle(Particle.SOUL_FIRE_FLAME, l, 1, 0, 0, 0, 0);
                        w.spawnParticle(Particle.DUST_COLOR_TRANSITION, l, 1, 0.05, 0.05, 0.05, 0, VOID_DUST);
                    }
                    if (s >= 10) { // SOUL FRENZY
                        for (int i = 0; i < 3; i++) {
                            double a = t * 0.4 + i * (Math.PI * 2 / 3);
                            Location l = base.clone().add(Math.cos(a) * 0.7, (t % 20) * 0.1, Math.sin(a) * 0.7);
                            w.spawnParticle(Particle.END_ROD, l, 1, 0, 0, 0, 0);
                            w.spawnParticle(Particle.SOUL, l, 1, 0, 0.05, 0, 0.01);
                        }
                        w.spawnParticle(Particle.ELECTRIC_SPARK, base.clone().add(0, 1, 0), 2, 0.5, 0.8, 0.5, 0.1);
                    }
                }
            }
        }.runTaskTimer(this, 0L, 2L);
    }

    /** Soul decay, tier buffs and the action bar HUD. */
    private void startStatusTask() {
        new BukkitRunnable() {
            int tick = 0;

            @Override
            public void run() {
                tick++;
                long now = System.currentTimeMillis();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    UUID id = p.getUniqueId();
                    int s = souls.getOrDefault(id, 0);

                    if (tick % 4 == 0 && s > 0 && now - lastHit.getOrDefault(id, 0L) > 8000) {
                        souls.put(id, s - 1);
                        s--;
                    }
                    if (!holdsSword(p)) continue;

                    if (tick % 4 == 0) {
                        if (s >= 5) p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 50, 0, false, false, true));
                        if (s >= 10) p.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 50, 0, false, false, true));
                    }
                    p.sendActionBar(buildHud(p, s));
                }
            }
        }.runTaskTimer(this, 0L, 5L);
    }

    private Component buildHud(Player p, int s) {
        UUID id = p.getUniqueId();
        Component bar = Component.text("\u2620 ", PURPLE);
        for (int i = 0; i < 10; i++) {
            bar = bar.append(Component.text("\u25AE", i < s ? TextColor.color(0xB06BFF) : NamedTextColor.DARK_GRAY));
        }
        if (s >= 10) bar = bar.append(Component.text(" FRENZY", NamedTextColor.RED, TextDecoration.BOLD));
        return bar.append(cdText("Voidstep", dashCd, id)).append(cdText("Cataclysm", slamCd, id))
                .append(cdText("Undying", undyingCd, id));
    }

    private Component cdText(String name, Map<UUID, Long> map, UUID id) {
        long r = remaining(map, id);
        Component state = r == 0 ? Component.text("READY", NamedTextColor.GREEN)
                : Component.text(String.format("%.1fs", r / 1000.0), NamedTextColor.RED);
        return Component.text("  " + name + ": ", NamedTextColor.GRAY).append(state);
    }

    private long remaining(Map<UUID, Long> map, UUID id) {
        return Math.max(0, map.getOrDefault(id, 0L) - System.currentTimeMillis());
    }

    // ------------------------------------------------------------------ combat events

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (internalDamage) return;

        // Boss melee: curse the player with Wither.
        if (e.getDamager() instanceof LivingEntity d
                && d.getPersistentDataContainer().has(bossKey, PersistentDataType.BYTE)
                && e.getEntity() instanceof Player victimPlayer) {
            victimPlayer.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 100, 1));
            Location mid = mid(victimPlayer);
            victimPlayer.getWorld().spawnParticle(Particle.SOUL, mid, 15, 0.3, 0.5, 0.3, 0.05);
            victimPlayer.getWorld().spawnParticle(Particle.LARGE_SMOKE, mid, 10, 0.3, 0.5, 0.3, 0.02);
            return;
        }

        if (!(e.getDamager() instanceof Player p)) return;
        if (!(e.getEntity() instanceof LivingEntity victim)) return;
        if (!holdsSword(p)) return;

        UUID id = p.getUniqueId();
        World w = victim.getWorld();
        Location mid = mid(victim);

        // Soul Siphon
        souls.put(id, Math.min(10, souls.getOrDefault(id, 0) + 1));
        lastHit.put(id, System.currentTimeMillis());
        double max = p.getAttribute(Attribute.MAX_HEALTH).getValue();
        p.setHealth(Math.min(max, p.getHealth() + 1.5));

        double dmg = e.getDamage();

        // Reaper's Mark
        double victimMax = victim.getAttribute(Attribute.MAX_HEALTH).getValue();
        if (victim.getHealth() < victimMax * 0.3) {
            dmg *= 1.3;
            w.spawnParticle(Particle.CRIT, mid, 25, 0.3, 0.5, 0.3, 0.4);
            w.spawnParticle(Particle.ENCHANTED_HIT, mid, 25, 0.3, 0.5, 0.3, 0.4);
        }

        // Every hit
        w.spawnParticle(Particle.SOUL_FIRE_FLAME, mid, 20, 0.3, 0.4, 0.3, 0.08);
        w.spawnParticle(Particle.SWEEP_ATTACK, mid, 1);
        w.spawnParticle(Particle.DUST_COLOR_TRANSITION, mid, 15, 0.4, 0.5, 0.4, 0, VOID_DUST);
        w.playSound(mid, Sound.PARTICLE_SOUL_ESCAPE, 1f, 0.8f);

        // Soul Rend
        int c = combo.getOrDefault(id, 0) + 1;
        if (c >= 5) {
            c = 0;
            dmg += 6;
            soulRend(victim);
        }
        combo.put(id, c);

        e.setDamage(dmg);
    }

    private void soulRend(LivingEntity victim) {
        World w = victim.getWorld();
        Location mid = mid(victim);
        victim.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 80, 1));
        victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 1));
        w.spawnParticle(Particle.SONIC_BOOM, mid, 1);
        w.spawnParticle(Particle.EXPLOSION, mid, 2, 0.2, 0.2, 0.2, 0);
        w.playSound(mid, Sound.ENTITY_WARDEN_SONIC_BOOM, 0.8f, 1.4f);
        w.playSound(mid, Sound.ENTITY_WITHER_HURT, 1f, 0.6f);

        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (t >= 14 || !victim.isValid()) {
                    cancel();
                    return;
                }
                Location base = victim.getLocation();
                for (int i = 0; i < 2; i++) {
                    double a = t * 0.7 + i * Math.PI;
                    Location l = base.clone().add(Math.cos(a) * 0.8, t * 0.17, Math.sin(a) * 0.8);
                    w.spawnParticle(Particle.SOUL, l, 1, 0, 0, 0, 0.02);
                    w.spawnParticle(Particle.DUST_COLOR_TRANSITION, l, 2, 0.05, 0.05, 0.05, 0, VOID_DUST);
                }
                t++;
            }
        }.runTaskTimer(this, 0L, 1L);
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        LivingEntity dead = e.getEntity();
        World w = dead.getWorld();

        // Boss dies: guaranteed sword + fireworks of souls.
        if (dead.getPersistentDataContainer().has(bossKey, PersistentDataType.BYTE)) {
            e.getDrops().add(createSword());
            e.setDroppedExp(2500);
            Location loc = mid(dead);
            w.spawnParticle(Particle.EXPLOSION_EMITTER, loc, 3, 2, 1, 2, 0);
            w.spawnParticle(Particle.SOUL, loc, 120, 1.5, 1.5, 1.5, 0.2);
            w.spawnParticle(Particle.END_ROD, loc, 80, 1.5, 2, 1.5, 0.15);
            w.playSound(loc, Sound.ENTITY_ENDER_DRAGON_DEATH, 2f, 0.7f);
            for (Player pl : Bukkit.getOnlinePlayers()) {
                if (pl.getWorld().equals(w) && pl.getLocation().distanceSquared(loc) < 80 * 80) {
                    pl.sendMessage(Component.text("The Void Reaver has fallen.", PURPLE));
                }
            }
            return;
        }

        // Soul harvest
        Player killer = dead.getKiller();
        if (killer == null || !holdsSword(killer)) return;
        UUID id = killer.getUniqueId();
        souls.put(id, Math.min(10, souls.getOrDefault(id, 0) + 2));
        lastHit.put(id, System.currentTimeMillis());
        double max = killer.getAttribute(Attribute.MAX_HEALTH).getValue();
        killer.setHealth(Math.min(max, killer.getHealth() + 4));
        killer.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 100, 1));

        Location from = mid(dead);
        w.playSound(from, Sound.ENTITY_WITHER_SPAWN, 0.4f, 2f);
        new BukkitRunnable() {
            int t = 0;
            final Location pos = from.clone();

            @Override
            public void run() {
                if (t++ > 25 || !killer.isValid()) {
                    cancel();
                    return;
                }
                Vector to = killer.getLocation().add(0, 1, 0).toVector().subtract(pos.toVector());
                if (to.lengthSquared() < 0.6) {
                    w.spawnParticle(Particle.TOTEM_OF_UNDYING, pos, 15, 0.3, 0.3, 0.3, 0.2);
                    w.playSound(pos, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.5f);
                    cancel();
                    return;
                }
                pos.add(to.normalize().multiply(0.8));
                w.spawnParticle(Particle.SOUL, pos, 3, 0.1, 0.1, 0.1, 0.01);
                w.spawnParticle(Particle.SOUL_FIRE_FLAME, pos, 2, 0.1, 0.1, 0.1, 0.01);
            }
        }.runTaskTimer(this, 0L, 1L);
    }

    /** Undying Wrath. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHurt(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (!holdsSword(p)) return;
        EntityDamageEvent.DamageCause cause = e.getCause();
        if (cause == EntityDamageEvent.DamageCause.VOID || cause == EntityDamageEvent.DamageCause.KILL
                || cause == EntityDamageEvent.DamageCause.SUICIDE) return;
        UUID id = p.getUniqueId();
        if (remaining(undyingCd, id) > 0) return;
        if (p.getHealth() - e.getFinalDamage() > 0) return;

        e.setCancelled(true);
        undyingCd.put(id, System.currentTimeMillis() + 90_000);
        p.setHealth(Math.min(p.getAttribute(Attribute.MAX_HEALTH).getValue(), 8));
        p.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 200, 3));
        p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 100, 2));
        p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 100, 1));
        souls.put(id, 10);
        lastHit.put(id, System.currentTimeMillis());

        World w = p.getWorld();
        Location loc = p.getLocation();
        w.spawnParticle(Particle.TOTEM_OF_UNDYING, loc.clone().add(0, 1, 0), 80, 0.5, 1, 0.5, 0.4);
        w.playSound(loc, Sound.ITEM_TOTEM_USE, 1.5f, 0.7f);
        p.sendMessage(Component.text("The Soulreaver refuses to let you die.", PURPLE));
        shockwave(p, loc, 9, 12);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        souls.remove(id);
        combo.remove(id);
        lastHit.remove(id);
    }

    // ------------------------------------------------------------------ active abilities

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        if (!holdsSword(p)) return;
        e.setCancelled(true);

        UUID id = p.getUniqueId();
        if (p.isSneaking()) {
            if (remaining(slamCd, id) > 0) {
                p.playSound(p.getLocation(), Sound.BLOCK_DISPENSER_FAIL, 1f, 1f);
                return;
            }
            slamCd.put(id, System.currentTimeMillis() + 14_000);
            int s = souls.getOrDefault(id, 0);
            souls.put(id, 0);
            slam(p, 6 + s * 0.6, 10 + s * 2.0);
        } else {
            if (remaining(dashCd, id) > 0) {
                p.playSound(p.getLocation(), Sound.BLOCK_DISPENSER_FAIL, 1f, 1f);
                return;
            }
            dashCd.put(id, System.currentTimeMillis() + 6_000);
            dash(p, p.getEyeLocation().getDirection(), 9);
        }
    }

    /** Voidstep: blink forward, carving through anything in the way. Used by players and the boss. */
    private void dash(LivingEntity c, Vector dirIn, double maxDist) {
        World w = c.getWorld();
        Vector dir = dirIn.clone().normalize();
        Location eye = c.getEyeLocation();
        double d = maxDist;

        RayTraceResult r = w.rayTraceBlocks(eye, dir, maxDist, FluidCollisionMode.NEVER, true);
        if (r != null) d = Math.max(0, r.getHitPosition().distance(eye.toVector()) - 0.9);

        Location start = c.getLocation();
        Location dest = start.clone().add(dir.clone().multiply(d));
        while (d > 0 && (!dest.getBlock().isPassable() || !dest.clone().add(0, 1, 0).getBlock().isPassable())) {
            d -= 0.5;
            dest = start.clone().add(dir.clone().multiply(Math.max(d, 0)));
        }

        w.spawnParticle(Particle.SONIC_BOOM, start.clone().add(0, 1, 0), 1);
        w.spawnParticle(Particle.REVERSE_PORTAL, start.clone().add(0, 1, 0), 40, 0.4, 0.8, 0.4, 0.3);
        w.playSound(start, Sound.ENTITY_ENDERMAN_TELEPORT, 1.2f, 0.6f);
        w.playSound(start, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.2f, 0.7f);

        Set<UUID> hit = new HashSet<>();
        for (double t = 0; t <= d; t += 0.5) {
            Location pt = start.clone().add(dir.clone().multiply(t)).add(0, 1, 0);
            w.spawnParticle(Particle.REVERSE_PORTAL, pt, 6, 0.25, 0.4, 0.25, 0.05);
            w.spawnParticle(Particle.SOUL_FIRE_FLAME, pt, 3, 0.2, 0.3, 0.2, 0.01);
            w.spawnParticle(Particle.DUST_COLOR_TRANSITION, pt, 4, 0.3, 0.4, 0.3, 0, VOID_DUST);
            for (Entity en : w.getNearbyEntities(pt, 1.6, 1.6, 1.6)) {
                if (en instanceof LivingEntity le && canHit(c, le) && hit.add(le.getUniqueId())) {
                    dealDamage(c, le, 8);
                    le.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 2));
                    w.spawnParticle(Particle.SWEEP_ATTACK, mid(le), 1);
                    w.spawnParticle(Particle.ENCHANTED_HIT, mid(le), 20, 0.3, 0.5, 0.3, 0.3);
                }
            }
        }

        c.teleport(dest);
        c.setFallDistance(0);
        c.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 20, 2, false, false));
        w.spawnParticle(Particle.EXPLOSION, dest.clone().add(0, 1, 0), 1);
        w.spawnParticle(Particle.SOUL_FIRE_FLAME, dest.clone().add(0, 1, 0), 30, 0.4, 0.6, 0.4, 0.1);
        w.playSound(dest, Sound.ENTITY_ENDERMAN_TELEPORT, 1.2f, 1.2f);
    }

    /** Cataclysm: hop, converge soul-fire, slam. */
    private void slam(LivingEntity c, double radius, double damage) {
        World w = c.getWorld();
        w.playSound(c.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 2f, 0.6f);
        c.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 40, 2, false, false));
        c.setVelocity(c.getVelocity().setY(0.75));

        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (!c.isValid()) {
                    cancel();
                    return;
                }
                Location base = c.getLocation();
                if (t < 14) {
                    for (int i = 0; i < 4; i++) {
                        double a = t * 0.6 + i * Math.PI / 2;
                        double rr = radius * 0.5 * (1 - t / 14.0) + 0.4;
                        Location l = base.clone().add(Math.cos(a) * rr, 0.2 + t * 0.12, Math.sin(a) * rr);
                        w.spawnParticle(Particle.SOUL_FIRE_FLAME, l, 1, 0, 0, 0, 0);
                        w.spawnParticle(Particle.REVERSE_PORTAL, l, 2, 0.1, 0.1, 0.1, 0.1);
                    }
                    if (t == 10) c.setVelocity(new Vector(0, -1.4, 0));
                    t++;
                    return;
                }
                cancel();
                RayTraceResult gr = w.rayTraceBlocks(base.clone().add(0, 0.5, 0), new Vector(0, -1, 0), 12,
                        FluidCollisionMode.NEVER, true);
                Location ground = gr != null ? gr.getHitPosition().toLocation(w) : base;
                shockwave(c, ground, radius, damage);
            }
        }.runTaskTimer(this, 0L, 1L);
    }

    /** Expanding ring of soul-fire that damages and launches whatever it touches. */
    private void shockwave(LivingEntity c, Location g, double radius, double damage) {
        World w = g.getWorld();
        w.playSound(g, Sound.ENTITY_GENERIC_EXPLODE, 2f, 0.6f);
        w.playSound(g, Sound.ENTITY_WARDEN_SONIC_BOOM, 2f, 0.8f);
        w.playSound(g, Sound.ENTITY_WITHER_BREAK_BLOCK, 1.5f, 0.7f);
        w.spawnParticle(Particle.EXPLOSION_EMITTER, g, 1);
        w.spawnParticle(Particle.SONIC_BOOM, g.clone().add(0, 1, 0), 1);
        w.spawnParticle(Particle.LARGE_SMOKE, g, 40, radius * 0.2, 0.2, radius * 0.2, 0.05);

        // Soul geyser at the impact point
        for (double y = 0; y < 8; y += 0.4) {
            w.spawnParticle(Particle.SOUL, g.clone().add(0, y, 0), 3, 0.25, 0, 0.25, 0.02);
            w.spawnParticle(Particle.END_ROD, g.clone().add(0, y, 0), 1, 0.1, 0, 0.1, 0.02);
        }

        new BukkitRunnable() {
            double r = 0;
            final Set<UUID> hit = new HashSet<>();

            @Override
            public void run() {
                r += radius / 10.0;
                int pts = (int) (r * 10) + 12;
                for (int i = 0; i < pts; i++) {
                    double a = 2 * Math.PI * i / pts;
                    Location l = g.clone().add(Math.cos(a) * r, 0.15, Math.sin(a) * r);
                    w.spawnParticle(Particle.SOUL_FIRE_FLAME, l, 1, 0.05, 0.05, 0.05, 0.02);
                    w.spawnParticle(Particle.DUST_COLOR_TRANSITION, l, 1, 0.1, 0.1, 0.1, 0, VOID_DUST);
                    if (i % 3 == 0) w.spawnParticle(Particle.END_ROD, l.clone().add(0, 0.3, 0), 1, 0, 0.3, 0, 0.05);
                }
                for (Entity en : w.getNearbyEntities(g, r + 1, 3, r + 1)) {
                    if (!(en instanceof LivingEntity le) || !canHit(c, le) || hit.contains(le.getUniqueId())) continue;
                    double dx = le.getLocation().getX() - g.getX();
                    double dz = le.getLocation().getZ() - g.getZ();
                    if (Math.hypot(dx, dz) > r + 0.8) continue;
                    hit.add(le.getUniqueId());

                    dealDamage(c, le, damage);
                    Vector kb = new Vector(dx, 0, dz);
                    if (kb.lengthSquared() < 1e-4) kb = new Vector(0, 0, 1);
                    le.setVelocity(kb.normalize().multiply(1.4).setY(0.7));
                    le.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 100, 1));
                    le.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 1));
                    if (le instanceof Player) le.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 40, 0));
                    w.spawnParticle(Particle.ENCHANTED_HIT, mid(le), 30, 0.3, 0.5, 0.3, 0.5);
                }
                if (r >= radius) cancel();
            }
        }.runTaskTimer(this, 0L, 1L);
    }

    // ------------------------------------------------------------------ helpers

    private void dealDamage(LivingEntity caster, LivingEntity target, double amount) {
        internalDamage = true;
        try {
            target.damage(amount, caster);
        } finally {
            internalDamage = false;
        }
    }

    private boolean canHit(LivingEntity caster, LivingEntity target) {
        if (target == caster || target instanceof ArmorStand) return false;
        if (target instanceof Player pl) {
            if (pl.getGameMode() == GameMode.CREATIVE || pl.getGameMode() == GameMode.SPECTATOR) return false;
        } else if (!(caster instanceof Player)) {
            return false; // the boss only hurts players
        }
        return true;
    }

    private Location mid(LivingEntity e) {
        return e.getLocation().add(0, e.getHeight() / 2, 0);
    }

    // ------------------------------------------------------------------ the boss: Void Reaver

    private void setBase(LivingEntity e, Attribute attr, double value) {
        AttributeInstance ai = e.getAttribute(attr);
        if (ai != null) ai.setBaseValue(value);
    }

    private void spawnBoss(Location loc) {
        World w = loc.getWorld();
        Component name = Component.text("\u2620 Void Reaver \u2620", PURPLE, TextDecoration.BOLD);

        WitherSkeleton boss = w.spawn(loc, WitherSkeleton.class, ws -> {
            ws.customName(name);
            ws.setCustomNameVisible(true);
            setBase(ws, Attribute.MAX_HEALTH, 600);
            setBase(ws, Attribute.ATTACK_DAMAGE, 14);
            setBase(ws, Attribute.MOVEMENT_SPEED, 0.32);
            setBase(ws, Attribute.KNOCKBACK_RESISTANCE, 0.9);
            setBase(ws, Attribute.ARMOR, 12);
            setBase(ws, Attribute.FOLLOW_RANGE, 48);
            ws.setHealth(600);
            ws.setPersistent(true);
            ws.setRemoveWhenFarAway(false);
            ws.getPersistentDataContainer().set(bossKey, PersistentDataType.BYTE, (byte) 1);
            ws.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, PotionEffect.INFINITE_DURATION, 1, false, false));

            EntityEquipment eq = ws.getEquipment();
            eq.setItemInMainHand(createSword());
            eq.setItemInMainHandDropChance(0f);
            eq.setHelmet(new ItemStack(Material.NETHERITE_HELMET));
            eq.setChestplate(new ItemStack(Material.NETHERITE_CHESTPLATE));
            eq.setLeggings(new ItemStack(Material.NETHERITE_LEGGINGS));
            eq.setBoots(new ItemStack(Material.NETHERITE_BOOTS));
            eq.setHelmetDropChance(0f);
            eq.setChestplateDropChance(0f);
            eq.setLeggingsDropChance(0f);
            eq.setBootsDropChance(0f);
        });

        w.strikeLightningEffect(loc);
        w.playSound(loc, Sound.ENTITY_WITHER_SPAWN, 2f, 0.6f);
        w.spawnParticle(Particle.REVERSE_PORTAL, loc.clone().add(0, 1, 0), 200, 1, 1.5, 1, 0.5);
        for (Player pl : w.getPlayers()) {
            if (pl.getLocation().distanceSquared(loc) < 100 * 100) {
                pl.sendMessage(Component.text("The Void Reaver awakens...", PURPLE));
            }
        }

        BossBar bar = BossBar.bossBar(name, 1f, BossBar.Color.PURPLE, BossBar.Overlay.NOTCHED_10);

        new BukkitRunnable() {
            int t = 0;
            boolean enraged = false;

            @Override
            public void run() {
                if (!boss.isValid()) {
                    for (Player p : Bukkit.getOnlinePlayers()) p.hideBossBar(bar);
                    cancel();
                    return;
                }
                t++;
                World bw = boss.getWorld();
                double max = boss.getAttribute(Attribute.MAX_HEALTH).getValue();
                float prog = (float) Math.max(0, Math.min(1, boss.getHealth() / max));
                bar.progress(prog);

                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getWorld().equals(bw) && p.getLocation().distanceSquared(boss.getLocation()) < 60 * 60) {
                        p.showBossBar(bar);
                    } else {
                        p.hideBossBar(bar);
                    }
                }

                // Aura
                Location base = boss.getLocation();
                for (int i = 0; i < 3; i++) {
                    double a = t * 0.5 + i * (Math.PI * 2 / 3);
                    Location l = base.clone().add(Math.cos(a) * 1.1, 0.3 + (t % 10) * 0.2, Math.sin(a) * 1.1);
                    bw.spawnParticle(Particle.SOUL_FIRE_FLAME, l, 1, 0, 0, 0, 0);
                    bw.spawnParticle(Particle.DUST_COLOR_TRANSITION, l, 2, 0.05, 0.05, 0.05, 0, VOID_DUST);
                }

                // Phase 2
                if (!enraged && prog < 0.5f) {
                    enraged = true;
                    bar.color(BossBar.Color.RED);
                    setBase(boss, Attribute.MOVEMENT_SPEED, 0.40);
                    setBase(boss, Attribute.ATTACK_DAMAGE, 20);
                    boss.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, PotionEffect.INFINITE_DURATION, 0, false, false));
                    bw.playSound(base, Sound.ENTITY_ENDER_DRAGON_GROWL, 3f, 0.6f);
                    bw.spawnParticle(Particle.EXPLOSION_EMITTER, base, 2, 1, 0.5, 1, 0);
                    for (Player p : bw.getPlayers()) {
                        if (p.getLocation().distanceSquared(base) < 60 * 60) {
                            p.sendMessage(Component.text("The Void Reaver is ENRAGED!", NamedTextColor.RED, TextDecoration.BOLD));
                        }
                    }
                    slam(boss, 10, 16);
                    return;
                }

                // Ability cycle: every 5s (phase 1) / 3s (phase 2); task runs every 5 ticks.
                int interval = enraged ? 12 : 20;
                if (t % interval != 0) return;
                if (!(boss instanceof Mob mob) || mob.getTarget() == null) return;
                LivingEntity target = mob.getTarget();

                switch (ThreadLocalRandom.current().nextInt(3)) {
                    case 0 -> slam(boss, enraged ? 10 : 7, 14);
                    case 1 -> {
                        bw.playSound(base, Sound.ENTITY_WITHER_SPAWN, 1.5f, 1.8f);
                        bw.spawnParticle(Particle.REVERSE_PORTAL, base.clone().add(0, 1, 0), 60, 0.5, 1, 0.5, 0.5);
                        Bukkit.getScheduler().runTaskLater(SoulreaverPlugin.this, () -> {
                            if (!boss.isValid() || !target.isValid()) return;
                            Vector to = target.getLocation().toVector().subtract(boss.getLocation().toVector());
                            double dist = Math.min(to.length() + 1.5, 14);
                            dash(boss, to.setY(0), dist);
                        }, 10L);
                    }
                    default -> barrage(boss, target);
                }
            }
        }.runTaskTimer(this, 20L, 5L);
    }

    /** Five homing soul missiles. */
    private void barrage(LivingEntity c, LivingEntity target) {
        for (int i = 0; i < 5; i++) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (!c.isValid() || !target.isValid()) return;
                launchMissile(c, target);
            }, i * 6L);
        }
    }

    private void launchMissile(LivingEntity c, LivingEntity target) {
        World w = c.getWorld();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        w.playSound(c.getEyeLocation(), Sound.ENTITY_WITHER_SHOOT, 1.2f, 1.4f);

        Location pos = c.getEyeLocation();
        Vector vel = new Vector(rnd.nextDouble(-1, 1), rnd.nextDouble(0.6, 1.2), rnd.nextDouble(-1, 1))
                .normalize().multiply(0.6);

        new BukkitRunnable() {
            int life = 0;

            @Override
            public void run() {
                if (life++ > 60 || !target.isValid() || !c.isValid()) {
                    cancel();
                    return;
                }
                Vector to = target.getEyeLocation().toVector().subtract(pos.toVector()).normalize().multiply(0.35);
                vel.multiply(0.75).add(to);
                if (vel.length() > 0.9) vel.normalize().multiply(0.9);
                pos.add(vel);

                w.spawnParticle(Particle.SOUL_FIRE_FLAME, pos, 3, 0.05, 0.05, 0.05, 0.01);
                w.spawnParticle(Particle.DUST_COLOR_TRANSITION, pos, 2, 0.1, 0.1, 0.1, 0, VOID_DUST);
                w.spawnParticle(Particle.SOUL, pos, 1, 0, 0, 0, 0);

                if (!pos.getBlock().isPassable()) {
                    w.spawnParticle(Particle.SOUL_FIRE_FLAME, pos, 15, 0.2, 0.2, 0.2, 0.1);
                    cancel();
                    return;
                }
                if (pos.distanceSquared(target.getEyeLocation()) < 1.5) {
                    w.spawnParticle(Particle.EXPLOSION, pos, 1);
                    w.spawnParticle(Particle.SOUL_FIRE_FLAME, pos, 30, 0.3, 0.3, 0.3, 0.15);
                    w.playSound(pos, Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 1.5f);
                    if (canHit(c, target)) dealDamage(c, target, 7);
                    cancel();
                }
            }
        }.runTaskTimer(this, 0L, 1L);
    }
}
