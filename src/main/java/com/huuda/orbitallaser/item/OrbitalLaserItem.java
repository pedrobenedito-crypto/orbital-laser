package com.huuda.orbitallaser.item;

import com.huuda.orbitallaser.config.ServerConfig;
import com.huuda.orbitallaser.strike.StrikeManager;
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
 * The remote that calls the strike. Use: lock the aimed block and start charging.
 * Sneak + use: cancel a charge in progress. All logic runs on the server.
 */
public class OrbitalLaserItem extends Item {

    public OrbitalLaserItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
            StrikeManager.get(serverPlayer.server).handleUse(serverPlayer);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.orbital_laser.use").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.orbital_laser.cancel").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.orbital_laser.range", ServerConfig.rangeOrDefault())
                .withStyle(ChatFormatting.DARK_AQUA));
    }
}
