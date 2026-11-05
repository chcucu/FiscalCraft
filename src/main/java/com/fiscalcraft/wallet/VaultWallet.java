package com.fiscalcraft.wallet;

import com.fiscalcraft.util.Money;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;

public final class VaultWallet implements WalletGateway {
    private final JavaPlugin plugin;
    private Economy economy;

    public VaultWallet(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean setup() {
        if (plugin.getServer().getPluginManager().getPlugin("Vault") == null) return false;
        RegisteredServiceProvider<Economy> registration = plugin.getServer().getServicesManager().getRegistration(Economy.class);
        if (registration == null) return false;
        economy = registration.getProvider();
        return economy != null;
    }

    public Economy economy() {
        return economy;
    }

    private OfflinePlayer player(String uuid) {
        return Bukkit.getOfflinePlayer(UUID.fromString(uuid));
    }

    @Override
    public long balance(String uuid) {
        return Money.fromVault(economy.getBalance(player(uuid)));
    }

    @Override
    public boolean withdraw(String uuid, long cents) {
        if (cents <= 0) return true;
        EconomyResponse response = economy.withdrawPlayer(player(uuid), Money.toVault(cents));
        return response != null && response.transactionSuccess();
    }

    @Override
    public boolean deposit(String uuid, long cents) {
        if (cents <= 0) return true;
        EconomyResponse response = economy.depositPlayer(player(uuid), Money.toVault(cents));
        return response != null && response.transactionSuccess();
    }
}
