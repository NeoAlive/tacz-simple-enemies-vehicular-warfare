package com.neoalive.tacz_sewv.command.quick.scavenge;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.tacz.guns.api.item.IAmmo;
import com.tacz.guns.api.item.IGun;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

/** What a whole run took, per stage, for the one summary at the end. */
final class ScavengeTally {

    /** Entries listed per stage before the rest fold into "+N more". */
    static final int SHOWN = 6;

    private static final class Line {
        final Component name;
        int count;

        Line(Component name) {
            this.name = name;
        }
    }

    private final Map<ScavengeStage, Map<String, Line>> byStage = new EnumMap<>(ScavengeStage.class);

    void add(ScavengeStage stage, ItemStack stack, int count) {
        if (count <= 0 || stack.isEmpty()) return;
        add(stage, keyOf(stack), stack.getHoverName(), count);
    }

    void add(ScavengeStage stage, String key, Component name, int count) {
        if (count <= 0) return;
        this.byStage.computeIfAbsent(stage, s -> new LinkedHashMap<>())
                .computeIfAbsent(key, k -> new Line(name.copy())).count += count;
    }

    boolean isEmpty() {
        return this.byStage.isEmpty();
    }

    /** One gray line per stage that took anything: "Ammo: 240x 5.56x45, 2x Grenade, +1 more". */
    List<Component> lines() {
        List<Component> out = new ArrayList<>();
        for (ScavengeStage stage : ScavengeStage.values()) {
            Map<String, Line> lines = this.byStage.get(stage);
            if (lines == null || lines.isEmpty()) continue;
            List<Line> sorted = new ArrayList<>(lines.values());
            sorted.sort((a, b) -> Integer.compare(b.count, a.count));
            MutableComponent list = Component.empty();
            int shown = Math.min(SHOWN, sorted.size());
            for (int i = 0; i < shown; i++) {
                if (i > 0) list.append(", ");
                Line line = sorted.get(i);
                list.append(Component.translatable("message.tacz_sewv.scavenge.entry", line.count, line.name));
            }
            if (sorted.size() > shown) {
                list.append(", ").append(Component.translatable("message.tacz_sewv.scavenge.more",
                        sorted.size() - shown));
            }
            out.add(Component.translatable("message.tacz_sewv.scavenge.stage." + stage.key(), list)
                    .withStyle(ChatFormatting.GRAY));
        }
        return out;
    }

    /** Item id plus TACZ's ammo or gun id, so calibres are not merged under one item. */
    static String keyOf(ItemStack stack) {
        String key = String.valueOf(ForgeRegistries.ITEMS.getKey(stack.getItem()));
        IAmmo ammo = IAmmo.getIAmmoOrNull(stack);
        if (ammo != null) return key + "|" + ammo.getAmmoId(stack);
        IGun gun = IGun.getIGunOrNull(stack);
        if (gun != null) return key + "|" + gun.getGunId(stack);
        return key;
    }
}
