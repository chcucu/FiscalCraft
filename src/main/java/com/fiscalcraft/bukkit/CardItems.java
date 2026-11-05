package com.fiscalcraft.bukkit;

import com.fiscalcraft.FiscalCraftPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

/** 实体银行卡物品：发光、显示持卡人与卡号、死亡不掉落由监听器处理；卡内不存钱。 */
public final class CardItems {
    private final NamespacedKey cardKey;

    public CardItems(FiscalCraftPlugin plugin) {
        this.cardKey = new NamespacedKey(plugin, "card_no");
    }

    public ItemStack create(String cardNo, String ownerName) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("银行卡 " + cardNo).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("持卡人: " + ownerName).decoration(TextDecoration.ITALIC, false),
                Component.text("卡号: " + cardNo).decoration(TextDecoration.ITALIC, false),
                Component.text("右键银行终端免密登录，卡内不存钱").decoration(TextDecoration.ITALIC, false)));
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(cardKey, PersistentDataType.STRING, cardNo);
        item.setItemMeta(meta);
        return item;
    }

    public String cardNo(ItemStack item) {
        if (item == null || item.getType() != Material.PAPER || !item.hasItemMeta()) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        PersistentDataContainer container = meta.getPersistentDataContainer();
        return container.get(cardKey, PersistentDataType.STRING);
    }

    public boolean isCard(ItemStack item) {
        return cardNo(item) != null;
    }
}
