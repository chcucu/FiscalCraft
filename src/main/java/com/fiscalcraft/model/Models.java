package com.fiscalcraft.model;

import java.util.List;

public final class Models {
    private Models() {
    }

    public record Account(
            String uuid,
            String name,
            long balance,
            String pinHash,
            String pinSalt,
            String securityQuestion,
            String securityAnswerHash,
            String securityAnswerSalt,
            int failCount,
            long lockedUntil,
            String status,
            long openedAt
    ) {
        public boolean active() {
            return "ACTIVE".equals(status);
        }
    }

    public record PlayerData(
            String uuid,
            String name,
            long firstJoin,
            long lastSeen,
            String lastIp,
            long lastIpAt,
            int level,
            long levelSince,
            long graceUntil,
            int compliance,
            int creditScore,
            String riskLevel
    ) {
    }

    public record Card(String cardNo, String ownerUuid, long issuedAt, String status) {
        public boolean active() {
            return "ACTIVE".equals(status);
        }
    }

    public record SharedAccount(String id, String name, String type, long balance,
                                String createdBy, long createdAt, String status) {
        public boolean active() {
            return "ACTIVE".equals(status);
        }
    }

    public record SharedMember(String sharedId, String uuid, long joinedAt, long netContribution) {
    }

    public record Loan(
            String id,
            String uuid,
            long principal,
            int ratePpm,
            int termDays,
            long startedAt,
            long dueAt,
            long outstanding,
            String status,
            long disbursedAt,
            long relatedOut,
            int extensions,
            String lastAccrualDay,
            long writtenOff,
            long closedAt
    ) {
        public boolean open() {
            return "ACTIVE".equals(status) || "OVERDUE".equals(status);
        }
    }

    public record Tx(
            String txId,
            long ts,
            String type,
            String fromUuid,
            String toUuid,
            long amount,
            long fee,
            Long fromBefore,
            Long fromAfter,
            Long toBefore,
            Long toAfter,
            String reason
    ) {
    }

    public record TaxRecord(
            String id,
            long ts,
            String uuid,
            String taxType,
            long base,
            long amount,
            long paid,
            String status,
            String dayKey,
            String detail
    ) {
        public long remaining() {
            return Math.max(0L, amount - paid);
        }
    }

    public record WalletOp(
            String opId,
            String playerUuid,
            String direction,
            String target,
            long amount,
            String state,
            long walletBefore,
            long bankBefore,
            long bankAfter,
            String reason,
            long createdAt,
            long updatedAt
    ) {
    }

    public record LevelSpec(
            int index,
            String key,
            String label,
            long minAvgAssets,
            long minFlow,
            int minCompliance,
            long cardFee,
            long terminalSingleLimit,
            int feePpm,
            int sessionMinutes,
            int historyLimit,
            int interestBonusPpm
    ) {
    }

    public record Bracket(long upToCents, int ratePpm) {
        public boolean openEnded() {
            return upToCents < 0;
        }
    }

    public record RiskSummary(String level, List<String> reasons, int flagCount) {
    }

    public record Report(
            long totalSupply,
            long cash,
            long bankDeposits,
            long sharedDeposits,
            long loanOutstanding,
            long treasury,
            long arrears,
            long top10Assets,
            double top10Share,
            double gini,
            int accounts,
            int activeLoans,
            int overdueLoans,
            long suspiciousCount
    ) {
    }
}
