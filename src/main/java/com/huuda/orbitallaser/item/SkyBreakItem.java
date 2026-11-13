package com.huuda.orbitallaser.item;

import com.huuda.orbitallaser.config.ServerConfig;
import com.huuda.orbitallaser.skybreak.SkyBreakManager;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Calls the Sky Breaks on the aimed block: a hand punches through the sky, an eye looks down, panes of sky fall and
 * a fist slams the land. Ported from Stink-O's Orbital Laser (MIT).
 */
public class SkyBreakItem extends Item {

    public SkyBreakItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
            SkyBreakManager.get(serverPlayer.server).handleUse(serverPlayer);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.orbital_laser.sky_breaks").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.orbital_laser.range", ServerConfig.rangeOrDefault())
                .withStyle(ChatFormatting.DARK_AQUA));
        tooltip.add(Component.translatable("tooltip.orbital_laser.credit").withStyle(ChatFormatting.DARK_GRAY));
    }
}
