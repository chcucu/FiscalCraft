package com.fiscalcraft.wallet;

/** Vault 钱包外部账本边界。所有金额为 long 分币。 */
public interface WalletGateway {
    long balance(String uuid);

    boolean withdraw(String uuid, long cents);

    boolean deposit(String uuid, long cents);
}
